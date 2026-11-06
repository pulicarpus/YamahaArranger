# FunkPopRock S542: audit akar masalah timing lintas output (BASSMIDI dan MIDI OUT)

> **Pembaruan 2026-11-06 (data perangkat):** lihat `diagnostics/FUNKPOPROCK_DEVICE_LATENCY_EVIDENCE.md`. H1 terukur besar di perangkat, H2 gugur, dan ditemukan H6: log diagnostik di dalam mutex synth.


**Status: ROOT CAUSE NOT YET PROVEN.**
- Simulasi host menyingkirkan beberapa penyebab: scheduler, CASM, parser, dan transisi Fill.
- Mekanisme kopling antar-output terbukti di kode, tetapi besarnya efek di perangkat belum terukur.
- Tidak ada patch produksi. P1 tidak diubah. Tidak ada APK, workflow, atau merge.

**Label bukti:**
- **CODE**: dibaca di sumber, dengan path:baris.
- **HOST**: rantai Kotlin produksi di JVM x86 (RtRig: port USB palsu, bridge native tiruan). Tidak mengatakan apa pun tentang kecepatan Redmi Pad.
- **MODEL**: biaya per panggilan native/USB yang *dipilih* untuk simulasi, bukan diukur.
- **PHYSICAL**: observasi pengguna.
- **HYPOTHESIS**: dugaan yang belum dibuktikan.

## 1. Executive summary

1. **Scheduler tidak drift (CODE + HOST).**
   - Setiap event dijadwalkan terhadap jam absolut, bukan delay relatif yang menumpuk.
   - Tanpa biaya output (sink), lateness drum terhadap grid ideal: p50 ≈ 0 ms, p95 ≤ 2,4 ms, maks 2–4 ms.
   - Lateness itu **sama** untuk drum saja, drum + bass, drum + chord, drum + pad/phrase, dan semua channel.
   - Rata-rata lateness per bagian (Main C bar 0, bar 1, Fill DD, Main D) datar, jadi tidak ada akumulasi dan tidak ada pergeseran timeline di batas section.
   - **Gejala "drum + satu pengiring jadi tersendat" tidak bisa direproduksi di host.**
2. **Kedua output tidak independen (CODE).**
   - Satu thread scheduler (`Dispatchers.Default`) menjalankan secara serial, per event: CASM → NOTE ON BASSMIDI lewat JNI → NOTE ON MIDI OUT lewat `MidiInputPort.send`.
   - NOTE ON BASSMIDI menunggu mutex synth yang **dipegang `render()` sepanjang `BASS_ChannelGetData`** (seluruh sintesis satu buffer).
   - Mode OUTPUT **MIDI OUT tetap merender BASSMIDI penuh** dan hanya membuang hasilnya (`memset` setelah render). Not tetap dikirim ke synth, jadi mutex tetap diperebutkan.
   - Akibatnya, beban BASSMIDI dapat memperlambat MIDI OUT di mode apa pun.
3. **Drum tidak pernah mendapat NOTE OFF di BASSMIDI (CODE + HOST).**
   - 326 NOTE ON drum vs 0 NOTE OFF dalam 8 detik. NOTE OFF drum dari Style dibuang sequencer (`OFF_NO_ACTIVE_LEDGER`).
   - Batas voice BASSMIDI = **1000**.
   - Kalau sampel drum di SF2 pengguna di-loop atau berekor panjang, voice menumpuk dan biaya render naik.
   - Ini cocok dengan pola "masing-masing normal, digabung tersendat": ambang beban CPU terlampaui hanya saat beban digabung. Ini masih **HYPOTHESIS**: di host tidak ada BASSMIDI asli maupun SF2 pengguna.
4. **Jika biaya per panggilan besar (MODEL 2 ms), lateness naik seiring jumlah channel aktif.** Lateness drum p50 / p99:
   - drum saja: 2,0 / 14,6 ms;
   - semua channel: 6,0 / 25,8 ms, maks 42 ms.

   Ini memperlihatkan **bagaimana** kopling bekerja. Ini belum membuktikan bahwa biaya per panggilan di perangkat memang sebesar itu.
