# YamahaArranger — Project Notes

> **File ini untuk konteks development.** Setiap sesi chat baru, baca file ini dulu biar langsung paham status project.

---

## 🎯 TUJUAN PROJECT

Membuat **aplikasi Android keyboard arranger** seperti ORG24 yang bisa:
1. Memainkan **style Yamaha S/SX** (file `.sty`, `.prs`, `.bcs`, `.sct`)
2. Menggunakan **PSR-E343** sebagai MIDI controller via USB OTG
3. Sound engine berbasis **FluidSynth** + SoundFont (SF2)
4. Output audio via speaker HP / TWS / AUX IN ke E343

**Target akhir:** User bisa memainkan style S/SX di E343 tanpa modifikasi hardware.

---

## 📱 DEVICE USER

- **Tablet:** Redmi Pad (Android, tidak di-root)
- **Keyboard:** Yamaha PSR-E343
- **Koneksi:** USB OTG + kabel USB A-B
- **Editor:** Acode (auto-push ke GitHub) + Termux (manual push untuk file besar)
- **Audio Output:** TWS

---

## 🏗️ ARSITEKTUR

### C++ Native (`app/src/main/cpp/`)
| File | Fungsi |
|------|--------|
| `native_lib.cpp` | JNI bridge + CASM finder |
| `audio_engine.cpp` | Oboe stream + render loop |
| `soundfont_player.cpp` | FluidSynth wrapper |
| `style_parser.cpp` | Parse SMF + marker events |
| `smf_reader.cpp` | Standard MIDI File reader |
| `voice.cpp` | Fallback voice (sine wave) |

### Kotlin (`app/src/main/java/com/yourapp/`)
| File | Fungsi |
|------|--------|
| `MainViewModel.kt` | State management + DebugLog |
| `MainScreen.kt` | UI Compose |
| `MainActivity.kt` | Entry point + file picker |
| `AudioEngineManager.kt` | Audio interface |
| `NativeAudioBridge.kt` | JNI extern |
| `ArrangerBrain.kt` | Transport control |
| `StyleSequencer.kt` | Pattern playback |
| `StyleRepository.kt` | Style loader |
| `NativeStyleBridge.kt` | Style JNI |
| `MidiInputManager.kt` | USB MIDI (package `com.yourapp.midi`) |
| `ChordDetector.kt` | Chord detection |

### Native Libraries (`app/src/main/jniLibs/`)
- **FluidSynth 2.6.0** prebuilt:
  - `libfluidsynth.so`, `libfluidsynth-assetloader.so`
  - `libFLAC.so`, `libogg.so`, `libopus.so`
  - `libsndfile.so`, `libvorbis*.so`
- 9 file per ABI (`arm64-v8a`, `armeabi-v7a`)

### Headers
- `app/src/main/cpp/fluidsynth/include/` — FluidSynth headers

---

## 🔧 WORKFLOW

### Edit Kode
- **Acode** untuk edit file biasa → auto-push ke GitHub
- **Termux** untuk:
  - Copy file binary besar (`.so`, headers)
  - Overwrite file yang sulit di Acode
  - Push fix kompleks

### Build APK
- **GitHub Actions** → `workflow_dispatch` manual (tidak auto-build)
- Repo: `github.com/pulicarpus/YamahaArranger`
- NDK: 26.3.11579264
- CMake: 3.22.1
- Gradle: 8.7

### Git Credential
- Sudah `credential.helper store` — tidak perlu login lagi
- Jika push dari Termux: `git push origin main`

---

## ✅ BUG YANG SUDAH DIFIX (18 BUG)

1. Theme Material3 → Material Components
2. `mipmap/ic_launcher` → adaptive icon XML
3. Oboe `find_package` → `prefab = true`
4. `BuildConfig` unresolved → `buildConfig = true`
5. `PianoKeyboard IntSize → Size`
6. `MidiInputManager.connect()` hilang
7. MIDI API: `openInputPort` → `openOutputPort`
8. Import path `com.yourapp.yamahaarranger.midi` → `com.yourapp.midi`
9. Emoji `✅` di import → parser Kotlin error
10. `combine` max 5 flow → bungkus dengan `Triple`
11. `tsf_channel_sounds_off_all` → butuh 2 argumen (loop 16 channel)
12. TSF `undefined symbol` → `tsf_impl.cpp` + `#define TSF_IMPLEMENTATION`
13. Channel mapping → extract dari status byte MIDI
14. `g_jvm` di anonymous namespace → external linkage
15. `@JvmStatic` di `DebugLog.add()` untuk JNI
16. `fluid_synth_set_channel_volume` tidak ada → pakai `fluid_synth_cc(ch, 7, vol)`
17. Duplicate `SoundFontPlayer::` symbols → audio_engine.cpp harus bersih
18. Volume clipping → anti-clipping + master gain 0.7

---

## 🔴 BUG TERCATAT BELUM DIFIX

### 1. Parser Main A-D
- **File:** `style_parser.cpp` → `classifyMarkerText()`
- **Masalah:** Semua Main A/B/C/D detect sebagai MainA
- **Penyebab:** String `"main"` mengandung 'a', jadi `t.find('a')` selalu match
- **Fix:** Cek huruf **setelah** kata "main", bukan di seluruh string
- **Status:** ⏳ Sprint CASM Parser

### 2. Voice Assignment
- **Masalah:** Semua melodic channel main piano
- **Penyebab:** Belum parse CASM untuk mapping channel → instrument
- **Status:** ⏳ Sprint CASM Parser

### 3. Chord Transposition
- **Masalah:** Style main pattern C-major mentah, tidak ikut chord user
- **Penyebab:** NTR/NTT belum diimplementasi
- **Status:** ⏳ Sprint CASM Parser

---

## 🎼 CASM DEBUG PROGRESS

**File style yang di-test:** `LoveSong3.S687.prs`

**Hasil dump CASM:**