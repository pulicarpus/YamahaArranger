# Validasi ulang routing offline — 2026-10-12

**NO-GO untuk SF2 master. Suite routing host lulus, tetapi suite agregat/historical guards belum seluruhnya lulus. Inventaris asli lengkap dan golden runtime belum tersedia.** GO terbatas untuk pengumpulan input/capture diagnostik read-only, bukan ekspor atau perubahan aplikasi.

Parent branch `plan/sf2-single-master`: `822cbda8f9b3b90aa7cf12b919e549767a3732f7`. Golden style source tetap `c75122daf5d1077db55d50d1c261144fde59af4a`. Alat, fixtures dan laporan audit sebelumnya dibaca/digunakan; keempat dokumen gate dibaca kembali. Tree branch tidak mengandung AGENTS.md. Git clone gagal karena proxy tidak terhubung; konektor GitHub dipakai untuk read snapshot dan commit diagnostik. Snapshot text terpisah diverifikasi melalui Git blob; seluruh `app/src/main` pada planning parent identik dengan checkpoint (termasuk empat file yang gagal guard historis). Tidak ada perubahan produksi/style/loader/workflow, APK, SF2 master, main/READY atau Voice & RELEASE.

## PASS / FAIL / UNVERIFIED

| Lingkup | Status | Hasil yang benar-benar diamati |
|---|---|---|
| Suite diagnostik lama sebelum perubahan | PASS | `test_sf2_golden_routing.py`:12 tests lulus ulang |
| Suite diagnostik setelah perbaikan tool | PASS |18 tests; sebagian besar synthetic regression, ditambah pemeriksaan membership/provenance parsial. Bukan validasi1,148 preset |
| Suite host checkpoint | PASS | `test_sf2_checkpoint_host.py`:11 targets/761 checks;84 fingerprint source dipin sebelum compile; BASS mocks/synthetic SF2 temporary |
| Native portion pada aggregate lama | PASS | Seluruh15 target native `test_voice_resolver.py` selesai sebelum guard pertama; tidak ada original PCM/device assertion |
| Aggregate lama, keseluruhan | **FAIL** | Exit1 pada `test_drum_shadow_guards.py`, `Unreviewed F12 file: StyleSequencer.kt`; tail setelahnya tidak otomatis dijalankan |
| Historical structural guards, dijalankan terpisah | **FAIL** | Drum shadow, mix percussion, production regression bypass, role PCM, diagnostic788; detail di bawah |
| F04 source guard | **FAIL + UNVERIFIED** | Satu assertion FAIL: F12 tidak mengenali StyleSequencer; pemeriksaan380 baseline files juga error karena snapshot historis tidak lengkap. Tiga negative cases pass; suite keseluruhan tidak PASS |
| S2/S3 guards dan native semantic self-test | PASS | `sff_dialect_source_guard.py`,4 dialect unit tests, `sff_casm_source_guard.py`,4 CASM unit tests, native S3 self-test |
| Metadata asli parsial | PASS terbatas |2 dedicated tests memverifikasi8 zone records,2 preset identities,2 source hashes, exact artifact SHA256/Git blob, raw/display PC dan key/velocity ranges |
| Inventaris lengkap1,148 / seluruh158 konflik asli | **UNVERIFIED** | CSV/manifest lengkap tidak ditemukan;0 full inventory inputs. Dua preset parsial bukan pengganti inventaris lengkap |
| Historical root-selection/F03 ownership/P0/F12/SFF1 harness | **UNVERIFIED** | Dicoba; resource/history snapshot kurang lengkap. SFF1 harness3/7 cases pass,4 errors; F03 ownership7 errors. Tidak dianggap PASS |
| Generated SDK/build-input tests | **UNVERIFIED** | Dicoba; setup error karena vendor BASS SDK/header/.so tidak tersedia;0 test cases berjalan |
| Real BASSMIDI, JVM pipeline, Android dan PCM | **UNVERIFIED** | SDK/native libraries, Kotlin compiler/test jars, full historical corpus/checkout dan perangkat tidak tersedia; tidak dibuat/didownload APK atau SDK substitute |
| Golden routing LoveSong | **UNVERIFIED** | Template CSV masih header saja:0 runtime decision rows |

Semua suite SF2 yang disiapkan sebelumnya dijalankan ulang. Aggregate lama juga dicoba secara utuh; setelah berhenti pada guard, sibling guards yang dapat dibaca dijalankan terpisah agar kegagalan pertama tidak menyembunyikan status berikutnya. Ini tidak boleh diringkas sebagai “seluruh tes repository PASS”: suite historis/JVM/real-SDK mempunyai prasyarat yang belum tersedia. `evaluate_percussion_inventory.py` tidak dapat direplay karena membutuhkan binary metadata ZIP dan full drum event replay; status UNVERIFIED, bukan original-inventory PASS.

