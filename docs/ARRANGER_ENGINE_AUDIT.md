# Audit Mendalam Engine Arranger YamahaArranger

## Baseline resmi
- Branch: main
- Commit baseline: 52e5b7ea6f8ee4b1166f999e198fea07c8573ab1
- Tanggal audit: 29 September 2026

## Tujuan
Dokumen ini membedah engine file-per-file dan membandingkannya dengan GigLad, vArranger, One Man Band, Android Arranger Keyboard, MIDI Voyager Pro 5.4.11, serta perilaku arranger Yamaha.

## Status
- 🟢 BENAR — sudah sejalan dengan perilaku yang diteliti.
- 🟡 SETENGAH — fondasinya benar tetapi belum lengkap atau masih rapuh.
- 🔴 SUMBER BUG — implementasi dapat secara langsung menyebabkan bug.
- ⚪ PERLU INSTRUMENTASI — perlu log/pengujian sebelum menyimpulkan.
- ⚫ LEGACY — masih ada tetapi bukan jalur utama.

## 1. ArrangerBrain.kt
🟢 Sudah benar: Main A-D, Auto Fill, activeSection/currentPlayingSection, chord settle, pemisahan RIGHT/LEFT/ACMP, sustain keyboard, transpose dan mixer/voice override.
🟡 Masih setengah: konsep Fill directional sudah ada di ArrangerBrain, tetapi parser native belum mempertahankan semua FillAB/BA/etc. Quantize juga masih memakai wall-clock sebelum masuk ke master clock.
🔴 Sumber bug: Intro atau Ending yang dipilih sebelum START dari keadaan idle belum dijamin menjadi Intro→Main atau Ending→Stop. Prioritas P1.

## 2. StyleSequencer.kt
🟢 Fondasi sangat baik: master clock, pending transition, successor queue, seamless transition, targeted NOTE_OFF, tanpa global allNotesOff, CASM policy, mixer, channel drum protection dan RTR 0-5.
🟡 Scheduler masih memakai coroutine delay/yield. Belum setara scheduler native sample-accurate, tetapi jangan diganti sebelum regression test membuktikannya perlu.
🔴 Sumber bug P0: activeTransposedNotes memakai sourceChannel:sourceNote sebagai identitas. Dua instance C5 yang overlap dapat saling menimpa. Target: section generation + track + channel + event index atau ID instance unik.

## 3. CasmNoteTransformer.kt
🟢 NTR, NTT, Root Trans, Root Fixed, Melody, Chord, Bass, High Key, Note Limit, Bass-On dan RTR sudah dimodelkan.
🟡 RTR Note Generator dan perilaku gitar SFF GE belum lengkap. Ini bukan prioritas pertama untuk bug suara saat ini.

## 4. StyleRepository.kt
🟢 Sudah mengambil section, track, channel, PPQ, meter, bank MSB/LSB, program, controller, CASM, voice name dan source/destination channel.
🟡 Setup tick-0 dan perubahan program runtime berada di dua jalur berbeda. Keduanya harus tetap konsisten.

## 5. style_parser.cpp
🟢 Sudah menangani CSEG, Sdec, Ctab/Ctb2, Cntt, NTR, NTT, RTR, Bass-On, High Key, Note Limit, multiple CSEG dan marker section.
🔴 Sumber bug P0: model native terutama hanya mempertahankan FillAA/BB/CC/DD, sedangkan ArrangerBrain mengenal Fill directional seperti FillAB/BA. Informasi arah dapat hilang saat parser membuat StyleSection.
🔴 Marker masih bergantung pada heuristik teks. Parser perlu grammar section yang lebih eksplisit agar variasi marker Yamaha tidak salah diklasifikasikan.

## 6. bassmidi_player.cpp — Voice Resolver
🟢 BASSMIDI aktif, bank MSB/LSB Yamaha, variation bank, melody/drum role, NOTEOFF1, preload NOWAIT, voice name dan preset enumeration sudah ada.
🔴 Sumber bug P0: resolver dapat memilih program number yang sama di bank lain sebelum mencari kecocokan semantic String/category. Ini sangat cocok dengan gejala Strings→Piano/E.Piano.
Urutan target: exact bank/program → explicit mapping → semantic name/category → family/category → numeric fallback yang masih kompatibel → Piano sebagai fallback melodic terakhir.
Jangan memperbaiki masalah ini di CASM.

