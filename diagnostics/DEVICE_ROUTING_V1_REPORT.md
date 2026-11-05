# DEVICE ROUTING V1 — SETTINGS → MIDI & AUDIO

- **Branch:** `feature/midi-song-v1`, dimulai dari HEAD `1edab41` (bersih, sama dengan remote). Tidak di-merge.
- **Status:** dites di host/CI saja. **Belum diuji di Redmi Pad, USB DAC, Bluetooth, atau PSR-E343.** Hasil host tidak membuktikan ke mana Android benar-benar mengalirkan suara.

## 1. Audit sebelum perubahan

| Bagian | Temuan |
|---|---|
| MIDI IN / OUT | `MidiInputManager.connect()` membuka **satu** perangkat (perangkat pertama yang punya output port). Port output 0 dipakai untuk MIDI IN (parser) dan port input 0 untuk MIDI OUT (router `MidiMixerOutput`). Tidak bisa memilih perangkat; IN dan OUT selalu perangkat yang sama |
| Cabut MIDI | Callback hanya mengosongkan tampilan tombol. Kalau keyboard dicabut saat tombol ditekan, NOTE OFF tidak pernah datang (bisa ada not gantung di internal). Port MIDI OUT yang mati tetap dianggap tersambung |
| Oboe | `AudioStreamBuilder` dibuka **tanpa `setDeviceId`**, jadi selalu ke perangkat default. **Tidak ada error callback**: kalau USB DAC/Bluetooth dicabut, stream AAudio putus dan tidak pernah dibuka ulang (suara internal diam) |
| Mode output | Hanya ada tombol MIDI OUT ON/OFF; internal selalu ON. `OutputMode` di Registration hanya untuk recall voice per slot, bukan global |
| E343 AUTO | Diaktifkan lewat mode di panel E343 AUTO (tersimpan) dan berlaku untuk **perangkat MIDI OUT apa pun** |
| Batasan Oboe/Android | `setDeviceId` dipatuhi oleh AAudio (Android 8.1+), diabaikan oleh OpenSL ES. `AudioManager.setPreferredDevice` / `AudioDeviceInfo` saja **tidak** mengubah jalur Oboe, jadi pemilihan harus dilakukan saat stream dibuka. ID perangkat Android berubah setiap kali perangkat dicolok ulang. Bluetooth A2DP tidak mendukung mode Exclusive/MMAP (jatuh ke Shared dengan latensi lebih besar) |

## 2. Mekanisme routing (perubahan minimal)

**MIDI.** `MidiInputManager.connectRouting(inInfo, outInfo)`:
- membuka perangkat MIDI IN (port output 0 → **parser yang sama**) dan perangkat MIDI OUT (port input 0 → **router yang sama**);
- perangkat yang sama dibuka **sekali**;
- tidak ada jalur input baru dan tidak ada pengiriman ganda;
- panggilan yang lebih baru membatalkan pembukaan lama yang belum selesai (generation counter); perangkat lama yang telat terbuka langsung ditutup.

Saat ganti perangkat MIDI OUT atau menutup:
- router melepas semua NOTE yang dimilikinya di perangkat **lama**;
- lalu dikirim CC64=0 dan CC123 di 16 channel (ini juga menutup not Song/RIGHT);
- setelah itu port ditutup.

Saat dicabut:
- **MIDI IN:** tombol yang masih ditekan dan sustain dilepas lewat callback `onNoteOff` / `onSustainChange` yang sama, satu kali per not. Datanya dari catatan `heldIn` di parser, yang dicatat bersamaan dengan `liveKeys`.
- **MIDI OUT:** router diputus, jadi tidak ada tulisan ke port mati.
- Colok ulang: perangkat tersambung kembali sesuai pilihan.

**Audio.**
- `AudioEngineManager.setOutputDevice(id)` menutup lalu membuka ulang stream Oboe dengan `builder.setDeviceId(id)` (0 = System Default, tanpa `setDeviceId`).
- Urutan kunci sama seperti saat memuat SF2: `readiness.operations`, lalu mutex SF2. Jadi ganti perangkat tidak pernah bentrok dengan pemuatan font.
- BASSMIDI, SoundFont, resolver, dan DSP balance tidak disentuh. Not tetap hidup selama jeda pendek saat stream dibuka ulang.
- Kalau perangkat pilihan gagal dibuka → **fallback ke System Default**, dan ini dilaporkan.

