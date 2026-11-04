# AUTO PSR-E343 VOICE MAPPING + AUTO BALANCE — laporan implementasi

- **Branch:** `feature/midi-song-v1` (tidak di-merge ke `main`).
- **Baseline:** Recorder V1C.1 (normal di Redmi Pad) dan audit `diagnostics/LOVE_SONG_BALANCE_E343_AUDIT.md`.
- **Lingkup:** **MIDI OUT saja**, channel Style 9–16 (0-based 8..15). Tidak diubah: INTERNAL BASSMIDI, resolver SF2, data Style, CASM/NTT, Style Sequencer, router `MidiMixerOutput`, RIGHT, SHORT_GATE, Song Player, Recorder.
- **Default: ORIGINAL.** Selama mode tidak diganti, byte yang dikirim sama persis dengan sebelumnya (adapter mengembalikan input yang sama).
- **Status perangkat:** hanya dites di host/CI. **Belum diuji di Redmi Pad + PSR-E343.** Auto Balance adalah estimasi, belum terbukti dengan pendengaran.

## 1. Audit sebelum implementasi

| Item | Temuan |
|---|---|
| `docs/yamaha/E343_VOICES.tsv` | 523 baris yang bisa dipilih lewat MIDI (550 nomor dikurangi 27 DUAL tanpa Bank/Program). Kolom `program_1_128` **1-based** seperti di manual; aplikasi menyimpan **0-based** (byte Program Change = tampilan − 1). Diverifikasi: Grand Piano 0/112/1 → PC byte 0, Finger Bass 0/112/34 → PC byte 33 |
| Bank | Voice panel umumnya MSB 0 / LSB 112–127 dengan nomor program keluarga GM; XGlite di MSB 0 / LSB 0–… |
| Kit | 18 kit: MSB 127 (Standard 1/2, Room, Rock, Electronic, Analog, Dance, Jazz, Brush, Symphony) dan MSB 126 (SFX, India, Arab, Kuba, …). `E343_DRUMKITS.tsv` memuat tabel nada per kit (nada yang tidak tercantum = diam). Kit "Cuban" hanya "sama dengan Standard Kit 1" lewat catatan kaki → ditandai *unverified* |
| Bundling | Kedua TSV disalin **tanpa perubahan** ke `app/src/main/assets/e343/` (guard memeriksa byte identik dengan `docs/yamaha`) |
| Titik sisip | `MidiInputManager.sendProgramChange` dan `setMixerControllers`: semua Bank/Program dan CC mixer Style menuju router lewat dua fungsi ini. Disisipkan di sini supaya Sequencer dan router tidak disentuh |

## 2. Arsitektur

```
StyleSequencer ──Bank/PC──► MidiInputManager.sendProgramChange ─► e343Out.voice()       ─► MidiMixerOutput ─► USB
AudioEngineManager ─CC7..─► MidiInputManager.setMixerControllers ─► e343Out.controllers() ─► MidiMixerOutput ─► USB
```
Paket baru `yamahaarranger/e343out/`:
- `E343Catalog`: parser daftar voice dan tabel nada kit.
- `E343VoiceMapper`: keputusan voice per permintaan.
- `E343StylePlan` (`E343StylePlanner`, `E343BalancePlanner`): rencana per Style, dihitung sekali saat Style dimuat.
- `E343OutAdapter`: penerapan saat berjalan.
- `E343OutProfileStore`: trim manual per Style, disimpan dalam file `<sha>.e343out` di sebelah profil Style Voice Editor. Format profil yang sudah ada tidak diubah.

**Prioritas voice:** voice manual EXTERNAL MIDI OUT di profil Style > AUTO E343 > voice asli bila sudah ada di daftar E343.
**Prioritas volume:** trim manual MIDI OUT tersimpan > slider Mixer yang digeser pengguna (bagian itu tidak diberi trim otomatis) > trim AUTO.

## 3. Aturan mapping voice

1. Permintaan sudah ada di daftar E343 → **ORIGINAL (E343 list)**, dikirim tanpa perubahan.
2. Keluarga ditentukan dari:
   - **nama voice CASM**: kata kunci berurutan, misalnya EP sebelum Piano, Reed sebelum Bass ("Bassoon"), Guitar dan Strings sebelum Bass;
   - kalau nama tidak membantu: **nomor program GM**, tetapi **tidak untuk MSB 8 (Mega Voice)** karena nomor programnya bukan keluarga GM;
   - kalau masih tidak ada: **peran part**, hanya untuk Bass → **ROLE FALLBACK**.
3. Di dalam keluarga, karakter dipilih dari:
   - kata kunci nama (fretless, slap, pick, nylon, jazz, mute, dist, slow, pizz, …);
   - lalu program GM yang sama di daftar E343 (voice panel didahulukan);
   - lalu kata nama yang sama (kata yang hanya menyebut keluarga diabaikan);
   - lalu default keluarga: Finger Bass, Grand Piano, Folk Guitar, String Ensemble, Warm Pad, …
