# YamahaArranger — Catatan Proyek

## Latest checkpoint — Runtime evidence and drum-zone candidate (2026-10-01)

- Branch `diag/audio-path-presence`; baseline APK #755 source `cf5e41565c1f8668795b850bf594ac4a7c48a348`, baseline docs HEAD `b1ae2a23d1788094cece469293543504c6b4a597`. Commit source/APK terbaru `8def797ace9da1646784e74c7f1019b278eee7ab`; **Build #756 SUKSES**, run36799888326 / job110171617935. Checkpoint HEAD sesudahnya hanya dokumentasi.
- Raw AllLog063648 and diagnostic AllLog074808 + Inspector074819 are now analyzed. Full evidence, file hashes, line references, architectural boundary and test instructions: [runtime evidence](docs/AUDIO_RUNTIME_EVIDENCE_20261001.md).
- Proven: dedicated drum SF2 has21 presets and noPC73; native fallback selects STANDAR PSR-SX128/0. Colombo Alternative73 belongs to a different source. All251 sampled drum NOTE_ONs are accepted and not remapped. Matching sample zones/Yamaha snare semantics remain unproven because Inspector contains no zone metadata.
- Proven: all775 sampled notes sent and map matches;521 positive-id source/native pairs preserve velocity. Low strings26/33–34 in MainD come from raw style. BassCC7=52 versus Piano81 are style data. String13 activation100 is later reset to62 by full mixer updates on CC11; this is an existing state inconsistency, not proof of the entire presence root cause. No hardcoded gain added.
- DROP_NO_POLICY_WITH_CHORD affects auxiliary src2 Piano rt → intendeddst11, at least114 notes in completed summaries; mask2ffc7c50 excludes currently played major/minor types. It does not drop the rhythm/Bass/Strings parts. No CASM bypass added.
- Candidate only adds read-only cached SF2 drum key/velocity zone/sample-name audit, complete style note histograms and explicit drop mask/type evidence. No family resolver, kit selection, remap, mixer values, timing, sustain/release, keyboard or UI change.
- Host validation:210 family +29 native +22 audio diagnostic +16 SF2 metadata checks (277 total). CI mengulang277 checks, kompilasi Android dua ABI/SDK BASS asli, upload artifact dan langkah Telegram sukses. [APK #756](https://github.com/pulicarpus/YamahaArranger/actions/runs/36799888326/artifacts/11134429464), archive12,483,868 bytes. Ini diagnostic candidate; cakupan sample perangkat belum diuji.
- Next: test same four fonts/Love Song MainD then MainA/MainC and FillCC/FillDD; match every drum key to DRUM ZONE liveVerified/metadataKnown and matchingZones. A compatible replacement kit/key-map fix requires that evidence or actual SF2 sample/zone inspection.

## Latest checkpoint — Runtime audio-path diagnostic (2026-10-01)

- Branch: `diag/audio-path-presence`; parent/source baseline `ba56c263bdad4c4f9fb00fa59d9ce2d3463656b9` (Build #754).
- Commit source/APK: `cf5e41565c1f8668795b850bf594ac4a7c48a348`. HEAD checkpoint sesudahnya hanya dokumentasi; source aplikasi identik dengan SHA APK.
- **Build #755 SUKSES**, run36797178721 / job110163179470. Tes258 checks, kompilasi Android, upload artifact dan Telegram berhasil. Satu build; tidak ada retry/fix spekulatif.
- [APK app-debug](https://github.com/pulicarpus/YamahaArranger/actions/runs/36797178721/artifacts/11134221355) / [workflow](https://github.com/pulicarpus/YamahaArranger/actions/runs/36797178721); archive12,459,240 bytes, artifact11134221355.
- Remote diff19 path sesuai rencana;111 blob lain/18 native library tetap; family resolver tidak berubah. sepuluh fungsi native yang dilindungi identik.
- Catatan compiler: DROP_TRANSFORM_NULL tidak dapat terjadi karena fallback source-note yang sudah ada membuat transformed nonnullable. Ini bukan bukti root cause dan tidak diubah; lihat checkpoint lengkap.
- Device report now confirms the family gate fix works. Remaining subjective issues: Piano dominant, Bass buried, Strings barely audible with Tyros t4 strings slow, snare missing/wrong. Raw AllLog was unavailable when #755 was designed; it has now been supplied and analyzed in the newer checkpoint above. Full sample/PCM presence root cause is still not proven.
- This candidate adds correlated STYLE PATH -> AUDIO PATH/LIVE/DRUM diagnostics, actual BASS preset/controller readback, sent/rejected counters, snare38/40 and lifecycle/duration evidence. It does not change gain, preset selection, drum mapping, CASM transforms, Main/Fill timing, sustain, keyboard voices or UI.
- Tests:210 family policy +26 native routing +22 diagnostic checks pass; audio still requires Android traces.
- [Full continuation and Android test procedure](docs/AUDIO_PATH_DIAGNOSTIC_CHECKPOINT_20261001.md). Use the same Love Song/Main D/SF2 stack, full mix then short solo passes, clear/export AllLog+Inspector and compare matching event ids. No speculative fix/build chain.

## Historical checkpoint — Family-preserving multi-SF2 (2026-09-30)

- Branch: `feat/family-preserving-multisf2`; parent `512bcc6f23636c22261b718036f5ef389cabe80a`.
- Commit terakhir source/APK: `ba56c263bdad4c4f9fb00fa59d9ce2d3463656b9`; family branch HEAD tetap SHA APK ini; pembaruan checkpoint akhir sebelumnya hanya lokal karena write GitHub terputus. Catatan lengkapnya kini disertakan pada branch diagnostic.
- **Build #754 SUKSES**, workflow run `36749544403`, job `110004325024`; tes CI, build Android dua ABI, upload APK dan Telegram berhasil. Tidak ada retry build.
- [APK app-debug](https://github.com/pulicarpus/YamahaArranger/actions/runs/36749544403/artifacts/11114086868) / [hasil workflow](https://github.com/pulicarpus/YamahaArranger/actions/runs/36749544403). Archive 12,436,339 bytes, artifact `11114086868`.
- Diff remote sesuai 21 path yang direncanakan; 108 blob lama lain, termasuk 18 native library/CMake, tetap identik. Source resolver/workflow di GitHub sama dengan snapshot yang diuji.
- Local workspace adalah snapshot teks untuk review, bukan clone remote; HEAD git lokal bukan SHA APK. Gunakan SHA GitHub di atas untuk melanjutkan.
- Full continuation document: [family-preserving resolver checkpoint](docs/FAMILY_PRESERVING_RESOLVER_CHECKPOINT_20260930.md).
- Concrete fix: A.Guitar -> Wide Piano 2 numeric collision now rejected before any score. One family/role-gated policy compares Yamaha + Colombo + optional Tyros globally. No universal Piano fallback; unresolved/failed melodic mapping blocks new notes explicitly.
- CI mutation scripts are removed; core-first load safety and Colombo admission are in source. Both APK workflows run the same C++ policy/native host tests then compile checked-out source.
- Validasi lokal dan CI: 210 policy + 19 native routing checks pass; 13 protected native functions unchanged; scheduler/CASM/MIDI/layout source unchanged.
- Before latest device report, Android audio was unverified. Latest report confirms family gate but weak orchestration. Earlier planned checks: test Love Song five melodic parts and dedicated drums; export AllLog/Inspector; inspect VOICE REQUEST/CANDIDATE/POOL/FINAL, MAP and preload. Check optional Tyros memory, reload, RIGHT1/2/3/LEFT and the existing performance regression boundary.
- This checkpoint supersedes historical suggestions below permitting Piano for a non-Piano request when its family is absent, Sax -> Clarinet, and cross-family fallback. Those paths are prohibited now. Historical findings are retained for context.

## Historical checkpoint Voice Resolver — Build 743 → next test

Build 743 membuktikan jalur audio style sudah aktif: CASM menghasilkan NOTE_ON, drum dan bass hidup, dan native resolver mencatat source SF2 secara eksplisit.

### Temuan Build 743

- CH10 Bass berhasil resolve ke source `BASS`.
- CH11 Piano berhasil resolve ke `Yamaha ConcertGrand`.
- CH12 Guitar dapat resolve ke `Steel Guitar` / `Hawaiian Guitar` sesuai request.
- Beberapa request Yamaha variation masih masuk ke fallback yang terlalu aman. Contoh yang terlihat di log 743: `PAD` dan `Horns` dapat berakhir pada `Yamaha ConcertGrand` ketika tidak ada kandidat semantic yang lolos.
- Ini adalah masalah voice identity/coverage, bukan bukti bahwa CASM, scheduler, atau audio channel mati.

### Aturan resolver yang harus dipertahankan

1. Exact bank + program.
2. MSB-only bank + program.
3. Semantic category/name match sebelum numeric same-PC.
4. Same-PC hanya boleh dipakai jika category kandidat tetap sama.
5. Jangan pernah menggunakan piano sebagai fallback universal untuk voice non-piano.

### Perbaikan berikutnya — Category-Preserving Final Fallback

Final fallback saat ini masih konservatif ke Piano Program 0 jika tidak ditemukan candidate aman. Tahap berikutnya harus mengganti fallback universal tersebut dengan fallback yang mempertahankan keluarga suara:

- PAD → PAD/Synth Pad → Synth
- HORNS/BRASS → Brass/Horn family
- SAX → Sax/Clarinet family
- FLUTE → Flute/Oboe family
- GUITAR → Guitar family
- STRINGS → String/Ensemble/Synth String family
- BASS → Bass family
- ORGAN → Organ family
- PIANO → Piano family
- CHOIR/VOICE → Choir/Voice family
- ACCORDION → Accordion family
- SYNTH → Synth/Pad family

Jika keluarga tersebut benar-benar tidak tersedia di SF2 primary, resolver harus mencoba SF2 fallback sebelum menggunakan family lain. Piano hanya boleh menjadi fallback terakhir untuk request yang memang berada di keluarga piano atau ketika tidak ada kandidat keluarga sama sekali dan keputusan tersebut tercatat eksplisit di log.

### Regression yang wajib diuji setelah perubahan

- Love Song Main D.
- Mute/unmute CH8–CH14.
- Bass tetap Bass.
- Piano tetap Piano.
- Guitar tidak berubah menjadi Piano/Drum.
- Strings tidak berubah menjadi Piano.
- PAD tidak berubah menjadi Piano jika ada Synth/Pad candidate.
- Horns tidak berubah menjadi Piano jika ada Brass candidate.
- Drum tetap berada di jalur drum bank 127/128.

### Catatan penting

Jangan mengubah CASM, StyleSequencer, timing, transition, atau routing drum untuk memperbaiki masalah ini. Bukti Build 743 menunjukkan event audio sudah masuk. Fokus tahap ini hanya pada voice resolver dan pemilihan source SF2.

