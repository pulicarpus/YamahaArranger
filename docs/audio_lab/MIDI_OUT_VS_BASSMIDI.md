# Investigasi RIGHT PSR-E343: MIDI OUT vs BASSMIDI (revisi 2)

Branch `investigate/midi-out-vs-bassmidi`. Tidak ada perubahan kode produksi, tidak ada APK, tidak ada merge.
Setiap klaim diberi label sumber: **[KODE]** dibaca dari kode, **[LAB]** diukur di Offline Audio Lab (BASSMIDI Linux asli + SF2 Anda, CI), **[PERANGKAT]** diamati/diukur di perangkat nyata, **[HIPOTESIS]** belum terbukti.

## 0. Koreksi: hipotesis Local ON / double trigger DIBATALKAN
[PERANGKAT] Pengguna: Local = OFF. Local OFF + MIDI OUT app OFF -> E343 diam. Local OFF + MIDI OUT ON -> E343 berbunyi normal dan responsif; tidak ada pengaturan E343 yang berubah.
Maka seluruh suara E343 pada pengujian itu berasal dari note yang dikirim app lewat USB. Hipotesis "suara lokal" dan "gema/double trigger" tidak berlaku dan tidak dipakai lagi. Setup ini justru isolasi yang bersih: Local OFF + MIDI OUT OFF = hanya BASSMIDI; Local OFF + MIDI OUT ON + RIGHT BASSMIDI di-mute = hanya tone generator (TG).

## 1. Fakta dari kode [KODE]
- Cabang: `ArrangerBrain.startRightNote`, per layer RIGHT (ch 0..2): `engineNoteOn` (BASSMIDI) lalu `midiInputManager.sendNoteOn` (USB), satu thread, berurutan. Karena MIDI OUT dikirim SETELAH panggilan BASSMIDI, blocking di sisi BASSMIDI akan ikut menunda MIDI OUT (yang terdengar responsif) -> blocking besar bukan penyebab dominan.
- Data identik di kedua jalur: note, velocity (tanpa kurva), channel, durasi/sustain, CC7=127, CC11=100xmaster, CC10=64, CC91=40, CC93=0, release 64. CC71/74/73/1 tidak pernah dikirim. Tidak ada Bank/Program otomatis ke E343.
- BASSMIDI: stream decode 48 kHz float (NOTEOFF1, SRC 2, 1000 voice, FONTEX2 satu-channel eksak). NOTE_ON dikirim dengan `BASS_MIDI_StreamEvent` di bawah `mutex_`; tidak ada ASYNC. PCM ditarik `BASS_ChannelGetData` di callback Oboe di bawah mutex yang sama, lalu clamp +-1.
- Oboe: diminta LowLatency + Exclusive, fallback None + Shared; Float, stereo, 48 kHz, Usage Media, ContentType Music; buffer diset 2 x burst.
- NORMAL mode (`NoteLogTuning.lean`) ada di branch `lab/offline-audio`, belum ada di APK yang pernah dites pengguna.

## 2. Pengukuran Offline Audio Lab [LAB] (run 39084812276)
Mikrodetik dari runner x86: hanya bukti RELATIF, bukan angka Redmi Pad.

**E1 - NOTE_ON -> sampel PCM pertama, tergantung ukuran blok render?** Blok 32/64/128/256/512/1024 frame menghasilkan frame pertama IDENTIK (selisih 0 frame) pada 12 preset x 2 velocity. Jadi BASSMIDI menerapkan event tepat di awal blok yang memakainya; engine tidak menambah penundaan sendiri. Satu-satunya penundaan engine = menunggu awal blok callback berikutnya (0..5.3 ms pada blok 256, rata-rata ~2.7 ms; cocok dengan 2.26 ms yang diamati di perangkat).

**Onset bawaan preset** (frame pertama di atas -60 dBFS / waktu mencapai -20 dB dan -10 dB puncak, velocity 100, C4):
| Preset | Colombo | Tyros 4 |
|---|---|---|
| Grand Piano | 0.35 ms / 1.0 / 3.3 ms | 3.7 ms / 7.2 / 8.8 ms |
| E.Piano | 0.1 ms / 0.3 / 0.6 | 15.6 ms / 15.9 / 16.4 |
| Steel Guitar | 0.04 ms / 0.6 / 2.7 | 3.1 ms / 4.9 / 8.4 |
| Strings | 0.3 ms / 5.9 / 17.4 | 0 ms / 24.7 / 92 |
| Organ | 0.4 ms / 6.1 / 10.6 | 6.9 ms / 10.5 / 12.7 |
| Brass | 2.0 ms / 9.0 / 13.0 | 0.6 ms / 16.4 / 24.7 |
Onset tidak bergantung pada velocity. Pada Tyros, piano/E.Piano/organ punya keterlambatan bawaan 4-16 ms sebelum suara muncul; pada Colombo piano/E.Piano/gitar < 1 ms.

