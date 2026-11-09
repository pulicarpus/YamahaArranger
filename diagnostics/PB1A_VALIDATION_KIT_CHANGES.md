# P-B1a: VALIDASI PERGANTIAN DRUM KIT ANTAR-SECTION

- **Status:** validasi host **PASS**. Validasi perangkat untuk Style dengan pergantian kit **belum dilakukan** (prosedur §4).
- **Patch P-B1a:** tetap di `experiment/pb1a-drum-bind-cache`; tidak di-merge.
- **Kode yang divalidasi:** `373b692` (tidak diubah oleh validasi ini; hanya tes dan tool tes ditambahkan).

## 1. Style yang dipakai

Hasil parser produksi (`dump_style`) per section, request drum di tick 0 (MSB/LSB/PC) pada channel dengan not drum:

| Style | ch8 (Rhythm 1) | ch9 (Rhythm 2) | Ganti kit? |
|---|---|---|---|
| FunkPopRock S542 | 127/0/27 semua section | 127/0/0 semua | tidak |
| EnglishWaltz2 S519 | 127/0/0 | 127/0/1 | tidak |
| HardRock2 S578 | 127/0/0 | 127/0/86 | tidak |
| Love Song T547 | 127/0/73 | 127/0/73 | tidak |
| **ROSITA.STY** (fixture repo) | Main A 0, **Main B 16**, Main C/D 0; Intro B / Fill / Ending: **tidak ada part** | **Main A 8**, section lain 0 | **ya** |

**ROSITA** dipakai untuk validasi.

## 2. Tes host `tests/pb1a_kit_change_test.cpp`

**Lingkungan:** BassMidiPlayer produksi + fixture emas dan mock R2-3 (fingerprint Colombo asli).

**Tur 16 section:** Intro B → Main A → Fill AA → Main A → Fill BB → Main B ×2 → Fill CC → Main C → Fill DD → Main D ×2 → Fill AA → Main A → Main B → Ending B.
- Ada perubahan preset melodik di tengah.
- Section tanpa part ch8 tidak mengirim request ch8 (seperti `applyVoicesFromCasm`).

**Dibandingkan OFF vs ON di setiap langkah:**

| Cek | Hasil |
|---|---|
| Readback live ch8/ch9: **file SF2, bank, program** | identik di 16/16 langkah (misalnya Main A `colombo…:127:0` / `colombo…:127:8`, Main B `…:127:16` / `…:127:0`) |
| **Tabel font yang dipublikasikan** (`BASS_MIDI_StreamSetFonts`, setiap entri) | identik di 16/16 langkah |
| Not drum yang diterima per langkah | identik |
| Event NOTE (stream, channel, key, velocity), urutan | identik (284 event) |
| Stuck note (NOTE ON tanpa NOTE OFF) | 0 di kedua mode |
| State akhir controller/program setiap channel | identik |
| ON hanya melewati request yang **identik** dengan binding terverifikasi channel itu (model independen per langkah) | **14 skip = tepat 14 kandidat identik** |
| Setiap pergantian kit di-rebind | **11 rebind** (Main A ch9 0→8, Main B ch8 0→16, dan kembali ke 0) |
| ASan + UBSan | PASS |

**Tes P-B1a sebelumnya** (`tests/pb1a_drum_bind_cache_test.cpp`: ganti kit, request tanpa resolusi, readback gagal, ganti mode): **PASS** (plain + ASan). Guard isolasi + pin P-A / P-B0.1 / P-B1a: **PASS**.

## 3. Yang tidak bisa dibuktikan di host

- **xrun:** butuh perangkat. Untuk FunkPopRock (kit tetap) sudah terukur: 0 xrun di OFF dan ON, `overBudget` 35 → 0 (`PB1A_DEVICE_RESULTS.md`).
- **Pemetaan resolver** (`resolveAB`) dari request Style ke kit SF2 di perangkat. Tes host mengasumsikan program sama, sesuai bukti perangkat 127/0/PC27 → PC27.
- **Bunyi kit** di telinga.

## 4. Prosedur uji perangkat (ROSITA, 2 run)

1. Inspector:
   - P-A = OPTIMIZED;
   - P-B0.1 PROBES = ON;
   - R2-2 SHADOW = OFF.
2. **Run 1:** P-B1a = **OFF**.
   1. RESET LATENCY.
   2. Mainkan ROSITA: Main A → Main B → Main C → Main D → Main A → Main B, masing-masing ±2 bar, dengan AutoFill.
   3. STOP. SAVE REPORT.
3. **Run 2:** P-B1a = **ON**. Sama persis.
4. Dengarkan: suara drum di Main A dan Main B harus berganti **sama** seperti di Run 1.

## 5. GO / NO-GO (validasi P-B1a)

**GO**, terbatas:
- P-B1a tetap toggle eksperimen (default OFF) di branch eksperimen;
- **tidak di-merge**;
- **bukan default ON** sampai uji perangkat ROSITA (§4) menunjukkan semua ini:
  - `PB1A rebound` > 0 di setiap pergantian kit;
  - `DRUM_AB_BOUND bindingVerified=1`;
  - `xrunsSinceReset` = 0;
  - kit terdengar sama dengan Run 1.

**NO-GO** bila kit berbeda dari Run 1, ada drum yang hilang, atau `bindingVerified=0`.

**Catatan:** manfaat P-B1a kecil di telinga (±9 ms per transisi, `PB1A_DEVICE_RESULTS.md`); nilainya terutama mengurangi beban di jalur transisi.
