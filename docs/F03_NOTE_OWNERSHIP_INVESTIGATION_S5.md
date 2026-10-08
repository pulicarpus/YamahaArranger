# F03 Note Ownership & Lifecycle Investigation — S5

## 1. Identity and scope

Repository `pulicarpus/YamahaArranger`; branch `fix/mix-percussion-fidelity-784`.
Audited source commit and intended S5 parent: `98a9d2a79b6226bf14b0a4be624f494a878b2ac5`; source tree `4e37f5c4cca0bab82b92ee3262858fe60e541619`; baseline Build798 SUCCESS.
The S5 commit/tree are the Git identity of this document and its evidence, resolved using `git log -1 --format='%H %P %T' -- docs/F03_NOTE_OWNERSHIP_INVESTIGATION_S5.md`; no self-referential hash is embedded. The final Work report records the actual published S5 commit, parent, tree and matching CI run. The report is `/workspace/S5_FINAL_REPORT.md`, with machine-readable `/workspace/S5-CI-proof.json`.

**F03 ownership limitation PROVEN in production code and dispatch traces. Audible missing sound, actual wrong native voice release, stuck synth voices and hardware Main/Fill loss remain UNKNOWN. No playback fix.** F01 stays UNRESOLVED; the existing F01–F15 ledger remains byte-identical. No S6 implementation starts here.

All changes are tests, host-only facades, diagnostics/reference artifacts and this document. Production metadata is unnecessary. No production source/build/workflow changes; no new audio route, note, policy, SF2, gain, voice mapping or scheduler timing. The proposed S6 algorithm is not used to create expected output.

## 2. Complete lifecycle and code map

| Boundary | Existing implementation and observation |
| --- | --- |
| Raw SMF | `app/src/main/cpp/smf_reader.cpp:62` running status; `:123` stores authored events in input order. Raw status0x90/velocity0 is preserved as raw0x90, not rewritten. All503 raw-event digests match S1. |
| Section projection | `app/src/main/cpp/style_parser.cpp:195` markers; `:205` half-open sections, source grouping; `:217` subtracts section origin; setup copied separately. Both ON instances of all523 overlaps survive projection. Terminal FillBA+1 remains F12. |
| CASM binding | `style_parser.cpp:80` Cntt override and `:169` source-bound policy copies; S3 descriptors/bindings frozen. Source/destination and NTR/NTT/RTR remain original, not guessed. |
| Kotlin event decode | `StyleRepository.kt:184` production packed decoder; `:202` derives ON from status/velocity; `:218` policy decoder. S5 real-window tests call both unchanged decoders. |
| Candidate/transform | `StyleSequencer.kt:895` select policy; `CasmNoteTransformer.kt:10` NTR/NTT/HIGH KEY/note limits. Existing transform/gates are observed, not corrected. Legacy root-selection is still not consumed (F01). |
| Scheduler | `StyleSequencer.kt:814` private suspend `playOnce`; `:833` ON-first same-tick comparator (F02); `:861` pending transition cutoff; absolute timeline/phase remain original. Host serial replay sets only clock origin so events are already due, and does not certify real-clock latency. |
| Ownership | `StyleSequencer.kt:73` active owner; `:94` single map slot; `:969` source-key lookup; `:973` OFF previous owner; `:989` new owner installed only for policy-backed non-drum events. Owner includes sourceTick/section/policy but no generation/instance discriminator in key. |
| Scheduled release | `StyleSequencer.kt:549` removes current source:key slot; `:553` external OFF; `:953` no-active OFF is not forwarded. Incoming OFF has no instance/generation/section token. |
| Chord retarget | `StyleSequencer.kt:452` object-identity guard; selected new policy/destination; `:486` RTR0 stop,1–4 existing replacement variants,5 deferred; `:503` external replacement OFF. No-chord releases current owners `:432`. |
| Internal bridge | `AudioEngineManager.kt:219` style ON and `:234` style OFF; SF2 fallback differs; `native_lib.cpp:196`/`:209` carries origin metadata to `audio_engine.cpp:247`/`:344`, then BassMidiPlayer. Origins do not add a melodic native per-instance ownership gate. |
| BASSMIDI melodic | `bassmidi_player.cpp:104` requests NOTEOFF1; `:1149` ON validates engine/family, percussion adapter may divert; `:1186` note+velocity to API; `:1192` OFF forwards destination key after optional percussion handling. API result is distinct from Kotlin dispatch and PCM. |
| Percussion | `percussion_fidelity_policy.h:135` bounded Owners queue with source/generation; `bassmidi_player.cpp:2341` popSource guard. This is a separate existing rhythm adapter, not the sequencer melodic map. It is unchanged; F05 rhythm scheduled-OFF gap remains. |
| Diagnostics | `audio_path_diagnostic.h:40` sent-note timestamps are observer queues, not synth voices; `chord_change_diagnostic.h:147` origin comparisons are diagnostic only. Neither repairs registry identity. |
| External MIDI | `MidiInputManager.kt:180`/`:190`: enable/port gate, destination channel+key packet, no owner token; errors caught. `:209` stop CC123 sent to16channels even when MIDI OUT flag is off, if port exists. |
| Sample fallback | `AudioEngineManager.kt:225` SF2-not-loaded delegates sample path; `audio_engine.cpp:325` releases every active sample voice matching pitch, no channel token. PCM/fallback lifecycle not measured by S5. |
| Section transition | Interrupted transition `StyleSequencer.kt:337` releases the entire active style-owner snapshot (not a filter on sourceSection), then starts Fill/queues remaining sections. Natural queued transition `:374` does not clear owners. Comments describing outgoing-only ownership are stronger than the actual snapshot membership. |
| Stop/restart | `StyleSequencer.kt:399` cancels job, resets clock/queue, allNotesOff both boundaries, clears map under lock. No explicit generation exists in active owner or scheduled OFF. Cancellation delivery race is not proven by a synthetic old-OFF injection. |
| ACMP | `ArrangerBrain.kt:262` disables/reset detector and applied/pending chord, but does not clear sequencer.currentChord or style registry; re-enable can release keyboard LEFT notes separately. Real controller test confirms retained style owner during running synthetic section. Keyboard sustain/transpose maps are separate. |