5. **Alat ukur yang menentukan sudah ada di APK.**
   - `LatencyProbe`, aktif secara default, di SF2 / STYLE INSPECTOR → RESET LATENCY / SAVE REPORT.
   - Probe ini mencatat durasi render, callback yang lewat budget, voice BASSMIDI aktif, waktu tunggu mutex not, dan lateness dispatch scheduler.
   - Satu sesi pengukuran di perangkat dapat membuktikan atau menggugurkan H1/H2 **tanpa APK baru** (bagian 12).

**GO/NO-GO:** NO-GO untuk patch apa pun sekarang. GO untuk satu eksperimen fisik pengukuran (bagian 12).

## 2. Baseline

| Item | Nilai |
|---|---|
| Branch audit | `claude/e343-audit-continue-q40lxg` |
| HEAD saat mulai | `96827ee8e7301931f4cd56238d9e8f28f3fbb253` (laporan FunkPopRock sebelumnya, di atas P1 `c63dec9`) |
| Kode yang diuji | Kode produksi di HEAD (berisi P1). P1 tidak diubah |
| Style | `FunkPopRock.S542.prs` (upload pengguna, tidak di-commit), SHA-256 `9eee8d1d218f38296330fa967e222286dd249f5e55f6460510a23857212dde3e`, PPQ 1920, 120 BPM, 4/4 |
| Laporan terdahulu yang diverifikasi ulang | `PSR_E343_MIDI_OUT_FILL_AUDIT.md`, `PSR_E343_FILL_P1_EXPERIMENT.md`, `PSR_E343_FILL_P1_BUILD.md`, `FUNKPOPROCK_S542_ACMP_FILL_D_AUDIT.md` |
| Fixture | `diagnostics/funkpoprock_timing/` (tes scratch, skrip analisis, ringkasan hasil host). Tidak dikompilasi oleh build atau runner tes mana pun |

## 3. Gejala (PHYSICAL, dari pengguna)

| Kondisi | Hasil dengar |
|---|---|
| ACMP ON | Kacau, terutama Main D dan Fill DD → Main D |
| ACMP OFF | Relatif rapi (pilihan kit drum kurang cocok) |
| Drum saja | Relatif normal |
| Semua pengiring tanpa drum | Relatif normal |
| Drum + satu channel pengiring | Mulai berantakan: nyangkut, tersendat, tidak ikut tempo |
| Main akor setelah kacau | Tidak memperbaiki |
| Audio internal (BASSMIDI) | Gejala juga terdengar |
| MIDI OUT + BASSMIDI bersamaan? | **Belum dikonfirmasi** |

**Relevansi temuan lama:**
- *Not Fill tertinggal* dan *P1*: tidak relevan untuk gejala timing (0 not terbawa, baseline = P1).
- *Bass ganda saat tanpa akor*: menambah kepadatan, tetapi tidak menjelaskan gejala yang tetap ada setelah akor dimainkan.
- *Kit drum E343 tidak cocok*: masalah timbre di MIDI OUT. Tidak menjelaskan gejala di BASSMIDI.

## 4. Arsitektur jalur aktual (CODE)

