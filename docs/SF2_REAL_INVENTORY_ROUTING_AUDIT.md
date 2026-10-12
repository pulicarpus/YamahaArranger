# Audit inventaris asli dan simulasi resolver — 2026-10-12

**PASS metadata dan konsistensi kedua CSV; PASS eksekusi simulasi kebijakan; FAIL pada guard historis yang sudah tidak cocok dengan checkpoint; runtime UNVERIFIED. NO-GO untuk ekspor master SF2.**

Branch `plan/sf2-single-master`, parent `7182d6a999010d3625f44f1fcfd7213ae6158998`; policy dan source style dipin pada `c75122daf5d1077db55d50d1c261144fde59af4a`. Tidak ada perubahan kode produksi, loader, style, workflow, main, READY, Voice & RELEASE, APK atau master SF2. Seluruh126 production blobs `app/src/main` planning parent cocok checkpoint. Perubahan hanya tools/fixtures/dokumen diagnostik. Input metadata asli disalin byte-identical; normalized CSV disimpan terpisah.

## Integritas input: yang terbukti dari metadata

| Input | Baris data | SHA256 bytes upload |
|---|---:|---|
| SF2_ORIGINAL_PRESET_METADATA.csv |1,148 | `4caa6a18582deb5ec4ddf867c3be518029af0a0e87ce079e764da594d7cac495` |
| SF2_REAL_COLLISION_GROUPS.csv |395 anggota /158 kelompok | `f4d16e764e36fbadc6acfefb7ee65bdf9df72330ff0daacca55d119d60612bde` |

Schema9 metadata columns dan10 collision columns diverifikasi persis, tanpa duplicate headers/provenance, baris kurang/lewat, malformed record, control character, bank/PC di luar range, konflik member yang hilang/berlebih, source-count salah, name/role/hash mismatch, atau UNKNOWN gain diganti0. SHA256 input dihitung untuk reproducibility; tidak ada external signature/checksum upload yang diberikan untuk authentication terpisah.

| Sumber | Presets | Raw banks | SHA256 deklarasi, cocok fingerprint repository |
|---|---:|---:|---|
| ColomboGMGS2_BM.sf2 |892 |52 | `9e4686e4aa7173ffd1baf7ec6f80fcb3fe90880e824cf8c4e1defb5d153d47eb` |
| SX Melody |93 |5 | `e8c7356159c200d945f13e113595ff06e7955779b0e9361709d9c7cdba164a82` |
| SX Drum |21 |1 | `a72158c09677c585eec348762a33da49509bf338c5b36d0e787af58cca0b9f8a` |
| Tyros4 |142 |3 | `de5b1404630840a2a897e77c6083e2231c5d1523e6571a8afd972a4f684d36ce` |

Semua source_filename/source_role/SHA256 cocok `tests/fixtures/percussion_inventory_fingerprints.json`. Ini membandingkan **deklarasi hash dalam CSV**, bukan rehash empat binary SF2 di sesi ini. Validasi source binary mengikuti bukti user pada gate sebelumnya; CSV tidak membuktikan sample/audio identity secara mandiri.

Perhitungan dari input: **911** unique raw bank/PC addresses, **158** cross-source collision groups, **395** total collision members, **237** extra colliding entries. Seluruh395 members mempunyai tuple source hash/bank/PC/name/role yang tepat di metadata dan source_count sesuai group cardinality. Seluruh3 known gain references cocok profile repository;392 collision members lain tetap `UNKNOWN_NOT_ZERO`.

Semua1,148 `original_phdr_order` dan `is_melodic_verified` masih **UNKNOWN**. CSV berstatus `ORIGINAL_PROVENANCE_VERIFIED_NOT_RUNTIME`; collision CSV tetap `UNRESOLVED_REQUIRES_REFERENCE_CAPTURE`. Unknown tidak dipromosikan menjadi phdr order, role sound yang terukur, source-load success, winner runtime, gain0, atau golden capture.

## Artefak dan cara mereproduksi

Semua data/results berada di `tests/fixtures/sf2_real/`:

- Kedua input CSV byte-identical; `results/normalized_original_inventory.csv` untuk schema tool sebelumnya dan `results/inventory_validation.json` untuk seluruh collision membership/source-bank projections.
- `results/preset_policy_classification.csv`: seluruh1,148 rows, stable preset_id, original metadata, native-cache inclusion menurut code, family classification menurut policy, dan gain evidence.
- `results/simulated_requests.csv` + `results/resolver_simulations.csv`: seluruh4,735 contexts dan selected IDs/score/reason/ties; request dibuat untuk **simulasi**, bukan event LoveSong yang diamati.
- `results/per_conflict_report.json`: satu laporan untuk setiap158 group, lengkap members/family/gain, case IDs, observed simulation selections/rejects/ties dan ambiguity flags/recommendations.
- `results/ambiguities.csv`:3,468 flagged simulation cases. `results/special_case_findings.json` menampilkan probes bass/strings/pad, LSB dan sensitivitas optional pool.
- `results/summary.json`, `test_results.json`, `test_output.txt`, `historical_guard_investigation.json`: totals, hashes, raw test evidence dan investigasi source history.

```sh
python3 tools/sf2_real_inventory_audit.py \
  tests/fixtures/sf2_real/SF2_ORIGINAL_PRESET_METADATA.csv \
  tests/fixtures/sf2_real/SF2_REAL_COLLISION_GROUPS.csv /tmp/sf2-real-results
python3 tools/sf2_golden_routing.py inventory /tmp/sf2-real-results/normalized_original_inventory.csv
python3 tools/test_sf2_real_inventory.py
python3 tools/test_sf2_golden_routing.py
python3 tools/test_sf2_partial_original_metadata.py
python3 tools/test_sf2_checkpoint_host.py
python3 tools/test_voice_resolver.py
```

Audit tool exit0 hanya berarti CSV valid dan simulation execution berhasil; error schema/provenance/coverage/determinism exit2/FAIL. Tidak ada BASS/Android/SF2/sample writes. Temp file hanya TSV/executable diagnostics; mock tests lama membuat SF2 fixture sementara, bukan gabungan/master.

## Simulasi: kebijakan yang diuji dan asumsi

`sf2_real_resolver_batch.cpp` meng-include header **persis checkpoint** `voice_resolver.h`, SHA256 `8fab33a64f530ccf77cd94de238ea6471571661eb8a761f6c6e54c5f299e29e0`; fingerprint diverifikasi sebelum compile C++17. Tidak ada implementasi skor/family baru dalam Python. Python menyiapkan input dan merangkum output; selected source/score berasal dari header production yang tidak diubah.

Pool asumsi: primary SX Melody source0, Colombo optional source1, Tyros optional source2, dedicated SX Drum terpisah. Ini sesuai source selection design, **bukan bukti semua load/preload sukses di perangkat**. Native melodic cache tidak memasukkan dedicated SX Drum atau raw127/128: **989** cache rows tersedia secara mekanis menurut code. Flag ini bukan `is_melodic_verified` dari pengukuran; bank126 tetap masuk cache dan dapat ditolak lewat semantic-family gate.

Untuk semua1,148 preset dibuat3 melodic probes: literal raw bank+nama asal row (jalur angka UI), generic MIDI bank+nama asal row, generic MIDI bank tanpa nama (dynamic-PC-like). Raw bank<128 pada generic mapping menjadi request MSB=raw bank/LSB0; raw>=128 dibagi MSB/LSB seperti normalizer. Literal UI bank8 berbeda dari packed MIDI8:0=1024. Nama row sengaja dipakai sebagai controlled input; UI sebenarnya memakai firstOrNull pada bank/PC sehingga belum tentu mengirim nama row tersebut.

Drum-bank rows juga diuji sebagai **melodic probes** untuk melihat role/family rejection; hasilnya tidak diklaim sebagai Rhythm route. Rhythm ditentukan destination channel9/10 di production, memakai cache drum khusus dan donor adapter; actual decisions memerlukan capture terpisah.

