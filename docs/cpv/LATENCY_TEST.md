# Uji latensi di Redmi Pad + PSR-E343 (CPV latency probe)

Tujuan: memisahkan **ingress MIDI**, **dispatch**, **render**, dan **output**. Waktu dispatch **bukan** waktu
suara terdengar. Sampai ada data dari langkah di bawah, penyebab keterlambatan **belum terbukti**.

## Yang diukur dan dari mana

| Tahap | Statistik di laporan | Batas pengukuran |
|---|---|---|
| Ingress USB MIDI | `LATENCY_KOTLIN stat=midiUsbTimestampToOnSend` | timestamp USB dari Android MIDI service → `onSend` dipanggil |
| Parsing + routing | `midiOnSendTotal`, `keyboardLockWait`, `rightNoteOnSendToAllLayersHandedToNative` | di thread MIDI; yang terakhir = semua layer RIGHT sudah diserahkan ke native |
| JNI + lock native | `jniKeyboardNoteOn_includesNativeLockWait` | satu panggilan `nativeSfNoteOnChannel` |
| Dispatch native | `LATENCY_NATIVE stat=keyboardNoteLockWait`, `keyboardNoteDispatch` | menunggu `mutex_` lalu `BASS_MIDI_StreamEvent` selesai |
| Antrian ke render | `keyboardNoteQueuedToRenderStart` | NOTE_ON masuk BASSMIDI → decode berikutnya dimulai |
| Sampel siap | `keyboardNoteQueuedToBufferReady` | sampai decode selesai (sebelum diserahkan ke Oboe) |
| Callback | `renderLockWait`, `renderDecode`, `callbackTotal`, `overBudget`, `over80pct` | `callbackBudgetUs` = 256 frame / 48 kHz ≈ 5333 µs |
| Output Android | `LATENCY_OUTPUT`, `LATENCY_OUTPUT_LIVE` | **estimasi** Oboe (`estimatedOutputLatencyMs`), bukan ukuran akustik |
| Section | `sectionRequestToBoundaryWake`, `sectionBoundaryToVoiceApplyStart`, `sectionVoiceApplyDuration`, `sectionVoiceApplyEndToFirstNoteDispatch`, `sectionBoundaryToFirstNoteDispatch`, `sectionFirstNoteLatenessVsSchedule` | permintaan tombol → bar berikutnya (kuantisasi, by design) → proses boundary → apply voice → note pertama |
| Scheduler | `styleEventWakeLatenessBeforeLifecycle`, `styleEventDispatchLatenessAfterLifecycle` | keterlambatan terhadap jadwal tick, sebelum dan sesudah monitor `lifecycle` |
| ACMP | `chordLastNoteToApplyChordNow`, `chordApplyDuration_handleChordChange`, `chordLastNoteToRetargetDone` | NOTE_ON LEFT terakhir → retarget selesai (termasuk settle 15 ms dan hop ke Main) |

Statistik: jumlah, rata-rata, batas atas p50/p95/p99 (per bucket), maksimum, dan histogram bucket
`<250µs, <500, <1ms, <2, <4, <8, <16, <32, <64ms, ≥64ms`.

## Prosedur (satu sesi, ±3 menit)

1. Pasang APK, muat ROSITA.STY atau style lain, hubungkan E343 lewat USB. Matikan MIDI OUT untuk uji ini.
2. Buka SF2 / STYLE INSPECTOR → tekan **RESET LATENCY**.
3. **RIGHT saja (style berhenti):** mainkan 20–30 nada Piano RIGHT1, lalu RIGHT1+2+3 aktif, masing-masing ±30 nada, dengan
   kecepatan dan jarak nada yang bervariasi. Catat secara subjektif apakah terasa terlambat.
4. **Style jalan:** START Main A. Mainkan RIGHT sambil style berjalan ±30 nada.
5. **Transisi:** tekan Main A→B→C→D masing-masing 3 kali, di awal dan di akhir bar. Lalu Fill (Auto Fill ON dan OFF).
6. **ACMP:** ganti chord C→F→G→C sebanyak 10 kali pelan, lalu 10 kali cepat.
7. Tanpa menyentuh apa pun: SAVE REPORT. Kirim file Inspector (blok `=== LATENCY PROBE ===`) dan catat urutan serta kesan subjektif.
8. Ulangi langkah 2–7 sekali lagi dengan layar Mixer **tertutup** dan sekali dengan Mixer **terbuka**
   (polling `channelBindings` hanya aktif di Mixer).

## Cara membaca hasil

| Pengamatan | Arti | Langkah berikut |
|---|---|---|
| `fallbackToShared=1` atau `perf≠12` atau `estimatedOutputLatencyMs` > ±30 ms | batas bawah ada di output Android, bukan di kode app | ubah konfigurasi stream (lihat rencana) |
| `callbackTotal` p99 mendekati `callbackBudgetUs`, `overBudget`>0, `xruns`>0 | render terlalu berat | kurangi beban render |
| `renderLockWait` atau `keyboardNoteLockWait` punya maksimum puluhan ms, bersamaan dengan transisi | `mutex_` ditahan oleh apply voice/preset | pecah atau pindahkan kerja section |
| `keyboardNoteQueuedToBufferReady` kecil (≤ ±10 ms) tetapi tetap terasa terlambat | keterlambatan ada **setelah** callback (output) atau di ingress | cek `LATENCY_OUTPUT` dan `midiUsbTimestampToOnSend` |
| `midiUsbTimestampToOnSend` besar | antrian Android MIDI/USB | di luar kendali app; coba kabel/port lain |
| `sectionVoiceApplyDuration` besar atau `sectionApplyEndToFirstNote` besar | kerja sebelum note pertama section baru | rencana transisi |
| `sectionRequestToBoundaryWake` ≈ sisa bar | kuantisasi ke bar berikutnya, perilaku yang dirancang | bukan bug |
| `chordLastNoteToApplyChordNow` ≈ 15 ms + hop Main | settle 15 ms ditambah dispatcher Main | lihat rencana ACMP |

## Catatan keterbatasan
- Angka dispatch dan render tidak membuktikan kapan suara keluar dari speaker/headphone. Satu-satunya cara memastikan
  itu adalah pengukuran akustik atau loopback; di sini hanya ada estimasi Oboe.
- Overhead probe pada host x86: ±0,03 µs per pembacaan jam, ±0,2 µs per siklus note+render+callback. Pada ARM perangkat
  belum diukur; diperkirakan tetap di bawah 1 µs per callback (anggaran 5333 µs).
- Build debug saat ini memakai `YAMAHA_ROLE_PCM_METERS` dan `YAMAHA_PCM_HEADROOM`, yang menambah kerja di dalam `render()`.
  Statistik `renderDecode` termasuk kerja itu.
