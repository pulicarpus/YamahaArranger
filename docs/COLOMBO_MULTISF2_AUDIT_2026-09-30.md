# Audit Colombo BASSMIDI dan Arah Multi-SF2 Resolver — 2026-09-30

Dokumen ini adalah catatan engineering untuk YamahaArranger. Audit ini tidak mengubah source code engine.

## Baseline dan sumber data

Audit didasarkan pada:
- corpus 569 Yamaha SX920 factory styles;
- 7 SF2 yang terinspeksi pada perangkat;
- `YamahaArranger_Inspector_20260930_223418.txt`;
- `YamahaArranger_AllLog_20260930_223325.txt`;
- `docs/SX920_SF2_VOICE_COVERAGE.md`;
- perilaku runtime Build 752.

## Fakta corpus 569 style

- Styles: 569
- Voice setup entries: 7,612
- Unique bank MSB:LSB + PC requests: 578
- Managed SF2 files: 7
- Total inspected presets: 1,624
- Same bank/MSB + PC: 250/578 unique requests
- Same-PC only: 328/578 unique requests
- No matching PC anywhere: 0/578

Kesimpulan: kekurangan utama bukan ketiadaan nomor program, tetapi pemilihan preset yang benar. Same-PC lintas bank tidak aman sebagai resolver umum.

## Inventory SF2

| SF2 | Preset |
|---|---:|
| ColomboGMGS2_BM.sf2 | 892 |
| Timbres Of Heaven GM_GS_XG_SFX V 3.4 Final.sf2 | 339 |
| yamaha tyros 4_just_t4_fixed.sf2 | 142 |
| merlin_GMpro(v3.15).sf2 | 136 |
| MELODI YAMAHA PSR-SX700 & SX900 PRIME.sf2 | 93 |
| DRUMKIT YAMAHA PSR-SX700 & SX900 PRIME.sf2 | 21 |
| Yamaha_PSR-SX700_CP80.sf2 | 1 |
| **Total** | **1,624** |

## Audit ColomboGMGS2_BM

Inspector membaca:
- valid SF2: true
- nama: `ColomboGMGS2 SoundFont (BassMIDI) V17.03`
- ukuran sekitar 273 MB
- samples: 3,288
- presets: 892

Bank GM dasar terstruktur dengan keluarga voice yang luas, termasuk Piano, Organ, Guitar, Bass, Strings, Brass/Horn, Reed/Sax, Woodwind, Synth dan Pad. Contoh yang terverifikasi pada inventory: Spanish Guitar, Steel String Guitar, Jazz Guitar, Clean Guitar, Palm Muted Guitar, Acoustic Bass, Finger Bass, Picked Bass, Fretless Bass, Slap Bass, Pop Bass, dan Synth Bass.

### Keputusan

Colombo layak menjadi **general-purpose fallback pool utama**, tetapi tidak boleh menggantikan Yamaha SF2 sebagai primary dan tidak boleh dipilih hanya karena mempunyai preset lebih banyak.

## Masalah besar: Yamaha MSB 104

Dari corpus:
- MSB 104: 2,342 voice entries
- 282 unique requests
- same-MSB+PC coverage: 0

Karena itu request Yamaha MSB104 tidak boleh diselesaikan dengan blind same-PC lookup. Resolver harus mempertimbangkan role, Yamaha bank family, voice family, variation dan semantic name.

## Drum harus dipisahkan dari melodic resolver

MSB 126/127 adalah rhythm/SFX-related pada corpus. Yamaha drum SF2 direpresentasikan pada SF2 bank 128. Jalur drum tidak boleh berkompetisi dengan melodic presets Colombo/Tyros.

Target:
- Yamaha rhythm 126/127 -> explicit drum-role handling -> Yamaha drum bank 128.
- Colombo drum/XG compatibility dapat diaudit kemudian sebagai fallback drum terpisah, bukan bagian dari melodic candidate pool.

## Temuan Build 752

Build 752 membuktikan perbaikan loading stack:
1. Core MELODY + DRUM established.
2. Secondary melody attached.
3. MELODY + FALLBACK + DRUM SF2 OK.

Namun Inspector/runtime preset scan masih menunjukkan 114 preset, yaitu 93 primary melody + 21 drum. Ini menunjukkan perbedaan penting antara **SF2 attached ke BASSMIDI** dan **preset tersedia di candidate inventory resolver**.

Tyros berhasil attached, tetapi belum otomatis berarti 142 preset Tyros ikut menjadi candidate pool resolver. Hal yang sama harus dicegah saat Colombo dimasukkan.

## Love Song sebagai regression case

Style Love Song pada trace terbaru meminta:
- CH10 Bass
- CH11 Piano
- CH12 A.Guitar
- CH13 Strings1
- CH14 Strings2

Runtime masih memilih sourceRole PRIMARY untuk voice tersebut. Contoh:
- A.Guitar -> Steel Guitar (PRIMARY)
- Strings1 -> String Yamaha (PRIMARY)

