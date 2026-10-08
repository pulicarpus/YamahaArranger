# F03 — Yamaha Reference Verification

## Keputusan dan baseline

**NO-GO untuk subset patch playback F03.** Bukti baru yang terbaca memperjelas
format pesan MIDI dan jalur akses dokumen resmi, tetapi belum menentukan pairing
musikal Yamaha pada tiga kasus di bawah. Tidak ada pengujian keyboard yang telah
dilakukan dalam tahap ini. Tidak ada prototype baru, perubahan produksi,
workflow, fixture/golden, Stage 3, APK eksperimen atau P2.

Repository `pulicarpus/YamahaArranger`, branch `fix/mix-percussion-fidelity-784`.
HEAD lokal/remote awal identik: `c92e7d3f2350847c65f9cef4fa2881805cd5b823`,
working tree bersih. Semua **378 file tracked baseline** dibekukan melalui SHA256
lokal sebelum penulisan. Baseline bukti adalah
[arsitektur](F03_ARCHITECTURE_DECISION.md), [S5](F03_NOTE_OWNERSHIP_INVESTIGATION_S5.md),
[S6](S6_F03_ROOT_CAUSE.md), [S7](S7_F03_BACKEND_CONTRACT.md),
[S8](S8_F03_NATIVE_VOICE_EVIDENCE.md), [P0](F03_TEST_ONLY_PROTOTYPE.md), dan
[P1](F03_P1_CERTIFICATION.md). Audit/eksperimen tersebut tidak dijalankan ulang.
Pembacaan case007 hanya menggunakan bukti beku, bukan audit corpus baru.

PROVEN berarti fakta langsung dalam scope sumber; OBSERVED berarti eksperimen
terbatas; UNKNOWN berarti perilaku belum diketahui; BLOCKED berarti belum memenuhi
gate implementasi. PASS tes/build tidak menggantikan bukti musikal. Pengujian
perangkat di seluruh prosedur berikut berstatus **UNRUN**.

## Sumber resmi yang benar-benar terbaca

Pemeriksaan akses 2026-10-08 (Asia/Jakarta), HTTPS/proxy normal, TLS tetap aktif.
Tidak menggunakan bypass, ekstraksi credential, mirror tidak terverifikasi atau
kutipan yang tidak dibaca. Status runtime policy sendiri `unknown`; kemampuan
membaca sumber dinilai dari respons aktual, bukan asumsi draft P1 telah aktif.

