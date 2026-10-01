# Checkpoint diagnostic seluruh kit drum dan fix CC11 — 2026-10-01

Branch: `diag/audio-path-presence`. Parent remote: `882505d44cf0f08a2834c067cb62ba2c246455da`. Baseline Android adalah Build #756, source `8def797ace9da1646784e74c7f1019b278eee7ab`. Commit fix CC11 terpisah: `8344604838cd9e4cae903c45218592f80d1d486d`. Commit audit `3a4150a891f4f8295c62b10ef9292be067dcf78e`; source kandidat terbaru `1fd6a820555b93b9e8a11bb2faa5261b4286ff92` setelah perbaikan import tes lama. Build **#758 SUKSES**, run `36806478056`, job `110191835224`. [APK app-debug](https://github.com/pulicarpus/YamahaArranger/actions/runs/36806478056/artifacts/11138395281). Archive ZIP 12,530,404 bytes; SHA256 `ac4db8005820e3e9c38b9a3db908043b1472121f7966643b6deabb6ccae9f445`. HEAD checkpoint sesudahnya hanya dokumentasi; source/APK tetap1fd6a82. Tidak ada PR atau merge.

## Bukti sebelum perubahan

AllLog082913 dan Inspector082925 menunjukkan dedicated drum SF2 memiliki 21 preset, tidak memiliki PC73, dan request PC73 berakhir pada STANDAR PSR-SX bank128/PC0. MainD satu section 30720 tick / PPQ1920 / 4 bar memuat 117 hit Rhythm1 dan 96 hit Rhythm2. Key31 dimainkan 8 kali velocity110 pada ketukan2/4, tanpa matching zone di PC0; key21 dimainkan32 kali velocity28/42 juga tanpa zone. Key33 serta percussion Rhythm1 memiliki zone. Tidak ada key38/40 di MainD; tidak dibuat remap snare.

[Audit Build756](BUILD756_DRUM_COMPATIBILITY_AUDIT.md) dan [CSV lengkap](BUILD756_MAIND_DRUM_HISTOGRAM.csv) mempertahankan histogram, inventaris21kit, nama sample/zone, batas bukti dan referensi XG. Label XG umum mendukung key31 sebagai backbeat/snare, tetapi variasi Yamaha kit73 dan timbre SF2 belum terbukti.

CC7 Strings1 efektif100 turun62 pada9pasangan event setelah CC11. `applyCasmChannelVoices` mengirim effective mixer100; raw mixerStates.volume tetap62. `applyStyleController` mengubah expression lalu mengirim ulang full mixer sehingga CC7 ikut berubah tanpa event CC7 baru. Velocity Strings dan raw CC7 Bass yang rendah berasal dari style, bukan target gain fix.

## Dua perubahan terpisah

1. Fix CC11 hanya menambahkan early return expression-only di `StyleSequencer.applyStyleController`, memakai setter yang sudah ada. CC11 tidak mengirim CC7/pan/reverb/chorus. Override expression lama tetap dihormati, native mute/volume pengguna tetap dipertahankan. Event CC7 eksplisit tetap melewati jalur lama. Tidak menambahkan gain, mengubah floor Strings, atau merekonsiliasi cache mixer lain secara luas.
2. Audit semua kit dilakukan saat tombol Inspector SAVE REPORT. Profil MainD dibentuk dari parsed style yang dimuat, bukan histogram hardcode Love Song atau aktivitas resolver. Event NOTE_OFF, CC, velocity0 dan melodic tidak dihitung. Key dan velocity tidak ditransform. Tujuan rhythm dibaca dari metadata CASM secara read-only; alternatif dengan destination sama dihitung sekali, rute ambiguity dilaporkan unavailable tanpa menebak. Playback/CASM tidak dipanggil atau dimodifikasi oleh profiler.

JNI membawa bin `channel,key,velocity,hitCount` ke native. `BassMidiPlayer.drumKitCoverage` mengambil snapshot metadata SF2 dedicated yang sudah di-cache ketika font dimuat, lalu melepas mutex sebelum menghitung/format. Tidak membuka ulang SF2, membaca PCM, memuat sample/font, mengirim nada uji, mengganti kit atau menyentuh FONTEX2. Nama preset phdr juga disimpan; apostrophe nama preset dipertahankan. Export berjalan di dispatcher latar, penulisan file di IO.