Line references refer to the frozen S4 production sources. Source files under arranger/style/audio above are in `app/src/main/java/com/yourapp/yamahaarranger`; MidiInputManager is under `com/yourapp/midi`; ArrangerBrain is physically under `com/yourapp/arranger` but has Yamaha arranger package.

### Every source-channel + note key location

`StyleSequencer.kt:454` (retarget stale-owner check), `:538` (releaseActive object check), `:550` (scheduled OFF removes current slot), `:969` (NOTE_ON lookup/replacement), `:989` (new assignment). The map has no section, part, generation or individual raw ON ordinal in its key. SourceTick/section fields are evidence, not identity. `chordOwnerEvidence` prints peer source:key:diagnostic-ID but never selects/releases a voice. Keyboard `ArrangerBrain.transposedNotes` is pitch-only and separate from F03 style ownership. Native percussion queues, BASS destination-key API and diagnostic timestamp queues must not be conflated with this source:key registry.

## 3. Corpus overlap inventory

Original archive SHA-256 `a9b89b90e1dee25c714a076a125d6c7173bcca0e8d2029c57092b3295035d465`; exactly503files verified against S1 per-file identities. Native parser is compiled unchanged and each relevant full section is checked against the freshly regenerated full S4 manifest. No full manifest/corpus is committed.

**523 overlapping NOTE_ON candidates /47 styles**, maximum multiplicity2. All523 prior/current ON pairs reside in the same section; **10 complete ON/paired-OFF windows cross a section boundary**. These are different statements: an OFF may be outside the section even though both ONs are inside it. Original event offset points at delta-time start; ordinal/status/tick/velocity remain available, including running-status events and zero-velocity OFFs.

[Full523 inventory CSV](../tests/fixtures/f03_overlap_inventory_s5.csv) and [reference with all raw offsets, candidate policy tuples and native section digests](../tests/fixtures/f03_ownership_reference_s5.json) include every case. `all_cases[*].events` carries source/key/raw tick/relative tick/velocity/status/byte offset/ordinal/section. Every candidate policy is retained so routing is not inferred from the original channel. Generated JVM windows include every same-source/key event between the earlier ON and last paired OFF, clipped to authored section; other sources are explicitly omitted. These are **reduced real counterexamples**, not full-section audio captures and not synthetic musical notes.

The FIFO pairing is an observer convention only: SMF contains key-based OFF, not an individual ON instance ID. The all523 count uses the unchanged S1 convention. No overlap is automatically labeled audible failure.

