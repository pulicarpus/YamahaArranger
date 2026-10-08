# S8 — F03 Native Voice Identification

## Keputusan dan integritas

**Signature B bertahan setelah OFF pertama pada seluruh empat urutan/source
trial Linux NOTEOFF1; release tail A menjelaskan UNKNOWN classifier S7.**
Ini bukti sample contribution dalam eksperimen SDK Linux sintetis, bukan handle
native voice, kontrak Yamaha, atau keberhasilan audio Android/PSR-E343.
Kontrol release panjang tetap UNKNOWN; tidak diubah menjadi PASS identity.
Implementasi F03 belum disetujui atau dilakukan. STOP S8; tidak ada S9.

Branch `fix/mix-percussion-fidelity-784`, baseline lokal/remote terverifikasi
`291153a58a0873584f2e0adfc7dd220673f1b8af`, parent
`7fe5b654ab70526c9f1f1a980aaf85e1dc9d834c`. Semua 353 berkas baseline tetap
byte-identical. Hanya dokumen, fixture baru dan tes/harness diagnostik berubah;
produksi, workflow, S5–S7 fixtures, golden, regression guards dan Stage3 exclusion
utuh. S1–S7 corpus/ownership investigation tidak diulang; controls S6/S7 menjadi
regresi dan frozen native probe S7 dijalankan untuk menjelaskan classifier.

Bukti mentah, hash SDK/source/ZIP dan reproduksi ada di
[`s8_native_evidence.json`](../tests/fixtures/s8_native_evidence.json).

## SDK dan batas provenance

Download vendor resmi kini berhasil setelah sebelumnya HTTP403. Linux x86_64:
BASS `33821187` / `0x02041203`, BASSMIDI `33820672` / `0x02041000`.
NOTEOFF1 dari **header asli** bernilai 65536; wrapper memeriksa flag stream nyata
lalu meneruskan call ke library asli. Tidak memakai mock constants sebagai
bukti SDK. Header/library dan ZIP SHA256 disimpan dalam evidence JSON.

Build #802 (`37757196098`) SUCCESS, tetapi arsip id11540508861 tetap HTTP404.
Metadata digest S7:
`ef9ff56f16514494c7490e761a2ac462b7e6d9eb2222d5c2b1c66482ed797b0b`.
Isi/hash arsip tidak diverifikasi ulang. **Angka S7 di bawah adalah rerun lokal
source yang dibekukan pada SDK yang dicatat, bukan metrics yang diambil dari
#802.** Kesamaan versi/hash library dengan #802 belum terverifikasi.

## Akar masalah harness Build #803

Build #803, commit `7d87de81dd0938e8d784e80f8d903071729544d5`, gagal step25.
Pengguna memverifikasi 261 JVM tests, 1 failed:
`StyleMixFidelityS8NativeVoiceTest.isolatedAvailableNativeSdkSignatures`,
assert exit-code runner di Kotlin line12. Log publik hanya exit1 / HTTP404.

Reproduksi source asli dengan SDK nyata memberi:

| Fixture variant | Reference energy A/B | Native accepted ONs sebelum guard | Exit |
| --- | --- | --- | --- |
| Original #803, preset `S8 signatures` | 0 /0 | 0 | 1: `nonzero reference error=0` |
| Hanya terminal pgen diperbaiki, nama original | 0 /0 | Original failure tetap | 1 |
| Hanya nama menjadi `Acoustic Guitar` | .120136748927 /3.56109028979 | 4 (A-only, B-only, A+B controls) | 0 |
| Nama + terminal pgen corrected | Nonzero, semua trial selesai | 19 ON /16 OFF;1 injected rejected ON total | 0 |

**PROVEN harness fault:** preset sintetis UNKNOWN-family ditolak oleh resolver
produksi yang memang wajib aktif. `voice_resolver.h:168` menolak
`unknown_candidate_family`; `BassMidiPlayer::findMelodicPreset` sekitar957
menghasilkan no compatible candidate; `BassMidiPlayer::noteOn`1172–1175
mengembalikan sebelum native send ketika `melodySourceProgram<0`. Jadi tidak ada
NOTE_ON diterima, reference PCM nol, lalu guard yang benar menggagalkan runner.
Bukan bug note ownership atau bukti synthesizer menolak ON yang sudah dikirim.