```
Style .prs → parser native (style_parser.cpp) → StyleSectionModel (event per part, tick PPQ 1920)
StyleSequencer.playbackJob = scope.launch(Dispatchers.Default)                StyleSequencer.kt:712          [T-SCHED, prioritas normal]
  playOnce: semua part digabung dan diurutkan per tick (NOTE ON sebelum OFF di tick yang sama)   :1320
  per event: waitForNextBarOrEvent → tenggat ABSOLUT tickDeadlineNanos(tick)  :615-640, :75-92 (TempoClock: anchor + segmen tempo)
             lifecycle{ synchronized(noteLifecycleLock) { CASM selectPolicy/transform, ledger, stageIo } }   :213-224
             setelah monitor dilepas, di THREAD YANG SAMA (OrderedNoteIo.execute)      :1563-1603
                 audioEngine.noteOnStyleChannel → JNI → BassMidiPlayer::noteOn: f12::SynthLock(mutex_)   bassmidi_player.cpp:1222-1224
                 midiInputManager.sendNoteOn → MidiMixerOutput.noteOn (@Synchronized) → MidiInputPort.send   MidiMixerOutput.kt:105, MidiInputManager.kt:39-42, :237-241
  NOTE OFF drum: tidak ada di ledger → dibuang ("OFF_NO_ACTIVE_LEDGER"), tidak ke BASSMIDI, tidak ke MIDI OUT   StyleSequencer.kt:1474-1483
  Mute mixer: DROP_MUTED sebelum JNI/USB                                       :1495
Oboe callback [T-AUDIO, LowLatency/Exclusive]  audio_engine.cpp:50-51, :208
  → BassMidiPlayer::render: f12::SynthLock(mutex_) DIPEGANG selama BASS_ChannelGetData    bassmidi_player.cpp:2356-2387
  → OUTPUT MODE MIDI OUT: render tetap jalan, hasilnya di-memset 0                audio_engine.cpp:239-247; MainViewModel.kt:793
BASSMIDI: voices_ = 1000, update period 10 ms                                   bassmidi_player.h:350, bassmidi_player.cpp:95-97, :129
```

**Jawaban atas pertanyaan audit:**

| Pertanyaan | Jawaban | Bukti |
|---|---|---|
| Thread scheduler | Coroutine di pool `Dispatchers.Default`. Bukan thread real-time | CODE :712 |
| Thread transformasi CASM | Sama, T-SCHED. Retarget saat ganti akor berjalan di Main | CODE; `docs/CHORD_CHANGE_STUTTER_ROOT_CAUSE.md` §2 |
| NOTE ON/OFF/CC/Bank/PC | Semuanya di `playOnce`, di dalam `lifecycle{}`. Not dijalankan setelah monitor dilepas, di thread yang sama | CODE :1380-1540 |
| Penjadwalan | Tenggat absolut dari anchor jam + segmen tempo. Tunggu dengan granularitas ms. Event yang terlambat langsung dikirim (tanpa akumulasi) | CODE :75-92, :615-640 |
| Output synchronous/blocking? | Ya, keduanya synchronous di T-SCHED: JNI (bisa menunggu mutex render) dan `MidiInputPort.send` (tulis socket, bisa memblok). Tanpa batching per tick: satu `send` per pesan | CODE |
| Satu output bisa menghambat yang lain? | **Ya.** Urutannya engine dulu, lalu MIDI OUT, di thread yang sama. Tunggu mutex BASSMIDI menunda MIDI OUT, dan `send` yang lambat menunda not BASSMIDI berikutnya. Mode MIDI OUT saja tidak menghilangkan render maupun mutex | CODE |
| Lock contention / backlog | `noteLifecycleLock` dipakai bersama retarget akor (Main thread). Mutex synth dipakai bersama render. Antrean tidak tumbuh tak terbatas karena jam absolut, tetapi event yang terlambat menumpuk ke depan dan "meledak" saat tunggu selesai | CODE + HOST |

## 5. Hasil per kombinasi part (HOST)

**Skenario:** Main C ±1,25 bar → `selectMainVariation(MainD)` → Fill DD (bar 2) → Main D (bar 3), ACMP ON, akor C ditahan. Kombinasi dibuat dengan mute mixer produksi (`setStyleChannelMute`).

**Model:**
- `sink`: MIDI OUT off, biaya 0, sebagai pengganti no-op;
- `both0`: MIDI OUT on, biaya 0;
- `int300`: MIDI OUT off, native 300 µs;
- `both300`: native 300 µs + USB 150 µs;
- `both2000`: native 2000 µs (pengganti mutex yang ditahan render panjang) + USB 150 µs.

### 5.1 Lateness drum persis terhadap grid ideal (ms; 253 NOTE ON drum, semuanya cocok)