| Style | Raw overlap candidates |
| --- | ---: |
| `Ballad/8BeatSoft.S686.bcs` | 7 |
| `Ballad/J-Ballad2.S261.prs` | 1 |
| `Ballad/PianoBallad1.S399.bcs` | 1 |
| `Ballad/PopBallad1.S732.bcs` | 1 |
| `Ballad/PopBallad2.S726.bcs` | 12 |
| `Ballad/PopBallad3.S374.bcs` | 104 |
| `Ballad/PopBallad4.S281.bcs` | 4 |
| `Ballad/SlowBallad1.S711.bcs` | 11 |
| `Ballrom/Rhumba.T006.bcs` | 1 |
| `Ballrom/Tango1.S069.bcs` | 5 |
| `Country/CountryTwoStep2.T147.prs` | 2 |
| `Country/LightPop.T015.bcs` | 1 |
| `Country/ModCntryBld1.S081.sst` | 1 |
| `Latin/Beguine2.S617.prs` | 34 |
| `Latin/BigBandMambo1.T109.sst` | 14 |
| `Latin/BigBandSalsa1.T109.sst` | 1 |
| `Latin/BigBandSamba1.T109.sst` | 3 |
| `Latin/BigBandSamba2.S215.sst` | 4 |
| `Latin/ComboSamba2.S725.pcs` | 6 |
| `Latin/CoolBossa.S629.sst` | 5 |
| `Latin/GuitarBossa1.S631.bcs` | 1 |
| `Latin/GuitarBossa5.S084.prs` | 8 |
| `Latin/GuitarBossa6.S081.prs` | 4 |
| `Latin/Merengue1.T109.sst` | 11 |
| `Latin/Montuno1.T109.sst` | 77 |
| `Latin/Montuno2.S725.sst` | 7 |
| `Latin/PopBossa1.S631.bcs` | 20 |
| `Latin/PopChaCha.S628.bcs` | 4 |
| `Latin/Reggae.S655.bcs` | 2 |
| `Movie&Show/BroadwayBallad1.S859.prs` | 9 |
| `Movie&Show/BroadwayBallad2.S718.prs` | 9 |
| `Movie&Show/SaturdayNight2.S517.prs` | 12 |
| `Movie&Show/Showtune1.S899.bcs` | 4 |
| `Pop&Rock/6-8Rock.S115.bcs` | 2 |
| `Pop&Rock/80'sFusion.T161.bcs` | 19 |
| `Pop&Rock/8Beat01.T110.bcs` | 21 |
| `Pop&Rock/8Beat02.T105.bcs` | 7 |
| `Pop&Rock/8Beat03.S014.bcs` | 12 |
| `Pop&Rock/8BeatModern2.S077.prs` | 1 |
| `Pop&Rock/8BeatPop1.T110.bcs` | 16 |
| `Pop&Rock/8BeatRock1.S619.bcs` | 21 |
| `Pop&Rock/FusionShuffle1.S717.bcs` | 12 |
| `Pop&Rock/GuitarPop3.S557.prs` | 2 |
| `Pop&Rock/J-PopHit.S851.prs` | 1 |
| `Pop&Rock/JazzRock1.S547.bcs` | 7 |
| `Pop&Rock/Rock1.S087.bcs` | 2 |
| `Pop&Rock/SwedishPopShfl.S732.prs` | 14 |

## 4. Production replay evidence and minimal counterexamples

1046 deterministic real-window executions =523windows × C-major/F-major. Production raw/parser policies are verified first; unchanged Kotlin event/policy decoders, selector, transformer, scheduler and registry execute. Both output boundaries are mocks; real external serialization and native production-player API calls are tested separately. This is not one joined JNI/hardware/PCM session.

The existing chord observer retains mostly destination11 scheduled context. S5 test-only spy captures **all pure diagnostic record strings** and still calls the original observer. It changes no candidate, owner, event ordering, synth/MIDI argument or sampling decision. Expected S1 captures are checked byte-for-byte. Diagnostic IDs/wall times are normalized out; raw/projection/dispatch ticks and velocities remain. The diagnostic lambda is read twice only as a pure string snapshot; production musical code is not mocked.

| Per523-window root pass | C-major | F-major |
| --- | ---: | ---: |
| Owner replacement reached | 276 | 276 |
| No owner replacement reached | 247 | 247 |
| Any orphan OFF at sequencer | 518 | 518 |
| Existing chord-policy rejection observed | 56 | 56 |
| Strict separated on1<on2<off1<off2 FIFO duration witnesses | 31 | 31 |