Perubahan minimum audio-harness: nama preset fixture menjadi `Acoustic Guitar`
seperti S7; tidak mengubah gate/resolver produksi atau assertion nonzero.
Ditemukan juga missing terminal pgen record: terminal pbag index1 menunjuk
melewati generator terakhir. Ditambahkan terminal record dan pemeriksaan
RIFF/index bounds. SDK saat ini cukup permisif sehingga name-only variant tetap
berhasil; **missing terminal bukan penyebab zero-PCM yang dibuktikan**.
CI fail-closed bila SDK yang semestinya disiapkan workflow tidak ada.
Tidak menghapus test, melemahkan assertions atau mengganti golden.

## Mengapa classifier S7 UNKNOWN

Frozen font S7 memakai waveform sama pada v96/v32, sample4000frames/~83.33ms,
no sampleModes54, default release/envelope. OFF/frame1024; pengukuran membuang
256frames (~5.33ms), lalu memakai frames1280–1535. Error ialah pointwise squared
error/reference energy; reference ages cocok, tetapi tidak memasukkan tail.

| Seluruh metrics S7, rerun asli | Nilai |
| --- | --- |
| referenceEnergyFirst /Second | .702091 /.00854306 |
| crossErrorFirst /Second | .73655 /1.1109 |
| sameErrorFirst /Second | .73655 /1.1109 |
| crossSourceSurvivor /sameSourceSurvivor | UNKNOWN /UNKNOWN |
| rejectedThenOffEnergy /stopEnergy | 0 /.00949045 |
| acceptedOns /acceptedOffs /injectedRejectedOns | 9 /3 /1 |

S8 menambah A-only-OFF dan B-only-OFF pada timeline/reference yang sama:

| Release-control metric | Nilai |
| --- | --- |
| A tail energy /B tail energy | .00949045 /0 |
| Actual cross energy /(A tail +B reference) energy | .0257606 /.0257606 |
| cross /same error terhadap A-tail+B | 0 /0 |

**PROVEN pada rerun ini:** actual PCM adalah B yang bertahan **ditambah release
tail A**, persis bitwise pada analyzed window. Classifier membandingkan mixture
itu dengan A-only atau B-only; error1.1109 ke B jauh di atas1e-5. UNKNOWN benar
untuk classifier lama yang tidak memodelkan tail, bukan bukti semua voices hilang.
Stop energy juga tail, tidak sama dengan sustained ownership atau audible result.

Alignment/phase dan normalisasi gain adalah sensitivitas desain, bukan penyebab
tambahan yang dibuktikan pada trial ini. Pada sinus ideal integer-cycle/unit-gain,
error=`2*(1-cos(deltaPhase))`;1e-5 hanya mengizinkan ~.003162rad atau gain error
~.3162%. Velocity juga dapat memengaruhi filter/envelope. Reference-age alignment
sudah cocok; additive-tail fit0 mengisolasi tail dari confounds tersebut.
SDK acceptance, waveform residual dan native instance handle tetap dibedakan.

## Rancangan S8 dan kontrol

Fixture baru dua velocity zones: A/v48/375Hz dan B/v112/1125Hz, dst11/pitch60.
Loop panjang integer periods; explicit attack/decay -12000timecents, sustain0cb,
release -12000timecents. Font kontrol release0timecents=1second. Satu sample zone
per velocity. Mixer send reverb/chorus0. Native note API memakai player produksi
unchanged; wrappers hanya pada binary test. Rejected ON adalah **injeksi wrapper**,
bukan genuine SDK/device rejection. No-NOTEOFF1 hanya test-stream comparison.

48kHz timeline: A ON0; B ON2048 (42.67ms); first OFF4096 (85.33ms). Late
measurement frames12288–14335 (256–298.67ms), setelah8192frames tail allowance.
Second OFF14336; pedal released jika sustain; cleanup window setelah61440frames.
References memiliki umur absolute yang sama. Mono fold dari stereo float;
2048frame sin/cos projections bins16/48 memberi powers phase-invariant.
Raw energy, powers, residual spectrum dan normalized ratios dicatat terpisah.