**E2 - biaya render 256 frame** (x86): idle ~0 us, 1 nada 20 us, blok yang memuat NOTE_ON 27-32 us, 16 voice 51-65 us, 48 voice 111-115 us (maks 209 us) dari anggaran 5333 us. Engine tidak punya biaya blok yang bisa menunda NOTE_ON secara berarti pada preset yang sudah di-preload.

**E3 - dingin vs preload:** blok pertama dengan NOTE_ON pada preset yang belum di-preload 1.1 ms (konstan di 12 kombinasi) vs 40-90 us setelah `BASS_MIDI_FontLoad`. Aplikasi sudah preload preset keyboard, jadi ini hanya pembenaran preload, bukan temuan baru; di ARM/UFS angka dingin akan lebih besar.

**Dari tabel lama (T3, T7, T9-T11):**
- Level: RIGHT app -5.0 dB vs netral; puncak satu nada -18.8 (Colombo) / -22.8 (Tyros) dBFS.
- Velocity: v45->v100 = +13.9 dB di hampir semua preset (kurva SF2 standar, sama dengan FluidR3). Timbre ikut berubah hanya di Colombo piano/bright/steel/E.Piano2 (jenuh di v>=90). Tyros dan FluidR3 piano: nol kopling.
- Punch pada level disamakan (T11, puncak 30 ms terhadap RMS 100-500 ms): grand piano Colombo 15.7 dB, Tyros 12.4, FluidR3 10.3; steel 15.6 / 14.4 / 14.1. Jadi "kurang menghentak" TIDAK didukung oleh bentuk serangan Colombo; pada Tyros E.Piano dan organ serangan lebih lambat.
- Energi >4 kHz serangan grand: FluidR3 3.33%, Colombo 0.13%, Tyros 0.10%.
- Pemutar produksi == BASSMIDI mentah (LSD 0.90 dB); tidak ada filter, gain, atau kontroler yang menggelapkan.

## 3. Pengamatan perangkat [PERANGKAT]
Dari laporan Redmi Pad yang sudah ada (APK dengan log aktif):
| Tahap | Nilai rata-rata | Catatan |
|---|---|---|
| USB -> onSend | 0.69 ms | |
| onSend -> semua layer di native | 2.83 ms | ~1.35 ms di antaranya dua baris log |
| NOTE_ON antre -> awal render | 2.26 ms | sesuai setengah periode blok |
| awal render -> buffer siap | ~1.0 ms | |
| Total sampai PCM diserahkan ke Oboe | sekitar 6-7 ms | rata-rata, bukan batas atas |
| Oboe: AAudio Shared + LowLatency, burst 256, buffer 512 (10.7 ms), 13 XRUN; 1 burst tidak stabil | | |
| `calculateLatencyMillis` | 18.8 ms pada satu sesi, "unavailable (ErrorInvalidState)" pada sesi lain | ESTIMASI Oboe, bukan pengukuran akustik; tidak dipakai sebagai kesimpulan |
| `renderLockWait` > 2 ms 212 kali (maks 10.8 ms) | penyebab belum diatribusikan | statistik hold ada di branch lab, belum pernah dijalankan di perangkat |
Tidak ada pengukuran waktu fisik dari tombol ke telinga untuk kedua jalur.

## 4. Jalur waktu NOTE_ON -> PCM -> output
1. USB -> `onNoteOn` (0.69 ms, perangkat).
2. `keyboardLock`, log, JNI -> `noteOn` di bawah `mutex_` (2.83 ms perangkat, ~1.35 ms log).
3. Event antre di BASSMIDI; menunggu awal blok render berikutnya (0..5.3 ms, rata-rata 2.3-2.7 ms).
4. Pada blok itu, sampel pertama sesuai onset preset (Colombo piano < 1 ms, Tyros 4-16 ms) - bukan penundaan engine, tetapi isi sampel/envelope SF2.
5. PCM diserahkan ke Oboe; sisa (buffer Oboe, HAL Android, DAC, kabel AUX, penguat E343) belum terukur.
Jalur MIDI OUT melewati tahap 1-2 (log juga) lalu satu tulis USB ke E343; tahap 3-5 tidak ada. TG E343 punya latensi sendiri yang tidak diketahui.

