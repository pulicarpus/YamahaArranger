# Audit dan persiapan golden routing — 2026-10-12

**GO untuk persiapan capture read-only; NO-GO untuk ekspor SF2 master.**
Audit mengacu persis pada `c75122daf5d1077db55d50d1c261144fde59af4a`, bukan eksperimen Voice & RELEASE `7121a35`. Parent perubahan rencana: `942ab1ffb9fe27767ace34ed60678fa0807699ee`. Keempat dokumen plan/source/provenance/capture-contract sudah dibaca. Status sumber SHA256 mengikuti bukti user dalam gate; binary laptop tidak tersedia untuk penghitungan ulang di sesi ini.

## Integritas dan batas pekerjaan

Checkout awal `/workspace/YamahaArranger` berada pada branch `work`, commit lama `18081d2`. Git fetch gagal karena proxy tidak terhubung. Snapshot text checkpoint diambil melalui konektor GitHub; 84 file dibandingkan dengan Git blob SHA1 checkpoint dan planning parent, semuanya identik. Daftar SHA256/Git blob disimpan dalam `tests/fixtures/sf2_golden_source_snapshot.json`. Tidak ada AGENTS.md pada kedua tree yang diperiksa. Pengujian dijalankan dalam snapshot scratch terpisah, bukan checkout lama.

Perubahan hanya dokumen, fixture, dan diagnostik host. Tidak ada edit production/loader/style/workflow, APK, SF2 master, penggabungan Voice & RELEASE, atau perubahan ref `main`, READY `c13fa3e1aec416b8378010e13b1c86914c246e4a`, dan checkpoint style. Synthetic SF2 sementara yang dibuat pengujian lama hanyalah fixture minimal/mock; bukan master atau gabungan binary sumber. Workflow push yang diperiksa tidak mencakup `plan/sf2-single-master`; tidak ada workflow dispatch atau PR build yang diminta.

## Seluruh jalur penentu preset

Nomor channel di tabel adalah MIDI **1-based**; kode Kotlin/C++ menggunakan zero-based. CASM dapat mengubah source-channel menjadi destination-channel: nama part/channel konvensional bukan bukti destination event aktual.

| Part | Channel konvensional | Jalur penentu |
|---|---:|---|
| Rhythm1 / Rhythm2 | 9 / 10 | parser setup → CASM destination → section/dynamic PC → native forced drum → cache font drum → fallback → opsional donor per-key |
| Bass | 11 | parser setup → section/CASM/override atau dynamic PC → family resolver → mapping per-channel → note admission |
| Chord1 / Chord2 | 12 / 13 | jalur melody yang sama; voice-name dan CASM selection dapat menentukan family |
| Pad | 14 | jalur melody; strings pada destination zero-based 13..14 dipaksa reapply saat section activation |
| Phrase1 / Phrase2 | 15 / 16 | jalur melody; preset source/program dapat berbeda dari request MIDI |
| RIGHT1 / RIGHT2 / RIGHT3 | 1 / 2 / 3 | `MainViewModel.setRightVoice` → pilihan nama preset pertama pada raw bank/PC → `AudioEngineManager.setChannelProgram` → resolver native; layer enable menentukan NOTE dispatch |
| LEFT | 4 | `MainViewModel.setChannelVoice(3,...)` pada jalur generic bila dipanggil → resolver native; `ArrangerBrain` mengirim LEFT notes hanya bila LEFT aktif dan ACMP off. ACMP+LEFT aktif masuk chord analyzer, bukan LEFT NOTE; LEFT off merutekan lower notes ke RIGHT layers |

