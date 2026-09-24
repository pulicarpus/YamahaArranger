# YamahaArranger — Project Notes (Current State)

> **Baca file ini di awal setiap sesi development.** File ini adalah sumber konteks utama untuk memahami status proyek sebelum melakukan perubahan.
>
> **Aturan penting:** `main` adalah production baseline. Branch dengan nama `research/`, `diag/`, `fix/`, `stage1/`, atau `feat/` tidak otomatis lebih baru atau lebih baik dari `main`. Perlakukan branch tersebut sebagai eksperimen/evidence sampai memang dipilih untuk diintegrasikan.
>
> **Development rule:** Jangan mengubah kode hanya karena menemukan kemungkinan masalah. Perubahan kode dilakukan hanya jika user secara eksplisit memintanya.

---

## 🎯 TUJUAN PROJECT

Membangun aplikasi **Android Yamaha Arranger** yang sedekat mungkin dengan perilaku arranger Yamaha/MIDI Voyager, dengan kemampuan:

1. Memainkan style Yamaha S/SX: `.sty`, `.prs`, `.bcs`, `.sct`.
2. Menggunakan Yamaha PSR-E343 sebagai MIDI controller melalui USB OTG.
3. Menggunakan SoundFont/SF2 dan engine FluidSynth/BASSMIDI untuk menghasilkan audio.
4. Menjalankan style secara realtime dengan chord detection, CASM, NTR/NTT/RTR, voice/channel routing, fills, intros, endings, dan transition.
5. Menghasilkan audio yang stabil dan musikal, dengan target parity sedekat mungkin terhadap MIDI Voyager/Yamaha arranger.
6. Mendukung output speaker/tablet/TWS/AUX sesuai setup user.

**Target akhir:** style Yamaha dapat dimainkan dari keyboard/controller dengan behavior dan hasil audio yang sedekat mungkin dengan arranger Yamaha/MIDI Voyager, tanpa modifikasi hardware pada PSR-E343.

---

## 📱 DEVICE / WORKFLOW USER

- Tablet: **Redmi Pad**, Android, non-root.
- Keyboard: **Yamaha PSR-E343**.
- MIDI: USB OTG.
- Editor: Acode.
- Termux: pekerjaan Git/binary/push yang lebih kompleks.
- Audio test: termasuk TWS.
- Repository: `pulicarpus/YamahaArranger`.
- Build toolchain:
  - JDK 17
  - Gradle 8.7
  - NDK 26.3.11579264
  - CMake 3.22.1
- GitHub Actions workflow: build APK dengan `assembleDebug`; workflow mendukung `workflow_dispatch` dan push ke `main`.

---

# 🏗️ ARSITEKTUR

## Native C++ — `app/src/main/cpp/`

Komponen utama yang telah ditemukan dalam proyek:

- `native_lib.cpp` — JNI bridge dan CASM-related native interface.
- `audio_engine.cpp` — Oboe audio stream/render path.
- `soundfont_player.cpp` — SoundFont/audio engine wrapper.
- `style_parser.cpp` — Yamaha style/SMF parsing dan marker/event handling.
- `smf_reader.cpp` — Standard MIDI File reader.
- `voice.cpp` — fallback/basic voice implementation.
- Native code juga menangani bagian realtime MIDI/audio/CASM yang berkembang sepanjang commit history.

## Kotlin — `app/src/main/java/com/yourapp/`

Komponen penting:

- `MainViewModel.kt` — state management + DebugLog.
- `MainScreen.kt` — UI Compose.
- `MainActivity.kt` — entry point/file picker.
- `AudioEngineManager.kt` — audio interface.
- `NativeAudioBridge.kt` — JNI audio bridge.
- `ArrangerBrain.kt` — arranger transport/control.
- `StyleSequencer.kt` — style playback/scheduling.
- `StyleRepository.kt` — style loading.
- `NativeStyleBridge.kt` — native style interface.
- `MidiInputManager.kt` — USB MIDI.
- `ChordDetector.kt` — chord detection.

## Audio libraries

Project memiliki prebuilt FluidSynth/SoundFont-related native libraries dan header di project tree. Audio work saat ini bukan lagi tahap membuat suara dasar, tetapi tahap **realtime stability + musical/audio parity**.

---

# 🎼 STATUS YAMAHA STYLE ENGINE