Penanganan cabut audio:
- Error callback Oboe (`onErrorAfterClose`) **hanya menyalakan flag**; tidak membuka stream di thread Oboe.
- Watchdog tiap 1 detik (di luar thread audio) membuka ulang stream.
- `AudioDeviceCallback` mengikuti colok/cabut. Kalau perangkat yang dipilih hilang, aplikasi memakai System Default dengan status DISCONNECTED.

**OUTPUT MODE** (disimpan):
- **INTERNAL:** BASSMIDI ON, MIDI OUT OFF.
- **MIDI OUT:** BASSMIDI OFF, MIDI OUT ON. BASSMIDI tetap merender (status not tetap tepat), tetapi sinyalnya diganti hening setelah render. Metronom tetap terdengar.
- **BOTH:** keduanya ON.
- Tombol MIDI OUT di bar tetap bekerja: ON = BOTH (seperti sebelumnya), OFF = INTERNAL.

**Audio Output Device** hanya menentukan ke mana stream BASSMIDI dikirim. **MIDI Output Device** hanya menentukan tujuan MIDI. Keduanya tidak dicampur.

**E343.** Mode panel E343 AUTO (algoritma tidak diubah) **hanya efektif** kalau perangkat MIDI OUT yang dipakai:
- dikonfirmasi pengguna di MIDI & AUDIO ("THIS IS MY PSR-E343"); atau
- menyebut dirinya "E343" di namanya.

Untuk perangkat lain, router menerima ORIGINAL (permintaan Style tanpa perubahan). Nama USB "Digital Keyboard" dari Yamaha **tidak** dianggap E343. Profil manual dan trim tersimpan tetap berfungsi seperti sebelumnya.

**Penyimpanan.** File `device_routing.txt` di penyimpanan privat aplikasi. Isinya: MIDI IN (AUTO/kunci), MIDI OUT (OFF/AUTO/kunci), audio (DEFAULT/kunci), mode, perangkat E343 yang dikonfirmasi, dan label.
- Kunci perangkat = transport|pabrikan|produk|nama untuk MIDI, dan jenis|nama produk untuk audio — **bukan ID Android**.
- Default untuk pengguna lama: AUTO / AUTO / System Default / INTERNAL. Ini sama dengan perilaku lama: perangkat pertama dipakai untuk IN dan OUT, dan MIDI OUT mati saat aplikasi dibuka.
- AUTO MIDI OUT mengikuti perangkat MIDI IN dulu, lalu perangkat USB/Bluetooth. Port virtual milik aplikasi lain tidak dipilih otomatis.
- Perangkat tersimpan yang tidak ada **tidak pernah diganti diam-diam**: statusnya DISCONNECTED dan tidak ada MIDI yang dikirim.

## 3. Perangkat yang ditawarkan

| Jenis | Sumber |
|---|---|
| MIDI IN / OUT | Semua `MidiManager.devices` (USB, Bluetooth MIDI yang sudah dipasangkan di Android, virtual), dengan nama dan pabrikan aslinya |
| Audio | Speaker internal, USB (device/headset/accessory), Bluetooth (A2DP, BLE), headphone kabel, ditambah System Default. Yang tidak ditawarkan: earpiece, SCO (jalur telepon), HDMI, telephony |

## 4. File yang berubah

| File | Perubahan |
|---|---|
| `app/src/main/cpp/audio_engine.h/.cpp`, `native_lib.cpp` | Hanya penambahan: `setDeviceId`, error callback (flag), mute internal setelah render, 4 fungsi JNI |
| `NativeAudioBridge.kt`, `AudioEngineManager.kt` | Hanya penambahan: `setOutputDevice`, `setInternalMuted`, status |
| `midi/MidiInputManager.kt` | Hanya penambahan: `connectRouting`, `closeRouting`, `inputDeviceRemoved`, `outputDeviceRemoved`, catatan tombol yang ditekan. `connect()` lama tetap ada |
| `routing/DeviceRouting.kt` (baru) | Aturan murni (tanpa Android), dites di JVM |
| `routing/DeviceRoutingController.kt` (baru) | Daftar perangkat, callback, watchdog, penyimpanan |
| `ui/DeviceRoutingPanel.kt` (baru), `MainActivity.kt` (1 baris), `SxMainScreen.kt` (tombol SETTINGS di bar MIDI) | UI |
| `ui/MainViewModel.kt` | Auto-connect lewat routing; OUTPUT MODE; gerbang E343 per perangkat |
| `ui/ContentResolverProvider.kt` | `appContext()`, `deviceRoutingFile()` |
| `ui/E343OutPanel.kt` | Satu baris status "applied / not applied" |
| Tes | `tests/rtdiag/DeviceRoutingRtTest.kt`, `tests/device_routing_native_test.cpp`, `tools/test_device_routing_native.py`, mock Oboe dan stub MIDI diperluas, guard, workflow Quick |

