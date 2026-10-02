# YamahaArranger: audit enam audition diagnostic #770 — lengkap

Tahap analysis only. Tidak ada perubahan kode, resolver, fallback, mapping, MIDI decoder, gain/mixer, controller atau arranger state. Numbering MSB127/LSB0/rawPC73 = Data List PC#74 PopDrumKit tetap CLOSED.

## Integritas

Enam request kini lengkap. Lima WAV dan sidecar yang diunggah ulang identik byte-for-byte dengan unggahan sebelumnya. Semua ZIP lulus CRC. SHA256 tiap WAV dihitung ulang dan cocok dengan sidecar. Semua WAV PCM16 stereo44100Hz,88200frames/2detik, nonzero dan tanpa clipping. Nilai peak/RMS PCM16 konsisten dengan sidecar floating-point dalam toleransi kuantisasi.

Fingerprint source SF2 yang dicatat sidecar cocok dengan full inventory. Seluruh relation Z cocok persis dengan inventory sebelumnya. Actual fontHandle sama dengan diagnosticFontHandle; actual bank/rawPC/key/velocity cocok request; sidecar mencatat verifikasi sebelum/sesudah satuNOTE_ON. Actual sample voice ID tetap unavailable. Cleanup/read-only state dicatat oleh diagnostic dan diuji regression sebelumnya; WAV sendiri bukan pembuktian independen terhadap seluruh state arranger.

## Hasil lengkap

Bank/rawPC/sourceKey di tabel adalah parameter candidate SF2, bukan mapping produksi. RMS dihitung sepanjang2detik termasuk diam; t95/t99 menunjukkan waktu kumulatif energi95%/99%, bukan akhir bunyi atau identitas articulation. Semua confidence semantik tetap berbasis kombinasi metadata, bukan hasil spektrum.

| SF2 | Bank/rawPC | SourceKey/velocity | Target Yamaha | Sample | Eligible relations | Peak dBFS | RMS2s dBFS | t95/t99 ms | Semantic classification |
|---|---|---|---|---|---:|---:|---:|---|---|
| Colombo | 128/2 | 40/110 | 31 | #2252 SC-55 Tight Snare | 1 | -10.92 | -37.12 | 48.4/123.7 | COMPATIBLE candidate — not EXACT |
| Colombo | 128/2 | 42/42 | 16 | #2233 SC-55_Close HiHat | 1 | -27.41 | -61.51 | 45.2/59.0 | UNKNOWN — edge non-proven |
| Colombo | 128/2 | 44/42 | 21 | #2234 SC-55_Pedal HiHat | 1 | -32.19 | -63.73 | 43.4/53.2 | COMPATIBLE candidate — not EXACT |
| MELODI | 126/35 | 42/42 | 16 | #1489 hi-hat closed | 2 | -32.85 | -65.26 | 36.4/51.1 | UNKNOWN — edge non-proven |
| MELODI | 126/35 | 44/42 | 21 | #1491 hi-hat pedal | 2 | -30.72 | -60.95 | 54.8/72.3 | COMPATIBLE candidate — not EXACT |
| MELODI | 126/35 | 91/110 | 31 | #1532 035Snare 3 | 1 | -15.62 | -39.80 | 121.7/239.2 | COMPATIBLE candidate — not EXACT |

## Perbandingan snare kini lengkap

Colombo bank128/rawPC2/source40/v110 adalah preset StandardSC55, instrument DK-StanSC55, sample2252 SC-55TightSnare. Satu relation, keys40:40/velocity0:127, original60/overrideRoot40, PG attenuation−25, IG attenuation0/filterFc12890/release3986, tanpa coarse/fine tuning eksplisit. Jadi original60 tidak berarti note40 ditranspose−20semitone: root di-override40.