Untuk setiap158 conflicts ditambah6 semantic contexts (blank, Bass, Strings1, Warm Pad, Grand Piano, A.Guitar) dan2 no-optional-source contexts. Ditambah15 Yamaha LSB8:1/8:2/8:5 probes dan12 MSB104/126/127 probes. Total **4,735** contexts. Dua replay pada setiap audit menghasilkan byte-identical decision output. Source/preset IDs canonical hanya index lookup; tidak dijadikan phdr ordinal.

Policy mempertahankan role/family rejection sebelum numeric score, consistent Yamaha identity decoding, MSB104 exception, exact/MSB/name scoring, original source0 bias, original basename/bank/PC/name stable ties, serta no-compatible-candidate suppression. Seluruh match/rejection memakai full metadata pool, bukan subset collision saja.

| Hasil simulasi | Jumlah |
|---|---:|
| Selected SX Melody |867 |
| Selected Colombo |868 |
| Selected Tyros |991 |
| Policy reject / NONE |2,009 |
| Total |4,735 |
| Top-score ties, resolved deterministically |604 |

**2,009 rejects bukan2,009 preset hilang atau kerusakan audio.** Context bisa meminta family yang sengaja berbeda, unsupported name/family, drum/SFX pada melody role, atau MSB126/127 yang dilarang. **604 ties bukan nondeterministic runtime**; policy memilih melalui stable tie-break. Actual filenames/cache aliases/import state harus dicapture sebelum menganggap tie result ini native reference.

Policy mengklasifikasikan **671** preset names/families sebagai UNKNOWN, termasuk **526** dalam melodic-cache pool. Ini keterbatasan classifier semantic/numeric evidence, bukan fakta bahwa sampel tidak memiliki suara atau seluruh preset tersebut harus dihapus.

## Per konflik dan daftar ambiguitas

`per_conflict_report.json` mempertahankan seluruh395 provenance members; tidak ada first/last winner yang dipaksakan menjadi manifest. Case IDs dapat dijoin ke resolver_simulations dan preset_id ke preset_policy_classification. Scope semua decisions: `SIMULATED_REQUEST_ORIGINAL_METADATA_POOL`; runtime selalu UNVERIFIED.

| Flag / temuan | Jumlah |
|---|---:|
| Group dengan member policy-family berbeda |90 |
| Group dengan setidaknya satu member UNKNOWN family |99 |
| Group raw126 memerlukan role/SFX review |2 |
| Group raw127/128 memerlukan drum priority/phdr capture |24 |
| Group bank lain>0 dengan literal UI/generic MIDI namespace berbeda |4 |
| Group mempunyai known source-keyed gain |3 |

Flag dapat tumpang-tindih. Pada simulation-case level terdapat2,782 selected IDs yang berbeda dari origin row,2,009 rejects dan604 ties; unionnya3,468 rows pada ambiguities.csv. Membandingkan origin metadata row dengan pilihan untuk hypothetical request tidak membuktikan aplikasi memilih suara salah. Rekomendasi setiap group adalah preserve semua source provenance dan capture exact request/native binding, dengan flags khusus di atas.

## Pemeriksaan khusus

**PC73 raw0-based (display74):** SX Drum21 preset tidak mempunyai PC73. Tyros raw127/128 juga tidak mempunyai PC73. Colombo mempunyai raw126:73 `GS Alternative`, raw127:73 `XG Alternative`, raw128:73 `Alternative`. Direct current `findDrumPreset` mencari dedicated SX Drum, tidak melakukan direct kit search pada optional Colombo/Tyros. Karena semua phdr ordinals UNKNOWN, **kit fallback pertama pada SX Drum belum dapat ditentukan**—CSV sorted PC0 bukan bukti entry pertama file.

Naive merge yang membuat Colombo127/128:73 tersedia pada font drum akan mengubah missing-kit fallback menjadi exact PC73; `preparePercussionLane` juga dapat berhenti melakukan donor substitution ketika exact requested kit menjadi tersedia. Raw126:73 bukan entry drum cache127/128. Preserve legacy semantics dan donor eligibility secara eksplisit sebelum export; jangan menyamakan “preset hadir” dengan compatibility/winner.