## 5. Kandidat penyebab (bukti mendukung / menentang / eksperimen / dampak / risiko)
| # | Kandidat | Mendukung | Menentang | Eksperimen pembeda | Dampak | Risiko |
|---|---|---|---|---|---|---|
| 1 | Latensi akhir jalur BASSMIDI lebih besar dari TG (buffer Oboe + HAL + AUX) | Pembanding responsif TG tidak punya tahap 3-5; "kurang responsif" konsisten | Besarnya tidak terukur; estimasi Oboe tidak konsisten; MIDI OUT juga ikut log | Rekam satu mikrofon, dua kondisi (lihat 7) | Tinggi bagi rasa main | Tinggi jika buffer diperkecil (1 burst tidak stabil) |
| 2 | Karakter jalur AUX/penguat/speaker E343 untuk sumber eksternal (TG biasanya melewati EQ/DSP internal; AUX tidak) | Dua jalur memakai penguat yang sama tetapi titik masuk berbeda | Tidak ada data | Mainkan rekaman piano pembanding lewat AUX vs TG, tanpa app | Tinggi bagi "tertutup" | Tidak ada (tanpa kode) |
| 3 | Isi SF2: Tyros piano/E.Piano/organ lambat 4-16 ms, gelap, tanpa kopling velocity; Colombo piano gelap di velocity rendah | T3, T7, T9, T10 | Colombo piano onset < 1 ms dan punch level-sama tinggi; FluidR3 juga datar terhadap velocity | Bandingkan preset RIGHT: Colombo vs Tyros pada keyboard yang sama (lewat mixer, tanpa Program Change ke E343) | Sedang-tinggi, tergantung preset RIGHT yang dipakai | Rendah-sedang (pilihan preset, bukan engine) |
| 4 | Level -5 dB dan puncak -19 dBFS vs TG | Terukur; level lebih tinggi dipersepsi lebih tajam | Level tidak mengubah timbre/onset | A/B level sama (lab sudah memisahkan) | Sedang | Rendah; headroom 7.6-12.6 dB |
| 5 | Kecepatan velocity E343 -> SF2: kurva standar SF2 dan velocity masuk rendah-sedang | T9/T10 | Velocity yang masuk ke TG sama persis | Histogram velocity RIGHT (sudah ada di app, sekali baca) | Sedang | Sedang jika membuat kurva |
| 6 | Blocking/kontensi `mutex_` atau log di thread MIDI | 212 tunggu > 2 ms; log 1.35 ms/nada; skew akor hingga ~8 ms | Jika dominan, MIDI OUT (dikirim sesudahnya) juga terasa lambat | NORMAL mode sudah ada; ukur ulang | Kecil-sedang (~2 ms/nada) | Rendah (sudah diuji regresi) |
| 7 | Konfigurasi engine BASSMIDI (SRC, voice, kontroler) | - | LAB: pemutar == mentah, kontroler netral | sudah | Nol | - |
| 8 | Efek audio Android | Tidak bisa dibantah | Tidak ada bukti | Bandingkan stream non-low-latency | Tak diketahui | - |

## 6. Terbukti vs hipotesis
**Terbukti:** data MIDI identik; engine BASSMIDI tidak menambah penundaan sampel (E1); biaya render tidak berarti (E2); onset bawaan preset di atas; pemutar == BASSMIDI mentah; software sampai Oboe ~6-7 ms rata-rata di perangkat; Local OFF mengisolasi TG.
**Hipotesis:** latensi fisik akhir jalur BASSMIDI; perlakuan EQ/DSP pada TG vs AUX; Tyros dipakai di RIGHT pengguna; pengaruh efek Android.

## 7. Keputusan
**NO-GO** untuk patch pengubah suara dan APK baru. Tidak ada pengukuran yang menunjuk satu penyebab terkoreksi; satu-satunya perubahan dengan bukti terukur (NORMAL log mode, ~1.3-2 ms/nada dan skew akor lebih kecil, tanpa perubahan suara) terlalu kecil untuk menjelaskan keluhan dan sudah siap ikut APK berikutnya bila ada.

**Pengukuran yang menentukan (bukan log, tanpa APK):**
1. Isolasi akustik: Local OFF. Rekam dengan satu ponsel di posisi tetap dekat speaker E343: (a) MIDI OUT OFF (hanya BASSMIDI), (b) MIDI OUT ON dan RIGHT BASSMIDI di-mute di mixer (hanya TG), nada staccato yang sama. Selisih waktu dari bunyi tuts mekanis ke onset suara di kedua rekaman memisahkan latensi (kandidat 1) dari karakter (kandidat 2-4). Pada pembagian ini: selisih > ~10 ms -> jalur output; selisih kecil tetapi tetap "tertutup" -> kandidat 2/3.
2. Rekaman pembanding lewat AUX: putar rekaman piano/gitar yang dikenal baik dari Redmi Pad lewat AUX. Jika juga terdengar "tertutup" dibanding TG, penyebab ada di jalur AUX/E343, bukan app.
3. Hanya jika (1) menunjukkan selisih besar: APK kecil yang membaca `getTimestamp` Oboe (latensi yang dilaporkan perangkat, bukan estimasi) sebelum menyentuh buffer.