## CASM — BUKAN TODO LAGI

Dokumentasi lama yang mengatakan CASM/NTR/NTT belum diimplementasikan sudah **stale**.

History project menunjukkan implementasi penting telah masuk, termasuk:

- CASM decoding/policy.
- Ctab/Ctb2 handling.
- Voice/channel mapping.
- Per-part transformation policy.
- NTR.
- NTT.
- RTR.
- Live chord revoicing melalui RTR.
- Chord changes yang melewati Yamaha CASM RTR engine.

Commit penting yang teridentifikasi:

- `2942924` — implement Yamaha CASM-aware NTR/NTT transposition.
- `9c1b77d` — live CASM RTR chord revoicing.
- `5c14e3a` — route chord changes through Yamaha CASM RTR engine.
- `6b82402` — show actual Yamaha style voices in channel UI.

### Cara berpikir debugging CASM

Jika ada suara/nada yang salah, jangan langsung menganggap parser CASM rusak. Pisahkan menjadi beberapa lapisan:

1. **Style parsing** — event/note/controller/program benar atau tidak.
2. **CASM policy** — part/chord region memilih Ctab/Ctb2 yang benar atau tidak.
3. **NTR/NTT** — note ditranspose dengan rule yang benar atau tidak.
4. **RTR** — chord change/revoice menghasilkan pitch yang benar atau tidak.
5. **Voice/channel routing** — event masuk ke channel/instrument yang benar atau tidak.
6. **SF2/BASSMIDI routing** — program/bank/preset yang benar atau tidak.
7. **Audio rendering/timing** — event benar tetapi terlambat/dropout atau tidak.

Jangan mencampur bug musical transformation dengan bug audio timing.

---

# 🎹 VOICE / CHANNEL / SF2 ROUTING

Project sudah bergerak jauh melewati tahap “semua channel memainkan piano”.

History menunjukkan adanya pekerjaan pada:

- actual Yamaha style voice mapping,
- Yamaha 14-bit bank routing,
- Rhythm 1 / Rhythm 2 drum routing,
- MIDI Voyager-style SF2 drum routing,
- melody + drum SF2,
- multi-SF2 routing/selection,
- expression parity.

Untuk style yang diuji, terutama `LoveSong.S687.prs` / `LoveSong3.S687.prs`, channel, program, bank, dan drum mapping harus dianalisis dari data style dan hasil log — bukan diasumsikan dari nomor channel saja.

**Catatan drum:** channel 9/10 representation harus selalu dibedakan antara zero-based/one-based MIDI channel convention. Jangan membuat asumsi baru tanpa melihat event/log aktual.

---

# 🎧 AUDIO ENGINE — FOKUS UTAMA SAAT INI

Project sekarang berada pada fase **Generation 4 / realtime audio parity**, bukan fase parser dasar.

Masalah utama yang sedang diteliti:

- audio callback contention,
- mutex/control-path contention,
- BASSMIDI render timing,
- asynchronous live MIDI events,
- program/voice changes,
- mixer changes,
- drum preset switching,
- CASM voice-apply window,
- transition timing,
- audio starvation/dropout/stutter,
- Intro → Main,
- Main → Fill → Main,
- Main A → B,
- Main → Ending.

### Prinsip utama

Jika style/CASM sudah menghasilkan NOTE_ON yang benar tetapi suara tersendat atau transition menyebabkan jeda, jangan mengubah CASM hanya untuk menghilangkan gejala audio timing.

Targetnya adalah **memisahkan jalur realtime MIDI/audio dari operasi berat seperti program/voice/mixer changes**.

---

# 🔬 RESEARCH / DIAGNOSTIC WORK

Beberapa branch yang telah ditemukan dari review repository:

- `diag/audio-render-timing`
- `diag/audio-timing-cache`
- `research/audio-realtime-isolation`
- `research/audio-realtime-isolation-async`
- `research/audio-transition-costs`
- `research/audio-transition-timing`
- `fix/audio-bassmidi-async-events`
- `fix/audio-callback-nonblocking-lock`
- `fix/audio-realtime-quality`
- `fix/audio-transition-stall`
- `fix/bassmidi-yamaha-bank-routing`
- `fix/casm-policy`
- `stage1/acmp-audio-build`
- `stage1/acmp-audio-fix`
- `stage1/acmp-audio-fix2`
- dan branch experiment lain.

