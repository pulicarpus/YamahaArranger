# Style Voice Inspector & Editor — laporan

Branch: `feature/style-voice-inspector-editor` (dari baseline LIVE V3.2 `f9c95a8`; tidak di-merge ke main).
Baseline terverifikasi lewat `git merge-base`/guard: memuat LIVE V3.2 (f9c95a8), Registration Voice Recall (541eec0), Master Transpose (cac8b8e), MIDI OUT PSR-E343, BASSMIDI/SF2 Colombo+Tyros, CASM/ACMP, StyleSequencer.

## Stage A — alur Voice Style yang sebenarnya (dari kode)

```
file .STY ──native style_parser.cpp (SMF + CASM/Ctab, SFF1; SFF2 belum punya detektor terverifikasi → dialek "UNKNOWN")
   └─ StyleRepository.loadStyle → ParsedStyle.sections["MainA","FillAA","IntroB",…] → StylePartModel(events, casmPolicies, program/bankMsb/bankLsb tick-0)
        └─ StyleVoiceRequests.extract: CC0/CC32/PC pada tick 0 per part (source ch → destination ch via CASM), PC dinamis tick>0 dicatat terpisah
             └─ StyleSequencer.applyVoicesFromCasm(section)  ← hanya saat NAMA section berubah (Main→Fill→Main memanggil lagi)
                  ├─ internal: audioEngine.setChannelProgram(dst, PC, bank=MSB*128+LSB, nama CASM) → resolver native (voice_resolver.h: hanya "same_family")
                  │            atau pin (nativePinChannelPreset) bila ada override
                  └─ MIDI OUT: midiInputManager.sendProgramChange(dst, PC, MSB, LSB) → MidiMixerOutput (replay saat konek)
             └─ PC dinamis (tick>0): sequencer menerapkan di tengah section (audio + MIDI OUT), kecuali ada override
```
Peran part = channel tujuan: 8 Rhythm1, 9 Rhythm2, 10 Bass, 11 Chord1, 12 Chord2, 13 Pad, 14 Phrase1, 15 Phrase2. Voice **tidak** satu per part: PC bisa beda per section dan di tengah section.
Fakta penting: resolver melodi native hanya menerima kandidat "same_family"; ia tidak membuktikan identitas Voice Yamaha. Karena itu EXACT hanya diberikan bila bank/program preset yang terikat sama persis dengan MSB*128+LSB / PC permintaan (kecocokan angka, bukan nama).

## Stage B — Inspector (read-only)
`StyleVoiceInventory.kt`: baris per (section, part): MSB/LSB/PC mentah + tampilan 1-based, channel sumber→tujuan, nama Voice hanya bila ada di tabel CASM, status `EXACT/FALLBACK/MISSING/AMBIGUOUS/UNKNOWN/OVERRIDDEN`, alasan, dan provenance (`AUTHORED / INTERPRETED / RESOLVED / RUNTIME_OBSERVED`).
Pengamatan runtime (ChannelBinding) hanya valid untuk section yang sedang aktif; section lain memakai pengecekan katalog (angka) atau mewarisi hasil bila permintaannya identik (ditandai "tidak diukur untuk section ini"); selain itu UNKNOWN + alasan.

### Contoh inventori dari fixture nyata (ROSITA.STY, parser C++ produksi, tanpa SF2 dimuat di host → semua UNKNOWN, ini benar)
```
style='ROSITA.STY' format=SFF format not identified active=- runtime=false
INTRO 2 | Rhythm 2 (src 10 -> dst 10) | 127:0:0 | not resolved | UNKNOWN
INTRO 2 | Chord 1 (src 12 -> dst 12) | -:-:- | — | UNKNOWN
INTRO 2 | Pad (src 14 -> dst 14) | -:-:- | — | UNKNOWN
MAIN A | Rhythm 1 (src 9 -> dst 9) | 127:0:0 | not resolved | UNKNOWN
MAIN A | Rhythm 2 (src 10 -> dst 10) | 127:0:8 | not resolved | UNKNOWN
MAIN A | Bass (src 11 -> dst 11) | 0:112:33 | not resolved | UNKNOWN
MAIN A | Chord 1 (src 12 -> dst 12) | 0:112:27 | not resolved | UNKNOWN
MAIN A | Chord 2 (src 13 -> dst 13) | 0:112:27 | not resolved | UNKNOWN
MAIN A | Pad (src 14 -> dst 14) | 0:112:48 | not resolved | UNKNOWN
MAIN A | Phrase 1 (src 15 -> dst 15) | 0:112:27 | not resolved | UNKNOWN
MAIN B | Rhythm 1 (src 9 -> dst 9) | 127:0:16 | not resolved | UNKNOWN
MAIN B | Rhythm 2 (src 10 -> dst 10) | 127:0:0 | not resolved | UNKNOWN
MAIN B | Bass (src 11 -> dst 11) | 0:112:33 | not resolved | UNKNOWN
MAIN B | Chord 1 (src 12 -> dst 12) | 0:112:27 | not resolved | UNKNOWN
MAIN B | Chord 2 (src 13 -> dst 13) | 0:112:3 | not resolved | UNKNOWN
MAIN B | Pad (src 14 -> dst 14) | 0:112:48 | not resolved | UNKNOWN
```
(Seluruh 10 section: INTRO 2, MAIN A–D, FILL A–D, ENDING 2. Part tanpa PC tick-0 tampil `-:-:-`, tidak dikarang. Format SFF tidak tampil di tes ini karena dump TSV tidak membawa `dialectIdentity`; di aplikasi diisi parser.)

