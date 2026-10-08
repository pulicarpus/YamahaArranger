"""P0 pure test model. No engine, MIDI, scheduler, native API or dispatch hook.
READY_SYNTHETIC means an explicit test assumption, NEVER backend certification.
"""
from dataclasses import dataclass, replace

@dataclass(frozen=True)
class EventId:
    decoded_index: int
    source: int
    key: int
    authored_tick: int
    raw_ordinal: int | None = None
    raw_offset: int | None = None
    provenance: str = 'DECODED_ONLY'

@dataclass(frozen=True)
class InstanceId:
    epoch: int
    style_hash: str
    visit: int
    iteration: int
    event: EventId
    occurrence: int

@dataclass(frozen=True)
class Instance:
    id: InstanceId
    section: str
    part: str
    projected_tick: int
    absolute_tick: int
    policy_provenance: str
    chord_version: int

@dataclass(frozen=True)
class Endpoint:
    kind: str
    identity: str
    output_epoch: int
    conflict_domain: str = 'CHANNEL_PITCH'

@dataclass(frozen=True)
class BindingId:
    instance: InstanceId
    revision: int
    endpoint: Endpoint
    destination: int
    pitch: int

    @property
    def output_key(self):
        e = self.endpoint
        return (e, None if e.conflict_domain == 'PITCH_WIDE' else self.destination, self.pitch)

@dataclass(frozen=True)
class Fact:
    binding: BindingId
    kind: str
    intent: str

@dataclass(frozen=True)
class Ticket:
    epoch: int
    visit: int
    iteration: int
    plan_revision: int

@dataclass(frozen=True)
class Off:
    event: EventId
    ticket: Ticket
    exact: InstanceId | None = None
    singleton_scope_approved: bool = False  # synthetic contract only

@dataclass(frozen=True)
class Pairing:
    kind: str
    candidates: tuple[InstanceId, ...] = ()
    reason: str = ''

@dataclass(frozen=True)
class Profile:
    mode: str = 'UNKNOWN'
    model_assumption: bool = False
    # Independent, caller-provided synthetic order; never inferred FIFO/Yamaha.
    order: tuple[BindingId, ...] = ()

@dataclass(frozen=True)
class Plan:
    status: str
    binding: BindingId | None = None
    reason: str = ''
    authority: Ticket | None = None
    assumption: Profile = Profile()

@dataclass(frozen=True)
class Snapshot:
    ticket: Ticket
    instances: tuple[InstanceId, ...]
    bindings: tuple[BindingId, ...]

