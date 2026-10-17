# Investigasi akar masalah RIGHT PSR-E343: MIDI OUT vs BASSMIDI

Branch `investigate/midi-out-vs-bassmidi` (dari `lab/offline-audio`). Tidak ada perubahan kode produksi, tidak ada APK, tidak ada merge.
Bukti: pembacaan kode (K) dan lab BASSMIDI nyata di CI, run 39051098282 (T9-T11 baru). Perangkat keras E343/Redmi tidak dapat diukur di cloud.

## 1. Tabel jalur data (dari kode)

| Aspek | MIDI OUT -> E343 | BASSMIDI -> Oboe -> AUX |
|---|---|---|
| Titik cabang | `ArrangerBrain.startRightNote`, loop channel 0..2, satu thread, berurutan | sama |
| Urutan | `engineNoteOn` (antre ke BASSMIDI) lalu `sendNoteOn` (tulis USB langsung) | |
| Note | `outputNote` (+transpose) | sama |
| Velocity | `RightVelocityTest.midiOutVelocity127(v)` = v apa adanya | `v/127f` -> `lround*127`, clamp 1..127 = sama |
| Channel | ch1..3 (idx 0..2); keyboard E343 sendiri mengirim di ch1 (`chordInputChannel=0`) | idx 0..2 pada BASSMIDI |
| Bank/Program | TIDAK dikirim otomatis (E343_NO_AUTO_KEYBOARD_BANK_PC); suara = panel E343 | preset SF2 pilihan app (default "Piano" 0/0) |
| CC7 | 127 dikirim setelah `setChannelMixer` pertama | 127 |
| CC11 | 100 x master/100 | 100 |
| CC10 / CC91 / CC93 | 64 / 40 / 0 dikirim ke E343 | 64 / 40 / 0 (reverb BASSMIDI) |
| CC72 | release 64 | release 64 |
| CC64 sustain | tidak diteruskan; note-off ditunda app sama seperti BASSMIDI | sama |
| CC71/74/73/1 | tidak pernah dikirim | tidak pernah dikirim |
| Note-off/durasi | sama (`releaseKeyboardNote`) | sama |
| Antrean | `MidiMixerOutput` sinkron, tulis `MidiInputPort.send` | decode stream ditarik callback Oboe |
| Latensi perangkat lunak | ~0 (satu write USB) | 6-9 ms app + buffer Oboe + AUX (output latency tidak terukur) |
| Level | di generator nada E343 (CC7 127 = di atas default 100) | -5 dB vs netral (CC11 100 + font 0.9), puncak 1 nada -19 dBFS |

Kesimpulan jalur: velocity, note, channel, durasi, dan CC IDENTIK. Tidak ada transformasi data tambahan di sisi BASSMIDI. Yang berbeda hanya pembangkit nada, level, dan latensi.

## 2. Temuan kunci dari kode (belum pernah diperhitungkan sebelumnya)

1. **App tidak pernah menyentuh Local Control E343** (grep: tidak ada CC122). Saat tuts ditekan, E343 sudah memainkan suara panelnya sendiri secara lokal, tanpa latensi, apa pun status MIDI OUT. Karena MIDI OUT mengirim balik note ke ch1 (channel yang sama dengan keyboard E343), kemungkinan terjadi suara lokal + gema (double trigger) pada voice yang sama. [K dari kode; perilaku E343 = hipotesis]
2. Maka "suara MIDI OUT yang tajam dan responsif" kemungkinan besar adalah **voice panel E343 (AWM hardware, latensi nol, kurva velocity bawaan Yamaha)**, bukan hasil jalur app. Pembanding itu bukan SF2 yang setara.
3. App juga mengirim CC7=127, CC91=40, CC93=0, CC10=64 ke E343 ch1-3. Itu menaikkan level bagian (default GM 100) sementara BASSMIDI justru -5 dB. Selisih level kedua jalur dari konfigurasi saja bisa >=5-9 dB. Level lebih tinggi dipersepsi lebih "tajam".
4. Panel E343 memilih voice sendiri; app memilih preset SF2 sendiri (default "Piano"). Tidak ada jaminan instrumen sama.

## 3. Pisahkan masalah (data lab)

