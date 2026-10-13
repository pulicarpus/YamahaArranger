# RIGHT OFF note lifecycle experiment

Base: successful APK run 38323200939, source 313bc115bb9f7a8b6ed6a6bd5da192fda7b38d3f.
Branch: experiment/right-off-note-lifecycle. No baseline merge.

The deterministic reproduction on the base failed: NOTE_ON followed by RIGHT OFF
never sent NOTE_OFF. The switch only changed an enabled flag; subsequent key-up
filtered its NOTE_OFF using that new flag. No per-part note ownership existed.
StyleSequencer.stop already sends global allNotesOff, explaining why Start/Stop
clears the hanging note. Its behavior is unchanged by this experiment.

RIGHT notes now retain their source key, owning channel and original output pitch.
OFF releases only that part's down/sustain-held notes, then sends channel-scoped
BASSMIDI SOUNDOFF (and external CC120 when MIDI OUT is enabled). This also stops
voices already in their RELEASE stage. Normal key-up still uses the existing
RELEASE envelope. Re-enabling a layer never retriggers an already held key or
creates ownership for it. Delayed key-up cannot target a newly enabled layer.

Keyboard UI setters and MIDI callbacks serialize ownership and native delivery
with a Kotlin monitor. A bounded latch test holds NOTE_ON inside the JNI mock and
requests OFF concurrently; OFF must complete after that NOTE_ON and its matching
NOTE_OFF. No queue of keyboard note events exists between JNI and BassMidiPlayer;
its existing synth mutex orders these events against rendering. No native mutex,
style scheduler, clock, CASM, fill or transition changes were made.

Tests cover all three RIGHT parts, key-down/OFF/key-up, key-up/OFF during long
release, Sustain ledger isolation, transpose/split/mode changes, OFF/ON while held,
external CC120 boundaries and the concurrent JNI case. Native PCM cases keep
other RIGHT held/releasing voices, LEFT and all ACMP channels sounding, then
measure that only the target voice stops and all presets/RELEASE values survive.
Android audio validation of this correction remains required after APK creation.

Brain changes are limited to keyboard fields/methods. The experiment gate pins
the entire reviewed Brain SHA and independently compares style state/loading,
chord/section/start-stop code and the RELEASE setter to the frozen checkpoint.
Adversarial tests reject style or RELEASE edits even with a changed review hash.
Original SHA guard sources/manifests and RELEASE probe files are pinned to 313bc115
and remain unchanged. The old F12 guard already fails for the unchanged checkpoint
StyleSequencer; that baseline failure is reported separately and never suppressed
or weakened. This experiment is not READY certification.

The Linux SDK cannot be downloaded in this managed workspace (HTTP403 observed
previously). Real BASSMIDI PCM is a mandatory Actions gate before any APK build.

Local validation: 36 JVM tests, 2205 recorder checks, 30 envelope checks, four
review-scope guard tests and seven native regression suites PASS. The initial
checkpoint reproduction failed as expected (missing NOTE_OFF); it now passes.
The untouched old F12 guard remains FAIL on the baseline StyleSequencer hash.
Real PCM and Android behavior are not inferred from the recorder results.