Report berisi sumber dedicated, style/section/tick/PPQ, requested PC perpart, count perchannel, semua preset dan:
- `KIT KEY`: nama, bank, PC, ch, key, min–max velocity aktual, hitCount, coveredHits, rentang jumlah zone dan semua nama instrument/sample beserta key/velocity range, frames dan sample type. Nama tidak dipotong oleh batas log runtime.
- `KIT VELOCITY`: setiap velocity aktual dengan hitCount, matching zone count dan nama/range semua matching zone. Ini mengungkap hole layer yang tersembunyi bila hanya memakai min/max.
- `KIT RANK`: coveredHits/totalHits, coveredUniqueKeys/totalKeys, missingImportantKeys khusus21/31 yang dipakai, missingKeys, partialKeys, unknownKeys/unknownHits. Satu hit dengan dua sample layer tetap satu covered hit. Unique key dianggap covered hanya bila semua velocity yang dipakai pada kedua part eligible. Ranking: coveredHits, coveredUniqueKeys, jumlah important missing, lalu bank/PC sebagai tie-break deterministik. Tidak memilih atau memasang hasil ranking.

Coverage membuktikan eligibility metadata. Coverage tidak membuktikan jenis snare/kick yang benar, sample readiness, PCM audibility, envelope atau kecocokan timbre Yamaha. Pemilihan kit berikutnya tetap menunggu hasil perangkat dan identifikasi semantic/sample yang memadai. Metadata invalid/tidak ada atau MainD tidak ada dilaporkan unavailable, bukan dibuat ranking palsu.

## File yang berubah

Fix terpisah: `StyleSequencer.kt`, `StyleExpressionRegressionTest.kt`, test-only dependency Mockito di `app/build.gradle.kts`.

Audit: `drum_kit_audit.h`, `sf2_zone_diagnostic.h`, `bassmidi_player.cpp/.h`, `audio_engine.cpp/.h`, `native_lib.cpp`, `NativeAudioBridge.kt`, `AudioEngineManager.kt`, `DrumStyleAuditProfile.kt`, `MainViewModel.kt` (snapshot style/export getter), `Sf2StyleInspectorScreen.kt` (SAVE REPORT async/export).

Validasi: `drum_kit_audit_test.cpp`, `native_resolver_test.cpp`, `sf2_zone_diagnostic_test.cpp`, `DrumStyleAuditProfileTest.kt`, `tools/test_voice_resolver.py`, `.github/workflows/build.yml` (menjalankan dua kelas JUnit sasaran sebelum assembleDebug). Workflow tetap mengompilasi checkout source; tidak ada script patch resolver CI.

Perbaikan build terukur: satu import `com.yourapp.yamahaarranger.chord.AcmpChordAnalyzer` ditambahkan pada tes lama `app/src/test/java/com/yourapp/chord/AcmpChordAnalyzerTest.kt`; source analyzer/CASM tetap identik.

Dokumen: checkpoint ini, audit/CSV Build756 dan PROJECT_NOTES.

## Regression boundary dan validasi

Native `bassmidi_player.cpp` byte-identik dengan parent setelah getter diagnostic baru dihapus. Semua fungsi native existing—resolver family, drum fallback, noteOn/noteOff, mixer, preload, sustain/release, render, pemuatan SF2—tetap identik. `StyleSequencer.kt` byte-identik setelah blok CC11 early return dihapus: scheduler, CASM policy/transform/mask/ranges, transition/MainFill/master clock tidak berubah.

Tidak ada perubahan RIGHT/LEFT, bank Yamaha preservation, FONTEX2, NOTEOFF1, NOWAIT source-preset preload, cache Fill→Main, UI keyboard atau gain Bass/Strings. Inspector export adalah satu-satunya perubahan UI diagnostic.

Host suite: family resolver210, native routing32, observer22, metadata18, coverage21kit14 =296 checks. Native tests membuktikan getter tidak mengubah MIDI events/FONTEX2 pada metadata unknown, dan setter expression tidak mengubah CC7. Pure coverage tests membuktikan seluruh21preset diranking, actual velocity holes/partial keys dihitung, missing21/31 terlihat, sample layers tidak menggandakan hits, nama/ranges muncul, metadata invalid tidak dianggap missing.

