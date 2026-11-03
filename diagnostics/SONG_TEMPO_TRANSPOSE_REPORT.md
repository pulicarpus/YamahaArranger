# SONG TEMPO + SONG TRANSPOSE — laporan implementasi

- **Branch:** `feature/midi-song-v1` (tidak di-merge ke `main`)
- **Baseline:** `931c52d` (Recorder V1C.1; sudah dites Bos di Redmi Pad: rekaman FULL ARRANGER normal). Baseline ini dipertahankan.
- **Status perangkat:** fitur ini dites di host/CI saja. **Belum diuji di Redmi Pad.**

## 1. Audit

| Bagian | Temuan |
|---|---|
| Tempo Song Player | Override tempo dalam persen (25–300 %) yang menskala **seluruh tempo map**, di-anchor ulang di posisi sekarang (tidak lompat). UI: −5 % / % / +5 % |
| Ledger note | Dikunci oleh **nada file**. Transpose yang berubah saat note berbunyi akan membuat NOTE OFF salah pasangan, jadi ledger harus dikunci oleh **nada yang dikirim** |
| Drum | `SongTimeline.drumChannels`: ch10, bank MSB 126/127, XG PART MODE. Ditambah: **GS "use for rhythm part"** (`41 dd 42 12 40 1x 15 vv`) |
| Stream BASSMIDI Song | Menerima pesan channel mentah. Transpose = nada lain di NOTE ON/OFF; tidak perlu perubahan native |
| **Bug yang ditemukan** | Dengan faktor tempo ≠ 1, `wallNsFor` membulatkan **ke bawah**. Pada waktu itu posisi lagu 1 µs sebelum event, sehingga event tidak pernah jatuh tempo dan scheduler berputar di waktu yang sama (di perangkat: berputar sampai jam bergerak). Sudah ada sejak V1A (mis. tempo 95 %). **Diperbaiki:** pembulatan ke atas + toleransi 1 µs |

## 2. Perilaku

**SONG TEMPO** (baris baru di halaman SONG, tombol 56 dp):
- **−1 / +1 BPM** terhadap tempo yang **terdengar sekarang** (tempo file di posisi ini × faktor), misalnya 120 → 121. Satu faktor menskala seluruh tempo map, jadi perubahan tempo di tengah lagu tetap dengan rasionya (60 BPM sesudah +2 dari 120 = 61). Batas 25–300 %.
- **ORIGINAL** = tempo map file (aktif bila faktor ≠ 1).
- Kartu TEMPO: BPM efektif, plus "MIDI x.x · y %" (tempo file dan faktor).

**SONG TRANSPOSE** −12…+12:
- Berlaku untuk semua channel **melodi** di semua output (INTERNAL dan MIDI OUT). **Channel drum dari konfigurasi file tidak pernah bergeser:** ch10, bank 126/127, XG PART MODE, GS rhythm part.
- Perubahan berlaku untuk NOTE ON berikutnya. Note yang sedang berbunyi berakhir dengan NOTE OFF **di nada yang dikirim** (FIFO per nada file, urutan NOTEOFF1). Panic (STOP/PAUSE/SEEK/MUTE/SOLO) mematikan di nada yang dikirim dan membersihkan ledger.
- Nada di luar 0..127 tidak dikirim, dan NOTE OFF-nya juga tidak.

**Independen:** hanya `SongPlayer`. Tempo Style (`ArrangerBrain.setTempo`) dan Master Transpose tidak disentuh (dikunci di guard).
**File asli tidak diubah.** Lagu baru dimulai di tempo dan kunci aslinya.
**Recorder V1C.1 tidak terpengaruh:** recorder merekam stream keyboard/Style, sedangkan output Song Player tidak di-tap.

## 3. Tes

| Tes | Hasil |
|---|---|
| CPV JVM: drum dari file (ch10 + XG bank 127 + GS rhythm), transpose hanya melodi di kedua output + NOTE OFF berpasangan per nada yang dikirim, transpose diubah saat note berbunyi, seek/pause/resume/stop dengan transpose, nada di luar range, tempo +1/−1/ORIGINAL dengan tempo map 120→60, regresi pembulatan (95/97/101/103/133 %) | **OK, 385 tes** |
| BASSMIDI asli (CI): nada transpose +2/−5/+7 terdengar tepat (± 5 cent); NOTE OFF di nada yang dikirim mematikan, sedangkan NOTE OFF di nada file (kontrol) membuat not gantung | CI |
| UI desktop (3 ukuran Redmi Pad): −1/+1/ORIGINAL dan −/+/0 ≥ 48 dp, menggerakkan player asli | CI |
| Regresi: Recorder V1B/V1C/paritas ROSITA, rtdiag unit, split, ACMP/LEFT, RIGHT USB velocity, E343 SHORT_GATE EW2, E343 MIDI OUT audit, MIDI OUT octave, transisi section, latency A/B, cpv native, right_diag, Song native, guard | lihat chat |

## 4. Keterbatasan
1. Transpose tidak memindahkan note yang **sedang** berbunyi (berlaku untuk note berikutnya, perilaku yang aman untuk pasangan NOTE OFF).
2. Drum ditentukan dari konfigurasi seluruh file (channel yang pernah menjadi drum tidak pernah ditranspose).
3. Tempo dan transpose tidak disimpan per lagu (lagu baru = asli).

## 5. Langkah tes Redmi Pad
1. SONG → OPEN satu MIDI (mis. file PSR-S710) → PLAY → **+1** beberapa kali: tempo naik 1 BPM per tekan, tanpa lompatan → **ORIGINAL**.
2. Lagu dengan perubahan tempo: +2 lalu dengarkan bagian sesudah perubahan tempo (rasio tetap).
3. **♯ +** sampai +3 saat lagu berjalan: melodi/bass/akor naik, **drum tetap**. Coba juga **♭ −** dan **0**.
4. Ubah transpose saat not panjang berbunyi, lalu seek, PAUSE/PLAY, STOP: tidak ada not gantung.
5. Output MIDI OUT ke PSR-E343: transpose ikut, drum tetap.
6. Regresi: Recorder FULL ARRANGER V1C.1 (rekam → LOAD TAKE), Style tempo, Master Transpose, SHORT_GATE.
