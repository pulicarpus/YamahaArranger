# P-B1a: HASIL PERANGKAT (Redmi Pad, 2026-11-09)

- **APK:** `experiment/pb1a-drum-bind-cache` @ `373b692` (run `42481608654`).
- **Kedua run:** FunkPopRock, Main C ↔ Main D, ACMP ON, OUTPUT INTERNAL, P-A OPTIMIZED, P-B0.1 PROBES ON, R2-2 SHADOW OFF (0 baris `DRUM_KIT_SHADOW`).
- **Sumber:** 2 laporan Inspector pengguna. Potongan + SHA-256 di `diagnostics/pb1a_device_20261109/`.
- **Label:**
  - **DEVICE**: angka dari laporan;
  - **INFERRED**: tafsiran;
  - **PHYSICAL**: kesan dengar (belum dilaporkan pengguna untuk run ini).

| Run | File | P-B1a | Transisi | Durasi |
|---|---|---|---|---|
| OFF | `…080754` | OFF (rebound 46/46) | 22 | 76 s |
| ON | `…080908` | ON (**skippedIdentical 38/38**, rebound 0) | 18 | 67 s |

## 1. Hasil (DEVICE, rata-rata kecuali disebut lain)

| Ukuran | OFF | ON | Perubahan |
|---|---|---|---|
| Voice apply total | 21,7 ms (maks 33,6) | **12,3 ms** (maks 18,6) | **−43 %** |
| `bindDrumAB` ×2 (tunggu + hold) | 11,7 ms | **2,5 ms** | −78 % |
| … hold per panggilan | 3,95 ms | **0,013 ms** | — |
| … `applyFonts` per transisi | 2 (7,7 ms) | **0** | dihapus |
| … tunggu mutex (render) | 3,8 ms | 2,5 ms | sisa yang tak terhindarkan dengan lock bersama |
| Sisa Kotlin (di luar probe native) | 10,0 ms | 9,7 ms | tidak berubah |
| Batas section → not pertama dikirim | 28,9 ms | **17,0 ms** | −41 % |
| Not pertama setelah transisi telat vs jadwal | 39,6 ms (maks 82) | **30,6 ms** (maks 64) | −23 % |
| Callback audio lewat budget (`overBudget`) | 35 | **0** | — |
| xrun sejak reset | 0 | 0 | — |
| Lateness scheduler (semua event) rata-rata / > 64 ms | 21,1 ms / 7,9 % | 20,9 ms / 9,2 % | tidak berubah |
| Binding drum ch8/ch9 | `bindingVerified=1`, PC 27 / 0 | `bindingVerified=1`, PC 27 / 0 | **kit sama** |

## 2. Kesimpulan

- **P-B1a bekerja seperti dirancang.**
  - Setiap transisi dengan kit sama tidak lagi membangun ulang tabel font (`applyFonts` 2 → 0).
  - Hold `bindDrumAB` turun dari ±4 ms ke ±13 µs.
  - Binding drum tetap terverifikasi dengan kit yang sama.
- **Voice apply turun 43 %** (21,7 → 12,3 ms) dan not pertama setelah Fill/Main dikirim 12 ms lebih cepat.
- **Gate ≥ 50 % pada voice apply tidak tercapai secara ketat** (43 %), karena baseline run OFF hari ini lebih rendah (21,7 ms vs 27,5 ms pada 2026-11-08). Komponen yang menjadi sasaran (`bindDrumAB`) turun 78 %.
- **Sisa ±10 ms voice apply sekarang di sisi Kotlin** (tidak berubah oleh P-B1a). Kandidat (INFERRED): ±20 `DebugLog.add` per transisi, `resolveAB` ×2, alokasi.
- **Keterlambatan not pertama masih ±31 ms**, karena scheduler sudah terlambat sebelum batas (lateness umum ±21 ms). Ini bukan masalah transisi; ini kopling scheduler ↔ render (sasaran P-B1c).
- **Kesan dengar (PHYSICAL):** belum dilaporkan. Perlu konfirmasi bahwa drum terdengar sama dan jeda Fill/Main berkurang.

## 3. Keputusan

- **GO (bersyarat konfirmasi dengar)** untuk menjadikan P-B1a kandidat produksi, tetap sebagai perubahan kecil berpengaman (`tableStale` + readback). Sebaiknya default ON setelah satu uji dengar lagi di Style lain dengan kit berbeda antar section (misalnya EnglishWaltz2, HardRock2), untuk memastikan jalur ganti kit tetap benar.
- **Berikutnya (urut dampak):**
  1. **P-B1c / scheduler:** lateness umum ±21 ms (8–9 % event > 64 ms) tetap terbesar dan memengaruhi semua not.
  2. Retarget akor: batch + log per not (P-B1b, dari `PB01_DEVICE_RESULTS.md`).
  3. Sisa Kotlin voice apply ±10 ms (log transisi).
