# BASSMIDI R1 — Koreksi audit request bernama (2026-10-14)

Status: kesalahan klasifikasi pada tabel ringkasan laporan Claude §6.3. Tidak ditemukan kesalahan fixture atau implementasi pada set request bernama.

Dokumen asli tetap utuh. Kontrak awal 98 REJECT→ACCEPT / 24 swap disimpan sebagai riwayat; koreksi eksplisit hanya untuk set 39.936 request bernama adalah 102 REJECT→ACCEPT / 20 swap / 0 ACCEPT→REJECT. Total perubahan tetap 122. Gate lainnya tidak berubah.

Bukti: baseline f4f2c613bebd09a3137bdbe0bc60f0895b597979; full-presets.tsv terverifikasi; header referensi voice_resolver_policyC.h; named_inv.cpp dan named_inv.out asli Claude; output eksekusi ulang tiga resolver pada seluruh 39.936 kasus. named_inv.out adalah golden perubahan bernama yang tersedia; tidak terdapat file golden bernama terpisah dalam ZIP. Golden tambahan ab_codex_C.tsv, ab_other_C.tsv, ab_msb0_C.tsv memakai set request yang berbeda, dan tidak menggantikan golden bernama ini.

Semua 39.936 pemenang implementasi sama dengan Policy C referensi. Semua 122 transisi baseline→Policy C sama dengan named_inv.out. Sisa 39.814 kasus tidak berubah. Tidak ada kasus golden yang hilang, tidak ada perubahan ekstra, dan 0 ACCEPT→REJECT. Baseline-golden-named.tsv sebelumnya juga cocok dengan baseline hasil eksekusi ulang, per request.

Penyebab selisih aritmetis: terdapat 24 perubahan di PC3/27 (8 pada LSB0, 8 pada LSB1, 8 pada LSB112). Hanya 20 dimulai dari ACCEPT. Empat lainnya dimulai dari REJECT sehingga bukan swap. Di luar keempatnya terdapat 98 REJECT→ACCEPT. Menganggap semua 24 perubahan slot sebagai swap menghasilkan angka tabel 98/24; klasifikasi berdasarkan baseline winner menghasilkan 102/20. Mekanisme penulisan ringkasan Claude tidak tersedia, sehingga audit tidak mengklaim mengetahui proses internal penulisnya.

## Empat kasus yang menjelaskan selisih

Preset pemenang berasal dari file persis MELODI YAMAHA PSR-SX700 & SX900 PRIME.sf2, bank preset 0.

| Request MSB/LSB/PC | Bank14 | Nama request | Baseline winner | Policy C winner | Klasifikasi dan alasan |
|---|---:|---|---|---|---|
| 0/1/3 | 1 | Honky Tonk | REJECT | Yamaha Honky Tonk @0/3 | REJECT→ACCEPT: baseline request UNKNOWN; C-2 decode memberi PIANO lewat allowlist persis. |
| 0/1/27 | 1 | Honky Tonk | REJECT | Clean yamaha @0/27 | REJECT→ACCEPT: baseline request UNKNOWN; C-2 decode memberi GUITAR lewat allowlist persis. |
| 0/112/3 | 112 | Honky Tonk | REJECT | Yamaha Honky Tonk @0/3 | REJECT→ACCEPT: baseline request UNKNOWN; C-2 decode memberi PIANO lewat allowlist persis. |
| 0/112/27 | 112 | Honky Tonk | REJECT | Clean yamaha @0/27 | REJECT→ACCEPT: baseline request UNKNOWN; C-2 decode memberi GUITAR lewat allowlist persis. |

Nama request Honky Tonk tidak dikenal namedFamily. Pada bank14 1/112, baseline juga tidak memakai GM family langsung (bank != 0); kandidat netral Prime/Tyros belum memberikan identitas family. Baseline select = -1. C-2 memberi family pada kandidat Prime persis di slotnya, lalu decodeRequest meneruskan family melalui hint walaupun nama preset tetap netral. Pemenang menjadi Prime Yamaha Honky Tonk (PIANO) atau Clean yamaha (GUITAR). Hal ini adalah penerimaan baru, bukan perpindahan dari voice lain.

## Koreksi tes yang eksplisit

Kode produksi dan allowlist tidak berubah. Tes host tunggal mempertahankan seluruh guard sebelumnya, mengganti hanya ekspektasi angka set bernama berdasarkan audit ini, dan menambahkan pencocokan setiap pasangan baseline/Policy C dengan named_inv.out. Kasus yang tidak tercantum di golden wajib tetap identik. Duplikasi golden ditolak; tepat 122 golden harus terlihat. 0 ACCEPT→REJECT dan pembatasan MSB0/slot swap tetap wajib. Perubahan ini memperkuat gate per kasus; bukan penghapusan atau pelonggaran assertion untuk memperoleh PASS.

Catatan ini terpisah di scratch, tidak menambah file ke patch produksi dua-file. Publikasi belum dilakukan. D1–D6 perangkat dan nama BASS runtime tetap UNVERIFIED; audit ini hanya menentukan kelayakan publikasi branch eksperimen.

## Hasil eksekusi ulang tiga resolver

PASS RECONCILIATION cases=39936 opened=102 swaps=20 closed=0 reference_mismatch=0 golden_mismatch=0 outside_policy=0 golden_changes_seen=122

## Gate final

Tes host produksi: seluruh 39.936 pemenang cocok dengan golden, 102 penerimaan baru, 20 swap, 0 penolakan baru, 0 perubahan tidak diharapkan; CONTRACT PASS 0. GO untuk publikasi branch eksperimen saja. Tidak ada commit/push, merge, atau APK pada tahap audit ini.
