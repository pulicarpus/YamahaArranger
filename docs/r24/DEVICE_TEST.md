# R2-4 Redmi Pad — Drum Punch diagnostic

Diagnostic only, default ORIGINAL. Not release/merge certification.
No global limiter or gain, source velocity, pitch, timing or preset change.
The four reviewed Colombo XG kick/snare keys alone use optional decode stems.
Shared versus independent FX tails may differ in LEVEL+/PUNCH+; not certified.

1. Install APK, load the same four SF2 and ROSITA.STY; verify golden fingerprints.
   Preserve app/REG data if Android reports a signature mismatch.
2. Set tempo 100 BPM. Keep mixer, FX, mute and bass settings identical for all runs.
   Select RESOLVED in SF2 / STYLE INSPECTOR while STOP.
3. Choose DRUM PUNCH R2-4: ORIGINAL. MARK PCM SESSION. START Main A–D,
   eight bars each; STOP and SAVE REPORT.
4. While STOP and after the playback job drains, choose LEVEL+. MARK, repeat
   the identical Main A–D sequence, STOP and SAVE REPORT.
5. While STOP choose PUNCH+. MARK, repeat and save. Prioritize PUNCH+ versus
   ORIGINAL; repeat O/P/O/P before deciding. No keyboard notes in paired runs.
6. Check DRUM_PUNCH mode 0/1/2, PUNCH_STEM prepared and postNoteReadback,
   DRUM_AB_BOUND Colombo fingerprint/bank127/PC0,8,16. Any fallback or nativeFailures
   invalidates an audio comparison; retain the report and return to ORIGINAL.
7. Compare PCM_SESSION peakSinceMark, RMS, clippedSinceMark/samples and nonfinite.
   Match frames, per-channel NOTE_ON counts, section sequence, mute states and
   zero mute changes. No nonfinite is acceptable. If clipping increases materially,
   stop the comparison and return ORIGINAL; do not call punch successful.
8. Listen for kick impact, snare attack, bass masking, unchanged hats/cymbals,
   pumping/clicks, FX tails and section transitions. Safe headroom restriction can
   reduce the requested boost to zero; maxAppliedExtra/guardedBlocks reveal this.
9. SAVE REPORT writes Downloads/YamahaArranger. Send all O/L/P reports together,
   with listening notes and any glitches; one unmatched peak number is insufficient.
10. Finally test STOP/START, mode refusal while playing, LIVE CONTROL, REG MEMORY,
    RELEASE, RIGHT OFF and E343 (Local Control OFF). Confirm mode changes and
    connection do not send automatic Bank/PC or extra MIDI notes to the E343.

The gain guard only restricts the added kick/snare contribution, linked across
stereo, with 20ms recovery. It does not fix existing clipping or attenuate bass.
PUNCH+ adds at most 6% attack gain decaying with a 12ms time constant, over a
12% level ceiling; real headroom sets the actual gain. Four extra BASS decoder
streams add device CPU cost. Host DSP timing excludes those decoder costs.