These categories overlap.272 replacement windows also have an orphan;4 replacement windows do not.190 no-replacement windows have an orphan without policy rejection;56 have rejection+orphan;1 no-replacement window has no orphan. **518 is not a defect/audible-failure count**, since untracked rhythm/no-policy paths and dropped ONs can produce expected/no-owner OFFs. F02 same-tick interactions stay separate; strict31 excludes ON/OFF same-tick ambiguity and cross-section windows.

[Complete1046 normalized production traces](../tests/fixtures/f03_dispatch_trace_s5.txt); [joined raw→dispatch→release/classification/first-divergence summary](../tests/fixtures/f03_dispatch_summary_s5.json). Every row provides transformed destination/pitch/velocity and final owner state. Classifications for both roots match, while pitches may differ; this is a source-identity observation, not invariance of musical output across chords.

### Counterexample007: J-Ballad2, EndingB, distinct duration witness

Original style `Ballad/J-Ballad2.S261.prs`, SHA `2938cdd500b628eabe88f44ce670f1af9ee7dee54b23599d8ded045b31f3a2c9`; native section SHA `1f6cc1dbfa1cfa6af0d4c8786124d6841a49eb575d822164eba3e15dc84c8699`. Source11/key67→destination11, C-major transformed67 (F-major69), NTR1/NTT2/RTR1, source chord0/type2. Ownership key `11:67` for both ON instances.

| Original ordinal | Byte offset | Raw tick | Section tick | Event/velocity | Observed production owner/dispatch |
| ---: | ---: | ---: | ---: | --- | --- |
| 3739 | 15592 | 202560 | 2880 | ON67/76 | ON dst11/pitch67; slot points to origin2880 |
| 3759 | 15685 | 204480 | 4800 | ON67/74 | **OFF old67 before any authored OFF**, remove owner2880; ON67; slot replaced by origin4800 |
| 3761 | 15693 | 204600 | 4920 | OFF67/0 | Remove current owner4800; OFF67; map becomes empty |
| 3832 | 15990 | 208780 | 9100 | OFF67/0 | OFF_NO_ACTIVE_LEDGER; neither boundary receives OFF |

At ON2 the raw source has two pending ON instances; registry cardinality is limited to one, and dispatch adds an early OFF. This structural first divergence is independent of selecting a Yamaha bit mask or repairing transform semantics. Under the declared FIFO observer model, the second instance loses 4180ticks (9100−4920). The exact Yamaha/repeated-note/device release semantics and actual audible duration are not certified by that arithmetic.

### Counterexample000: equal paired-OFF tick is not a distinct-duration proof

`Ballad/8BeatSoft.S686.bcs/MainA`, source14/key58→dst11/pitch60(C-major), velocities63/63. ON ordinals177/215 at offsets945/1104; relative ticks5760/9600. OFF ordinals220/221 at offsets1125/1130 share tick11440. Replacement and orphan are proven, but identical OFF endpoint does not establish a separate long second-note truncation interval. This prevents conflating every collision with the stronger duration witness.

## 5. First divergence separated by boundary

1. **Raw preservation:** no divergence; all raw events/corpus/projection/policies preserved by S1–S4 guards.
2. **Selection/transform:** existing selected policy/output recorded, with all alternative candidates retained; policy-drop/no-owner paths are not asserted as F03 collision.
3. **Owner admission:** first F03 divergence at `StyleSequencer.kt:969`, with actual early release at `:973` and replacement assignment `:989`. Two same-source/key ON instances cannot coexist. Old owner is not silently overwritten: an actual OFF is sent first.
4. **Scheduled OFF:** `:550` consumes whichever owner occupies source:key at that time; no match to source ordinal/generation/section. First OFF can remove the newer owner; final OFF drops at `:953`.
5. **Dispatch:** internal/external channel/pitch/velocity arguments match in all1046 replays. Owner/collision divergence already exists before JNI/Android MIDI port.
6. **Native acceptance:** separate unchanged native player test verifies requests and NOTEOFF1 configuration via API mock, not actual SDK acceptance in these cases.
7. **Execution/PCM:** UNKNOWN; no claim that a dispatched OFF terminates a particular audible voice, no measured Main/Fill sound loss.

## 6. A–J reproduction and transition relationships

