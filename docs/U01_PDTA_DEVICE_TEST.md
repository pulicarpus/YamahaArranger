# U0/U1 diagnostic APK — Redmi Pad

APK ini mempertahankan seluruh kode produksi dari 975e2a6. Policy C tetap aktif; katalog runtime OFF. Ini bukan release atau hasil uji suara yang sudah disahkan. Full regression CI historis masih BLOCKED; workflow diagnostik hanya mengesahkan gate host U0/U1 dan kompilasi Android. Probe kalibrasi BASSMIDI P1–P8 belum ditambahkan.

1. Unduh artifact YamahaArranger-U01-DIAGNOSTIC dari run GitHub Actions yang dilaporkan. Ekstrak ZIP dan instal APK. Cocokkan commit di apk-provenance.json. Bila Android menolak pembaruan karena tanda tangan berbeda, jangan hapus aplikasi/data REG terlebih dahulu; kirim pesan kesalahannya.
2. Gunakan keempat SF2 asli yang sama, dari penyimpanan perangkat. Jangan mengganti prioritas/font pilihan, mixer, gain, atau pengaturan release untuk pengambilan baseline ini. Font yang belum termuat tidak otomatis mendapat baris fingerprint.
3. Muat ROSITA, mainkan Main A, lalu STOP. Buka SF2 / STYLE INSPECTOR dan tekan **SAVE REPORT** di bagian atas. Ulangi untuk Main B, C, D, dan Fill DD. **SAVE DRUM (SMALL) bukan tombol ekspor STYLE FIDELITY.**
4. Laporan tersimpan di **Downloads/YamahaArranger/YamahaArranger_Inspector_*.txt**. Pastikan ada `STYLE FIDELITY U0`, `sectionAtExport`, `NATIVE`, dan `SF2_FINGERPRINT`. Laporan hanya mengatribusikan binding saat ekspor; section lain UNOBSERVED. Catat section yang baru dimainkan dan sectionAtExport yang tertulis; jangan menganggap laporan sebagai riwayat playback.
5. Kumpulkan fingerprint untuk keempat font. Jika hanya tiga termuat, simpan laporan terlebih dahulu, kemudian muat font yang belum tercatat dan simpan laporan berikutnya. Jangan ubah bank/program E343 untuk langkah ini. Tabel di bawah adalah referensi yang sudah dihitung ulang dari PDTA asli pada host.
6. Pada Inspector, **FULL METADATA (ZIP)** dapat mengekspor ulang seluruh P/I/S beserta nameHex. File juga berada di Downloads/YamahaArranger. Keluaran metadata memuat nama dan generator, bukan PCM.
7. Kirim laporan Inspector dan, bila perlu, ZIP metadata. Sertakan commit APK, nama SF2, mode INTERNAL/EXTERNAL/BOTH, section, dan gejala suara. Catat apakah ada jeda/glitch pada load/export. Saat menguji E343, gunakan Local Control OFF seperti baseline; pastikan connect/reconnect tidak mengubah voice keyboard otomatis.
8. Uji singkat LIVE CONTROL, REG SAVE/RECALL, RELEASE ON/OFF, RIGHT OFF, mixer, start/stop/fill. Catat hasil sebenarnya; hasil host bukan pengganti pengujian ini.

## Fingerprint dan waktu hashing

| Font | pdtaSha256 | Byte payload |
|---|---|---:|
| Colombo | ca157455dc307fd988d1edef1e2a0cfca245ab7d2bb5970f4c415c29444ba506 | 446466 |
| Prime Drum | 338385762217a9ecd9ecb8707fa71202a115da4eaad81441c27d714acbf61283 | 197194 |
| Prime Melodi | cfb096e3c197570554313aaca227f7e207ac64867e9871a025c1b4b2c6e9c136 | 208430 |
| Tyros 4 | 16bb60deafdf426cdc7e269b6c5f434cd5b7db6e14a83f86d17f54720176217a | 260470 |

Domain mencakup fourcc pdta; bukan hash file utuh. Cocokkan juga fileSha256 dengan catalog_manifest.json dari artifact/log. Jika baris menunjukkan UNAVAILABLE, nama berbeda, atau hash berbeda, jangan nyatakan font cocok.

`hashingNs / 1.000.000` menghasilkan milidetik validasi+hashing PDTA per font. Ambil satu pengukuran load untuk masing-masing font dan jumlahkan empat nilai; target desain <300 ms di Redmi Pad masih perlu dibuktikan. Timer ini tidak mencakup seluruh IO/load SF2 atau hashing file penuh yang sudah ada pada debug. Kode cache masih membaca file seperti baseline; optimasi pembaca seek-only belum dilakukan.

Status `UNVERIFIED` pada laporan perangkat saat ini adalah label diagnostik konservatif; aplikasi belum memasang katalog sebagai resolver. Pembandingan empat hash dilakukan terhadap golden di atas. Jangan menyalakan katalog runtime setelah hanya melihat hash cocok.

## Peak/RMS dan probe

ROLE_PCM/HEADROOM yang sudah ada dapat disertakan untuk pengamatan playback ROSITA. Probe kalibrasi P1–P8 dengan satu nada pada stream decode terpisah **belum diimplementasikan**. Jangan menafsirkan peak/RMS playback utama atau model level Claude sebagai hasil probe tersebut. Semantik clamp atenuasi BASSMIDI, model A/B, dan penilaian suara tetap gate perangkat/pekerjaan berikutnya.
