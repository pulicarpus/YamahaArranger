"""Tests of bookkeeping/denial only. No native or Yamaha voice simulation."""
import dataclasses
import json
from pathlib import Path
import unittest
from f03_architecture_prototype import (
    EventId, InstanceId, Instance, Endpoint, BindingId, Fact, Ticket,
    Off, Profile, Plan, Ledger,
)
ROOT = Path(__file__).resolve().parents[1]
BASS = Endpoint('BASSMIDI', 'synthetic-stream', 1)
MIDI = Endpoint('MIDI_OUT', 'synthetic-port', 1)
SELECTIVE = Profile('SELECTIVE_SYNTHETIC', True)

def note(ledger, serial=1, source=4, key=60, section='MainD', tick=0, raw=True):
    t = ledger.ticket
    event = EventId(serial, source, key, tick, serial if raw else None,
                    serial * 4 if raw else None, 'SYNTHETIC_RAW' if raw else 'DECODED_ONLY')
    iid = InstanceId(t.epoch, 'SYNTHETIC_STYLE', t.visit, t.iteration, event, serial)
    return Instance(iid, section, 'Chord1', tick, tick, 'SYNTHETIC_POLICY', 0)

def admitted(ledger, i, endpoint=BASS, dst=11, pitch=60):
    ledger = ledger.add(i)
    b = BindingId(i.id, 0, endpoint, dst, pitch)
    ledger = ledger.bind(b).record(b, 'SUBMITTED', 'on').record(b, 'NATIVE_ACCEPTED', 'native-return')
    return ledger, b

def off(ledger, source=4, key=60, exact=None, approved=False, ticket=None):
    return Off(EventId(999, source, key, 9), ticket or ledger.ticket, exact, approved)