Raw stdout/stderr tersedia pada `tests/fixtures/sf2_offline_revalidation_test_output.txt`; hasil terstruktur dan hash log pada `tests/fixtures/sf2_offline_revalidation_results.json`. Log percobaan yang error dipertahankan. Tidak ada assertion dihapus, guard dilonggarkan, baseline hash ditulis ulang, atau file produksi diperbaiki untuk membuat status menjadi PASS.

## Temuan baru: guard lama tidak mencakup checkpoint ini

| Guard | Penolakan aktual |
|---|---|
| drum shadow / mix percussion | `Unreviewed F12 file: app/src/main/java/com/yourapp/yamahaarranger/arranger/StyleSequencer.kt` |
| production regression bypass | `Protected #776 mismatch: app/src/main/java/com/yourapp/ui/ContentResolverProvider.kt` |
| role PCM | protected hash `app/src/main/java/com/yourapp/arranger/ArrangerBrain.kt` |
| diagnostic788 | protected hash `app/src/main/java/com/yourapp/ui/MainViewModel.kt` |

StyleSequencer checkpoint SHA256 adalah `c768285a3b4a303c270f1d1c68e04d559bb2951d28a249d3c19455715a97cf89`. F12 profile yang tersimpan mengenali before `09da84a3db83dddca662afc472cf74b7ead472bfaa59aa2d27e51f217cfb352e` atau after `ed80d7c5983ce23ab684601e25ec59b12d9c9e8b307b2abad13051b5ed02250a`; actual checkpoint tidak sama dengan keduanya. File bukan berubah oleh pekerjaan ini: Git blobs seluruh production tree planning parent cocok checkpoint. FAIL ini menunjukkan ketidakcocokan historical guard/profile dengan reference yang diminta; tidak membuktikan regresi audibel. Rekonsiliasi guard memerlukan review diagnostik tersendiri; pekerjaan ini tidak mengubah pin lama atau checkpoint.

## Data yang ditemukan dan yang belum tersedia

Pencarian mencakup tree branch dan file workspace/library/shared yang tersedia. Gate lama menyebut inventory laptop `D:\SF2\SF2_INVENTORY_REPORT\SF2_PRESET_INVENTORY.csv` dan provisional provenance1,148 rows sebagai conversation artifact, bukan checked-in file. Tidak ada file itu pada sesi ini. CSV repo yang ditemukan adalah histogram/demand drum, overlap event F03, root-word records SFF, dan capture template; semuanya berbeda dari inventaris preset.

Repository mempunyai `app/src/test/resources/global_drum_metadata.zip` (2,687,496 bytes), tetapi content binary belum dapat dibaca di sesi ini: `fetch_file(...,base64)` memberi content kosong; `fetch_blob` menolak dengan UnicodeDecodeError. Keberadaan archive bukan bukti bahwa jumlah/isi presetnya sudah tervalidasi. Dibutuhkan archive yang dapat dibaca atau export P/phdr ke CSV; tidak diasumsikan isi ZIP sama dengan inventaris1,148.

Header fixture `app/src/test/resources/drum_style_native_replay.tsv` juga dibaca: PPQ1920, IntroA length7680, source channel8 dengan CC0=127/CC32=0/PC73 dan note54 velocity110. Nama part/style pada header mengandung control/NUL characters dan tidak memberikan source-file SHA256; data itu tidak dipromosikan menjadi golden LoveSong atau actual resolver outcome. Full replay tetap belum tersedia di text snapshot untuk inventory evaluator, dan snippet tidak menggantikan capture live/per-event.

**Bukti asli parsial yang benar-benar dapat dibaca:** `app/src/test/resources/drum_audit770_zone_rows.tsv`, dipin pada planning parent, Git blob `ee39b493c0b45dc0bda6c03608eac779ce9983b9`. Delapan baris berlabel `classification=UNKNOWN` memuat:

| Sumber / bank / raw PC | Nama preset | Zona/key yang tersedia |
|---|---|---|
| Colombo /128 /2 | Standard SC55 |3 records: keys40,42,44 |
| SX Melody /126 /35 | EXP : ARABIC PA900 |5 records: keys42,44,91; beberapa hubungan/sample berulang |