Referensi source permanen: [tree checkpoint](https://github.com/pulicarpus/YamahaArranger/tree/c75122daf5d1077db55d50d1c261144fde59af4a).

1. **Input style:** `app/src/main/cpp/style_parser.cpp:196–217` membawa setup CC0/32/PC ke section dan membaca header sebelum note pertama; `NativeStyleBridge.kt`/`StyleModel.kt` meneruskan bank, PC, CASM policies dan destination. Section availability/hash tidak dapat ditentukan tanpa file style nyata.
2. **Section apply:** `StyleSequencer.kt:730–802` memakai first CASM policy untuk setup, mengelompokkan explicit program pertama per destination. Prioritas PC: override → explicit MIDI setup → `guessProgramFromVoiceName` (`:854`, angka akhir 0-based atau heuristik nama). Bank audio berasal dari source-part packed MSB*128+LSB; Rhythm memakai sentinel audio bank128 dan external MIDI MSB127/LSB0. Muted/locked/cache-hit bisa mencegah preset apply. Field bank pada override disimpan (`:244`) tetapi section apply pada checkpoint memakai bank sourcePart, bukan override bank; ini temuan audit, tidak diperbaiki.
3. **Dynamic setup:** `StyleSequencer.kt:1080–1113` menahan CC0/32 per **source** channel hingga Program Change, memilih destination lewat policy, lalu memanggil native **tanpa voiceName**. PC dinamis tidak mengikuti prioritas PC override section. Nama lama native hanya dipertahankan bila identity tidak berubah. Perubahan CASM/chord/range, mute, reserved keyboard destinations dan note suppression harus direkam per event.
4. **Manual selection:** `MainViewModel.kt:476–488`, `:581–588`, `:622–625` memuat RIGHT, generic channel, dan style override. UI memilih nama berdasarkan raw bank/PC `firstOrNull`; request tidak membawa hash/role preset UI. `BassMidiPlayer::presetList` (`:1754–1868`) mengurutkan/deduplikasi per role, bukan menyatukan identity antar-source. Konflik bank/PC pada UI belum membuktikan source tertentu terpilih. Tidak ada setter khusus LEFT bank/PC yang ditemukan; jangan mengarang status voice LEFT sebelum/ sesudah atau menganggap default sudah terinisialisasi.
5. **Bridge bersama:** `AudioEngineManager.kt:370` → `NativeAudioBridge` → `native_lib.cpp:74–80` → `audio_engine.cpp:255–260` → **BassMidiPlayer**, bukan implementasi FluidSynth lama `SoundFontPlayer`. Native preset memisahkan role berdasarkan destination zero-based 8/9 (`bassmidi_player.cpp:1227–1357`). Aux destinations yang diberi hint drum oleh Kotlin belum tentu drum menurut native; capture destination aktual wajib.
6. **Load/refresh:** primary replacement menghapus optional pool dan me-resolve initialized channels; successful fallback load me-resolve channels dan memasang ulang FONTEX2. Load failure/rollback, generation, table-readback failure, no-compatible-family dan suppressed new note merupakan keputusan yang harus dicatat, bukan dianggap preset default yang sah.

## Pemilihan empat SF2 dan resolver melody

`MainViewModel.kt:853–965`/reload `:1110` memilih pasangan berdasarkan filename: SX melody mendapat skor tertinggi, SX Drum primary drum; Colombo preferred optional slot, Tyros additional optional slot. `AudioEngineManager.kt:158–190` memuat primary+drum sebelum optional fonts, optional failure tidak wajib menggagalkan core pair. Ini **algoritme selection**, bukan bukti bahwa seluruh empat file berhasil dimuat pada perangkat. Urutan folder dan keberadaan file harus dicatat.

`loadRole`/`loadMelodyFallback` (`bassmidi_player.cpp:348–499`) mempertahankan original path/cache tetapi memuat **salinan normalized melody**; optional pool maksimum dua font. `rebuildMelodyPresetCache` menolak raw127/128. `findMelodicPreset` (`:898–969`) memakai header `voice_resolver.h` yang sama dengan pengujian host:

- Role/family gate mendahului numeric match. Nama semantic menang atas GM PC; GM family hanya untuk request bank0, atau candidate GM-source bank0 dengan nama unknown.
- Request variation tanpa nama hanya didecode dari identity Yamaha yang konsisten; MSB104 tidak boleh mendapat identity numeric. Ambiguitas family gagal tertutup.
- Base skor1000; Yamaha exact +100000 atau MSB +90000 kecuali MSB104; nama exact +8000, partial +1200; acoustic guitar +700; PC sama hanya +40; Yamaha source +20; primary source0 +5. Skor bukan instruksi memilih file pertama.
- Tie-break menggunakan basename SF2/bank/PC/name. Mengganti semua basename menjadi nama master atau semua source index menjadi0 mengubah policy. Manifest harus mempertahankan identity dan priority asli.
- Request MIDI MSB126/127 ditolak melody; candidate raw127/128/percussion ditolak. **Raw SF2 bank126 berbeda dari request MSB126**: raw126 masih bisa lolos melody bila family tepat; percussion/SFX pada126 memerlukan review per preset.
- No compatible candidate menghasilkan `melodySource=-1` dan suppress new notes, bukan generic Piano fallback. Note-off/ownership tetap perlu dipertahankan.

## Namespace bank dan BASSMIDI

`normalizeMelodySf2` (`:648–735`) mengurutkan raw banks selain127/128 lalu mengalokasikan virtual0..126, maksimum127 bank melody per font. Hanya byte bank pada `phdr` ditulis di salinan. Raw>=128 menjadi destination raw/128:raw%128; raw<128 menjadi destination raw:0. Raw1025 → MIDI8:1; raw8 → MIDI8:0 pada mapping generic. Resolver dapat memilih raw8 untuk request8:1 kemudian membuat mapping per-channel ke request itu.

`applyFonts` (`:140–307`) memasang channel-resolved mapping lebih dahulu; kemudian dedicated drum raw127 lalu128 ke destination drum128/LSB0/channel9–10; generic primary+secondary melody mencakup channels1–8 dan11–16. `sbank/spreset` adalah normalized **source**; `dbank/dbanklsb/dpreset` adalah request **destination**; `minchan/numchan` membatasi destination. Flag **count | BASS_MIDI_FONT_EX2** wajib. Melodic preload memakai source virtual bank dan source PC terpilih, bukan request PC; NOWAIT/failure tetap perlu dicatat.

Staging allocation 1,148 row tidak tersedia di repository/sesi dan tidak disahkan tool ini. Raw SF2 bank16-bit, runtime source bank0..126, special drum127/128, serta destination MIDI MSB/LSB bukan namespace yang dapat saling ditukar. Unique staging addresses saja tidak cukup untuk GO.

## Drum PC73, konflik127/128, dan donor

`rebuildDrumPresetCache`/`findDrumPreset` (`:745–833`) memindai hanya raw127/128 pada font drum aktif (atau primary melody bila drum tidak dimuat), dalam urutan **phdr**. Ia memilih PC yang sama pertama; bila tidak ditemukan memakai entry drum pertama. Secondary melody tidak menjadi fallback kit langsung melalui fungsi ini. PC73 adalah **raw0-based**, ditampilkan MIDI74; bukan PC74.

Konflik lain: generic FONTEX2 selalu raw127 sebelum128, sedangkan cache/preload mengikuti phdr. Fixture dengan duplicate PC0 phdr128 lalu127 menunjukkan preload bank128 tetapi mock live readback bank127. Ini bukti perilaku fixture/mapping, **bukan klaim hasil real BASS atau empat binary asli**. Golden harus menyimpan expected/cache/preload **dan** actual readback; master mapping tidak boleh dianggap valid dari cache saja.

`percussion_fidelity_policy.h` dan `bassmidi_player.cpp:2130–2345` menambah jalur terpisah bila `YAMAHA_COMPATIBLE_PERCUSSION` aktif:

- Catalog dari drum, primary dan secondary; raw126 dapat menjadi donor, metadata harus corroborate drum context, key tunggal, pitch/root/tuning, finite non-looping sample, modulator, stereo pairs, exclusive group, dan velocity coverage1..127. Tidak ada pemilihan donor baru per velocity: bundle mempertahankan layer velocity SF2; event velocity tetap diteruskan.
- Audition evidence keyed original content hash/bank/PC/key/target identity. Donor score mengutamakan observed target, key sama, bank128 lalu127 lalu lainnya, layers/path/PC/key. Different Yamaha identities tidak boleh diam-diam memakai bundle sample yang sama tanpa evidence.
- Import/preload dibatasi8 presets. Exact requested kit yang tersedia menghentikan persiapan substitution; held owners/generation, verified table/controller dan first-note readback juga menentukan donor boleh dipakai.
- Actual substitution hanya untuk original style-bank127*128, source-note/key/channel cocok, requested PC/generation cocok. Unsupported articulation/key-off, unsafe layer atau failed readback tetap legacy/abstain.
- Auxiliary stream128 channels menggunakan lane=original key dan memainkan donor key; table memetakan donor font/native-bank/PC ke destination program0. Native font handle, stream, lane, played-key dan generation harus dicatat terpisah dari part channel.
- Owners dan directional choke (group64→96) memelihara OFF across repeated notes/transition/reload. Tidak boleh merutekan OFF melalui keputusan preset yang baru.

Naive merge bisa menambahkan exact PC73, mengganti first kit, mengubah127/128 priority, mengubah donor tie-break/provenance, dan kehilangan audition evidence.

## Gain trim

`measured_balance_profiles.h` mengenali Melody SHA `e8c735…` raw0:0=-6dB dan raw8:1=-6dB; Tyros SHA `de5b14…` raw0:49=+12dB. `updateMeasuredBalance` (`:2605–2636`) berlaku accompaniment zero-based10..15, memverifikasi actual font/virtual-bank/PC sebelum nonzero DSP; ramp960 frames pada48kHz. Keyboard/Rhythm tidak memakai fungsi trim ini. CC7/CC11/style-bus trim/master gain/font volume adalah dimensi berbeda dan harus dicatat.

Default lookup runtime berisi0dB/evidenceNONE; itu **bukan** pengukuran gain sumber lain. Manifest tetap `UNKNOWN` bila tidak ada measured evidence. Hash master baru tidak akan cocok dengan profiles lama; provenance adaptation perlu review terpisah setelah gates. Host boundary test memeriksa3 profiles, conversion dB dan kehilangan evidence pada hash baru; tidak membuktikan perceived balance.

## Fixture dan alat yang disiapkan

| Artifact | Fungsi / batas |
|---|---|
| `sf2_golden_policy_cases.json` | 9 request/pool sintetis, collision0:0, variation8:1, MSB104, raw126 percussion, MSB126/127, family reject; bukan inventory1,148 |
| `sf2_golden_boundaries_test.cpp` | unchanged production player + BASS mocks; PC73 absent/present, phdr-order fallback, raw126 normalization, conflict127/128 dan known trims |
| `sf2_golden_capture_coverage.json` | 80 LoveSong section/part tasks +4 stopped-keyboard isolation tasks; seluruh84 **UNVERIFIED**; section availability, silence/absence dan meter files UNKNOWN |
| `sf2_golden_capture_template.csv` | header contract persis; **zero observed runtime rows** |
| `sf2_golden_capture_metadata_template.json` | reference/hash identity, build/raw trace/master UNKNOWN; bukan capture |
| `sf2_resolver_reference.cpp` | pure header replay read-only TSV, semua candidate rejection/score/selection; tanpa BASS, native mapping/gain/donor/runtime fabrication |
| `sf2_golden_routing.py` | canonical CSV dan comparator context/provenance/fallback/donor original tuple/trim/native master projection; duplicates/staging/conflicting mapping/UNKNOWN ditolak |

Replay membaca kandidat TSV dengan `source-index,original-filename,raw-bank,raw-PC,name,melodic-flag` dan request TSV `fixture-id,packed-request-bank,raw-PC,name`. Pemisah TAB; tidak ada header, tab/newline tidak boleh ada dalam field. Input candidate harus tetap menggunakan original provenance/nama source, bukan nama master. Output ke stdout; tidak menulis SF2/engine.

```sh
c++ -std=c++17 -Wall -Wextra -Werror -O2 -I app/src/main/cpp tools/sf2_resolver_reference.cpp -o /tmp/sf2-replay
/tmp/sf2-replay candidates.tsv requests.tsv > decisions.tsv
python3 tools/test_sf2_golden_routing.py
python3 tools/test_sf2_checkpoint_host.py /path/to/verified-checkpoint-tree
python3 tools/sf2_golden_routing.py canonicalize reference.csv > canonical.csv
python3 tools/sf2_golden_routing.py compare reference.csv master.csv manifest.json reference-meta.json master-meta.json
```

Comparator exit0 hanya equivalence **field routing yang diperiksa**, exit1 mismatch, exit2 invalid/UNVERIFIED. Manifest JSON adalah list `source_sha256,original_bank,original_program,master_bank,master_program,validation_status=VALIDATED_RUNTIME`; marker itu klaim pihak capture yang harus dibuktikan eksternal, bukan approval otomatis. Address source native master dibatasi0..128/program0..127 untuk diagnostic projection, **bukan** validasi seluruh constraints FONTEX2/drum mapping.

Fixture IDs harus menyertakan stable event ordinal; raw capture order dibandingkan sebelum canonicalization. Canonical output mengurutkan IDs dan mengganti process handle dengan provenance alias; simpan raw trace beserta handle mapping untuk membedakan duplicate/auxiliary handles. Alat tidak mengautentikasi VERIFIED/build stamps, tidak menguji PCM, tidak mereplay sequencer/CASM, dan belum mengekstrak semua existing Android log menjadi event capture. Nilai donor-bank/program pada schema adalah original provenance; actual auxiliary native bindings, controller/envelope/pitchbend, note owner/source generation dan transition lifecycle perlu **sidecar raw trace** dan comparison terpisah. Dengan batas ini tool selalu melaporkan PCM UNVERIFIED dan master_export_go=false, termasuk saat field routing equivalent.

## Cara memperoleh golden nyata berikutnya

1. Gunakan checkpoint frozen dan4 binaries original verified; arsipkan provenance/hash, build Android/BASSMIDI, urutan import, status optional-font/preload/readback, style SHA dan raw logs. Jangan memakai checkout Voice & RELEASE.
2. Daftarkan section aktual LoveSong Main/Fill/Intro/Ending; setiap part harus punya observed NOTE/decision atau explicit silence/absent dengan alasan. Template UNKNOWN tidak diubah menjadi SKIP tanpa inspeksi. Tambahkan meter2/4,3/4,4/4,6/8 hanya jika style file/hash dapat diidentifikasi.
3. Existing `shadowDrumSnapshot()` (`:2641`) read-only STOP snapshot memuat FONT/BANK/LIVE/generation, tetapi hanya Rhythm dan tidak setiap event. `VOICE REQUEST/FINAL SELECT`, `SET PRESET`, `SECTION_PRESET/DYNAMIC_PROGRAM`, note-zone/role/presence/owner reports memberi bukti parsial. Jangan menganggap snapshot STOP golden chronological decisions. Jika capture event lengkap membutuhkan instrumentation, isolasikan pada diagnostic branch terpisah sesuai contract; pekerjaan ini tidak mengubah production.
4. Capture RIGHT1/2/3/LEFT stopped-style independently, before/after voice selection dan readback/NOTE ownership. LEFT ACMP off diperlukan untuk actual voice. Style capture meliputi chord/ACMP/CASM/CC0/32+PC, velocity boundaries, first note, repeats, fill transitions/reload generations dan owner OFF.
5. Derive manifest collision-free dari full original inventory; pertahankan source-specific policy, PC73 legacy fallback, donor layers/audition evidence dan gain evidence. Capture proposed master decisions kelak harus memproyeksikan original provenance, bukan membandingkan new master hash dengan original hash sebagai identity yang sama.
6. Lengkapi PCM reference/Android listening, bank mapping limits/special banks, SF2 sample/generator/modulator/stereo/loop preservation, import-time/font handle count/RAM/PSS. Baru tinjau GO untuk experimental export; produksi memerlukan gate terpisah.

## Pengujian dan keputusan

Hasil final dan raw stdout/stderr dicatat di `docs/SF2_GOLDEN_ROUTING_TEST_EVIDENCE.md` serta fixtures log. Semua angka hasil host adalah synthetic policy/mocked API, bukan routing nyata LoveSong. Bahkan output pengujian lama yang bernama “Love Song” menggunakan candidate fixture, bukan file style/SF2 user.

**NO-GO:** golden runtime rows=0; original SF2 dan actual LoveSong/meter files tidak tersedia; full1,148 manifest tidak tersedia; BASS Linux SDK/Android device/PCM tidak tersedia; staging/runtime allocation dan preservation belum dibuktikan. Tidak ada equivalent routing/audio four-original vs master yang diklaim. **GO terbatas:** audit, fixture, executable comparator, deterministic offline policy export, dan persiapan capture.