MELODI bank126/rawPC35/source91/v110 adalah EXP:ARABICPA900, instrument ArabicYAMAHA, sample1532 035Snare3. Satu relation, keys91:91/velocity0:127, original61/overrideRoot91, release8000, tanpa coarse/fine tuning eksplisit. Source91 tidak berarti transpose+30semitone: root di-override91.

Kedua WAV mempunyai PCM valid dan berbeda. Colombo mempunyai peak sekitar4.70dB lebih tinggi dan RMS2s sekitar2.68dB lebih tinggi; energi lebih terkonsentrasi di awal (t95≈48.4ms vs121.7ms). Komponen spektral<200Hz≈18.4% Colombo vs64.9% MELODI;200–2000Hz≈67.4% vs23.7%. Ini deskripsi numerik, bukan label instrumen dari spektrum dan bukan alasan memilih winner.

Tail kecil tetap ada: terakhir melampaui−60dBFS sekitar864ms Colombo vs658ms MELODI. t95 lebih pendek tidak berarti seluruh tail Colombo lebih pendek. Render memakai isolated stream defaults/fontVolume1, bukan jaminan dry raw sample; envelope, generator/modulator, dan default effect engine dapat berkontribusi. Tidak menetapkan sebab tail dari WAV saja.

Metadata kedua bundle mendukung generic snare-hit COMPATIBLE (confidence sedang), namun belum membuktikan timbre/identity Snare4PD. Nama TightSnare dan Snare3 adalah evidence, bukan padanan exact Yamaha. Perbedaan level tidak memicu gain/mixer changes.

## Target16 dan21

Target16: dua source42 berisi named closed-hat candidates; PCM telah terkonfirmasi, tetapi articulation edge tetap UNKNOWN. Target Hi-HatEdge10PD tidak boleh disamakan dengan generic closed-hat hanya karena family atau frekuensi tinggi. Openness target juga tidak ditentukan hanya oleh kataEdge.

Target21: dua source44 menguatkan cross-key audition yang sesuai pedal family. Colombo memakai sample2234 mono/root44/exclusiveClass1 dalam context closed42/pedal44/open46; MELODI memakai sample1491 original44 dalam context yang sama dengan dua relation identik dan tanpa choke eksplisit. Klasifikasi COMPATIBLE kandidat (Colombo menengah–tinggi metadata, MELODI sedang), bukan EXACT. Dua relation MELODI bukan bukti engine membunyikan dua voice; getter actual samplevoice/layercontribution tidak tersedia. t95≈43.4ms/54.8ms tidak membuktikan exact closedchick maupun menyingkirkan splash berdasarkan envelope saja.

Metadata source44 menyediakan zone0–127 untuk seluruh47sourcehits target21 (28/35/36/42). WAV menguji v42 saja; velocity28/35/36 belum diaudition. Production PC0/1/24 same-key21 tetap gap karena mapping tidak diubah.

## Keputusan A–D

A. Belum terbukti satu preset memenuhi ketiga target secara semantik. Secara operasional, kedua preset menyediakan generic hat/pedal/snare melalui cross-key: Colombo42/44/40 dan MELODI42/44/91. Articulationedge16 masih UNKNOWN. Kebutuhan kombinasi lintasfont/preset belum terbukti wajib.

B. Evidence cukup mendukung kemampuan per-note semantic lookup dan cross-key substitution dalam proposal desain. Kit-level fallback saja tidak mewakili perbedaan map ini. Belum cukup memilih production mapping, kit-levelwinner, atau memutuskan fullper-note resolver wajib. Choke grouping, layering, ownership dan velocity behavior perlu dipertahankan dalam rancangan berikutnya.

C. Ya, pedal/foot candidates pada44 lebih relevan secara metadata untuk target21 dibanding memaksa sample same-key21. PCM kini membuktikan jalur diagnostic tersebut menghasilkan sinyal. Itu tidak menetapkan Yamaha exact identity atau winner.