**Tidak diubah:** Style Sequencer, CASM/NTT/SFF1/SFF2, resolver voice BASSMIDI, pemuatan SoundFont, Recorder V1B/V1C, Song Player, RIGHT USB velocity / SHORT_GATE, algoritma Auto E343, Style Voice Editor, balance Mixer, router `MidiMixerOutput`. Semua ini diperiksa oleh guard.

## 5. Tes

| Tes | Isi | Hasil |
|---|---|---|
| `DeviceRoutingRtTest` (MidiInputManager produksi + perangkat palsu) | IN dan OUT perangkat berbeda; perangkat yang tidak dipilih tidak dibuka dan tidak ditulisi; satu perangkat untuk IN+OUT dibuka sekali (tanpa NOTE ON ganda); MIDI OUT OFF = 0 tulisan; ganti OUT → NOTE OFF + CC64/CC123 ×16 di perangkat lama, perangkat baru bersih; cabut keyboard → tombol dan sustain dilepas sekali, colok ulang jalan; cabut modul → tidak ada tulisan, MIDI IN tetap jalan, colok ulang jalan; pembukaan lama yang telat ditutup; aturan AUTO/OFF/tersimpan/hilang/identitas/fallback audio/mode/gerbang E343; simpan dan pulihkan (file rusak = default); rantai produksi (parser → Brain → Sequencer → router) lewat `connectRouting`: semua NOTE melodi berpasangan | lihat §6 |
| `device_routing_native_test` (mock Oboe + mock BASS) | System Default = builder tanpa device; device 5 → builder menerima 5, stream melaporkan device sebenarnya; error callback → flag, open ulang → flag hilang; mute → hening, unmute → voice yang sama masih berbunyi | lihat §6 |
| Regresi | Love Song / ROSITA / HardRock2 / EnglishWaltz2 (E343 AUTO, audit MIDI OUT, octave, SHORT_GATE), Recorder V1B/V1C, paritas, RIGHT USB, split, Mixer, CPV JVM, Song native, R22/R23, RIGHT diag, latency, metronom, CPV native, guard | lihat §6 |

**Butuh uji fisik:**
- perpindahan audio nyata (speaker / USB DAC / Bluetooth) dan apakah AAudio mematuhi device ID di Redmi Pad;
- lama jeda saat stream dibuka ulang;
- perilaku saat dicabut;
- latensi Bluetooth;
- nama perangkat yang dilaporkan PSR-E343 lewat USB.

## 6. Hasil (commit `b81cb76`)

Commit `4ad2bce`: APK berhasil dikompilasi, tetapi CPV JVM gagal pada `InternalGainTest.nativeStageSitsAfterTheSynthAndNeverNearMidiOrVelocity`. Tes ini mensyaratkan gain internal tepat di antara render synth dan clamp, sedangkan baris mute disisipkan di antaranya. Diperbaiki di `b81cb76`: mute dipindah ke **setelah** clamp, dan hasil audionya identik.