JUnit targeted10cases: 5 memanggil metode controller produksi melalui reflection dengan mocked AudioEngineManager boundary (volume100 tetap, explicitCC7 bekerja, mute0 tetap, volume pengguna tetap, override expression dan controllerinvalid); 5 menguji profil style produksi (velocity histogram, routing metadata readonly, MainD absent, alternatif tidak double count, ambiguity ditolak). Mockito adalah dependency test saja, tidak masuk APK. Compiler Kotlin lokal tidak tersedia dan proxy shell tidak tersambung. **CI #758 memverifikasi 296 hostchecks, compileDebugUnitTestKotlin dan testDebugUnitTest untuk dua kelas targeted10cases, kemudian CMake/assembleDebug pada arm64-v8a dan armeabi-v7a memakai SDK BASS/BASSMIDI asli. Semua lulus.** Build berhasil belum membuktikan output Inspector di SF2 asli atau hasil audio perangkat.

## Instruksi tes Android

1. Install kandidat baru; gunakan empat SF2 yang sama. Tunggu dedicated drum SF2 selesai dimuat dan load Love Song yang sama. Tidak perlu upload SF2 atau SCAN ALL untuk audit cache ini.
2. Untuk export bersih, hentikan style, buka Inspector lalu SAVE REPORT. File tetap di Downloads/YamahaArranger/YamahaArranger_Inspector_....txt.
3. Periksa section `ALL LOADED DEDICATED DRUM KITS / MAIND COVERAGE`: `presets=21`; profil ch8=117/ch9=96; setiap KIT RANK denominator213 hits/10keys. PC0 diharapkan173/213 dan8/10keys bila metadata/baseline sama. PC0 key31/110 dan21/28,42 harus0zone. Kit lain belum diprediksi.
4. Jalankan MainD dengan chord yang sama beberapa bar. AllLog sampled AUDIO PATH Strings1 ch13 harus mempertahankan CC7=100 setelah CC11=126/125 ketika belum ada perintah CC7 atau tindakan volume baru. Pastikan explicit volume/mute/unmute tetap berfungsi. Tetap catat Bass/Piano/Strings/drum subjektif, tetapi jangan menganggap fix CC11 menyelesaikan timbre/orchestration.
5. Kirim Inspector lengkap dan AllLog. Tidak diperlukan file SF2.

Langkah berikut: bandingkan21rank khusus missing31/21, audit sample/zone semantic perkit, lalu hanya pilih mapping PC73 bila compatible terbukti. Tidak ada perubahan kit/remap/gain pada kandidat ini. Risiko yang tersisa: deep-copy cache mengambil mutex sebentar saat export; format/ranking sesudahnya di luar mutex, jadi export saat STOP direkomendasikan. Pemetaan source yang ambiguous sengaja tidak ditebak. Tes perangkat untuk report data asli dan CC7 runtime masih wajib.

## Riwayat build kandidat

- #757, run36806096726, source3a4150a:296hostchecks lulus, `compileDebugKotlin` aplikasi berhasil. Gagal `compileDebugUnitTestKotlin` karena13 unresolved references AcmpChordAnalyzer pada tes lama yang masih berada di package com.yourapp.chord. JNI/native ABI belum diuji pada attempt ini; assembleAPK belum berjalan. Tidak ada APK757.
- Commit1fd6a82 hanya memperbaiki import tes tersebut. Tidak ada perubahan audio/profile/resolver antara #757 dan #758. Ini koreksi berdasarkan error CI konkret, bukan percobaan kit/gain.
- #758, run36806478056/job110191835224, kandidat source1fd6a82: **SUCCESS**. JVM test build1m17s; assembleAPK28s;296hostchecks lulus; kedua ABI lulus; artifact11138395281 app-debug ZIP12,530,404bytes/digest di atas. Upload dan inherited Telegram step sukses. Total satu diagnostic implementation dan satu koreksi import tes berdasarkan failureCI; tidak ada perubahan audio spekulatif.

## Review tree akhir

Baseline882505d→kandidat1fd6a82:26path yang disengaja berubah (25candidate paths +1import tes lama),115blob lama lain termasuk18native library tetap identik, tidak ada penghapusan file. Diff candidate direview sebelum push; protected native playback dan StyleSequencer di luarCC11 tetap byte-identik. Checkpoint akhir hanya menyentuh dokumen dan tidak memicu APK lain karena paths-ignore workflow. Tidak ada PR/merge/main update. Source saat CI compile identik dengan commit1fd6a82, tanpa patch CI.