| Scenario | Fixture/provenance | What is asserted |
| --- | --- | --- |
| A/B two ONs, first OFF while second nominally pending | Synthetic plus all523 reduced real windows | Single slot replaces origin0 with origin1; early OFF, current-owner removal and final orphan; exact both-boundary transcript. |
| C two sources→same dst/pitch | Synthetic production selector/registry | Two separate source owners remain; source5 OFF carries dst11/pitch60 only. No native voice identity token; wrong audible owner UNKNOWN. Different-pitch negative control releases correct pitches. |
| D MainD→FillB→FillA→MainA | Clearly synthetic sections named MainD/FillBB/FillAA/MainA; real startPlayback coroutine and internal transition consumption | Test installs pending transition at exact synthetic tick1 from dispatch callback; outgoing owner released once, queued sections start in requested order, no global allNotesOff, no dangling final owner. Does not certify GUI button mapping, physical phase or real authored transition correctness. |
| E MainD→FillB→MainB | Same synthetic procedure, real production coroutine | Same termination/queue assertions. Both D/E use loopLimit1 and high PPQ, not wall-clock performance tests. |
| Natural section boundary | Synthetic serial playOnce MainD held note→FillBB OFF | Natural end preserves registry; incoming same-source/key OFF can consume prior-section owner. No sourceSection identity filter. Not automatically a bug—cross-boundary authored note termination may be intended. |
| F chord change | Synthetic real currentChord setter; RTR1/root trans and RTR0/no-chord controls | Same owner object updates output60→65; old OFF/new ON; scheduled OFF releases65. RTR0 stop and no-chord release are EXPECTED_NOTE_TERMINATION, not F03 fixes. |
| G stop/restart/generation | Synthetic actual stop plus serial restart and injected old OFF | stop emits ALL_OFF both boundaries/clears map; stale owner object cannot release replacement due identity guard. Scheduled old-OFF injection can consume new owner; no generation field. Actual late coroutine race remains UNKNOWN. |
| H same-tick ON/OFF | Synthetic unchanged comparator, existing S1 F02 capture also pinned | ON-first replacement and orphan preserved. No F02 repair; not treated as independent proof of F03 duration under authored same-tick semantics. |
| I remapping/internal/external | Synthetic owner destination11 vs incoming OFF policy13; real MIDI serializer; native production player separately | Scheduled OFF uses stored destination11. External packets destination13/pitch65 are exact0x9d/0x8d; disabled/missing-port/error cases observed; backend token gap preserved. |
| J ACMP during running style | Clearly synthetic held note; actual ArrangerBrain+sequencer coroutine, dispatch latch | OFF resets detector/applied state but chord and exact owner object remain; ON retains them; no additional style-release dispatch; normal scheduled OFF still clears owner. Keyboard LEFT releases are separate. |

All A–J assertions are current-code observations, not desired output fixtures for a future fix. Tests are not skipped/disabled. Synthetic delayed-OFF injection and exact-tick transition queue installation are explicit fixture controls, not evidence of real timing races.

## 7. Defect classification and epistemic status

| Classification | Status/evidence |
| --- | --- |
| SOURCE_OVERLAP | **PROVEN** 523 authored candidates/47style; raw offsets and both projected ONs. Not PCM failures. |
| IDENTITY_COLLISION | **PROVEN** single source:key slot and276 routed real-window replacement cases; all523 retain raw overlap classification even when not admitted. |
| OWNER_REPLACEMENT | **PROVEN** old OFF+remove then new assignment; trace and code. |
| ORPHAN_NOTE_OFF | **PROVEN at sequencer**518 windows; mix of replacement/drop/rhythm/no-owner paths, not518 bugs. |
| PREMATURE_NOTE_OFF | Early replacement OFF before authored OFF **PROVEN at dispatch**;31 strict witnesses support shortened newer interval relative to FIFO observer. Actual musical/device shortening **UNKNOWN**. |
| STUCK_NOTE | **UNKNOWN audible/native**; final map empty does not prove no sounding voice; rhythm one-shots and backend rejection/fallback are separate. |
| GENERATION_MISMATCH | Missing generation discriminator **PROVEN**; injected stale scheduled OFF consumes new owner **PROVEN under injection**; spontaneous stop/restart race **UNKNOWN**. |
| SECTION_TRANSITION_LOSS | Potential registry/instance loss **SUPPORTED** by one-slot replacement and natural boundary carry; actual D/E Main/Fill audible loss **UNKNOWN** without captured user session/hardware output. |
| EXPECTED_NOTE_TERMINATION | **PROVEN existing code** interrupted transition releases active snapshot; RTR0/no-chord/stop release; normal same-source scheduled OFF. Yamaha correctness of every transition remains outside proof. |
| UNKNOWN | Hardware voice release pairing, GUI timing, external receiver implementation, audio dropout/scheduler races, PCM envelopes and original Yamaha repeat-note intent. |