D. Enam audition awal sudah lengkap; tidak perlu meminta ulang WAV yang sama atau puluhan kandidat tambahan. Bukti yang masih kurang untuk final musical selection ialah articulation/provenance untuk16 dan reference Yamaha atau acceptance judgement tester yang eksplisit untuk target16/21/31. Untuk keputusan engineering tentang choke/interplay dan seluruh demandvelocity, single-note/v42 audition belum cukup; jangan mengklaim sudah diuji. Proposal diagnostic tambahan jika diperlukan harus diajukan sebelum implementasi.

Tidak ada EXACT terverifikasi. Tidak ada INCOMPATIBLE yang terbukti baru dari keenam WAV ini; candidate16 tetapUNKNOWN, candidate21/31 tetapCOMPATIBLE family/technique leads. Tidak ada winner atau perubahan resolver.

## Audit trail

- `YamahaArranger_SF2Audition_9e4686e4aa71_B128_PC2_K40_V110_20261002_181126_955_e8056a4c.wav`
  - WAV SHA256 `d46268266d7a1ebb7a70da5d2d5cec629d21ebe5080bace25954ef241823feb7`
  - SF2 fingerprint `9e4686e4aa7173ffd1baf7ec6f80fcb3fe90880e824cf8c4e1defb5d153d47eb`
  - Request 128/2/40/110; eligible relations 1; actual verified in sidecar and all relations match inventory.
- `YamahaArranger_SF2Audition_9e4686e4aa71_B128_PC2_K42_V42_20261002_180718_940_1c082d1b.wav`
  - WAV SHA256 `38ade5d4a08dfc60e0108cbd1e12b037058f96d126ba930d936b17ae17d63a83`
  - SF2 fingerprint `9e4686e4aa7173ffd1baf7ec6f80fcb3fe90880e824cf8c4e1defb5d153d47eb`
  - Request 128/2/42/42; eligible relations 1; actual verified in sidecar and all relations match inventory.
- `YamahaArranger_SF2Audition_9e4686e4aa71_B128_PC2_K44_V42_20261002_180930_579_e13d050f.wav`
  - WAV SHA256 `2048c7601309459e080c7efa180cfa2d99b905fdf436b0036dfb62b8eca1be17`
  - SF2 fingerprint `9e4686e4aa7173ffd1baf7ec6f80fcb3fe90880e824cf8c4e1defb5d153d47eb`
  - Request 128/2/44/42; eligible relations 1; actual verified in sidecar and all relations match inventory.
- `YamahaArranger_SF2Audition_e8c7356159c2_B126_PC35_K42_V42_20261002_180828_350_524bd85a.wav`
  - WAV SHA256 `d5d0fd590a08a391f2264096092d31460756c0081ea952d5f174a9d22271326e`
  - SF2 fingerprint `e8c7356159c200d945f13e113595ff06e7955779b0e9361709d9c7cdba164a82`
  - Request 126/35/42/42; eligible relations 2; actual verified in sidecar and all relations match inventory.
- `YamahaArranger_SF2Audition_e8c7356159c2_B126_PC35_K44_V42_20261002_181004_312_17607f99.wav`
  - WAV SHA256 `48f63254ea48864e3659a9193a6eda48475226140578024de0bdae24960b9c53`
  - SF2 fingerprint `e8c7356159c200d945f13e113595ff06e7955779b0e9361709d9c7cdba164a82`
  - Request 126/35/44/42; eligible relations 2; actual verified in sidecar and all relations match inventory.
- `YamahaArranger_SF2Audition_e8c7356159c2_B126_PC35_K91_V110_20261002_181227_397_31bba6e7.wav`
  - WAV SHA256 `aa01b3713472f977513be3f43537c744cdc4fe2858d74966a0a34c178b3766d0`
  - SF2 fingerprint `e8c7356159c200d945f13e113595ff06e7955779b0e9361709d9c7cdba164a82`
  - Request 126/35/91/110; eligible relations 1; actual verified in sidecar and all relations match inventory.