| Model / kombinasi | p50 | p95 | p99 | Maks | Rata-rata bar 0 → bar 1 → Fill DD → Main D |
|---|---|---|---|---|---|
| sink drum | 0,70 | 4,92 | 60,86* | 62,04* | 7,02* → 0,69 → 1,18 → 0,87 |
| sink drum+bass | 0,02 | 2,09 | 3,57 | 3,66 | 0,27 → −0,10 → 0,08 → 0,41 |
| sink drum+chord1 | −0,13 | 0,74 | 3,30 | 3,40 | 0,32 → −0,04 → 0,08 → −0,16 |
| sink drum+pad/phrase | 0,13 | 1,46 | 2,26 | 2,30 | 0,31 → 0,10 → 0,16 → 0,12 |
| sink semua | −0,04 | 1,23 | 2,89 | 2,94 | 0,20 → 0,00 → 0,10 → 0,06 |
| both0 drum | −0,01 | 0,84 | 2,81 | 2,89 | datar |
| both0 drum+chord1 | 0,07 | 2,43 | 22,64† | 22,67† | 0,55 → −0,05 → 0,33 → 1,89 |
| both0 semua | −0,01 | 0,46 | 2,12 | 2,15 | datar |
| int300 drum / semua | 0,24 / 0,43 | 1,61 / 2,37 | 3,94 / 3,40 | 4,56 / 3,70 | datar |
| both300 drum / semua | 0,30 / 0,69 | 2,37 / 3,47 | 5,68 / 15,35 | 6,65 / 16,29 | datar |
| **both2000 drum** | **1,97** | 10,21 | 14,57 | 16,83 | 2,82 → 1,84 → 3,79 → 3,26 |
| both2000 drum+bass | 2,06 | 10,03 | 12,50 | 14,45 | 3,11 → 1,99 → 3,30 → 3,46 |
| both2000 drum+chord1 | 3,12 | 11,98 | 14,95 | 17,66 | 3,85 → 3,04 → 4,13 → 4,63 |
| both2000 drum+pad/phrase | 2,19 | 10,72 | 15,05 | 19,59 | 3,45 → 2,24 → 3,45 → 3,89 |
| **both2000 semua** | **5,95** | 19,33 | 25,83 | 42,17 | 7,30 → 5,52 → 6,63 → 7,98 |

\* Run pertama JVM (JIT dingin). Outlier hanya di bar 0, lalu pulih. Bukan sifat kombinasi.
† Satu lonjakan tunggal pada run itu (noise host). Run yang sama dengan biaya 300 µs tidak menunjukkannya.

Data lengkap: `diagnostics/funkpoprock_timing/drum_lateness_host.tsv`. Statistik `LatencyProbe` produksi (wake dan dispatch, semua event, bucket µs): `latencyprobe_host.txt`.

### 5.2 Volume event dan instans not (HOST, run 8 detik)

| Kombinasi | NOTE ON engine drum / NOTE OFF drum | NOTE ON / OFF melodik | Puncak instans (oracle, termasuk drum tanpa OFF) | Puncak NOTE ON dalam 2 ms |
|---|---|---|---|---|
| drum | 326 / **0** | 0 / 0 | 326 | 7 |
| bass | — | 44 / 43 | 1 | — |
| chord (ch12+13) | — | 399 / 393 | 9 | — |
| drum+bass | 326 / 0 | 44 / 43 | 327 | 7 |
| drum+chord1 | 326 / 0 | 279 / 278 | 327 | 8 |
| drum+pad/phrase | 326 / 0 | 171 / 168 | 329 | 9 |
| tanpa drum | — | 614 / 604 | 17 | — |
| semua | 326 / 0 | 614 / 604 | 336 | 15 |

**Pembacaan:**
- "Puncak instans" hanya menghitung NOTE ON yang belum di-OFF. Di BASSMIDI, sampel drum yang tidak di-loop tetap berhenti sendiri. Yang terus berbunyi tanpa NOTE OFF hanya sampel yang di-loop. Data loop ada di SF2 pengguna: **UNKNOWN**.
- Melodik selalu berpasangan; selisih ON/OFF = not yang masih ditahan saat STOP.