**Bank126/127/128:** bank126 berisi63 Colombo dan2 SX Melody presets. Normalizer tetap memasukkan raw126 ke melodic-bank map; semantic role/family dan per-key zone evidence belum verified. Raw127/128 tetap dikecualikan melodic cache, dipetakan drum secara khusus. Cache fallback memakai original phdr order, generic FONTEX2 raw127 sebelum128; conflict24 groups membutuhkan actual readback. Current normalizer bank sets: SX Melody5, Colombo50 melodic banks (52 dikurangi127/128), Tyros1. Ini membantu capacity review per sumber, tetapi tidak membuktikan master allocation/BASS compatibility.

**Variasi Yamaha / bass / strings / pad:** untuk request MSB8, LSB1/2/5, probes berikut memberi pilihan yang sama pada ketiga LSB, sambil request destination tetap berbeda:

| Request raw PC / name | Selected source raw bank:PC | Nama metadata |
|---|---|---|
|0 / Piano |Colombo0:0 |Acoustic Grand Piano |
|1 / A.Guitar |SX Melody8:1 |Steel Guitar |
|17 / Bass |SX Melody8:17 |BASS |
|49 / Strings1 |Tyros0:49 |t4 strings slow |
|89 / Warm Pad |Tyros0:89 |woarm pad |

Ini controlled semantic probes, **bukan observed LoveSong channels** atau bukti variation LSB audibel sama. Di seluruh164 contexts per name: Bass memilih SX153/Tyros6/reject5; Strings1 SX142/Colombo3/Tyros14/reject5; Warm Pad Tyros155/Colombo4/reject5. MSB126/127 hard rejection teruji pada semua8 special probes. MSB104 tetap semantic-evidence-only.

**Fallback pool:** meniadakan Colombo mengubah selected ID pada45 dari158 blank conflict probes; meniadakan Tyros mengubah47. Daftar per-group tersedia di special_case_findings.json. Jangan menganggap satu-file merge tanpa original source-family/priority metadata akan mempertahankan hasil ini, atau optional load failure sudah tertutup oleh sekadar preset presence.

**Gain:** SX Melody0:0=-6dB,8:1=-6dB; Tyros0:49=+12dB. Semua nilai lain tetap UNKNOWN_NOT_ZERO sebagai measured evidence. Production trim berlaku accompaniment zero-based10..15 dan memerlukan source-hash/native-binding verification; tidak otomatis diterapkan ke keyboard/Rhythm. New master hash akan kehilangan identity match bila original provenance tidak dipertahankan. Simulasi di sini tidak mengukur perceived balance atau PCM.

## Pengujian PASS / FAIL / UNVERIFIED

| Suite | Status |
|---|---|
| New original CSV + resolver simulation tests |PASS14 tests, termasuk corruption/duplicate/missing/name/role/hash/gain negative gates, full coverage, PC73, special banks dan optional pools |
| Existing golden diagnostic tests |PASS18 tests |
| Existing partial original metadata tests |PASS2 tests |
| Frozen checkpoint host tests |PASS11 targets/761 checks; mocked BASS/synthetic fixtures |
| Existing aggregate native portion |PASS15 targets sebelum first historical guard |
| Existing aggregate keseluruhan |FAIL pada drum shadow historical guard |
| S2/S3 source guards + unit/native semantic tests |PASS;4 dialect+4 CASM unit cases serta native self-test |
| Drum shadow/mix percussion/regression bypass/role PCM/diagnostic788 historical guards |FAIL, sama seperti audit sebelumnya |
| F04 source guard |FAIL assertion + incomplete historical snapshot errors; tidak dipromosikan PASS |
| Real BASSMIDI/Android/PCM/complete event captures |UNVERIFIED; tidak tersedia dan tidak diganti simulasi audio |

Data tests14 mempunyai input asli, tetapi sebagian assertions menguji **simulasi** atau malformed copies in-memory. Tidak semua14 berarti evidence runtime asli. Full aggregate berhenti pada first FAIL; sibling source guards dijalankan tersendiri. Tidak ada klaim semua tes repository PASS.

