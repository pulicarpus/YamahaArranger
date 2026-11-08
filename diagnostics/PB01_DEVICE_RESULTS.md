# P-B0.1: HASIL PERANGKAT (Redmi Pad, 2026-11-08)

- **APK:** `experiment/pb01-targeted-probes` @ `57025d1` (run `42383396531`), P-A **OPTIMIZED**, P-B0.1 PROBES ON.
- **Konfigurasi:** FunkPopRock, Main D, ACMP ON, OUTPUT INTERNAL.
- **Sumber:** 4 laporan Inspector pengguna. Potongan bagian latensi + SHA-256 file asli ada di `diagnostics/pb01_device_20261108/`.
- **Label:**
  - **DEVICE**: angka dari laporan;
  - **INFERRED**: tafsiran;
  - **UNKNOWN**: belum terukur.

| Run | File | Isi | Shadow R2-2 | LEAN log |
|---|---|---|---|---|
| A1 | `…164124` | Fill/Main, 19 transisi, 76 s | ON (40 baris `DRUM_KIT_SHADOW`) | OFF |
| A2 | `…164231` | Ganti akor, 21 akor, 89 not diganti, 58 s | ON | OFF |
| B1 | `…173832` | Fill/Main, 16 transisi, 73 s | **OFF** (0 baris) | **ON** (menurut pengguna) |
| B2 | `…173932` | Ganti akor, 33 akor, 115 not diganti, 51 s | **OFF** | **ON** (menurut pengguna) |

**Catatan:**
- B mengubah dua hal sekaligus, jadi atribusinya per jalur kode. Di jalur voice apply hanya shadow yang berpengaruh; LEAN hanya menggerbang log NOTE di `noteOnChannel` / `noteOffChannel`.
- Status LEAN tidak tercatat di laporan.

## 1. Voice apply (Fill/Main)

| Ukuran (rata-rata) | A1 | B1 | Selisih |
|---|---|---|---|
| Voice apply total (Kotlin) | **37,5 ms** | **27,5 ms** | −10,0 ms |
| `bindDrumAB` ×2 (tunggu + hold) | 11,5 ms (31 %) | 17,3 ms (63 %) | +5,8 |
| … `applyFonts` (2 per transisi) | 8,0 ms (21 %) | 9,0 ms (33 %) | ≈ |
| … tunggu mutex | 3,3 ms | 8,1 ms (`bindDrumAB` wait 1,7 → 4,0 ms per panggilan) | +4,8 |
| `BASS_MIDI_StreamSetFonts` | 1,1 ms | (±1,1 ms) | ≈ |
| Preset / mixer melodik | 0 (cache) | 0 | — |
| **Sisa di luar probe** | **26,0 ms (69 %)** | **10,3 ms (37 %)** | **−15,8 ms** |
| Not pertama setelah transisi telat (`sectionFirstNoteLatenessVsSchedule`) | 74 ms (maks 100) | 57 ms (maks 85) | −17 ms |

B2 (transisi ikut tercatat): total 24,9 ms, `bindDrumAB` 16,7 ms, sisa 8,2 ms.

**Kesimpulan:**
- **±16 ms dari "sisa" ternyata shadow diagnostik R2-2** (INFERRED kuat: satu-satunya perubahan di jalur ini). Shadow menambah 2× `drumChannelSelection` per transisi, yaitu lock yang sama lewat `lock_guard` yang tidak terprobe, ditambah evaluasi/observe.
- Setelah shadow mati, **`bindDrumAB` menjadi bagian terbesar (±17 ms = 63 %)**:
  - separuhnya `applyFonts` yang berulang (2× per transisi, padahal kit tidak berubah);
  - separuhnya tunggu mutex yang dipegang render.
- **Q1 (apakah biaya voice apply dari `applyFonts` berulang):**
  - dengan shadow ON, **SEBAGIAN** (21 %);
  - tanpa shadow, `applyFonts` + tunggu lock untuk `bindDrumAB` yang berulang adalah **penyebab dominan** (63 %).
- Kenaikan tunggu `bindDrumAB` (1,7 → 4,0 ms) kemungkinan karena sebelumnya `drumChannelSelection` lebih dulu menyerap tunggu render (INFERRED).