## Stage C — UI
Overlay layar penuh `StyleVoiceEditorScreen.kt`, dibuka dari header Mixer (tombol **STYLE VOICES**, bisa dari ARRANGER maupun LIVE → MIXER) sehingga tombol transport LIVE V3.2 tidak berubah. Header: nama style, format SFF, output aktif, jumlah part/voice, status profil (UNSAVED/SAVED). Selector section hanya menampilkan section yang ada. Tabel PART | STYLE REQUEST | ACTUAL VOICE | STATUS | EDIT; ketuk baris → panel detail. Render Skia dari sumber Compose asli ada di branch `preview/style-voice-editor` (04–08 *.png; data specimen sintetis, bukan file Yamaha).

## Stage D — Editor penggantian
* **SF2/BASSMIDI**: semua preset melodi dari semua SoundFont yang termuat (bukan hanya GM), pencarian, identitas = nama file + sidik jari pdta + bank + program (kit bank 127/128 tidak ditawarkan untuk part melodi).
* **External MIDI OUT**: dari daftar EXT Registration milik pengguna, atau angka MSB/LSB/Program yang diketik (Program 1-128 ditampilkan 1-based, dikirim 0-based). **Aplikasi tidak memiliki katalog Voice PSR-E343 terverifikasi**, dan UI menyatakannya.
* Scope: *THIS SECTION* (part ini di section ini) / *ALL SECTIONS* (part ini di seluruh style). Prioritas: **section-specific → part-wide → request Style/resolver**; SF2 (internal) dan external (MIDI OUT) adalah dua target independen.
* Aturan PC di tengah section (diputuskan & didokumentasikan): override berlaku untuk **seluruh section**; PC dinamis style untuk part itu ditahan (log `DYNAMIC_PC_SUPPRESSED_BY_OVERRIDE`) dan ditampilkan di panel detail, tidak dihapus dari file.
* Aksi: APPLY (langsung terdengar, belum tersimpan), SAVE PROFILE, RESET PART, RESET SECTION, RESET ALL. Rhythm 1/2 **read-only** (membawa pemetaan nada drum) dengan alasan tampil di UI.

## Stage E — Audition
Empat nada uji pada channel Style milik part itu sendiri, **hanya saat Style STOP** (UI menjelaskan dan menonaktifkan tombol saat berjalan). Membandingkan ORIGINAL vs REPLACEMENT/CURRENT. Hanya NOTE_ON/OFF nada itu sendiri pada channel itu; tanpa allNotesOff/global reset; voice efektif dikembalikan setelahnya (tes: `auditionOwnsOnlyItsChannelAndRestoresTheEffectiveVoice`, `auditionCodeNeverUsesGlobalResets`). Tidak menyentuh RIGHT.

## Stage F — Persistensi
`style-<sha256 isi file>.json` di `filesDir/style_overrides`, terpisah dari file Style (Style file + Profile → mapping efektif). Schema 2: identitas style (hash+nama), scope, section, channel, ref request asli (MSB/LSB/PC), SoundFont (nama + pdta), target (SF2/EXTERNAL), revisi, waktu simpan, CRC32; tulis atomik + `.bak` + karantina; schema 1 lama dibaca & ditingkatkan saat menulis; schema lebih baru/rusak = read-only (tidak ditimpa). Preset hilang/berubah → status STALE_* terlihat, **tidak** diganti Piano. Contoh profil tersimpan lalu dimuat ulang identik (tes `savedProfileReloadsIdenticallyAfterRestart`):
```
{"crc32":"a89ca1fc","kind":"styleVoiceOverrides","payload":{"externalOverrides":[{"channel":12,"hasOrig":true,"label":"Strings","lsb":112,"msb":0,"origLsb":112,"origMsb":0,"origProgram":33,"program":48,"scope":"ALL","section":null}],"overrides":[{"bank":0,"channel":10,"drum":false,"hasOrig":true,"origLsb":112,"origMsb":0,"origProgram":33,"pdta":"pppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppp","presetName":"Violin","program":40,"scope":"SECTION","section":"MainA","sf2FileName":"Tyros.sf2"}],"revision":2,"styleFileName":"A.STY"},"savedAt":1234,"schema":2,"styleSha256":"043a718774c572bd8a25adbeb1bfcd5c0256ae11cecf9f9c3f925d0e52beaf89"}
```

