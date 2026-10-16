# YamahaArranger — AI Handoff (Claude Code ↔ Codex)

Updated: 2026-10-16. This document is the shared project handoff, not proof of completion. **Read and update this document at every agent handoff.**

## Repository and protected baseline
- Repository: `pulicarpus/YamahaArranger`.
- Approved migration baseline: `experiment/bassmidi-r2-3-drum-ab`, SHA `c75732f28fec58fbfbc7e626bc380b10a00722b8`. Diagnostic Android two-ABI Actions run `38833703203` succeeded.
- Working migration branch: `experiment/colombo-primary-voice-override`, reported created by Claude from R2-3. **Verify remote branch HEAD, ancestry, worktree and unpushed changes; do not assume the screenshot means code was pushed.**
- `main` is outdated; do not base migration on `main`. R2-4 Drum Punch (`experiment/bassmidi-r2-4-drum-punch`) is NOT the migration baseline.
- Technical plan from Claude: `COLOMBO_PRIMARY_STYLE_VOICE_PLAN.md` (place in `docs/COLOMBO_PRIMARY_STYLE_VOICE_PLAN.md` if not already tracked).

## Owner-approved target
1. **Colombo GM/GS2 is PRIMARY** for melody and drums; **Tyros 4 SECONDARY**, only when Colombo lacks a compatible preset. Match bank MSB/LSB, PC, instrument family, soundfont identity and drum mapping; do not choose solely by file name, load order or same PC.
2. Prime Drum SX700/SX900 and Prime Melodi SX700/SX900 must not auto-load or enter the automatic candidate pool under this policy. Do not delete general user ability to load SF2.
3. Preserve R2-3 RESOLVED drum identity: ROSITA Main A Rhythm2 requests XG Room bank127/LSB0/PC8 and should resolve to Colombo XG Room, not Prime Kendang Alex. Tyros GS drums only as compatible fallback. Unknown/custom kit: clearly UNRESOLVED, no arbitrary kit substitution; user may override manually.
4. Add **Style Voice Inspector** showing for each section/part/channel the source request (CASM, CC0, CC32, PC, dynamic bank/program events, missing-vs-explicit controls) and actual native SF2/bank/program/preset binding with resolver reason. Export via SAVE REPORT. Do not confuse requested and actual binding.
5. Add **Mixer manual voice override** selecting exact Colombo/Tyros SF2 preset including drums. Override must survive dynamic PC and Main/Fill transitions; RESET TO STYLE restores auto.
6. **Persist mapping per style** by file SHA-256, per channel across all sections in v1; design for per-section expansion. Use separate atomic/backup/quarantine store; validate SF2 fingerprint and preset identity on restore; mark STALE without deleting saved choice. No override leak to other styles. No change to REG MEMORY schema.
7. Internal voice override must NOT send automatic Bank/PC to PSR-E343, especially on connection. Preserve MIDI OUT behavior and RIGHT OFF.
8. Preserve CASM/ACMP, transport/scheduler, LIVE CONTROL, REG MEMORY, RELEASE, mixer gain/FX, clipping path and audio callback constraints. No global limiter and no R2-4 Drum Punch changes.

## Claude's read-only code findings
- `MainViewModel.preferredSoundFontPair` favors Prime; native `voice_resolver.h` may favor Tyros due to Yamaha-name bonus.
- `StyleChannelOverride.bank` is stored but ignored in audio selection; dynamic PC can overwrite manual selection; overrides not cleared on new style load.
- Mixer `setChannelVoice` bypasses persistent override; voice picker loses font identity.
- Native exact preset pinning exists for keyboard channels but not style channels; need safe explicit font+bank+program binding.
- `StyleRepository.extractVoiceSetup` should distinguish absent CC0/CC32 from explicit zero.
- Existing `AtomicBankStore` is a model for robust separate style mapping storage.

## Planned milestones (not automatically complete)
- P0: branch and isolated CI.
- P1: read-only Style Voice Inspector and native actual-binding getter; **APK-1** diagnostic with unchanged sound.
- P2: Colombo primary / Tyros secondary policy; resolved drums by default, no Prime auto-load.
- P3: manual override engine, dynamic-PC protection, explicit SF2 binding.
- P4: Mixer picker and reset.
- P5: atomic per-style persistence and restore.
- P6: integration/regression; **APK-2** for Redmi Pad + E343.
- P7: on-device verification. Never claim musical fidelity fixed from host tests alone.

## Latest observed status (screenshot, NOT independently verified in GitHub)
On 2026-10-16 Claude reported creating `experiment/colombo-primary-voice-override` from `c75732f`, passing 80 baseline JVM tests, starting P1, and adding a read-only native channel binding getter and ViewModel/report accessors. Claude was still running. **No commit, push, APK or completed P1 confirmed by that screenshot.** Next agent must check actual remote status first.

## Agent handoff rules
- Only ONE agent may edit the migration branch at a time. Claude and Codex must never write concurrently.
- Before work: fetch; inspect branch, HEAD, ancestry, git status, existing changes, CI, APK, unpushed commits and this file. Respect another agent's uncommitted work. Never reset/force-push without owner approval.
- During work: make scoped commits; run relevant safety tests; prioritize real diagnostic APK and Redmi Pad testing over repeated full audits.
- Before stopping: update this file with date, agent, branch, HEAD SHA, pushed/unpushed state, changed files, completed milestone, tests with PASS/FAIL, Actions/APK URLs, known blockers and **one exact next task**. Commit and push handoff when permitted.
- No merge into `main`, PR, or destructive operations without explicit owner approval.
- Communicate status and reports to owner **in Indonesian**. Keep code symbols/error messages unchanged.

## Latest handoff entry
2026-10-16 | Prepared by ChatGPT on independent documentation branch `docs/ai-handoff-colombo`. Migration implementation status remains pending verification. When Claude finishes its current running task, import this document into the migration branch and update it from real commit/test evidence. Codex should start from the verified latest migration HEAD, not this documentation branch.
