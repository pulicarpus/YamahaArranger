# LIVE Control V3 - laporan implementasi (Redmi Pad landscape)

Branch `feature/live-control-v3-current-baseline`, basis RIGHT `02ee6bc`, kontrak `be75a8b`. Tidak ada merge ke main.

## Terhubung ke state/aksi aplikasi yang sudah ada
- Header ringkas: nama Style (`state.styleName`), section aktif (`state.activeSection`), nama voice R1/R2/R3/L (`state.rightVoices`, ketuk = toggle part, tekan lama = panel EXT), TEMPO -/+ (`nudgeTempo`, tahan = ulang), TRANSPOSE -/+ (`onTransposeDown/Up`), LED ACMP (`acmpEnabled`) dan MIDI OUT (`midiOutEnabled`).
- Tombol Style: INTRO 1-3, MAIN A-D, FILL A-D, ENDING 1-3 lewat `onSectionSelected` (pemetaan `SECTION_BUTTON_MAP` yang sama dengan layar utama; aturan Main/Fill di `ArrangerBrain` tidak diubah).
  LED: biru tersedia, amber aktif, abu gelap tidak ada (tombol nonaktif), abu-biru netral = belum diketahui. Sumber: `ParsedStyle.sections` dari parser SFF1/SFF2 yang dimuat (`LiveSectionInventory.from`). Style belum ada atau parse tidak menghasilkan section = UNKNOWN, bukan "kosong". Tidak ada section yang dipalsukan.
- Transport: START/STOP (`onStartStop`, tetap mengikuti `startReady`), ACMP (`toggleAcmp`), AUTO FILL (`toggleAutoFill`), RELEASE (`toggleRelease`, sudah ada di layar LIVE lama).
- LIVE MIXER: empat fader vertikal terpisah R1, R2, R3, STYLE (bawah 0, atas 127, angka numerik) ke `onRight1/2/3VolumeChange` dan `onStyleVolumeChange` yang sudah ada. Tidak ada jalur audio baru.
- REG: delapan tombol dan navigasi bank memakai `onLiveSlotTap` / `onLiveBankChange` (recall/freeze apa adanya). Panel BANK/SAVE/FREEZE/EXT VOICE dipertahankan.

## Dihapus / tidak ditampilkan
- Tombol palsu "SYNC N/A" dan "TAP N/A" (tidak ada callback nyata) dihapus dari layar LIVE.
- Indikator PENDING tidak ada: `ArrangerBrain` tidak mengekspos status pending (hanya field privat), dan engine tidak diubah.

## Tidak diubah
StyleSequencer, CASM, ACMP, ArrangerBrain, BASSMIDI/Oboe (`app/src/main/cpp`), MidiInputManager, MidiMixerOutput, AudioEngineManager, resolver voice, loader SF2, note lifecycle. `MainViewModel.kt` hanya menambah 7 baris: adaptor baca-saja `liveSectionInventory`, diisi dari `ParsedStyle` yang sudah diparse setelah `arrangerBrain.loadStyle`. Dijaga oleh `tools/test_live_v3_guard.py` (daftar file yang boleh berubah, MainViewModel hanya penambahan).

## Pengujian
`tests/cpv/LiveV3Test.kt` (tersedia/absen/unknown/aktif, grid 3-4-4-3, setiap tombol punya aksi ViewModel nyata, empat fader independen dan monoton 0..127, tanpa tombol hantu/panggilan terlindungi, viewport landscape dan header ringkas) dijalankan bersama seluruh suite CPV JVM; `LiveControlLayoutTest` lama tetap lulus karena `LiveControlLayout.kt` tidak diubah.