Classifier exploratory: residual<.05; retained ratio>.5; absent ratio<.01;
lainnya UNKNOWN. Reference harus nonzero dan intended/unintended power>100x.
Tidak ada probability confidence yang dikarang. Observed reference separation
~5.17e8x A dan ~3.12e10x B. Survivor B memiliki ratioB1, ratioA~9.50e-10,
residual~8.21e-9: confidence tinggi **untuk signature fixture ini**, bukan seluruh
native voices/timbres/perangkat. Native handle tidak diamati.

## Expected versus actual PCM

Powers dalam sum-square projection units, bukan dB loudness/perceptual success.

| Trial | Expected diagnostic contract | Actual signature / ratios A,B | Energy /residual |
| --- | --- | --- | --- |
| A-only reference | A saja | A:1,6.53e-11 | .120136748927 /6.48e-9 |
| B-only reference | B saja | B:9.50e-10,1 | 3.56109028979 /8.21e-9 |
| A+B overlap | Kedua sample contributions | BOTH:1.00006163,.99998384 | 3.68117689108 /7.61e-9 |
| same-source OFF A→B | Logical release A; observe native | B:9.50e-10,1 | 3.56109028979 /8.21e-9 |
| same-source OFF B→A | Logical release B; observe native | **B**, sama seperti OFF A first | Sama |
| cross-source OFF A→B | Logical release A | B | Sama |
| cross-source OFF B→A | Logical release B | **B**, source metadata bukan selector | Sama |
| no NOTEOFF1 | Bandingkan, tanpa asumsi selection | SILENT:0,0 | 0 /0 |
| sustain sebelum OFF | Pedal menahan kontribusi | BOTH:1.00006163,.99998384 | 3.68117689108 /7.61e-9 |
| rejected A, admitted B, OFF A | Request tidak punya token A | SILENT:0,0; B dilepas | 0 /0 |
| long release,170.67ms allowance | Tail boleh masih tercampur | **UNKNOWN**:.01292091,.99999817 | 3.56267035136 /9.63e-6 |

Semua second-OFF/cleanup trial mempunyai final measured energy0. Acceptance
19 ON/16 OFF mencakup seluruh S8 trials, bukan owner-count per scenario;
1 injected rejection dilaporkan terpisah. ON/OFF A dan B berbeda secara logical
origin tetapi kedua OFF native tetap channel11,key60. Flag asli NOTEOFF1 memberi
observasi **older A dilepas, younger B bertahan** pada scope dua notes/font/SDK
ini, bahkan ketika caller mengatribusikan OFF ke B. Tidak menggeneralisasi Yamaha
FIFO atau selective native handle. A juga lebih pelan dan B lebih keras dalam
design velocity-zone ini; reverse ON order / swapped signature assignment perlu
sebelum mengklaim aturan FIFO universal. No-flag trial hanya mengukur post-OFF;
belum memisahkan perubahan overlap/retrigger pada ON dari allocation pada OFF. Kontrol no-flag membuktikan flag berpengaruh
pada kasus yang sama, bukan hanya diterima oleh API.

## Klasifikasi, Linux/Android dan konsekuensi F03

| Kesimpulan | Status dan batas |
| --- | --- |
| Original S8 family-invalid fixture gagal sebelum native NOTE_ON | PROVEN local SDK reproduction; cocok failure runner #803 yang dilaporkan pengguna; raw CI native log tetap tidak recovered |
| S7 rerun UNKNOWN disebabkan unmodelled A release tail | PROVEN additive fit error0; bukan metrics archive #802 |
| B signature bertahan setelah first OFF NOTEOFF1 | PROVEN empat source/order trials; high measured separation/margins |
| Long-release classifier identity | UNKNOWN karena ratioA .01292 melewati absent threshold .01 |
| Native voice handle/inaudible internal instance state | UNKNOWN; tidak dibaca oleh API probe |
| Genuine backend rejected-ON behavior | UNKNOWN; kontrol menggunakan injection saja |
| Android/PSR-E343 musical/audible correctness | UNKNOWN /runtime UNRUN |