Ini menunjukkan multi-SF2 saat ini belum bekerja sebagai global candidate pool.

## Arsitektur resolver yang disepakati

Jangan implementasikan sekadar urutan `SX700 -> Colombo -> Tyros` secara buta. Gunakan candidate pool dan scoring.

Pipeline target:

```text
Yamaha style voice
  -> decode MSB/LSB/PC/name
  -> determine role
     -> RHYTHM: explicit Yamaha drum path
     -> MELODIC:
          identify Yamaha bank family
          identify voice family
          identify variation
          collect candidates from:
             - Yamaha SX700/SX900
             - ColomboGMGS2_BM
             - Tyros 4
             - optional other managed SF2
          reject wrong-role/wrong-family candidates
          score compatible candidates
          select best candidate
          map to BASSMIDI
```

### Prioritas konseptual

1. Exact Yamaha identity / exact compatible bank+program.
2. Exact Yamaha family/variation.
3. Semantic candidate across the global SF2 pool.
4. Colombo GM/GS family fallback.
5. Tyros/Yamaha semantic alternate where it scores better than generic Colombo.
6. Other managed SF2 candidates if explicitly enabled.
7. Safe same-family fallback only as last resort.

Piano **tidak boleh** menjadi universal fallback untuk non-Piano.

### Scoring awal untuk desain (belum final)

- exact Yamaha identity: sangat tinggi
- correct role: mandatory
- exact family: high
- exact variation: high
- semantic name similarity: medium/high
- same PC: low supporting signal only
- wrong family: reject/large penalty
- drum/melodic mismatch: reject

Angka score final harus ditentukan setelah candidate matrix 578 unique requests dibuat dan direview; jangan hard-code score hanya berdasarkan contoh Love Song.

## Aturan source priority

Colombo adalah **fallback pool utama**, bukan pemenang otomatis. Contoh: bila Tyros mempunyai Yamaha-specific `Live Acoustic Guitar` yang secara family/variation lebih cocok daripada generic Colombo Steel Guitar, Tyros harus dapat menang melalui scoring.

Jadi prinsipnya:

`Yamaha exact first; lalu bandingkan kandidat compatible Colombo/Tyros berdasarkan family + variation + semantic quality.`

## Pekerjaan engineering berikutnya

Sebelum membuat perubahan besar pada Voice Resolver:

1. Pastikan preset inventory per SF2 tersedia bagi resolver, bukan hanya SF2 handle attached ke BASSMIDI.
2. Masukkan seluruh 892 preset Colombo ke candidate inventory tanpa mengubah jalur drum yang sudah stabil.
3. Masukkan inventory Tyros ke candidate inventory dengan source identity yang jelas.
4. Buat candidate matrix untuk 578 unique Yamaha requests dari corpus 569 style.
5. Klasifikasikan setiap request berdasarkan role -> Yamaha bank family -> voice family -> variation.
6. Tandai candidate exact, semantic-compatible, family-compatible, unsafe same-PC, dan reject.
7. Implementasikan global multi-SF2 scoring setelah matrix direview.
8. Tambahkan log keputusan yang menyebut request, source SF2, role, family, variation, score dan reason.
9. Regression test Love Song CH10-CH14.
10. Audit ulang seluruh 569 style setelah resolver baru stabil.

## Bukti minimum untuk build berikutnya

Build tidak boleh dianggap berhasil hanya karena APK hijau. Log harus membuktikan:

- Colombo benar-benar attached bila dipakai.
- candidate inventory melaporkan preset Colombo/Tyros, bukan hanya 114 primary+drum presets.
- source identity dapat dibedakan: PRIMARY_YAMAHA / COLOMBO / TYROS / DRUM.
- minimal satu request yang tidak cocok dengan primary dapat memilih Colombo atau Tyros karena score yang lebih baik.
- CH10-CH14 tetap mengirim NOTE_ON dan final preset state tidak tertimpa setelah resolver.
- drum tetap memakai jalur drum dan tidak ikut melodic candidate competition.

## Larangan regresi

Untuk pekerjaan Multi-SF2 ini jangan mengubah tanpa bukti:
- CASM
- scheduler/master clock
- transition/fill
- note ownership
- sustain
- MIDI E343 routing
- drum path yang sudah bekerja

Fokus perubahan harus berada pada: SF2 inventory -> candidate pool -> Voice Resolver -> BASSMIDI font mapping/preload.

## Checkpoint keputusan

- Build 752 adalah baseline runtime untuk keberhasilan attach secondary SF2.
- ColomboGMGS2_BM dipilih sebagai kandidat general-purpose fallback utama berdasarkan inventory 892 preset dan coverage family yang luas.
- Tyros tetap dipertahankan sebagai Yamaha-specific alternate candidate.
- Same-PC lintas bank tidak boleh menjadi keputusan utama.
- MSB104 adalah prioritas terbesar untuk semantic/family mapping.
- Implementasi berikutnya harus membuat global candidate pool yang benar sebelum tuning suara per-style.