4. Tidak ada keluarga → **NO SAFE CANDIDATE**: permintaan asli dikirim tanpa perubahan dan dilaporkan. Aplikasi tidak memilih preset sembarangan.
5. **Drum:** kit dipilih dari kata kunci nama (brush, jazz, rock, …), lalu dari program kit XG yang sama, lalu Standard Kit 1. Setelah itu **audit nada**: setiap nada drum yang dimainkan Style dicocokkan dengan tabel kit. Bila < 90 % NOTE ON berbunyi di kit itu → NO SAFE CANDIDATE (kit asli dikirim). Nada yang diam di kit pengganti dilaporkan.
6. **Mega Voice (MSB 8):** histogram velocity dihitung dan dilaporkan:
   - note di zona 106–127 (artikulasi/noise Mega) akan berbunyi sebagai note biasa di E343, jadi mengganti Bank/Program saja tidak cukup;
   - velocity median yang rendah berarti part ini lebih pelan di E343.

   **Velocity tidak diubah.**

Semua target nama dicek ada di daftar resmi (`catalogAuditAndEveryTargetExists`).

## 4. Auto Balance

- Dihitung sekali per Style dari data Style, **section Main**:
  - median velocity NOTE ON;
  - CC7 dan CC11 setup part (median berbobot jumlah note).
- Estimasi level per part: 40·log10(CC7/127) + 40·log10(CC11/127) + 40·log10(vel/127). Ini hukum gain yang direkomendasikan GM2; kurva E343 sendiri tidak dipublikasikan.
- Referensi level adalah **Bass**. Batas atas (*ceiling*) per peran, relatif terhadap Bass: Chord −2,5 dB, Pad/Phrase −17,5 dB. Part sustain diberi batas lebih rendah.
- **Hanya menurunkan, tidak pernah menaikkan.** Rhythm dan Bass tidak pernah di-trim. Potongan maksimum 16 dB.
- **Satu trim konstan per part untuk seluruh Style**, sehingga pergantian section/akor tidak memindahkan level dengan sendirinya. CC7 dan CC11 milik Style (termasuk fade) tetap lewat, hanya diskalakan. **Hanya CC7 yang diskalakan**; CC10/11/91/93 dan semua note lewat apa adanya.
- **Kalibrasi:** batas atas di-fit ke **satu fixture**, yaitu balance hasil telinga Bos untuk Love Song di E343. Ini titik awal, bukan pengukuran; Style lain harus dicek dengan telinga.

## 5. Hasil Love Song.T547.prs (SFF2), host

| Part | Diminta Style | Status | Dikirim ke E343 | Alasan | Trim AUTO | Telinga Bos | Selisih |
|---|---|---|---|---|---|---|---|
| Rhythm 1 | 127/0/74 'Perc.' | AUTO | Standard Kit 1 (127/0/1) | bukan kit E343; 7 nada / 515 NOTE ON, 100 % berbunyi | 127 | 127 | 0 |
| Rhythm 2 | 127/0/74 'Drums' | AUTO | Standard Kit 1 | 24 nada / 539 NOTE ON, 100 % berbunyi | 127 | 127 | 0 |
| Bass | 8/4/18 'Bass' | AUTO | **Finger Bass (0/112/34)** | nama 'Bass' → keluarga Bass → default | 127 | 127 | 0 |
| Chord 1 | 104/21/1 'Piano' | AUTO | **Grand Piano (0/112/1)** | program GM 1 yang sama | **62** (−12,5 dB) | 63 | −0,3 dB |
| Chord 2 | 8/16/2 dan 8/10/2 'A.Guitar' | AUTO | **Folk Guitar (0/112/26)** | karakter 'A.G' → Folk; ⚠ Mega, velocity median 40 | 127 | 127 | 0 |
| Pad | 8/5/50 'Strings1' | AUTO | **String Ensemble (0/112/49)** | default Strings; ⚠ 12/316 note di zona Mega 106–127 | **90** (−6,0 dB) | 99 | −1,7 dB |
| Phrase 1 | 8/5/50 'Strings2' | AUTO | String Ensemble | default Strings | **113** (−2,0 dB) | 97 | +2,7 dB |
| Phrase 2 | — | — | — | tidak ada note di Main | 127 | 97 | (tanpa data) |

Level terestimasi (Main):

| Part | dB |
|---|---|
| Rhythm 1 | −34,9 |
| Rhythm 2 | −26,4 |
| Bass | −28,8 |
| Chord 1 | −18,9 (+9,9 dB di atas Bass) |
| Chord 2 | −33,1 |
| Pad | −40,3 |
| Phrase 1 | −44,3 (hanya 4 note) |