| Dimensi | Bukti | Status |
|---|---|---|
| Kenyaringan | RIGHT app -5.0 dB vs netral; 1 nada puncak -18.8 (Colombo) / -22.8 (Tyros) dBFS; headroom tumpukan 4 nada 12.6 dB | terukur |
| Timbre | Colombo piano/steel kontras velocity: centroid +0.63/+1.25 oct dari v45 ke v100, jenuh v>=90; Tyros dan FluidR3 piano: 0 (hanya level). Energi >4 kHz grand: FluidR3 3.33%, Colombo 0.13%, Tyros 0.10% | terukur |
| Serangan | -10 dB: Colombo grand 3 ms, Tyros 10.5 ms, E.Piano 18.5 ms, strings 80.5 ms. Punch level-sama (T11): Colombo grand 15.7 dB, Tyros 12.4, FluidR3 10.3 | terukur |
| Respons velocity | v45->v100 = +13.9 dB di hampir semua preset (kurva SF2 standar, sama dengan FluidR3) | terukur |
| Latensi input | USB->app 0.69 ms | terukur (perangkat) |
| Latensi output | Oboe: Shared+LowLatency, burst 256; estimasi `calculateLatencyMillis` tidak pernah terekam di laporan; E343 lokal = ~0 | TIDAK terukur |

Skenario sentuhan E343 (T10, bobot ASUMSI): Colombo grand pada sentuhan lembut centroid 616 Hz, >4k 0.01%; pada keras 845 Hz. Jadi pada sentuhan lembut-sedang Colombo piano memang tertutup (SF2), tetapi FluidR3 pun tidak jauh lebih cerah di bawah 4 kHz.

## 4. Matriks kandidat penyebab

| # | Kandidat | Bukti pendukung | Bukti penentang | Eksperimen pembeda | Dampak musikal | Risiko regresi |
|---|---|---|---|---|---|---|
| A | Pembanding = suara lokal E343 (+gema), bukan jalur MIDI OUT app | Tidak ada kontrol Local; note dikirim balik ke ch1; latensi nol | Perilaku E343 belum diamati | Tanpa APK: Local Control OFF di E343 lalu bandingkan MIDI OUT ON vs BASSMIDI saja | Menjelaskan "punchy + responsif" sepenuhnya | Tidak ada (tanpa kode) |
| B | Perbedaan latensi output (Oboe+AUX vs 0) | Terasa "kurang natural/responsif" | Perangkat lunak 6-9 ms; buffer sudah di-A/B | Ukur: ketuk tuts, rekam AUX vs lokal; atau catat `calculateLatencyMillis` | Tinggi untuk rasa main | Tinggi bila ubah buffer (sudah tidak stabil di 1 burst) |
| C | Level -5 dB + puncak -19 dBFS vs E343 CC7 127 | Terhitung, terukur | Level tidak mengubah timbre | A/B level-sama (WAV T8/T11) | Sedang ("lemah") | Rendah (CC11 127 = +5 dB, headroom 7.6 dB) |
| D | Isi SF2 (Colombo piano gelap di velocity rendah, Tyros lambat/gelap) | T3, T7, T9, T10 | FluidR3 melalui rantai sama juga tidak punya kontras velocity pada piano | Pilih preset lain / FluidR3 di RIGHT dan dengarkan | Tinggi untuk timbre | Sedang (pilihan preset, bukan engine) |
| E | Konfigurasi engine BASSMIDI | Player == raw (LSD 0.90 dB) | Semua kontrol netral; SRC tidak mengubah | sudah diuji | Nol | - |
| F | Efek audio Android / karakter AUX | Tidak dapat dibantah | Tidak ada bukti | Dengar WAV lab lewat media player vs app (tanpa log) | Tak diketahui | - |
| G | Pembandingan tidak terisolasi (dua sumber terdengar bersamaan) | pengguna menurunkan volume BASSMIDI | | Mute RIGHT BASSMIDI di mixer app lalu dengar | Menentukan | Tidak ada |

## 5. Yang terbukti / hipotesis

Terbukti (kode atau terukur): data MIDI identik antar jalur; tidak ada Local Control di app; MIDI OUT mengirim CC7=127 CC11=100 CC91=40 CC93=0 CC10=64 CC72=64 tanpa Bank/PC; BASSMIDI RIGHT -5 dB vs netral; SF2 Colombo/Tyros properti timbre/onset di atas; player == BASSMIDI mentah.
Hipotesis: suara "MIDI OUT" adalah suara lokal E343 (atau lokal+gema); latensi output Oboe/AUX membuat rasa main kurang responsif; efek Android.

## 6. Patch minimal?

Tidak ada kandidat berbukti. Satu-satunya perubahan murah (RIGHT CC11 127, +5 dB) hanya mengubah level, tidak respons/timbre, dan belum ada bukti ia memperbaiki. Tidak dibuat APK. Tes tanpa kode (urut): G (mute RIGHT BASSMIDI, dengar E343 saja), lalu A (Local Control OFF), lalu B (ketuk-rekam).