### Investigasi historical guard

Pin F12 baseline `a3dc588b3aea2622a020ccbc95800f9cfbc6723e`, apk-source `a54a894a61034003facb080876970e67c407046a`. Guard StyleSequencer menerima before SHA256 `09da84a3db83dddca662afc472cf74b7ead472bfaa59aa2d27e51f217cfb352e` atau after `ed80d7c5983ce23ab684601e25ec59b12d9c9e8b307b2abad13051b5ed02250a`. Actual frozen checkpoint: `c768285a3b4a303c270f1d1c68e04d559bb2951d28a249d3c19455715a97cf89`.

GitHub history dibaca **pada ancestry checkpoint yang diminta**. Setelah F12 diagnostic commit `f798f9dc…` (2026-10-09), file mempunyai later NextBar/section-boundary experimental changes `e75cee02…` dan `4f061e31…` (2026-10-12 WIB). Jadi profile lama bukan current physical style source yang dipin user. Ini penjelasan source-history untuk mismatch, bukan permission mengganti checkpoint atau musical certification.

Guard lain menolak ContentResolverProvider (#776), ArrangerBrain (#786), MainViewModel (#788). Proof Git blobs menunjukkan semua126 production files planning parent identik dengan checkpoint, sehingga failure ini **bukan perubahan dari audit/CSV baru**. F04 full380-file audit juga belum lengkap pada text snapshot. Rekonsiliasi historical guard/profile perlu review terpisah dengan exact scope/hash/history; tidak mengubah guard/expected manifests hanya untuk lulus. Voice & RELEASE tidak dibaca/di-merge menjadi source golden, dan tidak ada READY/main ref diubah.

## Rekomendasi master dan gate berikutnya

1. Pertahankan seluruh1,148 identities `original_sha256:original_bank:original_program`, walaupun raw address hanya911. Jangan collapse158 groups menurut first/last entry, ranking metadata name, atau source order baru.
2. Buat manifest collision-free yang tetap menyimpan original basename/source index/role/family policy/bank/PC/name, request MSB/LSB/PC dan explicit channel FONTEX2 mapping. Jangan memakai staging address sebagai certified native bank. Master basename/hash/bank baru tidak boleh mengganti fields yang dipakai resolver ranking/evidence.
3. Preserve PC73 missing-kit behavior dan donor activation dari dedicated SX Drum. Dapatkan phdr ordinal asli dan kit first-entry sebelum menentukan legacy alias. Buktikan127/128 mapping priority dari actual native readback; raw126 review per preset/zone, tidak bulk-promote sebagai melody/drum.
4. Preserve3 known source-keyed gain profiles dan UNKNOWN untuk measured values lain; donor evidence keyed original hash/bank/PC/key/target. Full key/velocity layers, generators/modulators/sample/loop/stereo tetap harus dibuktikan dari binary/full zone metadata, bukan phdr/name CSV.
5. Capture LoveSong aktual: style bytes/hash, actual supported Main/Fill/Intro/Ending, source→destination CASM, dynamic CC0/32/PC/name, chord/ACMP, source load/order/generation, actual preset/cache/preload/readback, note ownership/controller/envelope/pitchbend, donor key/velocity/lane dan PCM reference. Stopped-style RIGHT1/2/3/LEFT isolation tetap gate terpisah; tidak memakai Voice & RELEASE experiment.
6. Sesudah golden reference lengkap, baru review allocation/export experimental master dan PCM/device listening/preservation/import time/handle count/RAM-PSS. Tidak ada master address allocation, SF2 export atau APK build pada fase ini.

**GO:** inventaris asli kini tersedia dan konsisten untuk manifest/capture preparation. **NO-GO:** master export/production conversion—phdr order, melodic/articulation verification, actual routing/donor/PCM/device evidence dan historical-guard reconciliation belum selesai. Nilai metadata yang diketahui, simulated winner, dan runtime unknown dipisahkan pada setiap output.
