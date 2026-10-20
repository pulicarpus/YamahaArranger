# Multi-SF2 Voice Selection — SX Prime Melody manual voices (laporan)

Branch: `experiment/multi-sf2-voice-selection` (dari `feature/style-voice-inspector-editor` @ `0c59106`; bukan dari main; tidak di-merge).
Dokumen sumber dibaca: `STYLE_VOICE_INSPECTOR_EDITOR_REPORT.md`, `SX920_SF2_VOICE_COVERAGE.md`, `COLOMBO_MULTISF2_AUDIT_2026-09-30.md`, `VOICE_COVERAGE_MATRIX_SUMMARY_20260930.md`, `SF2_SEMANTIC_INVENTORY_DIAGNOSTIC.md`.
Inventori lengkap Prime: `docs/SX_PRIME_PRESET_INVENTORY.md`.

## Temuan utama (akar masalah)
Sejak CPV, tumpukan SF2 otomatis hanyalah **Colombo (primary) + Tyros (fallback)**; `SoundFontRolePlanner` dan `loadCpvStack` sengaja **tidak memuat SX Prime Melody/Drum**. Akibatnya preset Prime tidak pernah ada di katalog BASSMIDI, sehingga tidak muncul di Voice Selector maupun Style Voice Editor. Alur Editor sendiri (REPLACE → APPLY → SAVE → RELOAD) sudah benar dan tidak ditulis ulang; yang kurang adalah **ketersediaan** Prime. Catatan: audit lama menyebut "SF2 attached ≠ preset ada di inventory resolver"; itu tetap berlaku (lihat bawah).

## Perubahan (minimal, tanpa mengubah resolver otomatis)
* SF2 ketiga bisa dipasang sebagai **manual-only** (`loadMelodyFallback(path, manualOnly=true)`; JNI `nativeLoadMelodyManualSoundFont`). Resolver otomatis melewatinya (`collect` tidak menyertakan font manual-only), jadi hasil otomatis tidak berubah. Preset-nya tampil di katalog (`nativeGetSoundFontPresets`) sehingga bisa dipilih lewat Voice Selector RIGHT1/2/3 (`setKeyboardPreset` memakai font id + bank + program), pin Style Voice Editor (`pinChannelPreset`), dan Registration (identitas file + bank + program + nama).
* `loadCpvStack`: bila ada `MELODI … PRIME.sf2` di folder SF2, ia dipasang manual-only setelah Tyros (kolam sekunder maks. 2: Tyros + Prime). Gagal memasang bukan kesalahan fatal (tumpukan lama tetap).
* Prime Drum **tidak** dipasang (jalur drum tidak disentuh).
* Biaya: SF2 Prime Melody 343 MB (+ salinan normalisasi BASSMIDI di cache) → waktu muat dan memori naik; belum diukur di Redmi Pad.

## Tahap 1 — Kandidat dari bukti (pdta asli perangkat, bukan nama SF2)
Sumber: `tests/fixtures/sf2_pdta/export.zip` (pdta SHA-256 Melodi `cfb096e3…c136`; SF2 SHA-256 `e8c73561…4a82`). Jumlah preset terverifikasi **93** (melodi) dan **21** (drum); enumerasi BASSMIDI (mock BASS + pdta asli) juga memberi 93.

**Electric Bass / bass listrik (hanya berdasar nama phdr — belum didengar):**

| Nama phdr | Bank | Program 0-based | 1-based | Catatan |
|---|---:|---:|---:|---|
| `elctrick bass` (ejaan asli file) | 0 | 32 | 33 | kandidat utama bass listrik |
| `BASS PSR` | 0 | 33 | 34 | |
| `BASS PSR` | 0 | 34 | 35 | |
| `BASS` | 8 | 17 | 18 | |
| `EXP : BASS FENDER` | 63 | 3 | 4 | bank ekspansi |
| `BASS SERA SM` | 63 | 1 | 2 | bank ekspansi |

**Suling / tiup:** `Suling S770` pada 0:52, 0:72, 0:73 (PC 53/73/74), `Flute reza748` 0:74 (PC75), `Pan Flute` 0:75 (PC76), `Shakuhachi` 0:53 (PC54), `SARUNE` 0:84. Catatan lama di `voice_resolver.h` menyebut sampel Suling S770 di PC52 adalah Flute; ini tetap dugaan sampai didengar.

Status per kandidat: **ada di katalog** = ya (pdta); **terpasang di BASSMIDI** = lulus di tes native (mock BASS), belum di perangkat; **bisa dipilih** lewat Voice Selector dan Style Voice Editor = ya, lewat kode (katalog semua SF2; Editor hanya mengecualikan bank 127/128). Nama bukan bukti bunyi; tidak ada kandidat yang direka.

