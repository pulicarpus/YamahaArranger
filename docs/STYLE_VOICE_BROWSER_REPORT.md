# Style & Voice Browser — laporan

Branch: `experiment/style-voice-browser` (dari `experiment/multi-sf2-voice-selection` @ `a340b5e`, yang memuat LIVE V3.2, Style Voice Inspector & Editor, Registration Recall fix dan Multi-SF2). Tidak di-merge ke main. Tidak ada fitur audio baru.

## Akar masalah grid membesar
`SxStyleLcdPage` membuat satu `Row(weight(1f))` untuk setiap `chunked(4)` dari item halaman, di dalam `Column` setinggi panel. Folder 1–4 Style hanya menghasilkan **satu** baris, sehingga baris itu mengambil seluruh tinggi panel dan tombol tampak raksasa; dengan 13–16 Style ada empat baris sehingga terlihat normal. Replika kode lama dirender di `14_style_BEFORE_bug_4_items.png` (branch `preview/style-voice-editor`).

Perbaikan: `BrowserLcdPage` selalu merender **4 baris × 4 kolom** berbobot sama; slot tanpa item hanyalah ruang kosong. Ukuran sel ditentukan panel, bukan jumlah item. Bukti dari render (piksel): sel pertama berukuran **358×210 px identik** pada folder 1, 4, 17 (P2) dan 66 Style, dan isinya identik piksel-per-piksel antara 4 dan 66 item.

## Perubahan
* `yamahaarranger/browser/BrowserModel.kt` (baru, murni Kotlin): `BrowserGrid` (16 slot tetap, 6 tombol halaman, kelompok halaman), `BrowserNav` (next/previous lintas folder, folder kosong dilewati, tanpa wrap-around, `restore`), `BrowserMemory`+codec+store (JSON atomik `browser_memory.json`), `BrowserSources` (folder Style, folder Voice = SoundFont, kunci item).
* `ui/BrowserLcd.kt` (baru): `BrowserLcdPage` dan `BrowserSidePanel` dipakai oleh Style **dan** Voice (warna/ukuran identik; palet disalin dari Style yang sudah ada).
* `ui/SxMainScreen.kt`: halaman LCD Style/Voice lama diganti komponen bersama; status sorotan/halaman/posisi; pemulihan saat browser dibuka. `SxMidiBar`, Home, dan layout lain tidak berubah.
* `ui/MainViewModel.kt`, `ui/ContentResolverProvider.kt`: memori browser (dibaca di IO; `null` sampai terbaca agar restore tidak balapan).
* `ui/StyleVoiceEditorScreen.kt`/`Overlay.kt`: konteks `STYLE_EDITOR` mengingat preset terakhir (disorot dan digulir ke sana).
* Preview/tes/workflow: `tools/ui_preview/*`, `tests/cpv/BrowserModelTest.kt`, workflow, guard.

## Perilaku
* **Style**: grid 4×4, P1–P6, panel kanan = folder (📁 nama, jumlah). Mengetuk sel = sorot + LOAD (perilaku lama). ◀ ▶ hanya memindahkan sorotan (lintas folder, halaman ikut); tombol LOAD memuat sorotan. Di atas 6 halaman muncul `P◀ P▶` (P7–P12, …) di panel kanan; grid dan tombol P tidak berubah ukuran.
* **Voice**: sama; panel kanan = SoundFont yang benar-benar termuat (`♪ nama.sf2`, jumlah). Sel menampilkan nama preset (maks. 2 baris) dan `bank:program · PCn`; ● dan garis oranye menandai voice aktif part itu; biru = sorotan. Filter CAT (siklus kategori GM) dan SEARCH (dialog) ada di header. Daftar berasal dari katalog (`sf2Presets`), bukan data contoh. Identitas item = file SF2 + bank + program; mengetuk sel/APPLY memakai `setKeyboardVoice` yang sudah ada.
* **Ingatan**: STYLE, RIGHT1, RIGHT2, RIGHT3, LEFT dan STYLE_EDITOR terpisah. Dipulihkan: folder, item, halaman (Voice: juga kategori dan pencarian bila dibuka dari part). Style memakai URI file; bila folder/file hilang atau tidak bisa dibaca, browser jatuh ke awal dengan pesan, tanpa mengarang posisi.
* Sorotan/panah tidak memuat Style atau mengubah Voice; hanya LOAD/APPLY/ketuk sel.

## Pengujian (host)
`BrowserModelTest` (10): 16 slot tetap untuk 0/1/4/15/16/17/66 item; kelompok halaman (Colombo 892 preset = 56 halaman); next/previous lintas folder dan melewati folder kosong, tanpa wrap; penelusuran penuh maju-mundur; nama file sama di folder berbeda; restore (folder/item/halaman, item hilang, folder hilang); memori antar-restart dan konteks independen; file rusak/skema baru diabaikan; Voice dengan bank/program sama di SF2 berbeda; aturan folder Style tak berubah. Seluruh suite CPV (219), LIVE/REG, Registration Recall dijalankan ulang.
Belum diuji: interaksi Compose di perangkat (ketuk, scroll, restart nyata), SAF/izin dicabut di Android (Style memakai jalur file; hilang → fallback awal).

## Render (branch `preview/style-voice-editor`)
`10_style_4_items`, `11_style_66_page1`, `12_style_1_item`, `13_style_17_page2`, `14_style_BEFORE_bug_4_items`, `15/16_voice_browser_prime_*`, `17_voice_page_group_2`. Data render adalah specimen (nama preset Prime asli dari pdta; folder Style sintetis).

## Uji di Redmi Pad
1. STYLE → buka folder dengan 4 Style: sel harus berukuran sama seperti folder 66 Style, 12 slot kosong. Ulangi untuk 1 dan 17 Style (P2 berisi satu).
2. Pilih Love Song → HOME → STYLE lagi: folder, sorotan, halaman kembali; ulangi setelah menutup paksa aplikasi.
3. ▶ di Style terakhir sebuah folder → sorotan ke Style pertama folder berikutnya; Style tidak ikut berganti sampai LOAD.
4. RIGHT1 → pilih SX Prime Melody → Suling S770 → APPLY; buka RIGHT2 (posisi RIGHT2 tidak ikut berubah), lalu RIGHT1 lagi (kembali ke Prime/Suling).
5. Colombo (892 preset): periksa P◀/P▶ dan ◀ ▶ melewati halaman.