@dataclass(frozen=True)
class Ledger:
    ticket: Ticket = Ticket(1, 1, 0, 0)
    instances: tuple[Instance, ...] = ()
    bindings: tuple[BindingId, ...] = ()
    facts: tuple[Fact, ...] = ()
    terminated: tuple[InstanceId, ...] = ()
    grants: tuple[tuple[Ticket, InstanceId], ...] = ()
    pending: Snapshot | None = None

    def add(self, instance):
        i = instance.id
        if self.pending is not None:
            raise ValueError('cleanup barrier precedes incoming ON')
        if (i.epoch, i.visit, i.iteration) != (self.ticket.epoch, self.ticket.visit, self.ticket.iteration):
            raise ValueError('stale ON generation')
        if any(x.id == i for x in self.instances):
            raise ValueError('duplicate instance identity')
        return replace(self, instances=self.instances + (instance,))

    def bind(self, b):
        if self.pending is not None or b.instance in self.terminated:
            raise ValueError('binding outside live authority')
        if b.instance not in tuple(x.id for x in self.instances) or b in self.bindings:
            raise ValueError('missing instance or duplicate binding')
        peers = [x for x in self.bindings if x.instance == b.instance and x.endpoint == b.endpoint]
        if any(self.state(x) not in ('RELEASE_SUBMITTED', 'SESSION_TERMINATED', 'LOGICAL_RELEASED') for x in peers):
            raise ValueError('old route must be retired before rebind')
        if peers and b.revision <= max(x.revision for x in peers):
            raise ValueError('route revision must increase')
        return replace(self, bindings=self.bindings + (b,), facts=self.facts + (Fact(b, 'CREATED', 'create'),))

    def state(self, b):
        return next((f.kind for f in reversed(self.facts) if f.binding == b), 'MISSING')

    def record(self, b, kind, intent):
        if b not in self.bindings or b.instance.epoch != self.ticket.epoch:
            raise ValueError('unknown/stale binding receipt')
        if any(f.binding == b and f.intent == intent for f in self.facts):
            raise ValueError('duplicate intent; unknown receipt is not retry permission')
        state = self.state(b)
        allowed = {'CREATED': ('POLICY_REJECTED', 'SUBMITTED', 'CANCELLED_BEFORE_SUBMIT'),
                   'SUBMITTED': ('NATIVE_ACCEPTED', 'NATIVE_REJECTED', 'ADMISSION_UNKNOWN')}
        if kind not in allowed.get(state, ()):
            raise ValueError('invalid admission transition')
        if kind.startswith('NATIVE_') and b.endpoint.kind != 'BASSMIDI':
            raise ValueError('MIDI transport is not native/receiver acknowledgment')
        return replace(self, facts=self.facts + (Fact(b, kind, intent),))

    def allows(self, ticket, target):
        if target in self.terminated or ticket.epoch != self.ticket.epoch or target.epoch != self.ticket.epoch:
            return False
        if (ticket, target) in self.grants:
            return True
        return ticket == self.ticket and (target.visit, target.iteration) == (ticket.visit, ticket.iteration)

    def resolve(self, off):
        if off.ticket.epoch != self.ticket.epoch or self.pending is not None:
            return Pairing('ORPHAN', reason='STALE_OR_FROZEN_AUTHORITY')
        peers = tuple(x.id for x in self.instances if x.id not in self.terminated and
                      (x.id.event.source, x.id.event.key) == (off.event.source, off.event.key) and
                      self.allows(off.ticket, x.id))
        if off.exact is not None:
            if off.exact not in peers:
                return Pairing('ORPHAN', reason='EXACT_TARGET_NOT_AUTHORIZED')
            return Pairing('EXACT', (off.exact,))
        if not peers:
            return Pairing('ORPHAN', reason='NO_AUTHORIZED_CANDIDATE')
        if len(peers) == 1 and off.singleton_scope_approved:
            return Pairing('UNAMBIGUOUS', peers, 'EXPLICIT_SYNTHETIC_SCOPE')
        return Pairing('AMBIGUOUS', peers, 'NO_APPROVED_PAIRING')

    def active_output(self, b):
        return tuple(x for x in self.bindings if x.output_key == b.output_key and
                     self.state(x) not in ('POLICY_REJECTED', 'NATIVE_REJECTED',
                                          'CANCELLED_BEFORE_SUBMIT', 'RELEASE_SUBMITTED',
                                          'LOGICAL_RELEASED', 'SESSION_TERMINATED'))

    def plan(self, b, profile=Profile(), ticket=None):
        ticket = self.ticket if ticket is None else ticket
        if b not in self.bindings or not self.allows(ticket, b.instance):
            return Plan('BLOCKED', b, 'STALE_OR_UNKNOWN_BINDING', ticket, profile)
        state = self.state(b)
        if state in ('POLICY_REJECTED', 'NATIVE_REJECTED', 'CANCELLED_BEFORE_SUBMIT'):
            return Plan('NO_WIRE', b, 'EXACT_REJECTED_INSTANCE', ticket, profile)
        if state != 'NATIVE_ACCEPTED':
            return Plan('BLOCKED', b, 'ADMISSION_UNKNOWN_OR_NOT_LIVE', ticket, profile)
        peers = self.active_output(b)
        if any(self.state(x) != 'NATIVE_ACCEPTED' for x in peers):
            return Plan('BLOCKED', b, 'UNKNOWN_CONFLICT_ADMISSION', ticket, profile)
        if not profile.model_assumption:
            return Plan('BLOCKED', b, 'BACKEND_CAPABILITY_UNKNOWN', ticket, profile)
        if profile.mode == 'SELECTIVE_SYNTHETIC':
            return Plan('READY_SYNTHETIC', b, 'HYPOTHETICAL_HANDLE_NOT_DEVICE_EVIDENCE', ticket, profile)
        if profile.mode == 'ORDERED_SYNTHETIC' and len(profile.order) == len(peers) and set(profile.order) == set(peers):
            if profile.order[0] == b:
                return Plan('READY_SYNTHETIC', b, 'EXPLICIT_TEST_ORDER_NOT_YAMAHA', ticket, profile)
            return Plan('BLOCKED', b, 'NONSELECTIVE_VICTIM_MISMATCH', ticket, profile)
        return Plan('BLOCKED', b, 'INCOMPLETE_OR_UNSUPPORTED_PROFILE', ticket, profile)

    def apply_plan(self, plan, intent):
        # No callback/dispatcher: only append an immutable hypothetical fact.
        if plan.status not in ('READY_SYNTHETIC', 'NO_WIRE') or plan.binding not in self.bindings:
            raise ValueError('unresolved plan cannot mutate ledger')
        b = plan.binding
        if plan.authority is None or self.plan(b, plan.assumption, plan.authority) != plan:
            raise ValueError('stale/forged release plan')
        if self.state(b) in ('RELEASE_SUBMITTED', 'LOGICAL_RELEASED', 'SESSION_TERMINATED'):
            raise ValueError('duplicate/stale release')
        if any(f.binding == b and f.intent == intent for f in self.facts):
            raise ValueError('duplicate intent')
        kind = 'LOGICAL_RELEASED' if plan.status == 'NO_WIRE' else 'RELEASE_SUBMITTED'
        return replace(self, facts=self.facts + (Fact(b, kind, intent),))

    def natural(self, visit, iteration, authorized_carry=()):
        if self.pending is not None or (visit, iteration) <= (self.ticket.visit, self.ticket.iteration):
            raise ValueError('nonmonotonic/frozen section or iteration')
        current = {x.id for x in self.instances if x.id not in self.terminated}
        if not set(authorized_carry) <= current:
            raise ValueError('carry references missing/terminated instance')
        t = Ticket(self.ticket.epoch, visit, iteration, self.ticket.plan_revision + 1)
        grants = tuple((ticket, i) for i in authorized_carry for ticket in (self.ticket, t))
        # Retain earlier explicitly authorized release tickets for these exact IDs.
        grants += tuple(g for g in self.grants if g[1] in authorized_carry)
        return replace(self, ticket=t, grants=grants)

    def interrupt(self, visit):
        if self.pending is not None or visit <= self.ticket.visit:
            raise ValueError('invalid interrupted transition')
        t = Ticket(self.ticket.epoch, visit, 0, self.ticket.plan_revision + 1)
        ids = tuple(x.id for x in self.instances if x.id not in self.terminated)
        bs = tuple(b for b in self.bindings if b.instance in ids and self.state(b) not in
                   ('RELEASE_SUBMITTED', 'LOGICAL_RELEASED', 'SESSION_TERMINATED'))
        snapshot = Snapshot(t, ids, bs)
        return replace(self, ticket=t, grants=tuple((t, i) for i in ids), pending=snapshot), snapshot

    def cleanup(self, snapshot, profiles):
        if self.pending != snapshot or snapshot.ticket != self.ticket:
            return self, 'BLOCKED'
        # Pure preflight over full set; no partial cleanup if any binding unsupported.
        plans = tuple(self.plan(b, profiles.get(b.endpoint, Profile())) for b in snapshot.bindings)
        if any(p.status not in ('READY_SYNTHETIC', 'NO_WIRE') for p in plans):
            return self, 'BLOCKED'
        result = self
        for n, p in enumerate(plans):
            result = result.apply_plan(p, f'cleanup:{self.ticket.plan_revision}:{n}')
        return replace(result, terminated=result.terminated + snapshot.instances,
                       grants=(), pending=None), 'READY_SYNTHETIC'

    def stop_restart(self):
        t = Ticket(self.ticket.epoch + 1, 1, 0, self.ticket.plan_revision + 1)
        facts = self.facts + tuple(Fact(b, 'SESSION_TERMINATED', f'stop:{self.ticket.epoch}')
                                  for b in self.bindings if b.instance.epoch == self.ticket.epoch)
        return replace(self, ticket=t, facts=facts, terminated=tuple(x.id for x in self.instances),
                       grants=(), pending=None)