Semua8 records mempunyai raw velocity0..127 dan singleton key range; displayPC=rawPC+1. Ini metadata export dari sumber asli, bukan synthetic presets, tetapi original SF2 tidak direhash kembali di sesi ini. Tidak ada records SX Drum/Tyros dalam subset ini. Eligible metadata tidak membuktikan actual donor, BASS readback, pitch/rendering atau Yamaha compatibility. Classification UNKNOWN dipertahankan.

`tests/fixtures/sf2_partial_original_inventory.csv` berisi tepat2 distinct preset tuples yang diekstrak dari records tersebut, bukan row yang direka. Provenance/limits tercatat dalam `sf2_partial_original_inventory_provenance.json`; dedicated tests membandingkan output dengan original artifact. Tidak ada alamat master dialokasikan.

## Validasi konflik dan resolver menurut scope

| Dataset | Rows / alamat unik / collision groups | Keputusan |
|---|---|---|
| Synthetic pool yang sudah ada |9 /8 /1 | Seluruh collision **yang ada dalam pool itu** ditemukan:0:0 Melody vs Colombo;9 requests direplay dengan pure `voice_resolver.h` checkpoint; trace86 rows byte-identical pada2 replay |
| Original-derived partial export |2 /2 /0 yang teramati | Struktur/provenance dapat diverifikasi; **tidak** berarti0 konflik global. Full conflicts_complete=false, status UNVERIFIED |
| Original full inventory |Tidak tersedia | Angka1,148 /911 /158 /237 berasal dari gate sebelumnya; tidak dihitung ulang atau ditampilkan sebagai measured result sesi ini |

Hasil lengkap subset terdapat pada `sf2_offline_synthetic_inventory_result.json`, `sf2_offline_partial_inventory_result.json` dan `sf2_offline_missing_inventory_result.json`. Synthetic identities menggunakan hashes buatan dari filename untuk test isolation; bukan hashes binary SF2 asli. `sf2_synthetic_policy_inventory.csv` diberi nama scope yang eksplisit. Pure decision trace pada `sf2_offline_synthetic_resolver_decisions.tsv` tetap **SYNTHETIC_POLICY_ONLY**.

Policy yang tersedia untuk diuji mencakup role/family gate sebelum numeric score; consistent Yamaha decoding; MSB104 exception; exact/MSB/name/primary priority dan stable tie; raw126 vs MIDI MSB126/127; rejection raw127/128 pada melody; collision0:0; no-compatible-family suppression. Existing production-player tests juga memeriksa PC73 absent/present, phdr-order drum fallback,127-before128 generic FONTEX2 conflict, per-key donor/velocity safety, note ownership/choke/generation, gain profiles-6/-6/+12 dan kehilangan profile saat hash berubah.

Itu **bukan** keputusan aktual untuk seluruh konflik asli. Resolver membutuhkan actual request MIDI MSB/LSB/PC, semantic name/CASM context, destination role dan active-source order; bank/PC collision list saja tidak menentukan winner. Pada metadata asli parsial, source-bank126:35 tetap memerlukan semantic/SFX review, dan drum-bank128:2 memerlukan actual kit/donor context. Tidak menebak PC73 ada/tidak pada empat binary asli dari fixture sintetis.

Bank virtual raw126 dari subset dua rows tidak dapat ditentukan: original bank-set sumber belum lengkap. Tool sekarang mengeluarkan `native_virtual_bank=UNKNOWN` untuk ORIGINAL partial inventory; `observed_subset_virtual_bank` hanya urutan hipotetis subset, bukan runtime mapping. Ini mencegah bank126 diproyeksikan keliru menjadi virtual0 sebagai “bukti asli”.

## Perubahan alat diagnostik

`tools/sf2_golden_routing.py` tetap read-only dan tidak menulis SF2/audio/engine:

- Tambah `inventory [csv] --scope ORIGINAL|SYNTHETIC`: enumerasi seluruh observed cross-source bank/PC conflicts, reject duplicate provenance/bad bank-PC/wrong hash-filename, counts dan explicit completeness. Tidak ada input → UNVERIFIED/exit2.
- Full ORIGINAL mengharuskan4 filename/hash yang cocok source gate dan counts892/21/93/142. Source count berlebih ditolak. Bila lengkap, totals911 unique addresses/158 groups/237 extras diperiksa terhadap gate. Ini validasi CSV/hash **declarations**, bukan rehash binary atau auditory certification.
- Partial ORIGINAL tidak boleh PASS full-inventory check dan tidak boleh mengklaim actual native virtual bank.
- Comparator mengharuskan metadata `evidence_scope`. SYNTHETIC equality diberi label SYNTHETIC. ORIGINAL membutuhkan hash-set empat sumber yang tepat, `--inventory` lengkap dan source/donor tuples yang ada di inventory. Scope/source-set berbeda ditolak.
- PCM tetap UNVERIFIED; semua outputs mempertahankan master_export_go=false. Validated-runtime manifest markers tetap memerlukan bukti eksternal; tool tidak menerbitkan approval.