class PrototypeTests(unittest.TestCase):
    def test_identity_immutable_and_occurrence_distinguishes_same_tick(self):
        l = Ledger();a = note(l);b = dataclasses.replace(a, id=dataclasses.replace(a.id, occurrence=2))
        with self.assertRaises(dataclasses.FrozenInstanceError):a.id.epoch = 2
        self.assertNotEqual(a.id, b.id);self.assertEqual(2, len(l.add(a).add(b).instances))
        with self.assertRaises(ValueError):l.add(a).add(a)

    def test_decoded_only_does_not_fabricate_raw_provenance(self):
        a = note(Ledger(), raw=False)
        self.assertIsNone(a.id.event.raw_offset);self.assertIsNone(a.id.event.raw_ordinal)
        self.assertEqual('DECODED_ONLY', a.id.event.provenance)

    def test_binding_revision_changes_route_not_instance(self):
        l = Ledger();l,b = admitted(l,note(l));new = dataclasses.replace(b,revision=1,destination=12,pitch=65)
        self.assertEqual(b.instance,new.instance);self.assertNotEqual(b,new)
        self.assertEqual((11,60),(b.destination,b.pitch))
        with self.assertRaises(ValueError):l.bind(new)
        l=l.apply_plan(l.plan(b,SELECTIVE),'release-old');l=l.bind(new)
        self.assertEqual('CREATED',l.state(new));self.assertEqual('RELEASE_SUBMITTED',l.state(b))
        self.assertEqual('BLOCKED',l.plan(b,SELECTIVE).status)

    def test_admission_independent_midi_submission_is_not_receiver_ack(self):
        l=Ledger();l,b=admitted(l,note(l));m=dataclasses.replace(b,endpoint=MIDI)
        l=l.bind(m).record(m,'SUBMITTED','midi-send').record(m,'ADMISSION_UNKNOWN','no-receiver-ack')
        self.assertEqual('NATIVE_ACCEPTED',l.state(b));self.assertEqual('ADMISSION_UNKNOWN',l.state(m))
        self.assertEqual('BLOCKED',l.plan(m,SELECTIVE).status)
        with self.assertRaises(ValueError):l.bind(m)
        fresh=Ledger().add(note(Ledger()));fresh=fresh.bind(m).record(m,'SUBMITTED','midi-send')
        with self.assertRaises(ValueError):fresh.record(m,'NATIVE_ACCEPTED','fictional-ack')

    def test_exact_unambiguous_ambiguous_and_orphan_are_distinct(self):
        l=Ledger();a=note(l);l=l.add(a)
        self.assertEqual('EXACT',l.resolve(off(l,exact=a.id)).kind)
        self.assertEqual('AMBIGUOUS',l.resolve(off(l)).kind)
        self.assertEqual('UNAMBIGUOUS',l.resolve(off(l,approved=True)).kind)
        self.assertEqual('ORPHAN',l.resolve(off(l,key=61,approved=True)).kind)

    def test_overlap_ambiguous_never_selects_fifo_or_lifo(self):
        l=Ledger();a=note(l,1);b=note(l,2,tick=4);l=l.add(a).add(b)
        r=l.resolve(off(l,approved=True));self.assertEqual('AMBIGUOUS',r.kind)
        self.assertEqual({a.id,b.id},set(r.candidates));self.assertEqual((),l.facts)
        self.assertEqual('EXACT',l.resolve(off(l,exact=b.id)).kind)

    def test_rejected_tombstone_prevents_skip_to_admitted_owner(self):
        l=Ledger();a=note(l,1);l=l.add(a);ab=BindingId(a.id,0,BASS,11,60)
        l=l.bind(ab).record(ab,'POLICY_REJECTED','policy')
        b=note(l,2);l,bb=admitted(l,b)
        self.assertEqual('AMBIGUOUS',l.resolve(off(l,approved=True)).kind)
        self.assertEqual('EXACT',l.resolve(off(l,exact=a.id)).kind)
        p=l.plan(ab);self.assertEqual('NO_WIRE',p.status)
        next_l=l.apply_plan(p,'consume-rejected-off')
        self.assertEqual('NATIVE_ACCEPTED',next_l.state(bb))
        self.assertEqual('LOGICAL_RELEASED',next_l.state(ab))
        self.assertFalse(any(f.kind=='RELEASE_SUBMITTED' for f in next_l.facts))

    def test_native_rejection_and_unknown_are_not_accepted(self):
        for outcome in ['NATIVE_REJECTED','ADMISSION_UNKNOWN']:
            with self.subTest(outcome=outcome):
                l=Ledger();a=note(l);l=l.add(a);b=BindingId(a.id,0,BASS,11,60)
                l=l.bind(b).record(b,'SUBMITTED','on').record(b,outcome,'result')
                self.assertEqual('NO_WIRE' if outcome=='NATIVE_REJECTED' else 'BLOCKED',l.plan(b).status)
                with self.assertRaises(ValueError):l.record(b,'SUBMITTED','retry')

    def test_cross_source_output_collision_and_endpoint_isolation(self):
        l=Ledger();l,a=admitted(l,note(l,1,source=4));l,b=admitted(l,note(l,2,source=5))
        self.assertEqual({a,b},set(l.active_output(a)))
        m=dataclasses.replace(a,endpoint=MIDI);l=l.bind(m).record(m,'SUBMITTED','midi-on')
        self.assertEqual((m,),l.active_output(m));self.assertEqual(2,len(l.active_output(a)))
        self.assertEqual('UNAMBIGUOUS',l.resolve(off(l,source=5,approved=True)).kind)
        self.assertEqual('BLOCKED',l.plan(b).status)

    def test_destination_pitch_stream_and_epoch_partition_conflicts(self):
        l=Ledger();bs=[]
        for n,e,d,p in [(1,BASS,11,60),(2,BASS,12,60),(3,BASS,11,61),
                        (4,Endpoint('BASSMIDI','other-stream',1),11,60),
                        (5,Endpoint('BASSMIDI','synthetic-stream',2),11,60)]:
            l,b=admitted(l,note(l,n),e,d,p);bs.append(b)
        for b in bs:self.assertEqual((b,),l.active_output(b))

    def test_pitch_wide_domain_does_not_fake_channel_isolation(self):
        e=Endpoint('BASSMIDI','synthetic-pitch-wide',1,'PITCH_WIDE');l=Ledger()
        l,a=admitted(l,note(l,1),e,11);l,b=admitted(l,note(l,2,source=5),e,12)
        self.assertEqual({a,b},set(l.active_output(a)))
        self.assertEqual('BLOCKED',l.plan(b,Profile('ORDERED_SYNTHETIC',True,(a,b))).status)

    def test_selective_and_ordered_capabilities_are_explicit_assumptions(self):
        l=Ledger();l,a=admitted(l,note(l,1));l,b=admitted(l,note(l,2,source=5))
        self.assertEqual('READY_SYNTHETIC',l.plan(b,SELECTIVE).status)
        ordered=Profile('ORDERED_SYNTHETIC',True,(a,b))
        self.assertEqual('READY_SYNTHETIC',l.plan(a,ordered).status)
        self.assertEqual('NONSELECTIVE_VICTIM_MISMATCH',l.plan(b,ordered).reason)
        self.assertEqual('BLOCKED',l.plan(a,Profile('ORDERED_SYNTHETIC',True,(a,))).status)
        self.assertEqual('BLOCKED',l.plan(a,Profile('S8_LINUX_OBSERVATION',False,(a,b))).status)

    def test_unknown_conflict_admission_blocks_known_native_owner(self):
        l=Ledger();l,a=admitted(l,note(l,1));i=note(l,2,source=5);l=l.add(i)
        b=BindingId(i.id,0,BASS,11,60);l=l.bind(b).record(b,'SUBMITTED','on')
        self.assertEqual('UNKNOWN_CONFLICT_ADMISSION',l.plan(a,SELECTIVE).reason)

    def test_natural_carry_authorizes_exact_old_and_new_tickets(self):
        l=Ledger();a=note(l);l,b=admitted(l,a);old=l.ticket
        carried=l.natural(2,0,(a.id,));self.assertEqual(l.facts,carried.facts)
        self.assertEqual('EXACT',carried.resolve(off(carried,exact=a.id)).kind)
        self.assertEqual('EXACT',carried.resolve(off(carried,exact=a.id,ticket=old)).kind)
        newer=note(carried,2,section='FillB');carried=carried.add(newer)
        self.assertEqual('ORPHAN',carried.resolve(off(carried,exact=newer.id,ticket=old)).kind)
        dropped=l.natural(2,0);self.assertEqual('ORPHAN',dropped.resolve(off(dropped,exact=a.id)).kind)
        self.assertEqual('NATIVE_ACCEPTED',dropped.state(b)) # no blanket cleanup

    def test_loop_iteration_and_plan_revision_are_not_modulo_aliases(self):
        l=Ledger();a=note(l);l=l.add(a);old=l.ticket;l=l.natural(1,1)
        self.assertEqual('ORPHAN',l.resolve(off(l,exact=a.id,ticket=old)).kind)
        new=note(l);self.assertNotEqual(a.id,new.id);l=l.add(new)
        stale=dataclasses.replace(l.ticket,plan_revision=l.ticket.plan_revision-1)
        self.assertEqual('ORPHAN',l.resolve(off(l,exact=new.id,ticket=stale)).kind)
        with self.assertRaises(ValueError):l.natural(1,0)

    def test_interrupted_cleanup_snapshot_includes_prior_carry(self):
        l=Ledger();a=note(l);l,ab=admitted(l,a);l=l.natural(2,0,(a.id,))
        b=note(l,2,section='FillB');l,bb=admitted(l,b,dst=12)
        frozen,snap=l.interrupt(3);self.assertEqual({a.id,b.id},set(snap.instances))
        with self.assertRaises(ValueError):frozen.add(note(frozen,3,section='FillA'))
        same,status=frozen.cleanup(snap,{})
        self.assertEqual('BLOCKED',status);self.assertEqual(frozen,same)
        cleaned,status=frozen.cleanup(snap,{BASS:SELECTIVE})
        self.assertEqual('READY_SYNTHETIC',status);self.assertIsNone(cleaned.pending)
        self.assertEqual({a.id,b.id},set(cleaned.terminated))
        self.assertEqual('RELEASE_SUBMITTED',cleaned.state(ab));self.assertEqual('RELEASE_SUBMITTED',cleaned.state(bb))
        incoming=note(cleaned,3,section='FillA');cleaned,_=admitted(cleaned,incoming)
        same,status=cleaned.cleanup(snap,{BASS:SELECTIVE})
        self.assertEqual('BLOCKED',status);self.assertEqual(cleaned,same)

    def test_main_fill_chain_and_variation_only_synthetic_logical_cleanup(self):
        for names in [('MainD','FillB','FillA','MainA'),('MainC','FillD','MainB')]:
            with self.subTest(chain=names):
                l=Ledger()
                for n,name in enumerate(names,1):
                    if n>1:
                        l,snap=l.interrupt(n);l,status=l.cleanup(snap,{BASS:SELECTIVE})
                        self.assertEqual('READY_SYNTHETIC',status)
                    l,_=admitted(l,note(l,n,section=name),dst=10+n)
                self.assertEqual(len(names),len(l.instances))
                self.assertEqual(len(names)-1,len(l.terminated))

    def test_stop_restart_invalidates_old_off_plan_receipt_and_snapshot(self):
        l=Ledger();i=note(l);l,b=admitted(l,i);old=l.ticket;p=l.plan(b,SELECTIVE)
        restarted=l.stop_restart();self.assertEqual('SESSION_TERMINATED',restarted.state(b))
        j=note(restarted);restarted,newb=admitted(restarted,j,Endpoint('BASSMIDI','synthetic-stream',2))
        self.assertEqual('ORPHAN',restarted.resolve(off(restarted,exact=j.id,ticket=old)).kind)
        with self.assertRaises(ValueError):restarted.apply_plan(p,'late-off')
        with self.assertRaises(ValueError):restarted.record(b,'NATIVE_ACCEPTED','late-ack')
        self.assertEqual('NATIVE_ACCEPTED',restarted.state(newb))

    def test_stale_plan_after_new_unknown_collision_cannot_commit(self):
        l=Ledger();l,a=admitted(l,note(l));p=l.plan(a,SELECTIVE)
        i=note(l,2,source=5);l=l.add(i);b=BindingId(i.id,0,BASS,11,60);l=l.bind(b)
        with self.assertRaises(ValueError):l.apply_plan(p,'off')
        with self.assertRaises(ValueError):l.apply_plan(Plan('READY_SYNTHETIC',a),'forged')

    def test_duplicate_intent_and_release_do_not_retry_or_mutate_history(self):
        l=Ledger();l,b=admitted(l,note(l));p=l.plan(b,SELECTIVE)
        next_l=l.apply_plan(p,'off');self.assertEqual('NATIVE_ACCEPTED',l.state(b))
        self.assertEqual('RELEASE_SUBMITTED',next_l.state(b))
        with self.assertRaises(ValueError):next_l.apply_plan(p,'off-again')
        with self.assertRaises(ValueError):l.record(b,'NATIVE_ACCEPTED','native-return')

    def test_cross_output_cleanup_cannot_hide_midi_unknown(self):
        l=Ledger();l,b=admitted(l,note(l));m=dataclasses.replace(b,endpoint=MIDI)
        l=l.bind(m).record(m,'SUBMITTED','send').record(m,'ADMISSION_UNKNOWN','ack-unknown')
        l,snap=l.interrupt(2);original=l
        next_l,status=l.cleanup(snap,{BASS:SELECTIVE,MIDI:SELECTIVE})
        self.assertEqual('BLOCKED',status);self.assertEqual(original,next_l)
        self.assertEqual('NATIVE_ACCEPTED',next_l.state(b))

    def test_s5_case007_preserves_two_ids_and_reports_ambiguous_authored_off(self):
        case=json.loads((ROOT/'tests/fixtures/f03_ownership_reference_s5.json').read_text())['all_cases'][7]
        l=Ledger();ids=[]
        for e in case['events'][:2]:
            event=EventId(e['ordinal'],e['source'],e['note'],e['tick'],e['ordinal'],e['byte_offset'],'FROZEN_S5_RAW')
            iid=InstanceId(1,case['style_sha256'],1,0,event,e['ordinal']);ids.append(iid)
            l=l.add(Instance(iid,case['section'],'S5_METADATA_ONLY',e['relative_tick'],e['tick'],'S5_SELECTED_POLICY_UNMODIFIED',0))
        e=case['events'][2];r=l.resolve(Off(EventId(e['ordinal'],e['source'],e['note'],e['tick']),l.ticket))
        self.assertEqual('AMBIGUOUS',r.kind);self.assertEqual(set(ids),set(r.candidates))
        self.assertEqual((),l.facts) # raw-input identity test, not transformed/native replay
        self.assertEqual('FIFO_OBSERVER_CONVENTION_NOT_YAMAHA_ORACLE',case['pairing'])

    def test_all523_saved_overlaps_retain_both_ids_without_fifo_or_audio_claim(self):
        ref=json.loads((ROOT/'tests/fixtures/f03_ownership_reference_s5.json').read_text())
        self.assertEqual(523,len(ref['all_cases']))
        for case in ref['all_cases']:
            l=Ledger()
            for serial,e in enumerate(case['events'][:2],1):
                self.assertGreater(e['velocity'],0)
                event=EventId(e['ordinal'],e['source'],e['note'],e['tick'],e['ordinal'],e['byte_offset'],'FROZEN_S5_RAW')
                i=Instance(InstanceId(1,case['style_sha256'],1,0,event,serial),case['section'],'UNKNOWN_PART',e['relative_tick'],e['tick'],'NOT_RECOMPUTED',0)
                l=l.add(i)
            self.assertEqual(2,len(l.instances));self.assertEqual('UNKNOWN',case['audible_failure'])

    def test_model_has_no_production_import_or_dispatch_callable(self):
        import ast
        p=ROOT/'tests/f03_architecture_prototype.py';tree=ast.parse(p.read_text())
        imports=[x.module for x in ast.walk(tree) if isinstance(x,ast.ImportFrom)]
        self.assertEqual(['dataclasses'],imports)
        self.assertFalse(any(isinstance(x,ast.Import) for x in ast.walk(tree)))
        self.assertFalse(any(n in p.read_text() for n in ['ctypes','subprocess','MidiInputManager','BassMidiPlayer','StyleSequencer']))

if __name__=='__main__':
    unittest.main(verbosity=2)