## 7. bassmidi_player.cpp — Realtime
🔴 Sumber bug P0: render() berbagi mutex dengan operasi kontrol/preset/preload. Callback audio dapat menunggu operasi yang lebih lambat. Ini kandidat kuat untuk pause/dropout saat transisi atau perubahan voice.
Target: callback realtime tidak boleh menunggu mutex UI/file/SF2.

## 8. AudioEngineManager.kt
🟢 BASSMIDI adalah jalur SF2 aktif; melody/drum/single SF2, sustain, release, mixer, master gain dan inspector tersedia.
🟡 Reload/import SF2 memang menghentikan stream sementara. Itu boleh untuk import SF2, tetapi tidak boleh terjadi saat Main→Fill atau Program Change.

## 9. NativeAudioBridge.kt / native_lib.cpp
🟢 JNI, audio lifecycle, note, preset, mixer, sustain/release, master gain, CASM serialization dan sanitasi metadata sudah terpisah dengan baik.
🟡 g_lastParsedStyle adalah global state untuk satu style aktif; jangan gunakan untuk parsing concurrent tanpa lifecycle guard.

## 10. audio_engine.cpp / audio_engine.h
🟢 Oboe low latency, exclusive→shared fallback, buffer target dan BASSMIDI→Oboe sudah ada.
🔴 Risiko utama tetap mutex di BassMidiPlayer::render.

## 11. CMakeLists.txt
🟢 BASS/BASSMIDI terhubung ke engine.
⚫ FluidSynth masih ter-link sebagai dependency legacy. Audit penghapusannya dilakukan setelah build stabil.

## 12. MidiInputManager.kt
🟢 Android MIDI input, running status, realtime byte handling, channel extraction, CC64, bank MSB/LSB, Program Change dan MIDI OUT sudah ada.
🟡 Chord input berpusat pada channel tertentu; tetap sebaiknya configurable.

## 13. ChordDetector / AcmpChordAnalyzer
🟢 Chord recognition terpisah dari audio rendering dan perubahan chord disettle sebelum CASM retargeting.
🟡 Delay 15 ms adalah kompromi Android dan jangan diperbesar tanpa alasan.

## 14. Regression Test
🔴 Gap terbesar: belum cukup test untuk Main→Fill→Main, Intro→Main, Fill→Main, Ending→Stop, repeated notes, drum+melody, Strings resolution dan meter 2/4, 3/4, 4/4, 6/8.

## Matriks hasil
| Area | Status |
|---|---|
| Master clock | 🟢/🟡 |
| Main A-D | 🟢 |
| Auto Fill | 🟢/🟡 |
| Directional Fill | 🔴 |
| Section ownership | 🟢 |
| Repeated note ownership | 🔴 |
| CASM | 🟡 |
| Yamaha bank identity | 🟢 |
| Voice resolver | 🔴 |
| Drum routing | 🟢/🟡 |
| Realtime audio mutex | 🔴 |
| Sustain | 🟢 panel / 🟡 native |
| Release Time | 🟢 |
| Meter | 🟡 |
| Regression tests | 🔴 |

## Prioritas
### P0
1. Voice Resolver.
2. Realtime BASSMIDI mutex.
3. Unique note-instance ownership.
4. Directional Fill parser/model.

### P1
1. Intro/Ending saat idle.
2. Satukan quantize dengan master musical tick.
3. Definisikan next-bar, next-beat, immediate dan any-beat.
4. Tambahkan regression test lengkap.

### P2
1. Native/high-priority MIDI scheduler.
2. VoiceResolver class terpisah.
3. StyleTransitionPlan.
4. PerformanceState untuk multifunction knob.
5. Audit penghapusan dependency FluidSynth.

## Kesimpulan
Engine tidak perlu di-rewrite. Arsitektur utama sudah benar: Yamaha Style → Parser → CASM → StyleSequencer → BASSMIDI → Oboe → Android.
Masalah utama terkonsentrasi pada identitas voice, identitas note, transisi section, dan realtime audio.