## Tahap 2 — Validasi alur Editor (Bass → `elctrick bass` Prime)
Diuji di host (tidak ada audio): preset berasal dari Prime (font id, bank 0, program 32; bukan 33, bukan 8:17), identitas SF2 (nama + pdta) tersimpan, pin native benar (`nativePinChannelPreset(10, fontIdPrime, 0, 32)`), tidak ada fallback Piano, APPLY berlaku pada part+section yang dimaksud, MAIN→FILL→MAIN melepas lalu memasang ulang pin, SAVE → baca ulang pada instans baru, status STALE bila pdta berubah atau preset hilang (tidak diganti). Tes: `CpvOverrideIntegrationTest.primeElectricBassReachesTheNativePinWithItsOwnFontBankAndProgram`, `primeBassProfileSurvivesSaveAndReload`.

## Tahap 3 — RIGHT1/2/3
Native: `setKeyboardPreset(0, primeId, 0, 52)` (Suling S770) terikat KEYBOARD ke Prime, tanpa menyentuh pin Style (`cpv_native_test`, bagian 9). Registration: identitas file+bank+program+nama; tes baru `R1b` membuktikan bank/program yang sama di dua SF2 dibedakan berdasar nama file, dan Prime hilang dilaporkan (bukan diganti Colombo).

## Tahap 4 — Rancangan AUTO + MANUAL (belum diimplementasikan)
Lapisan terpisah, masing-masing dengan datanya sendiri:
1. **Voice Style melodi** (kanal 10–15): prioritas = Manual override (profil, sudah ada) → exact compatible preset (bank+program+SF2 teridentifikasi, mis. tabel U01/pdta) → same-family/variasi Yamaha tervalidasi (matriks kandidat 578 request) → semantic compatible → safe fallback eksplisit (`UNKNOWN`/silent, bukan Piano). Kecocokan nomor Program saja **tidak** pernah cukup (audit: 328/578 hanya same-PC).
2. **Voice RIGHT/LEFT**: selalu manual (font id + bank + program); tidak memakai resolver Style.
3. **Drum kit** (Rhythm 1/2): jalur R2-3 terpisah dan tidak bersaing dengan melodi.
4. **Drum note mapping**: lapisan terpisah per Style (lihat Tahap 5).
Syarat sebelum resolver AUTO besar: matriks 578 request × kandidat (alat `tools/voice_candidate_matrix.py`) direview, rekonsiliasi hitungan 250 vs 25 exact, uji regresi Love Song CH10–CH14.

## Tahap 5 — Rencana Drum Dangdut (DESAIN SAJA)
Bukti struktur (bukan bukti bunyi): drum SF2 memuat preset bernama `Kendang Alex` (128:8, 220 zona, kunci 36–90 masing-masing 2 sampel), `KENDANG DHIDI` (128:36, banyak instrumen/kunci), dan instrumen bernama `@Kendang Clowor`, `Kendang Baru 2`, `KENDANG PENG 2`, `Dut Kendang02_1 C_2`, `@ GENDANG-1`; tidak ada nama "Ketipung" di preset/instrumen. Nama sampel hanya angka, jadi **keberadaan sampel kendang belum terbukti** sebelum didengar.
Tahap lanjut (belum diimplementasikan): (a) pilih kit dari semua SF2 termuat; (b) pilih SF2 sumber per Rhythm 1/2; (c) audition nada per kunci (STOP-only, seperti audition melodi); (d) remap nada drum per Style (kunci sumber→kunci tujuan) di Style Profile (schema 3, bagian `drumMap`), diterapkan di lapisan dispatch sempit setelah CASM dan tidak mengubah CASM/ACMP; (e) tes: Intro→Main, Fill, STOP, tanpa nada menggantung.

## Pengujian
JVM CPV 209, LIVE/REG 78 + 86, Registration Recall, native `cpv_native` (93 preset Prime), `u01 native`, `r23 native`. Belum ada uji audio/perangkat; **tidak ada klaim bunyi terverifikasi**.

## Instruksi uji di Redmi Pad
1. Letakkan `MELODI YAMAHA PSR-SX700 & SX900 PRIME.sf2` bersama Colombo dan Tyros di folder SF2; muat ulang SF2 (log harus memuat "CPV manual-only SF2 attached"). Catat waktu muat/memori.
2. RIGHT1 → Voice Selector → pilih `Suling S770` (Prime, 0:52): dengar dan lapor.
3. Muat Style → Mixer → STYLE VOICES → Bass → REPLACE → INTERNAL SF2 → cari `elctrick bass` (Prime 0:32) → THIS SECTION atau ALL SECTIONS → AUDITION (Style stop) → APPLY → SAVE PROFILE → muat ulang Style → Inspector menunjukkan OVERRIDDEN dengan SoundFont Prime. Mainkan MAIN/FILL.
4. Bandingkan dengan `BASS PSR` (0:33) dan `BASS` (8:17).
