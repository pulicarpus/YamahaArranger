# P-B0.2: HASIL PERANGKAT (Redmi Pad, 2026-11-09, ON → OFF → ON)

- **APK:** `experiment/pb02-sched-split` @ `bd5e7eb` (run `42579186444`).
- **Konfigurasi ketiga run** (diverifikasi dari laporan): FunkPopRock Main D, ACMP ON, OUTPUT INTERNAL, P-A **OPTIMIZED** (`logsUnderNoteLock=0`), R2-2 SHADOW **OFF** (0 baris `DRUM_KIT_SHADOW`), P-B1a **OFF**.
- **Sumber:** 3 laporan Inspector. Potongan + SHA-256 di `diagnostics/pb02_device_20261109/`.
- **Label:**
  - **DEVICE**: angka dari laporan;
  - **INFERRED**: tafsiran;
  - **UNKNOWN**: tidak terukur.

## 1. Validitas data

| Cek | Run 1 (ON, 74,7 s) | Run 2 (OFF, 51,4 s) | Run 3 (ON, 54,7 s) |
|---|---|---|---|
| `mismatches` (harus 0) | **0** | — | **0** |
| Event diklasifikasi | 9832 (263 tepat waktu, 1845 idle, 7724 backlog, 0 boundary) | — | 8120 (292, 1799, 6029, 0) |
| Buckets timeline = span | ya (59 890 ms) | — | ya (49 636 ms) |
| `tail` (sisa tak teramati setelah aktivitas terakhir) | rata-rata 0,16 ms, p95 ≤ 1 ms | — | 0,12 ms, p95 ≤ 0,5 ms |
| xrun / `overBudget` | 0 / 3 | 0 / 0 | 0 / 0 |

- **Batas atas WAKE valid:** `tail` jauh lebih kecil daripada lateness yang dianalisis (event > 32 ms).
- **Overhead probe:** Run 3 ON lateness rata-rata **8,04 ms** vs Run 2 OFF **8,28 ms**; 0 event > 64 ms di keduanya. **Tidak ada overhead terukur.**
- **Run 1 tercemar oleh cluster START:** 135 event `unknown`, rata-rata 127 ms (cluster pertama sebelum wake pertama, yang memang tidak teramati). Itu sebabnya `late64` di Run 1 = 252 dengan unknown 57 %. **Run 3 adalah run bersih** (unknown 1 %).

## 2. Pemisahan lateness (DEVICE)

| Porsi | Run 1 semua | Run 1 > 32 ms | Run 3 semua | **Run 3 > 32 ms** |
|---|---|---|---|---|
| WAKE (bangun terlambat dari idle) | 6,2 % | 0,0 % | 8,9 % | **0,0 %** |
| **JNI** (BASSMIDI: tunggu mutex + native) | 42,3 % | 27,8 % | 50,1 % | **47,7 %** |
| MIDI OUT | 0,3 % | 0,1 % | 0,4 % | 0,2 % |
| VOICE | 0 | 0 | 0 | 0 |
| **KOTLIN** (sibuk di luar JNI / MIDI OUT) | 37,4 % | 42,3 % | 39,7 % | **52,1 %** |
| UNKNOWN | 13,9 % | 29,8 % | 1,0 % | 0,0 % |

**Waktu thread scheduler (Run 3, 49,6 s):**

| Bucket | Waktu | Porsi |
|---|---|---|
| idle tepat waktu | 38,7 s | — |
| JNI | 5,4 s | **10,9 %** |
| Kotlin sibuk | 3,1 s | **6,3 %** |
| wake overrun | 2,4 s | — |
| MIDI OUT | 0,05 s | — |

**Kesimpulan:**
- **Wake jitter bukan penyebab lateness besar.** Kontribusinya 0 % pada event > 32 ms; per event idle rata-rata ±1,3–1,6 ms.
- **Lateness besar adalah backlog**, terbagi kira-kira **separuh JNI (±48 %) dan separuh Kotlin (±52 %)**.

## 3. Keputusan

| Gate (dari `PB02_SCHEDULER_LATENESS_SPLIT.md` §6) | Hasil | Keputusan |
|---|---|---|
| JNI ≥ 50 % pada `PB02_SHARE_late32ms` → P-B1c GO bersyarat | **47,7 %** (Run 3 bersih); 27,8 % (Run 1, tercemar unknown) | **Tidak tercapai** (tipis) |
| WAKE ≥ 50 % → penjadwalan thread | 0 % | NO |
| KOTLIN ≥ 50 % → probe kerja Kotlin | **52,1 %** (Run 3) | **Tercapai (tipis)** |

**Keputusan:**
- **P-B1c (queue) NO-GO untuk saat ini.** Queue hanya menghapus porsi JNI (±48 %), sehingga lateness backlog paling banyak turun sekitar separuh.
- **Porsi Kotlin sama besarnya dan belum dipahami.**

## 4. Porsi KOTLIN: yang diketahui dan yang tidak

**Yang dimasukkan KOTLIN (CODE, `StyleSequencer.kt`, read-only):**
- seleksi CASM per event;
- `lifecycle {}` + ledger;
- string detail diagnostik yang dibangun di setiap event, misalnya `"transformAsMelody=…"` pada `diagnostic("FORWARD", …)`;
- `StyleAudioPathDiagnostic.event` (log ter-sampling);
- alokasi per event (`casmPolicies.ifEmpty{…}.map{…}.distinct()`);
- di awal setiap loop/section: `section.parts.flatMap{…}.sortedWith(…)` untuk seluruh event section (±1000 objek), dikerjakan di thread scheduler tepat sebelum event tick 0;
- tunggu lock Kotlin, GC, preemption.

**UNKNOWN:** pembagian di antara semua itu, khususnya:
- CPU sungguhan vs tertahan/preempted;
- seberapa besar biaya `flatMap + sort` di awal loop.

Probe P-B0.2 tidak bisa memisahkannya tanpa menyentuh StyleSequencer.

## 5. Langkah berikut yang disarankan (menunggu keputusan pengguna)

1. **P-B0.3 (observer, tanpa menyentuh StyleSequencer):** tambahkan waktu CPU thread (`Debug.threadCpuTimeNanos()`) di hook yang sama (wake, awal/akhir JNI). Porsi KOTLIN lalu terpisah menjadi:
   - **CPU Kotlin** (kerja nyata: CASM, diagnostik, sort);
   - **tertahan** (lock / GC / preemption).

   Tambahkan juga penanda "event tick 0 sebuah loop" (sudah bisa dikenali dari due = awal loop) untuk mengukur biaya awal loop.
2. **Bila CPU Kotlin dominan:** perbaikannya ada di StyleSequencer (diagnostik per event / sort per loop), yang **dibekukan oleh guard P1**. Itu butuh persetujuan eksplisit untuk membuka file tersebut secara sempit.
3. **P-B1c tetap kandidat** untuk porsi JNI (±48 %). Paling bermanfaat bila digabung dengan pengurangan kerja Kotlin, karena keduanya bersifat serial di thread yang sama.