## 6. Scheduled vs actual, burst, backlog, drift

| Ukuran | Hasil | Label |
|---|---|---|
| Model jam | Absolut (anchor + tick × ns/tick, segmen tempo). Tidak ada `delay` relatif yang menumpuk | CODE :75-92 |
| Drift akumulatif | Tidak ada. Rata-rata per bagian datar di 40 run (tabel 5.1) | HOST |
| Pergantian section | Tidak menggeser timeline. Lateness Fill DD/Main D ≈ bar 0/1. Main D mulai tepat di tick bar | HOST (+ audit sebelumnya) |
| Burst di batas Fill DD → Main D | Downbeat Main D: 15 NOTE ON dalam 2 ms (semua channel). Tidak ada Bank/PC/CC di batas | HOST (audit sebelumnya) |
| Backlog | Dengan biaya per panggilan c dan n event di satu tick, event terakhir telat ≈ n·c. Tidak ada antrean yang terus tumbuh; pulih di tick berikutnya yang kosong | CODE + MODEL |
| Recovery setelah Fill | Main D sama dengan sebelum Fill di semua model | HOST |
| Granularitas tunggu | `waitMs` dibulatkan ke bawah (ms), sisa < 1 ms langsung dikirim. Jitter ±1–2 ms di host. Di Android bergantung penjadwalan thread pool non-RT | CODE :630-638 / UNKNOWN di perangkat |

## 7. BASSMIDI vs MIDI OUT

| Aspek | BASSMIDI | MIDI OUT | Bukti |
|---|---|---|---|
| Dipanggil dari | T-SCHED, synchronous, **lebih dulu** | T-SCHED, synchronous, setelah BASSMIDI | CODE :1533-1540 |
| Sumber blocking | `mutex_` synth. Dipegang render selama satu buffer penuh, dan oleh preset/mixer | `MidiMixerOutput` @Synchronized (dipakai bersama MIDI IN / echo) + tulis socket `MidiInputPort.send` | CODE |
| Batching | Event masuk antrean BASSMIDI, diproses saat render berikutnya | Tidak ada: satu `send` per pesan | CODE |
| NOTE OFF drum | Tidak dikirim | Tidak dikirim (kecuali eksperimen SHORT_GATE yang sempit) | CODE + HOST |
| Pada mode MIDI OUT saja | **Tetap dirender** (output di-nol-kan) | Aktif | CODE audio_engine.cpp:239-247 |
| Pada mode INTERNAL saja | Aktif | Router nonaktif (tidak ada `send`) | CODE |
| Batas perangkat | Voice 1000, CPU Redmi Pad: UNKNOWN | Polifoni E343, perilaku voice-stealing: UNKNOWN | — |

**Konsekuensi (CODE):**
- Mematikan BASSMIDI lewat OUTPUT MODE tidak menghapus bebannya.
- Gejala di MIDI OUT dan di BASSMIDI bisa punya sumber yang sama: biaya render, jumlah voice, dan mutex.
- Kebalikannya juga mungkin: mode BOTH menambah biaya USB ke jalur yang sama.

## 8. Hipotesis

