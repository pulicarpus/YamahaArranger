# YamahaArranger — Catatan Proyek

## Latest checkpoint — Runtime audio-path diagnostic (2026-10-01)

- Branch: `diag/audio-path-presence`; parent/source baseline `ba56c263bdad4c4f9fb00fa59d9ce2d3463656b9` (Build #754).
- Implementation commit/build: pending publication; final APK evidence will be appended after CI.
- Device report now confirms the family gate fix works. Remaining subjective issues: Piano dominant, Bass buried, Strings barely audible with Tyros t4 strings slow, snare missing/wrong. Raw latest AllLog was not available; root cause is not proven.
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