## 2. Retarget akor

| Ukuran (rata-rata) | A2 | B2 | Selisih |
|---|---|---|---|
| Retarget per akor (`handleChordChange`) | **29,9 ms** (maks 75) | **25,8 ms** (maks 68) | −4,1 ms |
| Not diganti per akor | 4,2 | 3,5 | — |
| Akuisisi mutex per akor | 8,7 (= **2 per not**) | 7,1 (= 2 per not) | — |
| Total tunggu mutex | 8,9 ms (30 %) | 9,8 ms (38 %) | ≈ |
| Tunggu per akuisisi (thread main) | 1,03 ms | 0,86 ms | ≈ |
| **Sisa Kotlin** | **20,6 ms (69 %)** | **15,7 ms (61 %)** | −4,9 ms |
| Per not diganti | 8,5 ms | 7,8 ms | −0,7 |
| Akor terakhir → retarget selesai | 54 ms | 52 ms | −2 |

**Kesimpulan:**
- **Q2 (apakah retarget terhambat akuisisi mutex berulang):** **TERBUKTI ADA, tetapi bukan mayoritas.**
  - Tepat 2 akuisisi per not, masing-masing ±1 ms tunggu, total 30–38 %.
  - LEAN (tanpa log NOTE) hanya menghemat ±5 ms per akor.
- **Sisa ±4,5 ms per not masih di sisi Kotlin** di thread UI. Kandidat (INFERRED):
  - 2× `dlog` per not ("CASM RETARGET", "RTR PITCH SHIFT"; tidak digerbang LEAN);
  - CASM `selectPolicy` / transform;
  - thread UI yang juga mengerjakan frame Compose (retarget berjalan di thread main).
- Pembagian sisa ini **UNKNOWN**. Perlu mengukur waktu CPU thread vs waktu dinding.

## 3. Audio dan scheduler

| Ukuran | A1 | A2 | B1 | B2 |
|---|---|---|---|---|
| `xrunsSinceReset` | 0 | 0 | 1 | 0 |
| `overBudget` (callback > 5,33 ms) | 27 | 5 | 28 | 17 |
| Lateness scheduler rata-rata / maks | 41 / 144 ms | 19 / 127 ms | 33 / 148 ms | 26 / 113 ms |
| Event > 64 ms | 23 % | 6,5 % | 18 % | 13 % |
| Tunggu mutex per NOTE ON scheduler | 1,7 ms | 1,2 ms | 1,5 ms | 1,5 ms |

- **Audio internal stabil dengan P-A OPTIMIZED:** xrun 0–1 per run. Laporan LEGACY 2026-11-06 mencatat 56–106 xrun per run. Itu sesi berbeda, jadi ini bukan A/B ketat.
- **Lateness scheduler tetap tinggi** dan tidak banyak berubah oleh shadow/LEAN. Setiap NOTE ON/OFF scheduler masih menunggu render ±1,5 ms, serial. Ini sasaran P-B1c (antrean), setelah P-B1a/b.

## 4. Rekomendasi

1. **Segera, tanpa kode:** biarkan **R2-2 SHADOW OFF** selama bermain (diagnostik, menambah ±16 ms per transisi).
2. **P-B1a, GO:** `bindDrumAB` / `applyFonts` hanya bila kit berubah. Bentuk: eksperimen toggle, default perilaku lama, tes "event identik".
   - Target: voice apply ±27 ms → ≤ 10 ms; not pertama setelah transisi ≤ 30 ms.
   - Risiko: binding drum harus tetap terverifikasi (readback), dan kasus ganti kit harus tetap jalan.
3. **P-B1b, GO bersyarat:** batch retarget (satu akuisisi untuk semua not) menghemat ±2 ms per not. Sebelum itu, satu probe kecil untuk memecah sisa Kotlin ±4,5 ms per not:
   - waktu CPU thread vs dinding di jendela retarget;
   - durasi `dlog` / `DebugLog.add` per thread.

   Bisa dipasang tanpa menyentuh StyleSequencer.
4. **P-B1c (antrean + single synth owner):** tetap menunggu hasil P-B1a/b.