F03 relates to Main/Fill in two ways: lost old instances cannot be included in an outgoing snapshot, and natural section queues retain source-key ownership across sections. Interrupted transitions do intentionally terminate all currently tracked style owners; that termination alone is not a bug. Source audio loss before ownership (CASM rejection, articulation or SF2 family gate) must remain separate from F03.

## 8. Internal BASSMIDI vs external MIDI OUT

The sequencer delivers equivalent scheduled source-derived dst/pitch/velocity sequences in the controlled tests. Internal melodic BASS requests are destination-key-only at the final API even when diagnostic origins include source. NOTEOFF1 is configured; source5 OFF for a shared destination key cannot express a selective source5 native voice token. The C++ API recorder verifies requests/flag, not oldest-voice runtime semantics. A Kotlin per-source queue alone cannot prove safe behavior for all cross-source, same-destination/pitch overlap cases.

External MIDI packets also have no source/generation/ON-instance ID. Receiver may use oldest/newest/all matching-note termination; no Yamaha/external receiver implementation is inferred. MIDI OUT disabled or port missing can drop sends while internal audio proceeds. Exception handling allows sequencer to continue without acknowledgment. Stop CC123 behavior is independent from note flag gate. Internal SF2-not-loaded sample fallback can release all same-pitch sample voices; that is a distinct backend scope, unchanged here.

Native8 checks execute the unchanged BassMidiPlayer with synthetic SF2 metadata and mocked API, including shared-key/source-origin requests, current replacement transcript, stop16channels and failed-note attempts. Existing real-SDK CI percussion checks remain workflow gates, but do not certify PCM for these F03 melodic examples.

## 9. S6 proposal — NOT IMPLEMENTED

**Smallest candidate for same-source F03:** replace the one-slot source:key value with a bounded collection of immutable note-instance records. Each admitted ON records an instance token, original ordinal/part/source key, current output destination/pitch, section/session generation, admission/dispatch state and original policy. ON2 must not fabricate OFF solely because the source key already exists. Retarget/release operates on exact live instance token; all tracked instances must be visible to transition/stop cleanup.

Scheduled OFF needs an explicitly approved pairing contract (FIFO candidate, consistent with the present observer convention and configured NOTEOFF1 intention, **not a Yamaha-certified oracle**). Dropped/rejected ONs need accounting that cannot consume a different admitted instance. Generation should reject obsolete scheduled work before it can mutate a newer session. Add state guards without changing clock/comparator/transform/preset code.

**Safety blocker for blind queue-only implementation:** multiple source identities can converge to same output destination/pitch, and BASS/external APIs expose no per-instance handle. Per-source FIFO does not guarantee the correct native voice when OFF order differs from global ON order. S6 must either obtain reproducible backend/receiver release fixtures and constrain its scope to certified cases, or separately authorize a backend-aware ownership design. Reference counting/coalescing/retrigger changes can alter sound and are not silently acceptable. No chosen suppression/coalescing policy is implemented or certified here.

### Proposed acceptance criteria/regression gates

1. Same-source/key two ONs coexist in admitted logical registry; no replacement OFF without authored/RTR/transition/stop reason. OFF pairing policy approved explicitly; synthetic A/B and31 distinct-tick real witnesses prove correct instance lifetime, final cleanup and no orphan caused by owner overwrite.
2. C same-output foreign-source OFF is tested with real BASS SDK and audible/voice-state evidence; external receiver semantics captured or declared device-specific. Do not assert “wrong voice fixed” from Mockito/API mock alone.
3. Both D/E real GUI/phase transition paths captured with source/instance/generation/dst/pitch/dispatch/native-result/PCM trace; preserve expected interrupted cleanup and natural-boundary semantics. Synthetic D/E remain controls.
4. Chord retarget, RTR0–5, no-chord, ACMP, stop/restart/generation, retained notes, rejected ON and channel remap covered. Reject stale generation deterministically; do not discard new-session OFF.
5. F01 remains UNRESOLVED; F02 and F04–F15 untouched. Production changes limited to approved ownership scope. No comparator/transform/CASM/voice/gain/percussion changes as incidental cleanup.
6. All503 corpus raw/parser/projection/policy/music/golden references remain exact. A real playback fix will intentionally change F03 behavioral capture/digest; **user approval and separately enumerated F03-only expected deltas are required before changing any frozen S1 musical expectation**. All non-F03 digests stay identical. S5 grants no such change.
7. Full CI,249 current S5 tests plus new S6 meaningful assertions, both native ABIs, Stage3 APK exclusion guard, artifact/Telegram workflow. No skipped tests, no corpus changes, no automatic fixture re-recording.

