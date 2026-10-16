# LIVE/REG NOTE_OFF capture investigation

Baseline: d456d733c788b46ae06fe09c8b687eeb4915d554. Production R2-3 drum
binding changes were retained throughout. No scheduler, transport, registration
implementation or MIDI OUT change was used to resolve this test failure.

The failed assertion requires `[138,36,0]` after external registration recall.
The test waits until a concurrently written recorder contains NOTE_ON, then
clears that recorder. Waiting for NOTE_ON does not reserve an interval before
NOTE_OFF: the producer can emit OFF before the observer resumes and clears it.
The production clock anchor is established before setup; overdue events can
already be due when the worker dispatches them. This is shared baseline behavior.
The fixture's tick120/PPQ480 at 120 BPM has a nominal 125 ms OFF deadline, not a
guaranteed 125 ms window in which the test thread can reset the recorder.

## Evidence and limits

The original single method was run 30 times on each version in the same host,
JVM, dependency set and runner: 30/30 PASS on baseline, 30/30 PASS on draft.
The prior full-suite failure therefore was not treated as a deterministic
R2-3 source regression, nor silently replaced by one successful retry.

An explicit observer interleaving was tested on scratch copies: allow natural
section completion before resuming the observer's reset. The production methods
and original assertions were unchanged. Both versions fail 3/3 at the original
NOTE_OFF assertion. The trace records the real MIDI port call `[138,36,0]`
before RESET; the post-reset capture lacks it. Example baseline/draft traces
contain NOTE_ON, NOTE_OFF, natural completion, RESET, then external Bank/PC/CC11.
This experiment deliberately models a late observer. It does not claim external
recall occurred before natural completion in that particular diagnostic run.
It proves the recorder can erase a correctly emitted OFF and reproduce the
failure signature. The historical failed run lacked a pre-reset trace, so its
exact thread interleaving cannot be recovered retrospectively.

## Final fixture correction

Only the failing test fixture is changed. A CountDownLatch is released when
external recall completes. The mocked native NOTE_OFF callback waits on that
event before returning to the sequencer's real MIDI NOTE_OFF path. The test
also explicitly asserts that style completion has not occurred before recall.
The recorder reset, recorder snapshot, all original assertions, clock and real
sequencer/MIDI dispatch remain unchanged. A finally block releases the latch
on failures before stopping the test sequencer. The three-second bound detects
a deadlock; it is not a sleep or a fixed wait inserted to make timing pass.

This guarantees the intended concurrent recall/active-style scenario without
requiring the test observer to win a scheduler race. An earlier append-only
recorder prototype was discarded: preserving the original capture window plus
an explicit active-style handshake provides a stronger concurrency contract.
No such prototype changes remain in the committed fixture.

The reviewed fixture hash is pinned separately from production hashes. The new
boundary verifier retains all original assertion text and rejects fixture/guard
mutations. The original historical guards themselves are byte-identical and
remain separately incompatible with the newer diagnostic source profile.

## Final verification

The final handshake fixture passes 30/30 isolated repetitions on each original
baseline and draft binary. Deliberately postponing the observer until the
scheduled native OFF is pending passes 3/3 on each: the style remains active
until recall releases that OFF. A negative port-recorder mutation that drops
`[138,36,0]` still fails the original assertion 3/3 on each. No original
assertion was changed. The full LIVE/REG suite passes 85/85 with the final
fixture. Machine-readable counts and trace digests are in recall-evidence.json.
