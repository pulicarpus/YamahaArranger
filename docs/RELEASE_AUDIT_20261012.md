# Audit RELEASE — 2026-10-12

Branch kerja: `claude/fitur-realis-audit-ocmbkl`, dibuat dari HEAD remote terbaru
`experiment/universal-sf2-release` = `28f697fd4a21effaa1485c823de76f4414421890`.
Tidak ada merge. `main`, checkpoint style, READY dan `plan/sf2-single-master` tidak disentuh.
ArrangerBrain, StyleSequencer, CASM, loader style dan parser tetap byte-identik
(guard `STYLE_CHECKPOINT` lulus).

## Keluhan

Di Android, dengan Sustain OFF dan style berhenti, RELEASE 0 dan 127 pada preset
Strings terdengar sama.

## Tahap 1: audit (bukti dari kode)

| Mata rantai | Status | Bukti |
|---|---|---|
| Knob → ViewModel | utuh | `SxMainScreen` → `onReleaseUp/Down` → `_releaseTime` (0..127) |
| ViewModel → Brain → JNI | utuh | collector `Dispatchers.Default` → `ArrangerBrain.setKeyboardReleaseTime` → `AudioEngineManager` → `nativeSetKeyboardReleaseTime` |
| JNI → BASSMIDI | utuh | `BASS_MIDI_StreamEvent(ch 0..2, MIDI_EVENT_RELEASE)` + readback `StreamGetEvent` |
| Setelah ganti preset | dipasang ulang | `setChannelPreset` dan `setKeyboardPreset` → `applyKeyboardRelease(ch)` |
| Setelah ganti/reload SF2 dan restart decoder | dipasang ulang | `loadRole`, `loadMelodyFallback`, `ensureEngine`, `AudioEngineManager.start/resume` |
| Controller tertimpa | tidak ditemukan | tidak ada `MIDI_EVENT_RESET`/`SYSTEM`/SysEx/`StreamEvents` ke ch 0..2; hanya `applyKeyboardRelease` yang menulis RELEASE |
| NOTE_OFF keyboard | normal | `MIDI_EVENT_NOTE` velocity 0 (bukan SOUNDOFF); `SOUNDOFF` hanya saat ganti voice part itu |
| Sustain | ledger Kotlin | panel Sustain menunda NOTE_OFF di `ArrangerBrain`; native `setKeyboardSustain` hanya log; CC64 BASS tidak dipakai |
| Batas voice/CPU yang memotong ekor | tidak ada | hanya `BASS_ATTRIB_MIDI_VOICES=1000`; tidak ada `MIDI_CPU`/kill |
| Normalisasi SF2 | aman | `normalizeMelodySf2` hanya menulis ulang field bank `phdr`; generator envelope tetap utuh |
| Reverb | aktif di live | ch 0..2 tidak pernah diberi send reverb, jadi memakai default BASSMIDI; probe lama memakai reverb 0 |
| **MIDI OUT** | **celah nyata** | `MidiInputManager` hanya meneruskan nada dan bank/program. **CC72 tidak pernah dikirim**, jadi RELEASE tidak bisa memengaruhi suara keyboard eksternal |

Bukti BASSMIDI asli sebelumnya (CI run 38260304142, Linux, SF2 sintetis loop dengan
release 1 s): ekor −40 dB 10 / 400 / ≥2000 ms untuk 0 / 64 / 127. Datanya cocok dengan
offset relatif sekitar ±6400 timecent (kira-kira ×1/40 sampai ×40) terhadap release
milik SF2. Ini **inferensi dari pengukuran, bukan dokumentasi BASS**.

### Akar masalah: yang terbukti dan yang belum

- **Terbukti (MIDI OUT):** RELEASE tidak pernah sampai ke keyboard eksternal.
  Diperbaiki dengan CC72 (lihat Tahap 2). Efek di PSR-E343 **UNVERIFIED**.
- **Belum terbukti (audio internal Android):** seluruh jalur kontrol sampai BASSMIDI
  utuh dan sudah diverifikasi lewat readback. Karena RELEASE di BASSMIDI bersifat
  relatif terhadap envelope SF2, hasil "0 = 127" di Android paling mungkin berasal dari
  salah satu hal berikut (semuanya bisa dibedakan oleh diagnostik baru):
  1. generator release (38) preset Strings itu hampir nol atau tidak ada (default SF2
     −12000 tc ≈ 1 ms), sehingga ×40 pun tetap di bawah ~40 ms;
  2. ekor yang terdengar didominasi reverb default (sama untuk 0 dan 127);
  3. sampel tidak di-loop dan habis sebelum atau sesaat setelah tuts dilepas.

  File SF2 Yamaha milik pengguna tidak ada di workspace ini, jadi tidak ada klaim yang
  dibuat untuk preset Yamaha.

## Tahap 2: perubahan

