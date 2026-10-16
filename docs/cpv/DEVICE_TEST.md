# CPV — uji perangkat (Redmi Pad + PSR-E343)

Branch `experiment/colombo-primary-voice-override`, baseline R2-3 `c75732f`.
Drum Punch R2-4 tidak ada di branch ini.

## APK-1 — Style Voice Inspector (diagnostik)

APK-1 hanya menambah tampilan. Suara harus sama dengan R2-3.

1. Pasang APK, buka style (misalnya ROSITA.STY), lalu STOP.
2. Buka SF2 / STYLE INSPECTOR → tab **VOICES**.
3. Periksa per section:
   - SRC→DST, part/CASM, request MSB:LSB:PC (tanda `-` berarti style tidak mengirim nilai itu);
   - Program Change dinamis;
   - kolom ACTUAL (binding engine saat ini).
4. ACTUAL hanya menggambarkan section yang sedang aktif. Pilih section, START sebentar, STOP, lalu tekan REFRESH.
5. Channel yang tidak menemukan instrumen ditandai **UNRESOLVED** (merah) dan tidak berbunyi. Ini bukan error aplikasi.
6. SAVE REPORT → file Inspector memuat blok `STYLE VOICE INSPECTOR` (`STYLE_VOICE_REQUEST` / `STYLE_VOICE_ACTUAL`).
7. Regresi singkat: LIVE CONTROL, REG MEMORY, RELEASE, RIGHT OFF tetap normal. Saat E343 tersambung, tidak ada Bank/PC otomatis.

## APK-2 — Colombo utama + override per style

Persiapan:
- Folder `/storage/emulated/0/YamahaArranger/SF2` berisi ColomboGMGS2 dan Tyros 4.
- File Prime boleh ada, tetapi tidak dimuat otomatis.

1. **Pemuatan SF2.**
   - Status SF2 menampilkan `Colombo… + Tyros…`.
   - Log berisi `CPV SF2 plan: PRIMARY=Colombo FALLBACK=Tyros notAutoLoaded=…Prime…` dan `CPV drum route=R23_RESOLVED ok=true`.
2. **Resolver melodik.**
   - Inspector VOICES → kolom ACTUAL untuk ch10–15 bertuliskan `PRIMARY` (Colombo).
   - `SECONDARY` (Tyros) hanya muncul bila Colombo tidak punya kandidat yang cocok.
   - `UNRESOLVED` berarti channel diam (tidak diganti preset lain). Catat channel dan request-nya.
3. **Drum.**
   - ch8/9 menampilkan `DRUM_R23` dengan Colombo 127:PC (XG) atau 128:PC (GS).
   - Kit custom (MSB 126 dll.) menampilkan `UNRESOLVED` dan diam.
4. **Override manual.**
   - Mixer → DETAIL pada strip style → VOICE → pilih COLOMBO/TYROS 4 → pilih preset.
   - Strip menampilkan badge `OVR`; DETAIL menampilkan `ENGINE: BOUND PINNED …`.
5. **Pergantian section.**
   - START → Main A → Fill → Main B/C/D.
   - Suara override tetap; log berisi `CPV PIN keeps chN` dan `DYNAMIC_PC_SUPPRESSED_BY_OVERRIDE` bila style mengirim PC di tengah pattern.
6. **Penyimpanan.**
   - Tutup aplikasi sepenuhnya, buka lagi, lalu muat style yang sama → badge `OVR` kembali dan suara override aktif.
7. **Tidak bocor.**
   - Muat style lain → tidak ada badge `OVR` dari style sebelumnya.
   - Kembali ke style pertama → override pulih.
8. **STALE.**
   - Jika SF2 yang dipakai override tidak dimuat, badge menjadi `STALE` dan channel memakai voice style.
   - Data override tetap tersimpan.
9. **RESET KE STYLE** di dialog voice → badge hilang dan voice style kembali segera.
10. **MIDI OUT E343** (Local Control OFF):
    - memilih atau me-reset override tidak mengirim Bank/PC apa pun;
    - saat connect tidak ada Bank/PC keyboard otomatis.
11. **Regresi:** LIVE CONTROL, REG MEMORY, RELEASE, RIGHT OFF.
    - Registrasi lama yang memakai voice Prime menampilkan voice tidak tersedia, bukan crash.

File override: `files/style_overrides/style-<sha256>.json` (schema 1).
Entri `scope=SECTION` sudah didukung format dan dipertahankan, tetapi belum dipakai saat playback.