| ID | URL dan hasil aktual | Isi terbaca dan batasnya |
|---|---|---|
| M1 | [MIDI 1.0 Detailed Specification](https://midi.org/midi-1-0-detailed-specification), HTTP200,78513 byte | Halaman pengantar menyebut revisi1996 dan nama `M1_v4-2-1_MIDI_1-0_Detailed_Specification_96-1-4.pdf`; **bukan isi PDF normatif** |
| M2 | [Summary of MIDI 1.0 Messages](https://midi.org/summary-of-midi-1-0-messages), HTTP200,86250 byte | Tabel NOTE_ON/NOTE_OFF: channel0–15, key, velocity. Halaman memperingatkan bahwa ringkasan tidak lengkap dan implementasi perlu spesifikasi resmi |
| Y0 | [Sitemap Yamaha](https://manual.yamaha.com/sitemap.xml), HTTP200,174759 byte | Katalog URL; tidak berisi aturan lifecycle. Tidak ditemukan entri SX720/Genos pada snapshot yang diperiksa; ini bukan bukti manual/rule tidak ada |
| Y1 | [Katalog manual Yamaha PSR-E483](https://manual.yamaha.com/mi/rt/psr-e483/downloads/country.html), HTTP200,9406 byte | Daftar tautan support regional. Ini navigasi resmi, **bukan manual musikal PSR-SX720/E343**, dan tidak menjadi oracle |
| B1 | [BASSMIDI StreamCreate](https://www.un4seen.com/doc/bassmidi/BASS_MIDI_StreamCreate.html), HTTP200,8499 byte | Kontrak vendor NOTEOFF1: release instance tertua; tanpa flag semua instance note tersebut. Sama dengan sumber P1; bukan bukti baru Yamaha |

SHA256 respons yang dibaca:

```text
M1 b10d2fe2f8ace69cf9c5b975c4d18b06c3d52b9d02f353cae4119c9d6f925bfe
M2 eefb4eb2d83f840b6fe265c04d3393bcbf0e23e030e8197ecff992a7bff1e606
Y0 82740d090a205d355e7afb26f5b0eb171eb31f113458b4630ee3429d75669a14
Y1 33f0204f0dbfea5c75f030aa5fc9914e091208fe52c3c4598b46370b6eb4e546
B1 adede40809f266f6c6f0dd397efa3e41242582898f1e8aba4ee1bc182fe6f808
```

### Sumber yang belum terbaca

Tombol Download aktual pada M1 mengarah ke
[Google Drive file 1ewRrvMEFRPlKon6nfSCxqnTMEu70sz0c](https://drive.google.com/file/d/1ewRrvMEFRPlKon6nfSCxqnTMEu70sz0c/view).
Request normal ditolak proxy CONNECT403. Metadata deskripsi halaman memuat teks
“Login to Download”, tetapi tombol aktual tersebut adalah tautan Drive. **Tidak
menyimpulkan login diperlukan atau mencoba melewati autentikasi**. Isi klausul
repeated NOTE_ON/NOTE_OFF pada PDF belum diperiksa; nomor halaman/kutipan tidak
boleh dikarang. M1 landing-page HTTP200 bukan sertifikasi isi Detailed Specification.

[Halaman unduhan resmi PSR-SX720](https://usa.yamaha.com/products/musical_instruments/keyboards/arranger_workstations/psr-sx720/downloads.html)
dan `https://download.yamaha.com/` ditolak proxy CONNECT403. Kandidat HTML
`https://manual.yamaha.com/mi/kb-ekb/psr-sx920-sx720/en/index.html` dan kandidat
`psrsx920-sx720`, Genos2 serta `mi/rt/psr-sx920-sx720`/`psr-sx720`
mengembalikan404. Katalog Y1 hanya mengarahkan ke support regional; tidak
menyediakan teks aturan yang diperlukan. Tidak menyamakan404 dengan ketiadaan
manual atau aturan Yamaha. Pencarian ini terbatas, bukan survei seluruh publikasi.

| Topik resmi yang diminta | Status isi teknis dalam tahap ini |
|---|---|
| MIDI Detailed Specification: repeated note/receiver choice | BLOCKED membaca PDF; klausul UNKNOWN |
| Yamaha SFF1/SFF2 dan CASM | Dokumen normatif/teknis relevan belum terbaca; UNKNOWN |
| NTR/NTT/RTR: held-note lifecycle | Penjelasan resmi relevan belum terbaca; UNKNOWN |
| Style Creator: penyimpanan/import overlapping events | Manual relevan belum terbaca; UNKNOWN |
| Section transition pada PSR-SX720 | Manual relevan belum terbaca; UNKNOWN |

Diperlukan edisi, model, bab/halaman dan teks yang tepat. Deskripsi transposition
NTR/NTT atau chord-change RTR, bila nanti ditemukan, tidak otomatis mengatur
pairing repeated notes atau cleanup saat Main/Fill. Jangan menganggap perilaku
PSR-SX720 sama dengan PSR-E343 atau versi SFF berbeda tanpa bukti scope.

## Tiga kasus penentu

### A — Repeated note: EndingB case007

Bukti beku [reference S5](../tests/fixtures/f03_ownership_reference_s5.json),
id007, `Ballad/J-Ballad2.S261.prs`, hash style
`2938cdd500b628eabe88f44ce670f1af9ee7dee54b23599d8ded045b31f3a2c9`.
Source11 berarti status channel12 dalam penomoran MIDI1–16; key67. Ini
**EndingB, cross_section=false**, bukan witness Main/Fill atau cross-source.

| Event | Raw ordinal / offset | Absolute tick | Section-relative tick | Velocity |
|---|---|---:|---:|---:|
| ON pertama, label analisis A | 3739 /15592 |202560|2880|76|
| ON kedua, label analisis B |3759 /15685|204480|4800|74|
| OFF pertama |3761 /15693|204600|4920|0|
| OFF kedua |3832 /15990|208780|9100|0|

| Hipotesis yang harus dibedakan | Expected interval relatif section, hanya hipotesis |
|---|---|
| FIFO | A2880→4920; B4800→9100 |
| LIFO | A2880→9100; B4800→4920 |
| First OFF melepas keduanya | Keduanya berakhir/release di4920; OFF9100 tidak punya held instance |
| Retrigger pada ON kedua | A berubah/berakhir di4800; perlu kontrol overlap sebelum OFF |
| Tail menutupi hasil | PCM setelah4920 bisa memuat tail dan held contribution sekaligus; UNKNOWN bila tidak terpisah |

PROVEN: empat event raw tersimpan dan format M2 tidak memuat token instance.
OBSERVED dari P1: allocator Linux pada fixture dua-signature melepas instance
tertua, independen dari reverse ON/velocity assignment dalam scope eksperimen.
UNKNOWN: pairing yang dimaksud Yamaha pada style asli/receiver tersebut.
Nama field S5 `FIFO_OBSERVER_CONVENTION_NOT_YAMAHA_ORACLE` tetap dipertahankan.
Output aplikasi, 276 replacements dan31 shortened intervals tidak dijadikan
oracle atau jumlah kegagalan suara Yamaha. **Kasus A BLOCKED untuk patch.**

### B — Note aktif saat Main/Fill/section berubah

Bukti S6/P0 mengenai natural carry, snapshot interrupted cleanup dan generation
guard tetap bukti aplikasi/model. Belum ada referensi resmi atau capture Yamaha
untuk menentukan apakah note lama dibawa, dilepas, atau ditransformasikan.

Pisahkan kondisi berikut: natural end/loop; request Main sebelum boundary;
request mid-section yang mungkin di-quantize; Fill lalu Main; Intro/Ending;
chord tetap versus berubah; pedal/sustain; RTR; ACMP tetap versus toggle.
Perubahan chord tidak boleh disamakan dengan pergantian section. Button request,
section activation, scheduled OFF, dan audible tail adalah empat waktu berbeda.

Belum boleh menetapkan “semua notes carry” atau “semua notes stop saat Fill”.
Jika keyboard menunggu barline, itu bukti timing switch, **bukan pairing OFF**.
Jika PCM tetap ada setelah switch, belum membuktikan carry: bisa release tail,
effect tail, atau note baru dari section berikutnya. **Kasus B UNKNOWN/BLOCKED.**

### C — Dua source/part menuju destination/pitch sama

M2 membuktikan paket NOTE_OFF hanya mengalamatkan channel/key dengan velocity;
token part musikal tidak tersedia dalam format pesan tersebut. P1 membuktikan
batas event API aplikasi dan mengobservasi oldest release pada Linux; itu tidak
menentukan durasi yang dimaksud untuk masing-masing part Yamaha.

Jangan menggabungkan dua witness dari style berbeda menjadi collision nyata.
Case007 bukan cross-source. Witness C harus berasal dari satu style/visit/chord
yang sama, dengan dua source identities dan transform/binding provenance yang
membuktikan output channel/pitch benar-benar bertemu. MIDI OUT dari keyboard bisa
menyajikan channel berbeda walaupun output aplikasi menyatu; catat hasil itu,
jangan memaksakan asumsi routing aplikasi ke Yamaha.

Jika intended OFF mengakhiri part B namun allocator melepas A, logical ownership
dan physical release tidak selaras. Refcount, suppression, retrigger, reorder
atau channel split mengubah musikal/routing dan bukan solusi otomatis.
**Kasus C UNKNOWN/BLOCKED**, termasuk receiver PSR-E343 dan dual-output.

## Prosedur keyboard asli — disiapkan, belum dijalankan

### Rekaman dan kontrol bersama

Gunakan komputer/sequencer MIDI terpisah yang tidak memanggil YamahaArranger.
Catat model/firmware, port/interface, bank/PC/nama voice aktual, receive-channel
configuration, tempo, chord/split, ACMP, pedal, effects, mixer dan style SHA256.
Jangan menebak nomor preset berdasarkan nama GM. Matikan MIDI echo/thru; gunakan
satu jalur input agar ON tidak digandakan. Pisahkan mode receiver MIDI IN dari
mode native style engine. PSR-E343 boleh menguji receiver, tetapi kemampuan memuat
style/section tertentu harus diverifikasi; jika tidak didukung, tandai UNRUN.

Rekam stereo PCM lossless (misalnya48kHz/24-bit), timestamp actual MIDI input dan
output bila keyboard mengeluarkannya, serta video/display/panel saat section
berubah. Simpan raw audio/log, setting dan clock-alignment offsets; timestamp
rencana kirim tidak menggantikan timestamp wire. Ulangi minimal5 kali dari state
bersih, tidak ada note/effect tail trial sebelumnya. Controller/panic cleanup
hanya sebelum/ setelah trial, tidak di dalam measurement. Record actual jitter.

Kalibrasi voice sustained yang benar-benar peka velocity pada perangkat:
A-only, B-only, keduanya tanpa OFF pertama, dan single-note OFF dengan umur note
sama. Jangan memilih organ yang mengabaikan velocity atau piano yang habis decay
lalu menganggap kontribusi tidak ada. Jangan mengganti program di antara ON
untuk menciptakan signature tanpa membuktikan dampaknya pada held voice.

### A1 — Receiver test, urutan byte dan waktu

Contoh menggunakan channel12 (zero-based11), key67. Label A/B hanya anotasi log;
**tidak dikirim sebagai voice identity**. Pedal off sebelum trial:
`BB 40 00` (CC64), `BB 42 00` (CC66), bila receiver mendukungnya.
Pilih voice/mixer melalui setting yang telah diverifikasi dan tunggu state tenang.

| t sejak awal trial | Byte hex yang dikirim | Makna |
|---:|---|---|
|0ms|`9B 43 30`|ON A,velocity48|
|250ms|`9B 43 70`|ON B,velocity112|
|1000ms|`8B 43 00`|OFF pertama: tanpa pilihan A/B|
|3000ms|`8B 43 00`|OFF kedua: paket identik|
|sesudah capture selesai|cleanup terkontrol, dicatat|Tidak dijadikan bukti pairing|

Lakukan versi velocity112→48, sehingga usia dan loudness tidak tertukar sebagai
alasan pairing. Tambahkan kontrol tanpa OFF pertama, A-only/B-only pada slot umur
yang sama, dan dua OFF berurutan di1000ms (actual wire order dicatat; port serial
bukan simultan sempurna). Jangan menganggap NOTE_ON kedua pasti membuat voice
kedua: kontrol overlap harus membuktikan kontribusi terpisah terlebih dahulu.

Untuk pola case007 lakukan trial terpisah: ON `9B 43 4C` pada0ms, ON
`9B 43 4A` pada1920ms, OFF `8B 43 00` pada2040ms dan6220ms. Ini **normalisasi
eksperimental1tick=1ms** dari delta raw, bukan tempo/PPQN style asli. Velocity76/74
mungkin sulit dibedakan; hasil dari kontrol48/112 tidak otomatis berlaku padanya.
Untuk native replay asli, baca MThd division dan tempo map dari file asli; hitung
waktu melalui tempo map, bukan menebak PPQN dari angka tick fixture.

### Membedakan tail dari held voice

Ukur release single-note pada umur/pedal/effects yang sama dan rekam sampai
mencapai noise floor yang dikalibrasi. Tentukan `T_tail` konservatif dari trial
ulang, misalnya RMS di bawah noise floor+6dB selama≥500ms, dengan window100ms;
ambang ini harus diperiksa terhadap SNR nyata, bukan dianggap aturan Yamaha.
Pastikan reference held-only masih jelas di atas noise pada late window.

Jika `T_tail` terlalu panjang untuk jendela antara OFF pertama dan kedua,
jalankan trial **tambahan** dengan OFF kedua pada `1000ms + T_tail +2000ms`;
catat jadwal tersebut terlebih dahulu. Trial tambahan bukan native case007 dengan
timing asli. Bandingkan attack, sustain dan tail terhadap reference umur-sama.
Jangan menganggap penjumlahan PCM reference linear tanpa validasi A+B/no-OFF.

Klasifikasikan survivor hanya jika kontribusi A versus B terbedakan dengan margin
terukur lintas repeats; retained PCM saja bukan identity. Jika references mirip,
nonlinear/phase/envelope berubah, tail masih bercampur, atau satu-note control
sudah silent, hasil **UNKNOWN**, bukan memilih FIFO/LIFO yang paling cocok.
Sustain-on adalah trial terpisah; jangan gunakan pedal-held PCM sebagai bukti
bahwa OFF tidak diterima. Hardware voice handles tidak diklaim teramati.

### B1 — Native style Main/Fill tanpa mencampur timing dan pairing

1. Gunakan style asli terotorisasi atau salinan test style terpisah; catat hash,
   marker section, event raw, source/part dan CASM settings. Jika Style Creator
   dipakai, export/re-read untuk memeriksa apakah import/quantization mengubah
   overlap/OFF; jangan menganggap editor mempertahankan event. Tidak membuat atau
   merekam ulang fixture repository dalam tahap ini.
2. Pilih4/4,tempo120 untuk test style, chordC mayor stabil dan ACMP tetap. Verifikasi
   chord yang dikenali pada display, jangan menebak MIDI channel chord recognition.
   Pada tempo ini satu quarter500ms dan bar2000ms; style asli memakai meter/tempo
   aslinya, bukan dipaksa menjadi test style. Pilih note witness yang masih jelas
   sustained sampai sekitar boundary terukur, bukan voice yang telah decay.
3. Tentukan boundary aktual `B` dari playback/capture. Ambil kontrol tanpa switch,
   request next Main pada `B−250ms`, serta request pada `B+250ms` pada trial lain.
   Rekam button/request time **dan** actual new-section onset `S`. Jika UI request
   di-quantize, laporkan demikian; tidak menamainya immediate interruption.
4. Uji urutan Main D→Fill B→Fill A→Main A bila perangkat/style benar-benar mendukung
   pemilihan tersebut. Catat marker nyata (misalnya FillBB/FillAA), AUTO FILL dan
   target Main. Tidak menganggap label panel identik marker file; tidak mengganti
   Fill yang tidak tersedia dengan Break lalu mengklaim kasus sama.
5. Bandingkan old-note control sebelum `S`, release/tail setelah `S`, dan new-note
   attacks. Part-solo adalah trial tambahan, dengan mute ditetapkan sebelum start;
   bisa memengaruhi allocator sehingga tidak menggantikan unmuted reference.
   Catat chord-change/RTR pada trial terpisah; pedal dan ACMP toggle juga terpisah.
6. Bila perlu test style crossing note: rencanakan old ON pada `B−750ms`, authored
   old OFF pada `B+1250ms`, dan new-section ON pada `S+250ms`. **Export dan verifikasi
   event benar-benar dapat disimpan/diterima Yamaha**. Bila editor/loader memotong
   note di boundary atau menolak style, jangan mengarang cross-boundary witness.

Carry dinyatakan OBSERVED hanya jika old contribution terbukti masih held melalui
`S` dan tidak sama dengan tail/new attack; stop atau transform juga memerlukan
atribusi old contribution. Waktu Fill dimulai sendiri tidak menentukan pasangan
OFF. Ulangi Intro/Ending dan loop setelah case utama, bukan menggeneralisasikannya.

### C1 — Native style versus collapsed receiver collision

Pada satu style/visit yang tervalidasi, isolasi part A dan B sebagai kontrol,
lalu gabungan, chord/tempo/CASM tetap. Pilih event yang output pitch-nya sama;
periksa apakah output **channel** juga sama pada reference Yamaha. Jika berbeda,
itu tidak membuktikan shared-channel release dan tidak boleh dicollapse diam-diam.

Sebagai receiver-only control, dua track DAW berlabel sourceA/sourceB mengirim
ke channel12/key67 dengan timeline A1, lalu swap track/velocity/birth order.
Setelah merge, byte input sama dengan kasusA; receiver tidak memperoleh nama
track. Hasil ini menguji allocator Yamaha, **bukan kepemilikan dua part style**.
Native part ownership harus ditelusuri lewat style provenance, solo references,
MIDI OUT dan PCM independen. Unknown admission atau hasil yang tidak dapat
memisahkan contributions tetap UNKNOWN. Jangan menyimpulkan Linux BASSMIDI atau
receiver MIDI IN adalah implementasi native style engine Yamaha.

## Satu eksperimen minimum yang paling menentukan

**Rekam EndingB case007 pada Yamaha arranger yang bisa memuat style asli dengan
hash di atas**, termasuk kontrol single-contribution/release-tail berumur sama,
MIDI OUT, PCM dan setting chord/voice/tempo. Cari apakah kontribusi ON3739 atau
ON3759 bertahan setelah event3761. Ini langsung membedakan hipotesis musikal pada
witness nyata; MIDI OUT OFF4920 tanpa calibrated PCM attribution belum cukup.

Jika style asli atau calibrated identity tidak tersedia, hasil tetap BLOCKED atau
UNKNOWN. Normalized receiver A1 berguna untuk persiapan, tetapi tidak membuka
GO bagi case007. Corpus `sff1.zip` asli/hash belum tersedia; tidak mengklaim
rerun503/503. Capturecase007 pun hanya membuka review scope caseA tertentu,
bukan otomatis mengizinkan seluruh Main/Fill/cross-source atau patch produksi.

## Gate implementasi, integritas dan CI

Tidak ada subset F03 yang memperoleh GO dari sumber baru ini. Sebelum keputusan
berubah: bukti Yamaha yang secara independen menentukan intended pairing/carry,
capability release targetAndroid/receiver pada scope yang sama, provenance/admission
tepat, regression/golden parity, corpus asli untuk gate503 dan explicit approval.
Unsupported selective release tidak ditutupi refcount/retrigger/reorder. P2 tidak
dimulai dan baseline suara tetap digunakan.

Validasi dokumen: seluruh378 file tracked lama byte-identical, sole addition
file ini, diff whitespace dan link lokal diperiksa, HEAD remote/working tree
bersih diverifikasi setelah push. Tidak menjalankan ulang S1–S8/P0/P1 atau native
probe untuk perubahan dokumentasi. Workflow `docs/**` paths-ignore tetap;
**tidak ada build baru yang diminta**. Referensi CI tetap
[Build #807 SUCCESS](https://github.com/pulicarpus/YamahaArranger/actions/runs/37780609074)
pada commit diagnostik `ba97641759c8e6d4f4b16103fb4bf1c9b56e8b67` sebagaimana P1.
Status inherited itu bukan hasil pengujian perangkat atau tes baru tahap ini.

**STOP setelah dokumen ini. NO-GO patch produksi, tidak ada prototype/APK/P2
atau perubahan playback tanpa persetujuan dan bukti berikutnya.**