CSV minimum: `source_filename,source_sha256,original_bank,original_program,original_name`; kolom ekstra boleh dipertahankan. Bila CSV user mempunyai header lain, inspeksi dan konversi kolom secara eksplisit—jangan membuat rows untuk melengkapi jumlah. Original bank/PC adalah namespace SF2, bukan MIDI destination/staging. Simpan phdr ordinal terpisah untuk fallback order.

```sh
python3 tools/test_sf2_golden_routing.py
python3 tools/test_sf2_partial_original_metadata.py
python3 tools/test_sf2_checkpoint_host.py
python3 tools/test_voice_resolver.py
python3 tools/sf2_golden_routing.py inventory
python3 tools/sf2_golden_routing.py inventory tests/fixtures/sf2_partial_original_inventory.csv
python3 tools/sf2_golden_routing.py inventory tests/fixtures/sf2_synthetic_policy_inventory.csv --scope SYNTHETIC
python3 tools/sf2_golden_routing.py inventory /path/to/original-inventory.csv
python3 tools/sf2_golden_routing.py compare reference.csv proposed.csv manifest.json reference-meta.json proposed-meta.json --inventory /path/to/original-inventory.csv
```

Expected CLI inventory exits: missing2/UNVERIFIED; partial2/UNVERIFIED; synthetic0/PASS dengan full_original_inventory_validated=false. Malformed inventory statusFAIL/exit2. Comparator tetap exit0 equality field dalam scope yang dinyatakan,1 mismatch,2 invalid/incomplete; bukan GO master. Metadata capture template kini memakai evidence_scope=ORIGINAL dan inventory pathUNKNOWN.

## Input minimum berikutnya untuk golden LoveSong

1. **Inventory asli lengkap1,148 rows** beserta4 original SHA256/filenames, raw bank/PC/name dan laporan source verification. Sertakan preset/phdr ordinal, khususnya drum127/128, agar first-entry fallback tidak ditebak dari CSV yang diurutkan. Original binaries atau full metadata export yang tidak terpotong diperlukan untuk zone/layer/generator/modulator/sample/stereo/loop/donor evidence.
2. **File LoveSong aktual** (nama/path + bytes atau parser export yang lengkap dan dapat dipin), SHA256, section list/availability/Main A–D/Fill A–D/Intro/Ending, meter/ticks/setup CC0/32+PC dan CASM policies/destinations. Tidak menganggap seluruh section ada. Sertakan silence/absent part secara eksplisit dengan alasan. Meter fixtures lain hanya dengan actual file/hash.
3. **Reference capture pada checkpoint frozen**, build identity Android/BASSMIDI dan flags,4 loaded-source identities/role/import order/load/preload outcomes, normalized bank tables, native handle/source-generation mapping. Setiap event perlu stable ordinal/tick, section/chord/ACMP, source/destination part-channel, request MSB/LSB/PC/name, selected original provenance, fallback reason, expected dan actual preset readback. STOP snapshot saja tidak cukup.
4. **Drum/ownership detail:** PC73 raw0-based (=display74), actual127/128 collision resolution; key/velocity/donor provenance dan played-key/auxiliary lane/native bank/PC; first-note readback, repeated-note OFF owner, generation/choke/transition trace; controller/envelope/pitchbend history. Kurangnya donor evidence tetap UNKNOWN/legacy-abstain, bukan0dB atau kit default yang direka.
5. **Stopped-style keyboard isolation** RIGHT1/2/3/LEFT before/after selection, actual readback/NOTE owner dan enable/ACMP states; LEFT actual voice memerlukan ACMP off. Jangan memakai Voice & RELEASE experiment sebagai golden.
6. **PCM reference dan measured balance** diperlukan untuk audible gate berikutnya: original4-source render LoveSong sections, source-keyed trim evidence, raw trace/PCM hashes dan device listening. Offline CSV equality tidak menggantikan PCM. Master allocation/preservation/BASS limits/import time/RAM-PSS tetap gate sesudah reference, belum dilakukan.

Prioritas input sekarang: `SF2_PRESET_INVENTORY.csv` lengkap + source-verification report, LoveSong aktual, lalu event captures checkpoint. Dengan data sesi saat ini, full original resolver certification tetap UNVERIFIED dan master tetap **NO-GO**, ditambah unresolved FAIL historical guards. Tidak ada data runtime atau rows missing yang dibuat agar gate terlihat lulus.