### Risk/rollback

Risks: FIFO-versus-receiver mismatch, unbounded owner retention, dropped-ON pairing, chord retarget multiplicity, source→destination collisions, section ownership cleanup, cancellation races and one-shot rhythm regression. Rollback smallest ownership commit to S5/S4 behavior; keep diagnostics/reference history. Do not remove F03 counterexamples to hide regression. Existing source guards pin full production identity; a future authorized S6 source identity change needs explicit file hashes and independently retained non-F03 behavioral gate, not removal of protection.

## 10. Validation, preservation and CI artifacts

Local S4 baseline: conformance503/503;63+5=68 JVM tests; host regression PASS. S5: **83 JVM tests PASS** (=68 unchanged+12ownership+3real MIDI/ACMP);1046 real-window traces; native8API-recording checks; S5 evidence/source negative gates7PASS. S2/S3 source guards4+4 andS4 evidence10 remain PASS. S3 full503matrix/71239bindings and S4 full503/53word/384hypothesis audits PASS.

Exact191 existing S4 production/build/workflow/test/golden/reference files are hashed in [preservation reference](../tests/fixtures/f03_preservation_reference_s5.json); portable125-production source/tree guard remains exact. Existing test/harness sources are unchanged except additive entry points in the two host tools. Largest new references remain far below16MiB. Corpus/full event manifest remain generated, uncommitted.

| Identity | S4 baseline / S5 result |
| --- | --- |
| Production diff vs S4 | EMPTY |
| Physical production source-table SHA256 | `fd3ec12803ecaebb6f220451d5ccc8fe1f96282ee6c64d4c18076e9958a74dff` IDENTICAL |
| Musical canonical SHA256 | `21ac9cc72e12523d2d0f791be69da7626e143d44d0fd0f1c70a20af26e7ba308` IDENTICAL |
| Full generated manifest SHA256 | `7701f47cf7cf7e8166cd0ba6ffdc5a4792af7ba2743e8b386fa1237fb086f56b` IDENTICAL |
| Manifest canonical content SHA256 | `080bc872b7269aef2cd9d5f3cac86eafef2c2e0e4a506054eccce57cb815e9c8` IDENTICAL |
| S3 semantic matrix SHA256 | `eaaaed3566c60e14e8a8910b1b646e87f52f1077d61a9b61cbef876b557be7ac` IDENTICAL |
| S4 evidence SHA256 | `39e3a406eed42d827c7fc894700d882f09d429171061cd472c28565ef9643ad9` IDENTICAL |
|20 old capture/golden/routing/order digests | BYTE_IDENTICAL |
| F01–F15 ledger | BYTE_IDENTICAL |
| S5 raw overlap inventory evidence SHA256 | `109b14870b8667cb65898ebbd69ebfaefb0aade219c134179940b1256fc56d54` new observational artifact |
| S5 normalized1046 dispatch trace SHA256 | `59a4feffa24ed6ddd376587ddb74c35a09356a2f437b7406f9feb03f963eadb5` new observational artifact |
| S5 joined summary SHA256 | `7fd785ad9377484595529cc70ed0d12fd7edc1bb78ecabfcd394fd9298150395` new observational artifact |

CI is run only after publishing the S5 tree through GitHub integration. Required checks include host regression, S5 evidence/native probes, all Android unit tests, arm64-v8a/armeabi-v7a builds, Stage3 exclusion in both APK libraries, APK+proof artifacts and Telegram. The final commit-specific run URL, actual test counts, ABI step conclusions, artifact IDs/archive hashes and Telegram conclusion are supplied in the final Work report/CI proof referenced in section1. No success is inferred from baseline Build798 or from a dispatch-only mock. CI portability failures may change only S5 test tooling; production/musical expectations must remain fixed.

**STOP after S5 verification and final report. S6 is a proposal only.**