**Peringatan:** beberapa branch sangat divergen dari `main`. Nama branch tidak cukup untuk menentukan branch mana yang production-ready.

---

# 🔎 OPEN PR / RESEARCH YANG RELEVAN

## PR #16 — BASSMIDI async live events

Branch: `fix/audio-bassmidi-async-events`

Tujuan:
- mengaktifkan asynchronous BASSMIDI event handling,
- menggunakan preallocated async event queue,
- live MIDI event diarahkan ke update cycle berikutnya,
- mengurangi thread contention.

Tidak dimaksudkan untuk mengubah CASM/style scheduling/SF2 routing.

**Status:** perlu validasi APK/device sebelum dianggap solusi final.

## PR #17 — Oboe callback non-blocking

Branch: `fix/audio-callback-nonblocking-lock`

Tujuan:
- render callback tidak menunggu `mutex_`,
- menggunakan try-lock pada render path,
- jika control path sedang mengubah state BASSMIDI, callback dapat mengeluarkan silence untuk burst tersebut daripada blocking.

Tidak dimaksudkan mengubah CASM, style scheduling, SF2 routing, atau MIDI event logic.

**Status:** draft/research; perlu device test.

## PR #19 — transition timing research

Branch: `research/audio-transition-timing`

Tujuan diagnostic-only:

- mengukur section quantize wake,
- transition queue/interruption,
- CASM voice-apply boundary,
- first note setelah transition.

Tidak dimaksudkan mengubah behavior audio/CASM/SF2/section scheduling.

## PR #20 — per-channel transition voice cost

Branch: `research/audio-transition-costs`

Tujuan:

- mengukur biaya `setChannelProgram()`,
- mengukur `setChannelMixer()`,
- melihat biaya perubahan program drum channel,
- mengukur total CASM voice-apply window.

Konteks penting: penelitian ini dibuat karena pernah terlihat CASM voice-apply window sekitar **11–35 ms**, yang cukup besar untuk realtime audio.

**Jangan merge hanya berdasarkan nama branch.** Hasil APK/device test harus menjadi bukti.

---

# 🧪 TEST STYLE UTAMA

Style yang menjadi referensi penting:

- `LoveSong.S687.prs`
- `LoveSong3.S687.prs`

Perbandingan utama:

1. Yamaha style dimainkan di aplikasi.
2. Style/sound yang sama dibandingkan dengan MIDI Voyager bila memungkinkan.
3. Log MIDI/CASM dibandingkan.
4. Audio timing dibandingkan.
5. Baru setelah itu lakukan perubahan.

Catatan penting dari testing sebelumnya:
- Style yang sama dapat terdengar sangat berbeda dari MIDI Voyager walaupun file style dan SF2 yang digunakan sama.
- MIDI Voyager tidak mendukung style `.prs` secara native seperti arranger Yamaha, sehingga perbandingan harus dipahami sebagai perbandingan hasil playback/engine, bukan bukti bahwa parser harus meniru implementasi internal MIDI Voyager.

---

# 🧭 METODOLOGI DEBUG

Untuk setiap bug, tentukan dahulu kategorinya:

### A. Musical/CASM
Contoh:
- string tidak mengikuti chord,
- bass/piano salah note,
- NTR/NTT salah,
- RTR salah,
- chord region salah.

### B. Routing/Voice
Contoh:
- instrument salah,
- program/bank salah,
- Rhythm 1/2 salah,
- drum kit salah,
- SF2 salah.

### C. Timing/Realtime Audio
Contoh:
- suara putus,
- click/dropout,
- fill menyebabkan jeda,
- transition terlambat,
- first note setelah transition hilang,
- program change menyebabkan stall.

### D. UI/Transport
Contoh:
- Main A/B/C/D salah,
- Fill/Intro/Ending state salah,
- tempo/measure salah,
- section scheduler salah.

**Aturan:** perbaiki lapisan yang terbukti bermasalah. Jangan merombak CASM untuk masalah yang sebenarnya berada di audio callback.

---

# 🏁 PRIORITAS DEVELOPMENT SAAT INI

Urutan kerja yang disarankan:

1. **Stabilkan realtime audio.**
2. Ukur biaya voice/program/mixer transition.
3. Minimalkan blocking di audio callback.
4. Validasi BASSMIDI async event path.
5. Validasi transition timing.
6. Test:
   - Intro → Main
   - Main A → B
   - Main → Fill → Main
   - Main → Ending
7. Bandingkan hasil dengan MIDI Voyager secara terkontrol.
8. Setelah timing/stability stabil, lanjutkan refinement kualitas audio/musical parity.
9. Jangan menganggap research branch sebagai production fix sebelum APK diuji pada perangkat nyata.

---

# 📌 STATUS PROJECT

| Area | Status |
|---|---|
| Android foundation | ✅ |
| MIDI input | ✅ |
| Chord detection | ✅ |
| SMF parsing | ✅ |
| Yamaha style parsing | ✅ |
| CASM decoding/policy | ✅ / developing |
| NTR | ✅ |
| NTT | ✅ |
| RTR | ✅ |
| Voice/channel mapping | ✅ / developing |
| Yamaha bank routing | ✅ |
| Drum/Rhythm routing | ✅ / developing |
| SF2 | ✅ |
| BASSMIDI | ✅ / developing |
| Style sequencing | ✅ / developing |
| Main A-D | ✅ |
| Fill/transition | 🔄 developing |
| Intro/Ending | 🔄 developing |
| MIDI Voyager parity | 🔄 |
| Audio quality parity | 🔄 |
| Realtime audio stability | 🔄 **CURRENT FOCUS** |
| Transition timing research | 🔬 |
| Per-channel voice cost | 🔬 |
| BASSMIDI async | 🔬 |
| Oboe non-blocking callback | 🔬 |
| Production-ready realtime engine | ⏳ |

---

# ⚠️ DOKUMENTASI LAMA YANG STALE

README dan `PROJECT_NOTES.md` lama masih mengandung beberapa pernyataan seperti:

- CASM belum selesai,
- NTR/NTT belum ada,
- voice assignment belum selesai,
- SoundFont masih placeholder,
- Main A-D parser masih bug utama.

**Jangan gunakan pernyataan tersebut sebagai current state.**

Git history lebih baru menunjukkan implementasi sudah jauh melewati tahap tersebut.

Jika ada konflik antara dokumentasi lama dan kode/history terbaru, lakukan verifikasi terhadap:

1. `main`,
2. commit terbaru yang relevan,
3. log/runtime test,
4. branch research/fix yang memang sedang diuji.

---

# 🌳 BRANCH RULE

### `main`
Production baseline / baseline comparison.

### `fix/*`
Candidate fixes. Belum otomatis production-ready.

### `research/*`
Eksperimen dan instrumentation. Biasanya jangan dianggap solusi final.

### `diag/*`
Diagnostic/instrumentation.

### `stage1/*`
Tahap integrasi/eksperimen historis.

### `feat/*`
Feature branch; harus diverifikasi terhadap `main`.

**Sebelum menggabungkan branch, selalu cek:**
- base commit,
- ahead/behind,
- changed files,
- purpose,
- build status,
- device test.

---

# 🧠 SATU KALIMAT KONTEKS PROJECT

> **YamahaArranger sudah bukan lagi proyek parser/style dasar; engine sekarang sudah memiliki CASM/NTR/NTT/RTR dan routing SoundFont/BASSMIDI yang cukup jauh, sehingga fokus utama saat ini adalah menyelesaikan parity audio dan stabilitas realtime—terutama timing transition, voice/program changes, BASSMIDI scheduling, dan Oboe callback—tanpa merusak sistem CASM yang sudah bekerja.**

---

# 📋 SESSION START CHECKLIST

Sebelum melakukan pekerjaan:

1. Baca `PROJECT_NOTES.md`.
2. Pastikan branch yang sedang dilihat.
3. Jangan menganggap branch research/fix lebih baru dari `main`.
4. Periksa commit terbaru bila status diragukan.
5. Bedakan bug CASM, routing, sequencing, dan audio timing.
6. Gunakan log aktual sebagai bukti.
7. Untuk perubahan kode, tunggu instruksi eksplisit user.
8. Setelah perubahan yang diminta, build dan device-test bila memungkinkan.
9. Update catatan proyek jika status arsitektur benar-benar berubah.

---

**Current project phase: Generation 4 — Realtime Audio Stability + Yamaha/MIDI Voyager Parity.**
