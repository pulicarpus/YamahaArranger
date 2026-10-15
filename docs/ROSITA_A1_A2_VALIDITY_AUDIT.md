# ROSITA A1/A2 — Audit Validitas Probe Clipping

| Item | Nilai |
|---|---|
| Bukti | Baseline `Inspector_20261015_213307`, **A1** `…_215009` (hanya Rhythm1/2 aktif), **A2** `…_215719` (Rhythm1/2 di-mute) |
| Kode | `experiment/universal-yamaha-u0-u1` @ `163f769`, read-only |
| Sifat | Read-only; playback, gain, Policy C, dan SF2 tidak diubah; tanpa commit, push, atau build |

Label: **[T]** terbukti dari counter/log/kode; **[K]** indikasi kuat; **[?]** belum diketahui.

---

## 1. Kesimpulan

1. **A1 dan A2 berjalan di proses aplikasi yang sama dengan baseline; app tidak di-restart** [T].
   - `AUDIO_OUTPUT starts=2`, `stops=1`, dan `fallbackCallbacks=2055` identik di ketiga laporan.
   - `callbacks` naik monoton: 38.016 → 229.722 → 310.259.
2. **Peak 4,69848 di A1 dan A2 adalah nilai maksimum yang terbawa dari sebelum baseline**, bukan hasil A1/A2 [T].
   - `HEADROOM_WORST decode=34056 frame=8718080 generation=16` identik di ketiganya.
   - Decode 34056 < 35.961 (jumlah render saat ekspor baseline), jadi terjadi sebelum 21:33:07.
3. **Delta counter bisa dihitung per interval, tetapi interval A1 tidak valid sebagai "drum saja"** [T]:
   - Di interval A1, channel melodik menerima **194 NOTE_ON**.
   - Bass melewati 1 sebanyak 14 kali.
   - ch11 memulai binding baru (Clean yamaha, gen18) pada +949 s dari interval.
4. **Interval A2 valid sebagai "tanpa drum baru"** [T]: ch8/ch9 `attempts +0` selama 429,5 s, dan mute mengirim CC7 = 0. **Clipping tetap terjadi: 5.590 sampel**. Jadi **clipping tidak eksklusif berasal dari drum** [T, dengan catatan keyboard [?]].
5. **Drum sebagai sumber clipping utama: BELUM TERBUKTI.** Probe harus diulang dengan counter yang di-reset atau ditandai per sesi (§5–6).
6. **R2-0 (minimal) harus mendahului aktivasi R2-2.** R2-2 dalam mode *shadow* boleh berjalan paralel karena tidak mengubah suara.

---

## 2. Apakah A1/A2 bisa dipisahkan?

| Dimensi | Temuan | Bisa memisahkan? |
|---|---|---|
| Timestamp ekspor | 21:33:07 → 21:50:09 (1022 s) → 21:57:19 (430 s) | Hanya batas interval, bukan isi |
| Counter sampel | ΔPCM_MIX samples / 2 / 48 kHz = **1022,4 s** dan **429,5 s**. Render berjalan terus (termasuk saat diam), cocok dengan selisih timestamp. | Ya, sebagai interval |
| Peak | Running max process-lifetime | **Tidak.** Peak per interval tidak diketahui. |
| Clipped samples | Kumulatif: Δ A1 = **88.828**; Δ A2 = **5.590** | Ya per interval, tetapi bercampur dengan semua aktivitas di interval itu |
| `HEADROOM_WORST` | Beku di decode 34056 (sebelum baseline) | Tidak |
| Generation mapping | 16 → 21 → 30 | Menunjukkan ada perubahan binding/section di kedua interval |
| `NATIVE attempts` per channel (Δ) | A1: ch8 +56, ch9 +370, **ch10 +23, ch11 +51, ch12 +39, ch13 +12, ch14 +69**. A2: **ch8/9 +0**, ch10 +32, ch11 +60, ch12 +66, ch13 +15, ch14 +48. | Ya: membuktikan isi tiap interval |
| `zeroController` Δ | 0 di semua channel, kedua interval | Nada yang terkirim tidak terkirim saat CC7/CC11 = 0 (konsisten dengan `DROP_MUTED`) |
| `HEADROOM_ROLE` Δ (energi dan over) | A1: Bass RMS −28,9 dBFS, **over +14**. A2: Bass −23,9 dBFS, over +4. | Ya, per peran melodik |
| Jendela `ROLE_PCM` | ch11 w3 (Clean yamaha, gen18) mulai di **A1 +949 s**; ch11 w4/w5 (t4 strings, Honky Tonk) di A2 +416…418 s | Mengonfirmasi melodik aktif di akhir interval A1 |
| Status mute di snapshot | A1: ch10–15 `muted=true`; A2: ch8–9 `muted=true` | Hanya keadaan **saat ekspor**, bukan sepanjang interval |