Linux memakai x86_64 vendor binaries, decode float48kHz dan synthetic SF2 tanpa
physical output. Android app memakai NDK r26d, arm64-v8a/armeabi-v7a; compile
checks tidak menjalankan JNI/device BASSMIDI allocation, real SF2, sample-rate
negotiation atau hardware. Android SDK binary/version equivalence belum dibuktikan.
PSR-E343 menerima MIDI channel/key, bukan PCM signature/font/token; receiver punya
allocator sendiri. Tidak ada inference Android/PSR dari eksperimen Linux.

Rancangan F03 terkecil tetap immutable logical instances, explicit admission dan
release attribution, section/session discriminator, serta per-output accounting.
Per-source FIFO saja tidak cukup untuk cross-source collision: OFF B dapat
melepas native A pada scope yang diobservasi. Carry harus mempertahankan token
secara sengaja; interrupted cleanup hanya snapshot-owned instances; stale OFF
jangan mengonsumsi incoming owner. Reorder/coalescing/count/retrigger/channel
allocation mengubah musikal/routing dan memerlukan kontrak/persetujuan terpisah.
Tidak ada kandidat diterapkan atau dinyatakan aman secara umum.

Syarat sebelum implementasi: pin/read #802 and new CI SDK evidence; repeat on
shipped Android libraries/ABIs dengan real SF2/PCM; calibrate repeat/noise/filter
and release windows; establish authored note pairing and cross-source policy;
verify real rejected admission/ack path; verify physical MIDI OUT/PSR-E343;
retain Main/Fill natural carry/interrupted/stale controls, rhythm/non-rhythm,
overlaps, golden captures dan Stage3 guards. Corpus asli503/hash juga harus
tersedia untuk conformance rerun. Bukti Linux ini belum mengizinkan F03 umum.

## Regresi dan CI

Seluruh host regression dijalankan ulang sesudah koreksi: resolver/metadata/
native-recorder/source guards PASS; S1–S5 JVM83 PASS; gabungan S6–S8 JVM12 PASS;
fail-closed SFF1 harness7 PASS. Counts overlap, jangan dijumlahkan sebagai tests
independen. Local real SDK S8 dan frozen S7 rerun PASS execution; long-tail voice
identity tetap UNKNOWN. RIFF/terminal bounds PASS. No local regression FAIL.
Original503 corpus tetap BLOCKED; Android device PCM/PSR-E343 UNRUN.

Build #803 FAIL di satu tes S8,260 tests lainnya PASS menurut pemeriksaan
pengguna. Koreksi diagnostic-only commit
`517b784ded77ec9d997b2a6ec61201250752eaa6` dipush ke branch yang sama.
[Build #804](https://github.com/pulicarpus/YamahaArranger/actions/runs/37763724067)
**SUCCESS**: host/native SDK guards, Gradle testDebugUnitTest, assembleDebug,
serta Stage3 exclusion arm64-v8a dan armeabi-v7a. Android compilation/JVM bukan
Android device PCM. Detail langkah/status ada di
[`s8_ci_evidence.json`](s8_ci_evidence.json).

Artefak metadata yang terverifikasi: `mix-percussion-proof` id11544066944,
110KB, digest
`1be5f11caa747cc39f5894341a062f7ac90b184322b797a6f37bb52634ad0eb7`;
`app-debug` id11543757510,12.5MB, digest
`14de4108be7ec0dd69858e91edae474cd18ca9ca3570e37786a54d6582b04147`.
Download arsip tetap HTTP404, sehingga isi XML/SDK hash CI belum diaudit;
measured PCM di laporan berasal dari evidence lokal yang sudah di-commit,
bukan asumsi dari warna hijau CI. Upload pattern workflow mencakup XML S8
StyleMixFidelity, tetapi contents tidak diklaim telah dibaca.

Follow-up final hanya dokumentasi CI dan batas generalisasi, parent commit
517b784; sesuai paths-ignore docs/**, tidak memerlukan build baru. Status akhir:
regresi tersedia PASS, corpus503 BLOCKED, physical Android/PSR-E343 UNRUN,
long-release identity dan native handles tetap UNKNOWN. Tidak ada produksi/F03
implementation; STOP setelah S8.
