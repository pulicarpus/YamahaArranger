# Registration voice recall and Master Transpose fixes

## Registration recall returned the wrong external voice
Cause (code + MIDI-byte tests): the normal voice selector (`setKeyboardVoice`) sends Bank MSB, LSB and Program to MIDI OUT but never
recorded that identity. Recall applied SF2 steps with no MIDI mirror (by design) and only sent an external voice when the slot
carried one from the EXT list. A slot saved after a normal selection therefore had no external voice (or a stale EXT-list entry), and
recall restored the on-screen SF2 voice while the keyboard kept the last voice it had received (Piano).
Fix: `Sf2ExternalMapping` is the single SF2 -> MSB/LSB/Program mapping used by the selector and by recall; the selector records what it
sends; recall of an SF2 step keeps the identity in step; the planner falls back to the mapping for slots without an explicit entry
(older BOTH slots recover). Not recoverable: older EXTERNAL slots, which stored neither an SF2 reference nor an external voice.
Not changed: SF2 recall sends nothing for INTERNAL, FREEZE VOICES and MIDI OUT OFF still send nothing, no automatic Bank/Program on
connect, no Piano default or reset. Recall now also sends the same CC72 release follow-up the selector sends.

## Master Transpose affected only RIGHT/LEFT
Cause: transpose lived in `ArrangerBrain` for keyboard notes only; `StyleSequencer` had just the per-channel override.
Fix: `StyleSequencer.setMasterTranspose`, added to the final melodic pitch after CASM (NTR/NTT) and the channel override, never to
rhythm/drum destinations. The ledger remembers the pitch used at NOTE_ON so NOTE_OFF always matches; retargeting a held note adds the
current transpose and root-only retarget removes the applied transpose before choosing the octave. Chord recognition still sees the
keys as played. A transpose change applies to the next note and to the next chord retarget; sounding notes are not moved.