**Kesimpulan validitas:**
- **A1 tidak valid** sebagai isolasi drum. Melodik berbunyi di dalam interval; diperkirakan mute diaktifkan setelah sebagian interval berjalan atau di-toggle.
- Sebagai pembanding: tingkat clip mix penuh di baseline adalah 0,43%. Hanya sekitar 3 menit mix penuh di dalam interval 17 menit sudah cukup menjelaskan Δ 88.828 tanpa kontribusi drum.
- **A2 valid sebagian:** bebas drum baru selama seluruh interval, tetapi peak interval dan aktivitas keyboard tidak diketahui.

---

## 3. Apakah mute benar-benar menghentikan audio?

| Jalur | Mekanisme di kode | Status |
|---|---|---|
| NOTE_ON baru dari style | `StyleSequencer.kt:1164` `if(channelOverride?.muted==true) { diagnostic("DROP_MUTED"); return }` | [T]. Data A2: ch8/9 attempts +0. |
| Nada yang sedang berbunyi | `setChannelMute` → `applyEffectiveMixer` → `audioEngine.setChannelMixer(channel, 0, …)` (`:842`) → `MIDI_EVENT_VOLUME 0` | [T] di kode; senyap seketika [K] |
| Drum jalur legacy (ch8/9 di stream utama) | Sama: CC7 = 0 di channel BASSMIDI 8/9. Lane kompatibel tidak aktif (`lane8/9 samples=0`). | [K] senyap |
| Lane perkusi kompatibel (bila aktif di masa depan) | Stream terpisah; perlu dicek apakah ikut menerima mute | [?] |
| FX tail (reverb/chorus bersama) | CC91/CC93 tidak diubah oleh mute. Apakah send BASSMIDI dihitung setelah CC7 (post-fader)? | [?]: perlu meter FX atau sesi A3 (send = 0) |
| Section setup channel yang di-mute | `:764` channel yang di-mute dilewati saat setup preset; `:772` volume 0 | [T] |
| Keyboard ch0–3 | Tidak dipengaruhi mute style; Inspector tidak punya counter keyboard | [?] |
| MIDI OUT E343 | `syncMixerMidiMute` terpisah; tidak dianalisis dan tidak diubah | – |

---

## 4. Temuan terbukti vs belum terbukti

| # | Pernyataan | Status |
|---|---|---|
| F1 | Ketiga laporan berasal dari satu proses; counter kumulatif | **[T]** |
| F2 | Peak 4,698 dan `HEADROOM_WORST` di A1/A2 berasal dari sebelum baseline | **[T]** |
| F3 | Interval A1 berisi aktivitas melodik (194 NOTE_ON; bass over +14) | **[T]** |
| F4 | Interval A2 tidak berisi NOTE_ON drum | **[T]** |
| F5 | Clipping terjadi tanpa drum baru (A2 Δclipped = 5.590; clip rate 0,0136%) | **[T]** (asumsi keyboard tidak dimainkan, [?]) |
| F6 | Bass sendiri melewati 1 di kedua interval (over +14 dan +4) | **[T]** |
| F7 | Clip rate interval A1 (0,0905%) 6,6× lebih tinggi dari A2 | [T] angka; **tidak bisa** diatribusikan ke drum (F3) |
| F8 | Drum Prime adalah sumber utama peak 4,7 | **Belum terbukti** |
| F9 | Mute menghentikan FX tail | **Belum terbukti** |
| F10 | Keyboard tidak berkontribusi | **Belum terbukti** |

Koreksi atas `ROSITA_INSPECTOR_ROOT_CAUSE_REPORT.md` §3.2: kalimat "kandidat utama: drum" tetap berstatus [K], belum [T]. Clipping juga sudah terbukti terjadi tanpa drum (F5).

---

## 5. Probe ulang tanpa perubahan kode (bisa dilakukan sekarang)

Kunci validitasnya adalah **restart proses**, karena counter bersifat process-lifetime.

