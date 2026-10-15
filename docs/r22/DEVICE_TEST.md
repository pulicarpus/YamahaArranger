# R2-2 Shadow DrumKit diagnostic — Redmi Pad

This APK observes; audio remains Policy C / legacy drum selection. Playback catalog OFF. No kit switching, gain/FX adjustment, automatic external Bank/PC or R2-3 activation is provided. No audio improvement is claimed. Historical full regression source-profile CI remains BLOCKED.

1. Install the signed debug APK from this commit's Actions artifact. Preserve app/registration data if Android reports a signing conflict; do not uninstall to resolve it without backing up your data.
2. Load the same four SF2 once, load the original ROSITA.STY, and select INTERNAL. STOP. Open SF2 / STYLE INSPECTOR. R2-2 SHADOW should show ON. Read DRUM KIT SHADOW to refresh the visible report.
3. Set the S1 drum-only mute configuration (ch10–15 muted; keyboard untouched), wait at least 3 seconds, then MARK PCM SESSION.
4. START Main A for 8 bars, Main B for 8 bars, Main C for 8 bars, Main D for 8 bars, STOP, wait 5 seconds, SAVE REPORT. Use the top SAVE REPORT button, not the smaller drum-only export.
5. While STOP, set R2-2 SHADOW OFF. MARK a fresh session and repeat exactly the same sequence. Alternate ON/OFF for three pairs without app restart or SF2 reload. The toggle only controls shadow observation; it never switches audio kits.
6. Export reports from Downloads/YamahaArranger/YamahaArranger_Inspector_*.txt. Keep ON/OFF filenames paired. With shadow OFF the report retains previous rows, explicitly labeled enabled=false; submitted/completed should not increase.

Verify before accepting a pair:

- NOTE_ON deltas, including keyboard=0, match; muteChangesSinceMark=0 and mute states match. PCM durations/frame counts should be comparable.
- Current actual kits still Prime legacy. Main A ch9 actual Kendang Alex versus shadow Colombo 127/8 XG Room; Main B ch8 actual ALEX KEPRI versus shadow Colombo 127/16 XG Rock. All other audited Main A–D requests shadow Colombo 127/0 XG Standard 1.
- Actual Kendang coverage 34/42 missing key33; ALEX KEPRI 8/10 missing key34; Colombo full coverage for every audited main section.
- No extra MIDI traffic, SF2 reload, controller change, preset remap, unexpected bank/program or keyboard activity on E343 connection.
- Inspect DRUM_KIT_SHADOW: dropped=0, submitted=completed, actualReason agrees with NATIVE resolverReason for the corresponding current request. Actual identity is cached native selection evidence, not independent per-voice BASS sample binding proof. Re-export after STOP if worker completion is pending.
- p99Us <2000 on the device; compare fallbackCallbacks / underrun counters against shadow OFF. Host performance does not certify device latency or underruns.
- SFF2 runtime detection is inherited UNKNOWN when the baseline cannot establish it; no new dialect detection is claimed. Source SINT_OR_TICK0_SETUP preserves raw setup values but does not distinguish injected SInt from ordinary tick-zero MIDI setup.
- Repeat with 2–3 other SFF1/SFF2/GS/custom/unknown styles, and manually verify LIVE CONTROL, REG MEMORY, RELEASE, RIGHT OFF and MIDI OUT. Existing custom program/bank overrides without a fingerprint kit identity are unresolved by shadow, never guessed.

Original ROSITA byte replay remains UNVERIFIED until the exact style used in S1/S2 is supplied. The host audit-range fixture proves the supplied Main A–D coverage table over each entire observed min/max velocity interval; it does not reconstruct missing event velocities or style bytes.

If any output or sound changes between shadow ON/OFF, stop testing and send both complete reports. R2-3 resolver activation and audio A/B remain separate, unauthorized work.