| ID | Hipotesis | Status | Dasar |
|---|---|---|---|
| G1 | Scheduler drift karena delay relatif | **GUGUR** | CODE (jam absolut) + HOST (rata-rata datar) |
| G2 | Pergantian section menggeser timeline atau menimbulkan burst besar | **GUGUR (host)** | HOST |
| G3 | Not Fill tertinggal / P1 | **GUGUR** untuk Style ini | Audit sebelumnya: 0 terbawa, baseline = P1 |
| G4 | Parser salah menempatkan tick (drum vs pengiring tidak sejajar) | **GUGUR** | SOURCE: onset semua part di grid 1/16 ± humanisasi 0–80 tick (≤ 21 ms, pola strum 20 tick). Tidak ada event di luar panjang section yang berarti |
| G5 | Banjir CC/pitch bend/SysEx di tengah section | **GUGUR** | SOURCE: hanya CC/PC di tick 0. 8 SysEx XG (part dry level) di tick 0 |
| G6 | Biaya Kotlin per event (CASM, diagnostik) melebihi celah antar-event | **GUGUR (host)** | HOST sink: p95 ≤ 2,4 ms di semua kombinasi. Di CPU Android lebih lambat: belum diukur |
| H1 | Kopling: satu thread serial melakukan JNI (menunggu mutex render) + USB untuk semua channel, sehingga biaya per panggilan × jumlah event per tick menjadi lateness | **MEKANISME TERBUKTI (CODE + MODEL)**, besarnya di perangkat **BELUM DIUJI** | §4, §5.1 |
| H2 | Drum tanpa NOTE OFF + voice 1000: sampel drum yang di-loop/panjang di SF2 menumpuk voice, render makin berat, callback lewat budget (BASSMIDI tersendat) dan mutex ditahan lama (MIDI OUT ikut telat). Tiap bagian di bawah ambang, gabungannya melewati | **BELUM DAPAT DIUJI** di host (tidak ada BASSMIDI asli/SF2; unduhan BASSMIDI Linux ditolak proxy 403) | CODE: 0 NOTE OFF drum, voices_ = 1000 |
| H3 | Kepadatan Main D / Fill DD (tertinggi di Style) memperbesar H1/H2 | Konsisten | SOURCE (audit sebelumnya) |
| H4 | Prioritas/penjadwalan `Dispatchers.Default` di Android saat CPU sibuk (render berat) | BELUM DIUJI | CODE |
| H5 | Batas polifoni E343 | BELUM DIUJI; hanya relevan untuk MIDI OUT, bukan BASSMIDI | — |

## 9. Root cause

**ROOT CAUSE NOT YET PROVEN.**
- Penyebab yang tidak terbukti di host: scheduler, CASM, parser, dan transisi section.
- Kandidat terkuat: kombinasi H1 dan H2, yaitu beban render BASSMIDI dan mutex synth bersama.
  - H1 terbukti ada di kode.
  - H2 memenuhi semua pengamatan fisik (masing-masing normal, digabung tersendat, terjadi juga di BASSMIDI, akor tidak menolong, ACMP OFF lebih ringan).
  - Keduanya belum diukur di perangkat.

## 10. Rencana perbaikan paling kecil (BELUM boleh dikerjakan; bergantung hasil §12)

| Bila pengukuran menunjukkan… | Patch minimal yang paling aman | Lingkup |
|---|---|---|
| `activeBassVoices` naik terus dan `renderDecode` / `overBudget` naik (H2 terbukti) | NOTE OFF drum ke **BASSMIDI** di tick NOTE OFF yang tertulis di Style (ledger drum terpisah, hanya engine). Alternatif lebih kecil: batas voice BASSMIDI dari 1000 ke nilai yang terukur aman | StyleSequencer jalur drum, atau satu konstanta native |
| `styleNoteLockWait` / `styleNoteDispatch` besar, tetapi voice tidak menumpuk (H1 dominan) | Jangan memegang mutex synth selama `BASS_ChannelGetData`, atau pindahkan pengiriman event ke antrean lock-free yang dikonsumsi render. Butuh desain sendiri, bukan patch cepat | native |
| `send` USB lambat (lateness hanya di mode BOTH / MIDI OUT) | Pisahkan pengiriman MIDI OUT ke thread writer dengan antrean berurutan, dengan timestamp tetap dari scheduler | MidiMixerOutput |
| Tidak satu pun | Kembali ke audit: CPU per event Kotlin di perangkat (G6) dan prioritas thread (H4) | — |

Yang tidak disarankan:
- delay atau throttle;
- CC123 global;
- mengubah tempo, CASM, atau routing untuk menyembunyikan gejala.

