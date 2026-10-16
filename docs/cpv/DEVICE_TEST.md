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

Diisi pada milestone APK-2.