| Suite (lokal, berurutan) | Hasil |
|---|---|
| CPV JVM | OK (385) |
| Native Device Routing (mock Oboe/BASS) | PASS: default tanpa device, device 5 diteruskan, flag disconnect, mute hening, unmute voice tetap |
| DeviceRoutingRtTest | OK (10) |
| Love Song: E343 AUTO + paritas Recorder + routing | OK (20) |
| HardRock2: audit MIDI OUT + octave + E343 AUTO | OK (19) |
| EnglishWaltz2: SHORT_GATE + E343 AUTO | OK (22) |
| ROSITA: paritas Recorder + Transpose/ACMP/LEFT + E343 AUTO + routing | OK (28) |
| Recorder V1B/V1C | OK (3) |
| rtdiag default + router MIDI OUT | OK (52) |
| MIDI IN split + RIGHT USB velocity + Mixer keys | OK (25) |
| Song native, R22 LIVE/REG, R23 ROSITA byte replay, RIGHT diag, latency A/B, metronom, CPV native | PASS |
| Guard isolasi | PASS |
| CI Quick `42010987323` | **SUCCESS** (APK, tes, UI) |
| CI Style Voice Inspector & Editor APK `42010987581` | **SUCCESS** (host + android) |

APK: Quick https://github.com/pulicarpus/YamahaArranger/actions/runs/42010987323/artifacts/13406763664 · Full https://github.com/pulicarpus/YamahaArranger/actions/runs/42010987581/artifacts/13411691472

Catatan: di putaran pertama, satu tes SHORT_GATE EnglishWaltz2 gagal di `n >= 20`, yaitu jumlah NOTE ON dalam 1500 ms. Penyebabnya host dibebani tes lain secara paralel; jalur itu tidak memakai routing. Saat dijalankan sendiri dan dalam putaran berurutan, tes itu lulus (13/13, 22/22).

## 7. Risiko regresi

1. **Default sama dengan perilaku lama**, tetapi semua koneksi sekarang lewat `connectRouting` (bukan `connect`). Risiko: perbedaan waktu buka di perangkat nyata.
2. **E343 AUTO kini perlu konfirmasi perangkat** (sesuai permintaan). Kalau sebelumnya sudah memilih AUTO, profil **tidak** aktif sampai perangkat ditandai di MIDI & AUDIO.
3. **Watchdog membuka ulang stream** setelah error Oboe. Kalau Android terus memutus stream, akan ada percobaan buka ulang tiap 1 detik.
4. **Mode MIDI OUT** membuat internal hening, tetapi CPU render tetap jalan.
5. **Ganti MIDI IN mengubah MIDI OUT juga sesaat** kalau keduanya beda perangkat: router dibuka ulang dan CC123 dikirim ke perangkat OUT.

## 8. Langkah tes Redmi Pad + PSR-E343

1. Instal APK, buka aplikasi **tanpa** mengubah apa pun: perilaku harus seperti sebelumnya (E343 tersambung otomatis, MIDI OUT mati). Bar MIDI menampilkan "IN … · OUT … · INTERNAL".
2. Bar bawah → **SETTINGS** → MIDI & AUDIO. Pastikan nama perangkat asli tampil di MIDI INPUT dan MIDI OUTPUT.
3. **OUTPUT MODE:**
   - BOTH: internal + E343 berbunyi;
   - MIDI OUT: hanya E343 (internal hening, Style tetap jalan);
   - INTERNAL: hanya tablet.
4. **MIDI OUTPUT** → pilih E343 → tekan **THIS IS MY PSR-E343**. Lalu buka Style Voice Editor → E343 AUTO → pilih mode AUTO. Status harus "Applied". Pilih MIDI OUTPUT OFF: tidak ada bunyi dari E343.
5. **Cabut USB E343 saat Style dan tombol RIGHT sedang berbunyi:**
   - tidak boleh ada not gantung di tablet;
   - status berubah menjadi DISCONNECTED;
   - colok ulang: tersambung kembali.
6. **AUDIO OUTPUT:**
   - pilih speaker internal / USB DAC / Bluetooth (kalau ada) saat Style berbunyi;
   - suara pindah dengan jeda singkat dan tidak ada not gantung;
   - cabut USB DAC: suara kembali ke System Default;
   - catat latensi Bluetooth.
7. Tutup lalu buka aplikasi: pilihan dipulihkan. Perangkat yang tidak ada tampil sebagai "(not connected)" dan tidak diganti.
8. **Regresi:** Love Song, ROSITA, RIGHT1/2/3, LEFT, ACMP, split, Recorder FULL ARRANGER V1C.1 (rekam → LOAD TAKE), Song Player.
9. Kirim log (DebugLog). Baris yang dicari: `🔀 ROUTING`, `🔈 AUDIO OUTPUT`, `🔌 MIDI`.