Kit pengganti — nama bunyi di Standard Kit 1:
- **Rhythm 1:** 54 Tambourine, 69 Cabasa, 75 Claves, 80/81 Triangle, 82 Shaker, 84 Bell Tree.
- **Rhythm 2:** 13 Surdo Mute, 15 Hi Q, 16 Whip Slap, 17/18 Scratch, 19 Finger Snap, 21/22 Metronome, 31 Snare H Soft, 33 Bass Drum Soft, 37 Side Stick, 41–50 Tom, 49/57 Crash, 51/59 Ride, 52 Chinese, 53 Ride Cup, 55 Splash.

Nada rendah 13–22 berbunyi di E343, tetapi apakah bunyinya sesuai maksud kit Yamaha asli **harus didengar**.

Style lain (CI): HardRock2 → Finger Bass, Distortion Guitar, Rock Organ, Clean Guitar; EnglishWaltz2 → Finger Bass/Folk Guitar tetap ORIGINAL, Pno → Grand Piano, Str → Chamber Strings, FrHn → French Horn.

## 6. Tes

| Tes | Isi | Hasil |
|---|---|---|
| `E343AutoMapRtTest` (rantai produksi + router + port USB palsu) | katalog dan penomoran; semua target ada di daftar; aturan mapping (Mega tanpa nama → NO SAFE; role fallback; kit + audit nada; zona Mega); balance tidak pernah menaikkan; Love Song tur **Intro A → Main A → Main B → Fill → Main C → Main D → Ending A** dengan akor **C → F → G** tiap section: setiap Bank/PC yang ditulis di ch9–16 ada di daftar E343, tidak ada not gantung, CC7 terkirim = CC7 Style × trim, Piano CC7 81 → 40; ORIGINAL = permintaan Style tanpa perubahan (Bass PC 17, CC7 52/81); slider Mixer manual menonaktifkan trim auto di part itu; trim manual tersimpan menang; ganti mode saat main → voice/CC7 langsung dikirim ulang, dan kembali ke ORIGINAL memulihkan permintaan asli | lihat §8 |
| Fixture balance | trim AUTO vs posisi slider Bos, toleransi ±3 dB | Chord1 −0,3, Pad −1,7, Phrase1 +2,7 dB |
| Regresi | lihat §8 | |

Catatan: Love Song bukan fixture repo (file milik Bos), jadi tes Love Song dijalankan lokal. CI menjalankan tes yang sama pada HardRock2, EnglishWaltz2 dan ROSITA (bagian khusus Love Song di-*skip* otomatis).

## 7. Batasan (perlu pengujian fisik E343)
1. Kualitas musikal pengganti (Folk Guitar untuk Mega A.Guitar, String Ensemble untuk Strings1) belum didengar.
2. Kurva velocity E343 tidak dipublikasikan. Part Mega dengan velocity rendah (Guitar median 40) bisa terlalu pelan di E343; Auto Balance **tidak menaikkannya** (tidak memaksa 127, velocity tidak diubah). Koreksi manual: tombol trim per part, atau turunkan part lain.
3. Kalibrasi berasal dari satu Style. Batas atas Pad/Phrase (−17,5 dB) cukup jauh dan bisa terlalu kuat untuk Style lain.
4. Note di zona artikulasi Mega hanya dilaporkan, tidak difilter.
5. Mengganti mode saat main menghasilkan satu lompatan CC7 (disengaja; pergantian section tidak menghasilkan lompatan baru).
6. Pemilihan mode ada di panel **E343 AUTO** (Style Voice Editor). Bar MIDI OUT utama tidak diubah.

## 8. Hasil regresi
(diisi dari log; lihat chat)

## 9. Langkah tes Redmi Pad + PSR-E343
1. **Siapkan:** muat Love Song, MIDI OUT ON. Kembalikan **voice EXTERNAL manual** dan **slider Mixer** ke kondisi awal (RESET ALL di Style Voice Editor; slider 127). Kalau tidak, pengaturan manual yang lama tetap menang.
2. **Mode ORIGINAL:** STYLE VOICES → **E343 AUTO** → ORIGINAL. Pastikan perilakunya sama seperti sebelumnya (hanya Drum + Piano terdengar).
3. **AUTO E343:** pilih AUTO E343, START, akor C → F → G. Bass, Guitar, Strings harus terdengar. Tabel panel menunjukkan voice asli → pengganti dan alasannya.
4. **AUTO E343 + BALANCE:** pilih mode ini. Piano harus turun (CC7 dikirim 40). Bandingkan dengan balance manual Bos.
5. **Pergantian section dan voice:** Intro, Main A/B/C/D, Fill, Ending. Pastikan tidak ada lompatan volume dan tidak ada not gantung.
6. **Koreksi manual:** tombol −10/−3/+3/+10 per part, lalu **SAVE MANUAL TRIMS TO STYLE**. Muat ulang Style dan pastikan trim kembali.
7. **WRITE REPORT TO LOG** → kirim log. Catat part yang bunyinya tidak cocok, terutama drum nada 13–22 dan Guitar.
8. **Regresi:** Recorder FULL ARRANGER V1C.1 (rekam → LOAD TAKE), Song Player, SHORT_GATE, RIGHT.
