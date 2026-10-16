# R2-3 Redmi Pad diagnostic A/B

Diagnostic only. Default LEGACY. No merge/release or improved-sound claim.
Historical full regression CI remains BLOCKED by baseline source-profile guards;
this APK passes the separate R2-3 functional and exact-source gates only.

1. Install the diagnostic APK; import the four original audited SF2 files and
   ROSITA.STY. Check all four PDTA golden fingerprints in the Inspector.
2. STOP. Select LEGACY in SF2 / STYLE INSPECTOR. If the app asks to wait for
   playback jobs to finish, wait and retry. Never toggle during START.
3. Mute channels 10–15 for the drum-only comparison; leave channels 8/9 active.
   Do not touch the keyboard. Press MARK PCM SESSION while stopped.
4. START Main A for 8 bars, then Main B/C/D each for 8 bars; STOP and SAVE REPORT.
   Keep tempo, section sequence, mute and controllers equal between runs.
5. Repeat in RESOLVED, alternating L/R/L/R/L/R (three runs each). Mark each run.
6. In RESOLVED, DRUM_AB_BOUND must report bindingVerified=1, actualBank=127,
   the Colombo fingerprint and actualPC 0 (Standard), 8 (Room), 16 (Rock),
   following requests/sections. Readback uses the same native stream as decode
   and real NOTE_ON dispatch. A failed binding is silent, never implicit Prime.
   DRUM_AB_BOUND is a current snapshot, not a per-sample audible-voice proof.
7. Save reports from Downloads/YamahaArranger. Compare PCM_SESSION peakSinceMark,
   clippedSinceMark, RMS and per-channel NOTE_ON deltas. Note counts must match
   between paired runs; muteChangesSinceMark must remain 0. Log the section
   sequence separately; the current snapshot is not a complete frame timeline.
8. Repeat a full-mix comparison with all style channels active and equal controls.
9. Check STOP → LEGACY/RESOLVED → START for stuck notes and stale kits; check
   LIVE CONTROL, REG MEMORY, RIGHT OFF and RELEASE. Connect PSR-E343 with Local
   Control OFF and confirm no automatic keyboard Bank/PC messages on connection.

No gain/limiter adjustments are part of this experiment. Preserve the reports
and describe missing hits, timing, sound and clipping without assuming Colombo
is louder, quieter or musically better. Device audio, real BASS audibility and
Android performance remain UNVERIFIED until these runs are supplied.