1. Pengaturan Android → Aplikasi → YamahaArranger → **Force stop**. Buka app, tunggu SF2 selesai dimuat, lalu muat ROSITA dengan mode INTERNAL.
2. **Atur mute sebelum menekan START.** Jangan menyentuh keyboard. Jangan mengubah mute selama sesi.
3. Mainkan urutan yang sama untuk semua sesi: Main A 8 bar → Main D 8 bar → STOP. Tunggu 5 detik, lalu ekspor Inspector.
4. Sesi:

   | Sesi | Konfigurasi |
   |---|---|
   | S0 | Semua part aktif |
   | S1 | Hanya Rhythm1/2 |
   | S2 | Rhythm1/2 di-mute |
   | S3 | Sama dengan S2, tetapi Reverb/Chorus semua part = 0 |

   Force stop sebelum setiap sesi.

Gate validitas per laporan (semua harus terpenuhi):
- `AUDIO_OUTPUT callbacks` kecil (sesi baru). `HEADROOM_WORST decode` < `HEADROOM_SCOPE renders` dari laporan yang sama.
- Untuk S1: `NATIVE attempts` ch10–15 = 0. Untuk S2/S3: ch8–9 = 0.
- `VOICE muted` sesuai rencana.
- Jumlah `STYLE_VELOCITY count` per channel sebanding antar sesi (urutan section sama).

Interpretasi:

| Hasil | Arti |
|---|---|
| Peak S1 ≫ 1 dan peak S2 ≈ peak bass | Drum adalah penyumbang puncak |
| Peak S2 ≫ 1,2 | Puncak dari bass + gitar + FX; bandingkan dengan S3 |
| S3 jauh lebih rendah dari S2 | Peran FX terbukti |

---

## 6. Spesifikasi diagnostik minimal untuk Codex (R2-0, observasi saja)

Tujuan: membuat perbandingan sesi valid **tanpa restart** dan tanpa mengubah suara. Tanpa `BASS_MIDI_StreamGetChannel` untuk Rhythm/keyboard (lihat komentar kode `bassmidi_player.cpp` ~2577: GetChannel mengubah semantik FX per-key drum).

### D1. Penanda sesi PCM (wajib, terkecil)

- Tombol Inspector **"MARK PCM SESSION"** (debug). Dipanggil dari UI thread lewat JNI. Native mencatat snapshot berikut di bawah mutex yang sama dengan export:
  - `sessionId`, `markFrame` (= `pcmSamples_/2`), timestamp.
  - Seluruh counter kumulatif: `PCM_MIX` samples/energi/clipped, `HEADROOM` per stage dan per role, `NATIVE` attempts/sent per ch0–15, `STYLE_VELOCITY` count.
  - Status mute dan CC91/CC93 per channel saat mark.
- Akumulator baru **`peakSinceMark`** dan **`clippedSinceMark`** di jalur `HEADROOM`. Ini satu perbandingan float per sampel di loop yang sudah ada; tidak ada alokasi dan tidak ada tulis ke buffer audio.
- Export menambah blok berikut (delta = sekarang − snapshot):

  ```
  PCM_SESSION id=… markFrame=… frames=… seconds=… peak=… clipped=… rms=…
              noteOnDelta[ch0..15]=… keyboardNoteOnDelta=… muteAtMark=[…] muteNow=[…]
              muteChangesSinceMark=… fxSendNonZeroChannels=[…] worstFrame=… worstGeneration=…
  ```

- `muteChangesSinceMark` dihitung dari `setChannelMute` (counter di Kotlin, dikirim ke native atau diekspor di sisi Kotlin).

### D2. Counter keyboard (wajib)

`keyboardNoteOnSent[ch0..3]` di jalur NOTE_ON keyboard (counter integer saja). Ikut masuk ke delta sesi.

### D3. Meter Rhythm1/Rhythm2 tanpa GetChannel (opsional, bila D1 belum cukup)

Atribusi dilakukan lewat isolasi MIDI (mute), bukan tap per channel. D1 + mute sudah memberi "peak drum-only" yang valid. **Tidak** menambah tap pada ch8/9 dan ch0–3.

### D4. Meter FX bersama (opsional)

**[?] API:** pastikan di header BASSMIDI asli (repo hanya memuat mock) apakah tersedia handle kanal efek reverb/chorus untuk tap READONLY. Bila tidak tersedia, gunakan sesi S3 (send = 0) sebagai pembanding. Jangan menambah stream baru.

### D5. Label yang menyesatkan

- Tandai blok `=== STYLE / RESOLVER ===` dengan `legacy_ui_map_not_engine_binding`.
- Tambahkan di header export: `counters=process_lifetime; use PCM_SESSION for per-test deltas`.