1. **Diagnostik akar masalah di APK** (`RELEASE PCM • RIGHT n` di UTILITY), hanya baca:
   - `RELEASE LIVE`: nilai knob dibandingkan readback controller live di part tersebut
     (`delivered`), CC64 di BASS, dan send reverb/chorus live.
   - `RELEASE SF2 ENVELOPE`: generator 38 efektif (instrument + offset preset, nilai
     default bila tidak ada), mode loop (54), durasi sampel non-loop, dan modulator yang
     menuju release, dari file SF2 **asli** dengan bank asli, untuk key/velocity probe.
   - `RELEASE PCM … lastAboveMinus60dBMs`: ambang kedua yang konsisten.
   - `RELEASE PCM reverbTail`: ekor RELEASE 0 dengan send live, termasuk sinyal dry. Hanya
     dilaporkan, tidak masuk WAV; urutan WAV tetap 0, 64, 127.
   - `RELEASE DIAGNOSIS=` salah satu dari: `LIVE_CONTROLLER_NOT_DELIVERED`,
     `BASS_SUSTAIN_HOLDS_NOTES`, `RELEASE_RESPONDS_AT_DECODER`,
     `PRESET_LIMITATION_SF2_RELEASE_NEAR_INSTANT`,
     `PRESET_LIMITATION_FINITE_SAMPLES_END_BEFORE_KEY_UP`, `NO_HELD_PCM_AT_PROBE_KEY`,
     `UNEXPLAINED_NO_RESPONSE_REPORT_AS_DEFECT`, `INCONCLUSIVE_*`.
     Preset tidak pernah disalahkan bila selisih ekor 0→127 ≥ 50 ms.
   - `RELEASE CONTEXT`: Sustain panel, MIDI OUT, nilai knob.
2. **MIDI OUT CC72** (hanya bila MIDI OUT ON): dikirim ke ch 0..2 saat knob berubah,
   saat MIDI OUT diaktifkan, dan setelah mirror program change RIGHT. BASSMIDI tetap
   menerima RELEASE lewat jalur lamanya.

Yang **tidak** diubah: tidak ada CC7/CC11 atau gain sebagai RELEASE palsu, tidak ada
penulisan generator SF2, tidak ada perubahan routing voice, LEFT/ACMP, Sustain,
scheduler atau style.

## Tahap 3: tes

| Tes | Lokal (sesi ini) | CI BASSMIDI asli |
|---|---|---|
| Kontrak routing/release native (mock) | PASS 1926 cek (sebelum: 1911) | dijalankan workflow |
| Interpretasi envelope SF2 (tanpa BASS) | PASS 25 cek | dijalankan workflow |
| Regresi lifecycle/resolver/bank/percussion/role PCM/snapshot | PASS | dijalankan workflow |
| JVM kontrak (+ CC72 MIDI OUT, + Sustain menunda NOTE_OFF) | PASS 27 tes (sebelum: 25) | dijalankan workflow |
| Matriks karakter (Strings loop 0,5 s; Pad loop 2 s; Piano finite 2 s / release 0,25 s; preset tanpa gen 38) lewat jalur produksi | tidak bisa (SDK BASS diblokir proxy) | **UNVERIFIED sampai CI berjalan** |
| Isolasi: NOTE_OFF RIGHT1 tidak mematikan RIGHT2; ganti voice RIGHT2 tidak memotong ekor RIGHT1 | tidak bisa | **UNVERIFIED sampai CI berjalan** |

Asersi CI baru (BASS asli): untuk tiga karakter dengan release SF2 yang terdengar,
ekor −40 dB harus naik tegas 0 < 64 < 127 dan diagnosis probe harus
`RELEASE_RESPONDS_AT_DECODER`. Untuk preset tanpa generator release, hasilnya dicetak
dan diagnosis `PRESET_LIMITATION_*` hanya diizinkan bila selisih ekor live < 50 ms.

## Status

| Target | Status |
|---|---|
| Jalur kontrol RELEASE (kode) | PASS (audit + readback) |
| BASSMIDI asli, SF2 sintetis | PASS sebelumnya (run 38260304142); matriks baru UNVERIFIED |
| Android, SF2 Yamaha | UNVERIFIED: perlu log `RELEASE DIAGNOSIS` dari perangkat |
| PSR-E343 via MIDI OUT (CC72) | UNVERIFIED |
| APK | UNVERIFIED: belum ada run GitHub Actions untuk commit ini |

## Integrasi Codex — 2026-10-13

Basis lokal dan remote RELEASE diverifikasi sebagai `28f697fd4a21effaa1485c823de76f4414421890`.
Patch diterapkan sekali menggunakan `git apply` pada cabang eksperimen terpisah.
SHA-256 patch handoff: `e98a754ecea1424669556141ce6996b26e76c871310c86f2f5397ed3282a4de5`.
Angka dalam tabel sebelumnya merupakan hasil historis Claude, bukan hasil integrasi.

Audit independen menambahkan pengaman diagnostik tanpa mengubah jalur audio:

- Decoder gagal harus menghasilkan `INCONCLUSIVE_PCM_PROBE_FAILED`, bukan keterbatasan preset.
- Modulator release yang tidak diketahui atau custom menjaga diagnosis tetap inconclusive.
- Durasi mentah shdr tidak membuktikan sampel habis ketika pitch atau alamat sampel berubah.
- Decode tambahan dengan send reverb/chorus live juga mengandung sinyal dry; ini bukan
  pengukuran reverb saja atau bukti pasti bahwa reverb menutupi RELEASE.

SDK BASS Linux tidak tersedia lokal dan unduhan un4seen ditolak proxy HTTP 403.
Pengujian PCM asli dijalankan sebagai gate GitHub Actions sebelum build APK. Tidak ada
asersi matriks karakter/isolation dari handoff yang dilemahkan atau dihapus.

Hasil lokal integrasi: suite RELEASE lulus (27 JVM, 30 envelope, 1926 recorder,
serta tujuh suite regresi native). Guard SHA lama `test_drum_shadow_guards.py`
gagal dengan `Unreviewed F12 file: .../StyleSequencer.kt`. Error yang sama
direproduksi pada salinan bersih basis `28f697f`: ini kegagalan baseline,
bukan perubahan style oleh patch. Guard tetap utuh; status ini bukan PASS.
Pemeriksaan byte terhadap checkpoint `c75122d` lulus. Tes real BASS dan APK
harus dilihat pada run Actions untuk commit integrasi, bukan diasumsikan dari mock.