## Stage G — Integrasi playback (lapisan sempit di StyleSequencer, tanpa refactor)
`sectionVoicePins` + `partExternal/sectionExternal`; pin efektif direkonsiliasi saat `applyVoicesFromCasm` (hanya bila ada pin section; tanpa itu perilaku lama identik). External mengganti hanya Bank/PC yang dikirim ke MIDI OUT; drum (8/9) dan RIGHT/LEFT ditolak. Reset mengirim kembali request Style.

## Pengujian (host JVM, 202 tes CPV + 85 LIVE/REG + Registration Recall)
Inspector (`StyleVoiceInventoryTest`, `StyleVoiceRositaTest` fixture nyata), model/profil (`StyleVoiceOverrideProfileTest`, `StyleVoiceEditorOpsTest`: replace 1 part/1 section/seluruh section, reset, simpan/muat, restart, SF2 berubah/hilang, schema lama/rusak), playback (`CpvOverrideIntegrationTest`: section pin, prioritas, Fill→Main, external vs internal, drum tak berubah, audition). Master Transpose & Registration Recall tetap lulus (suite yang sama).

## Batas yang belum terbukti / keterbatasan
* **Tidak ada verifikasi perangkat**: bunyi audition, biaya `applyFonts` native saat pin berganti di transisi Fill→Main (hanya terjadi bila pengguna memakai override per-section), dan perilaku nyata PSR-E343 belum diuji. Tes host tidak membuktikan kemiripan suara Yamaha.
* Status runtime hanya untuk section aktif; section lain = hasil katalog/warisan atau UNKNOWN.
* SFF2: tidak ada detektor terverifikasi → format ditampilkan "tidak teridentifikasi".
* Drum kit per-style tetap lewat picker Mixer lama (tidak diedit di editor ini).
* Perubahan yang belum di-SAVE hilang saat style dimuat ulang; picker Mixer menyimpan hanya perubahannya sendiri.
* Skrip guard historis (`test_drum_shadow_guards`, `test_mix_percussion_boundaries`, `test_f12_timing`, `test_production_regression_bypass`) sudah gagal pada baseline dan bukan bagian CI; tidak diubah.

## Perbaikan setelah uji perangkat (Rhythm 1/2 tampil MISSING padahal terdengar)
Akar masalah: sebelum START engine belum membuat binding (`UNRESOLVED/no_verified_drum_binding` untuk drum, `UNINITIALIZED` untuk melodi; perilaku yang disengaja, lihat `tests/cpv_native_test.cpp`). Inspector memetakan `UNRESOLVED` ke `MISSING (silent)` dan snapshot tidak dimuat ulang saat START.
Perbaikan (hanya pelaporan Inspector; drum mapping, BASSMIDI, StyleSequencer, CASM, ACMP dan jalur audio tidak disentuh):
* `UNRESOLVED/no_verified_drum_binding`, `UNINITIALIZED` → `UNKNOWN` dengan kode `NO_BINDING_REPORTED`. `MISSING` (`PRESET_MISSING`) hanya untuk bukti eksplisit engine (`no_compatible_candidate`, `keyboard_preset_missing`). Readback gagal → `UNKNOWN` (`BINDING_NOT_VERIFIED`). `AUDIO_SILENT` tidak pernah dihasilkan: Inspector tidak mengukur audio.
* Snapshot membawa `InspectorSession(playing)`; alasan menjelaskan "Style berhenti: engine mengikat Voice saat section dimulai; ini bukan bukti bagian itu diam". Hasil "tidak ada binding" tidak diwariskan ke section lain (`NOT_MEASURED_THIS_SECTION`).
* Muat ulang otomatis saat `isPlaying` berubah, dibatasi: maksimal 4 pembacaan berjarak 400 ms, hanya selama belum ada binding; katalog SoundFont dibaca sekali per pemicu, di luar loop.
* Tes regresi: sebelum START, sesudah binding, sedang berjalan tanpa binding, bukti MISSING eksplisit, dan pergantian section.