### Gate R2-0

| Gate | Syarat |
|---|---|
| G1 | Host test: akumulator `peakSinceMark` = max dari buffer sintetis setelah mark; delta counter benar; mark dua kali → sesi baru |
| G2 | Recorder MIDI OUT E343 byte-identik; tidak ada event MIDI baru dari mark/export |
| G3 | Render identik: PCM keluaran sebelum/sesudah patch sama bit-per-bit pada fixture host. Tidak ada tulis ke buffer. |
| G4 | LIVE CONTROL, REG MEMORY, RELEASE, RIGHT OFF, dan tes PCM existing hijau |
| G5 | Overhead render < 1% (ukur callback time di perangkat) |

---

## 7. Acceptance gate perbandingan A1 vs A2 (dengan D1, atau dengan force stop)

Sebuah pasangan sesi dinyatakan **VALID** bila:

1. Setiap sesi punya `peak`/`clipped`/`rms` **sejak mark** (atau sejak proses baru), bukan process-lifetime.
2. `muteChangesSinceMark = 0` dan `muteAtMark = muteNow` sesuai rencana sesi.
3. NOTE_ON delta channel yang di-mute = 0. Channel aktif > 0. `keyboardNoteOnDelta = 0`.
4. Urutan dan durasi section sama (± 10% frames). `STYLE_VELOCITY` delta per channel aktif sebanding (± 10%).
5. Mark diambil ≥ 3 s setelah perubahan mute terakhir, saat transport STOP (tidak ada tail dari konfigurasi sebelumnya).
6. Binding identik antar sesi (preset per channel sama; `mappingGeneration` delta hanya dari section yang sama).
7. Status FX send dicatat. Sesi S3 hanya berbeda pada send.

Bila salah satu gagal → **INVALID**, dan hasilnya tidak boleh dipakai untuk atribusi.

---

## 8. GO/NO-GO tahap berikutnya

| Item | Keputusan | Alasan |
|---|---|---|
| Ulang probe S0–S3 dengan force stop (tanpa kode) | **GO sekarang** | Satu-satunya cara valid tanpa patch |
| R2-0 minimal (D1 + D2 + D5) | **GO** | Observasi saja; membuat sesi dan atribusi valid tanpa restart |
| D3 tap Rhythm/keyboard via GetChannel | **NO-GO** | Mengubah semantik FX drum (komentar kode) |
| D4 tap FX | **GO bersyarat** | Hanya bila API resmi tersedia dan READONLY |
| R2-2 DrumKitResolver mode shadow | **GO** (paralel) | Tidak mengubah suara |
| R2-2 aktif (mengubah kit) | **NO-GO sampai** S0–S3 valid atau R2-0 menunjukkan kontribusi drum | Perlu baseline terukur untuk membuktikan perbaikan |
| Klaim "drum adalah sumber clipping" | **NO-GO** | F8 belum terbukti; F5 membuktikan ada clipping tanpa drum |
| Limiter / normalisasi / turun gain global | **NO-GO** | Tidak berubah |

---

## Lampiran — Delta per interval (dihitung dari counter kumulatif)

| Interval | Δ waktu | RMS interval | Δ clipped | Clip rate |
|---|---|---|---|---|
| A1 (21:33:07 → 21:50:09) | 1022,4 s | −23,1 dBFS | 88.828 | 0,0905% |
| A2 (21:50:09 → 21:57:19) | 429,5 s | −23,4 dBFS | 5.590 | 0,0136% |

Δ HEADROOM_ROLE (RMS interval termasuk diam / Δ over):

| Ch | Peran | A1 RMS | A1 over | A2 RMS | A2 over |
|---|---|---|---|---|---|
| ch10 | Bass | −28,9 | +14 | −23,9 | +4 |
| ch11 | Chord1 | −53,0 | 0 | −49,9 | 0 |
| ch12 | Chord2 | −49,2 | 0 | −46,2 | 0 |
| ch13 | Pad | −60,0 | 0 | −57,8 | 0 |
| ch14 | Phrase1 | −53,2 | 0 | −51,3 | 0 |
| ch15 | Phrase2 | tidak aktif | 0 | tidak aktif | 0 |

Skrip perhitungan: `delta.py` (scratch). Sumber baris: `AUDIO_OUTPUT`, `PCM_MIX`, `HEADROOM_*`, `NATIVE`, `ROLE_PCM`, `VOICE` di ketiga laporan.