## 11. Risiko regresi (untuk patch kandidat)

- **NOTE OFF drum ke BASSMIDI:**
  - mengubah ekor drum di semua Style (cymbal/open hi-hat bisa terpotong kalau SF2 menghormati NOTE OFF);
  - pengaruh ke paritas Recorder (live = file = playback);
  - pengaruh ke eksperimen E343 SHORT_GATE.

  Uji wajib: R-tes drum dan pengukuran PCM yang sudah ada (`drum_*_real_bass_test`), Recorder parity, E343 AUTO.
- **Menurunkan batas voice:** voice-stealing terdengar pada Style padat dan RIGHT 1/2/3.
- **Melepas mutex render:** risiko race di BASSMIDI dan seluruh jalur diagnostik native. Ini perubahan besar.
- **Thread writer MIDI OUT:** urutan ON/OFF per kunci (P1 OrderedNoteIo), panic STOP, Device Routing.
- Guard isolasi harus diberi pengecualian sempit yang ter-hash, seperti P1. Jangan dilonggarkan.

## 12. Eksperimen fisik paling menentukan (tanpa APK baru)

Pakai APK eksperimen P1 yang sudah terpasang (atau baseline). `LatencyProbe` Kotlin dan native aktif secara default.

1. Muat FunkPopRock, ACMP ON, mainkan akor C (tuts kiri), lalu pilih Main D.
2. Buka **SF2 / STYLE INSPECTOR → RESET LATENCY**.
3. **OUTPUT MODE = INTERNAL** (MIDI OUT off).
4. Jalankan **4 run, masing-masing ±60 detik**. Setelah setiap run, tekan **SAVE REPORT** (file di `Downloads/YamahaArranger/`), lalu **RESET LATENCY**:
   - a. drum saja (mute semua pengiring);
   - b. semua pengiring tanpa drum;
   - c. drum + Bass;
   - d. semua channel.
5. Ulangi c dan d dengan **OUTPUT MODE = MIDI OUT** lalu **BOTH**. Catat mode yang dipakai di setiap run.
6. Baris yang menentukan di laporan:

   | Baris | Bagaimana membacanya |
   |---|---|
   | `LATENCY_NATIVE stat=activeBassVoices` | Naik terus atau ratusan voice: H2 |
   | `renderDecode`, `callbackTotal`, `overBudget=` (baris header `LATENCY_NATIVE clock=…`) / `callbackIntervalOver150pctOfBudget`, `LATENCY_OUTPUT_LIVE` (xrun) | Underrun: tersendat di BASSMIDI |
   | `styleNoteLockWait`, `hold` "render" | Mutex synth: H1 |
   | `LATENCY_KOTLIN stat=styleEventDispatchLatenessAfterLifecycle` p99/max | Keterlambatan scheduler |

**Hasil yang diharapkan bila H2 benar:**
- run a dan b rendah;
- run c dan d menunjukkan voice tinggi, render dan callback lewat budget, serta lateness dispatch naik;
- hal yang sama terjadi juga di mode MIDI OUT saja.

## 13. GO / NO-GO

- **NO-GO:**
  - patch produksi apa pun sekarang, termasuk P2/P3/P4;
  - patch scheduler, karena scheduler tidak terbukti bersalah.
- **GO:** eksperimen fisik §12 (hanya baca, tanpa APK baru). Patch berikutnya dipilih dari tabel §10 berdasarkan baris laporan mana yang naik.

## 14. Hasil tes (HOST)

| Tes | Hasil |
|---|---|
| `FunkTimingScratchTest` (40 run: 5 model × 8 kombinasi) | PASS (berjalan, tanpa asersi; hanya pengukuran) |
| `FunkAuditScratchTest` (audit sebelumnya, A–G × ORIGINAL/BALANCE, baseline dan P1) | PASS |
| BASSMIDI asli di host | NOT TESTED (unduhan library ditolak proxy) |
| Perangkat (Redmi Pad + PSR-E343) | NOT TESTED |
