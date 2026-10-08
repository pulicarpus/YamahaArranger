# S7 — F03 backend & ownership contract verification

## Identitas, mode dan keputusan

Repository `pulicarpus/YamahaArranger`, branch `fix/mix-percussion-fidelity-784`.
HEAD lokal dan remote sebelum S7:
`0faa84f1302a6059d79b57f3de1401c2f4ee90a7`, direct parent
`b151919bd9192098d9b8cfd8c3f2cc0d5772b02f`.
[Build S6 #800](https://github.com/pulicarpus/YamahaArranger/actions/runs/37751811580)
SUCCESS. Semua 346 berkas tracked baseline S6 tetap byte-identical, termasuk
produksi, workflow, guards, golden dan seluruh fixture F03. S7 hanya menambah
dokumen, fixture evidence, source test dan runner diagnostik.

Investigasi raw corpus/parser/first divergence S1–S6 tidak diulang. Bukti 523
overlap/47 style, 276 replacement dan 31 shortened dispatch intervals tetap
bersumber dari fixture S5 yang dibekukan. S6 controls dijalankan sebagai regresi.
F01 tetap UNRESOLVED, F01–F15 ledger tidak diubah, Stage 3 tidak diaktifkan.

**Belum ada kontrak pairing yang disertifikasi aman untuk implementasi F03.**
FIFO dapat menjadi kandidat untuk subset overlap dengan kontrak event/backend
yang cocok; bukan oracle Yamaha. Dua source yang konvergen ke destination/pitch
sama, rejected native ON, stale work dan carry harus memperoleh kontrak terpisah.
Tidak ada FIFO/LIFO/count/retrigger yang diterapkan pada playback.

## Tiga identitas yang tidak boleh disamakan

| Identitas | Observasi tersedia | Yang belum dibuktikan |
| --- | --- | --- |
| Logical owner | Source/key, diagnostic origin, section, tick, dst/pitch; registry dan dispatch produksi dari S5/S6 | Musical ON/OFF pairing intent Yamaha; generation discriminator aktual |
| Native voice instance | SDK event request/return bila real probe tersedia; flag stream; API recorder hanya request | Selective voice handle/token; voice yang dipilih receiver untuk seluruh kasus |
| Audible voice | PCM float dari probe SDK Linux sintetis bila dieksekusi | PCM/style/timbre/device/perceptual effect pada Android atau PSR-E343 |

`AudioPathOrigin` adalah metadata diagnostic, bukan handle untuk memilih native
voice. `BassMidiPlayer::noteOff` mengirim `MIDI_EVENT_NOTE` dengan destination
channel dan key, velocity0. `noteOn` dan `noteOff` production return `void` ke
caller, sedangkan private `send` mengetahui hasil bool API. Logical registry
tidak mendapatkan acknowledgment native dari signature tersebut. External
`sendNoteOn`/`sendNoteOff` juga tidak mengembalikan acknowledgment receiver.

## Matriks kontrak ownership

Fixture contract sintetis: A ON/t0/v96, B ON/t1/v32, source/key sama,
destination11/pitch60; OFF/t2, OFF/t3 tanpa instance ID dalam wire packet.
`StyleMixFidelityS7BackendContractTest` menjalankan model alternatif **di test source saja**.
Model bukan native voice simulation yang diklaim sebagai bukti audio.

| Kandidat | Pairing/model dispatch | Prasyarat aman | Risiko / keputusan S7 |
| --- | --- | --- | --- |
| FIFO per source/key | ON A, ON B, OFF A/t2, OFF B/t3 | OFF authored memang FIFO; semua admission tercatat; backend menerima multiplicity dan release order selaras | Candidate terbatas; bukan Yamaha-certified. Cross-source output collision belum diselesaikan |
| LIFO per source/key | ON A, ON B, OFF B/t2, OFF A/t3 | Authored OFF dan backend release memang LIFO atau punya selective handle | Dapat cocok untuk kontrak lain; bertentangan dengan backend FIFO jika tidak ada handle. Tidak dipilih |
| Coalesced reference counting | ON A/t0, OFF shared/t3; ON B dan OFF pertama disuppress | Authored voices boleh digabung secara eksplisit | Menghilangkan attack/velocity B, memperpanjang A. Sound behavior berubah; bukan instance-preserving fix |
| Forward-all-ON + suppress OFF sampai count0 | Dua ON tetapi hanya satu OFF di akhir | Backend satu OFF bisa melepas semua voices dan kebijakan tersebut disetujui | Jika NOTEOFF1 melepas satu instance, satu voice dapat tetap hidup. Count equality bukan native cleanup proof |
| Retrigger / one-slot replacement | ON A/t0, OFF A/t1, ON B/t1, OFF B/t2; OFF/t3 orphan | Authored retrigger harus sengaja menjadi kontrak | Menghilangkan overlap; menyerupai defect S5/S6. Tidak memenuhi interval-preservation FIFO |
| Immutable instances + per-output accounting | Token logical/section/session + dst/key queue, explicit release | Pairing approved, admission diketahui, native/receiver contract cocok atau certified scope restriction | Kandidat arsitektur; belum implementation-ready secara umum |

Source FIFO dan destination FIFO adalah dua urutan berbeda. Counterexample
model: source4 A ON/t0, source5 B ON/t1, source5 OFF/t2. Registry per-source
ingin melepas B; hypothetical native destination-FIFO melepas A. Reference
counting atau mengubah FIFO menjadi global FIFO tidak otomatis mengizinkan
mengubah authored lifetime. Menunda/reorder OFF, menambah channel, coalescing
atau suppressing events adalah perubahan musikal/routing yang perlu persetujuan.

## Hasil verifikasi BASSMIDI

### Source dan API recorder, terverifikasi lokal

Player produksi tetap menggunakan `BASS_MIDI_NOTEOFF1` saat stream dibuat.
Komentar source mengatakan oldest matching instance, tetapi komentar itu tidak
menjadi bukti eksperimen/library specification. Dokumentasi resmi dan SDK
download lokal `https://www.un4seen.com/...` mengembalikan HTTP403. Tidak ada
bypass proxy, TLS/checksum verification atau penggunaan SDK palsu sebagai SDK asli.

`tests/s7_backend_contract_test.cpp`, mode recorder, mengompilasi player asli
dengan test doubles yang sudah ada. **6 checks PASS**:

- Loader dan flag produksi tetap terpakai.
- ON A/source4/v96 dan ON B/source5/v32 menjadi values `60|(96<<8)` dan
  `60|(32<<8)` pada destination11.
- OFF B lalu OFF A serta OFF A lalu OFF B sama-sama menghasilkan values
  `[60,60]`. Source-origin tidak menjadi selector voice.
- Injected failed ON tetap merupakan recorded attempt, lalu admitted B dan OFF
  A menghasilkan request key60 tanpa token native A/B.

Recorder tidak mensimulasikan oldest/newest/native PCM. Runtime release voice
tetap UNKNOWN pada hasil recorder. Enam checks tersebut bukan enam audio tests.

### Probe SDK asli yang dapat dijalankan CI

[`test_s7_backend_native.py`](../tools/test_s7_backend_native.py) selalu menjalankan
recorder dan, jika headers/libs resmi sudah tersedia, memakai existing Linux
x86_64 SDK roots `/tmp/bass-linux` dan `/tmp/bassmidi-linux`. Workflow baseline
mengunduh keduanya lewat HTTPS sebelum Android unit tests; workflow tidak diubah.
JUnit S7 memanggil probe terisolasi ini. Local mode real SDK **BLOCKED** karena
headers/libs tidak tersedia; test recorder PASS tidak dipromosikan menjadi PCM PASS.

Real mode link ke SDK asli dan player asli. Link wrappers merekam return API
dan stream flags; hanya satu kasus memakai explicit test-wrapper ON rejection.
SF2 sintetis dari existing fixture generator memiliki satu zone/sample; mixer
test menonaktifkan send reverb/chorus melalui API publik, tanpa mengubah produksi.
Fixture, library versions dan SHA256 headers/libs dicatat; samples bukan corpus
Yamaha dan bukan soundfont perangkat pengguna.

| Eksperimen SDK | Event/sample-frame positions | Observasi yang direkam |
| --- | --- | --- |
| Reference A saja | ON A/v96/frame0 | Nonzero finite PCM signature A |
| Reference B saja | Silence512 frames, ON B/v32/frame512 | Nonzero finite PCM signature B, distinguishable dari A |
| Cross-source | A/source4@0, B/source5@512, OFF ber-origin B@1024 | Residual PCM dibanding kedua references, source attribution vs surviving signature |
| Same-source | A/source4@0, B/source4@512, OFF ber-origin B@1024 | Kontrol same key/source; bandingkan signature seperti cross-source |
| Rejected A | ON A rejected oleh wrapper, ON B accepted, OFF ber-origin A | Rejection injection eksplisit; record residual energy dan API accepted counts |
| Cleanup | Dua ON, `allNotesOff()` | Record residual energy pada release-settling window; bukan voice-handle inventory |

Window analisis adalah frame1280–1535: 256 frames awal setelah OFF diabaikan.
Sample/velocity/time dan SF2 release configuration adalah kontrol test, bukan
device timing. Survivor diklasifikasi FIRST_SIGNATURE atau SECOND_SIGNATURE
hanya jika normalized squared error <1e-5 terhadap satu reference dan >0.01
terhadap yang lain; selain itu UNKNOWN. Finite/nonzero references dan perbedaan
signature wajib, jadi zero/silent probe tidak dianggap berhasil.

`real_sdk: PASS` di proof berarti eksperimen SDK berjalan dan sanity gates lulus;
pairing tetap dibaca dari metrics/classification, bukan disimpulkan dari exit0.
Release tail, layered samples, sustained notes, pedal, stealing, filters, other
sample rates, Android library version dan arbitrary soundfonts belum certified.
Native voice handles tidak diamati. Bahkan SECOND_SIGNATURE hanya membuktikan
residual synthetic PCM sesuai B dalam eksperimen tersebut, bukan seluruh voice
selection semantics atau correctness Yamaha.

Hasil real mode untuk commit ini dicatat oleh CI di
`build/s7-native/proof.json` dan stdout Android unit test. Local result BLOCKED
tidak disamarkan menjadi hasil CI. CI outcome dan keteraksesan metrics final
dilaporkan pada laporan akhir commit-specific, tanpa menebak dari Build800.
JUnit XML class S7 cocok dengan existing `TEST-*StyleMixFidelity*.xml` artifact
pattern, sehingga stdout/proof dapat disimpan dalam `mix-percussion-proof`
tanpa mengubah workflow atau menimpa berkas proof baseline.

## Expected versus actual dan batas bukti

| Skenario | Expected explicit contract | Actual tersedia | Status |
| --- | --- | --- | --- |
| Raw overlap case007 S5 | Dua distinct ON identities; FIFO intervals hanya observer contract | Owner replacement dan first OFF memilih latest owner; intervals terpotong relatif FIFO | PROVEN registry/dispatch; authored/audio intent UNKNOWN |
| Cross-source output collision | source5 OFF melepas logical B saja | Native request key60; actual MIDI OFF `[0x8b,60,0]`, sama untuk A/B | PROVEN identity erasure; voice selection UNKNOWN tanpa real metrics |
| Native ON rejection | Rejected A tidak memiliki native voice; OFF A tidak boleh memakan admitted B dalam fixture contract | Recorder menerima attempt, bridge tidak memberikan admission ack; real rejection experiment terisolasi bila SDK tersedia | API/admission gap PROVEN; real driver failure occurrence UNKNOWN |
| Natural carry S6 | A MainD boleh hidup ke FillBB dan diakhiri explicit FillAA OFF | Owner object tetap sama, OFF menggunakan stored destination11 | PASS control, carry correctness relatif synthetic contract |
| Interrupted MainD→FillBB→FillAA→MainA | Release outgoing snapshot; incoming overlap punya own identity | No ALL_OFF; boundary cleanup lulus; incoming replacement tetap terlihat | PASS cleanup control, bukan GUI/device fidelity |
| Injected stale section OFF S6 | Old MainD OFF tidak melepas new FillBB owner dalam explicit fixture | Stored new destination12 dipilih oleh source/key-only lookup | PROVEN under injection; spontaneous late task/race UNKNOWN |
| Session restart / stale owner object S5 | Stale object tidak menghapus replacement | `releaseActive` object guard bekerja; scheduled key-only path berbeda | PASS stale-object control; bukan complete stale-task contract |

First divergence S6 tidak berubah: `StyleSequencer.kt:969–975` replacement
sebelum transform; scheduled OFF lookup line550 hanya source/key. S7 tidak
mengulang traversal corpus atau memperbaiki map tersebut.

## Natural carry, interrupted cleanup dan generation

Session epoch dan section/iteration generation harus dibedakan. Stale work dari
session yang sudah dibatalkan perlu ditolak sebelum wire dispatch, sedangkan
owner yang memang dibawa ke section berikut dalam session yang sama tidak boleh
dibunuh oleh blanket generation mismatch atau semua OFF lintas-section dibuang.
Carry harus mengacu exact live instance yang diizinkan kontrak. Interrupted
transition cleanup harus dapat melihat seluruh admitted instances, bukan hanya
satu slot yang tersisa. Object identity guard tidak menggantikan scheduled event
identity dan native admission evidence.

S6 controls diuji ulang, bukan dianggap sebagai capture perangkat: interrupted
ON ticks `[0,1,2,5,6,9,10]`, variant MainC→FillDD→MainB
`[0,1,2,5,6]`; natural carry dan explicit old-OFF injection tetap menghasilkan
transcript baseline yang sama. Keyboard/ACMP and rhythm guards tetap aktif.

## Risiko MIDI OUT dan PSR-E343

Production serializer diuji langsung: dua ON destination11/key60/v96,v32
menghasilkan `[155,60,96]`, `[155,60,32]`; kedua OFF `[139,60,0]`.
Destination11 internal adalah MIDI channel12 dalam numbering 1–16. Tidak ada
source, ordinal, note-instance atau generation dalam packet. Port mock menguji
bytes, bukan penerimaan atau suara PSR-E343.

PSR-E343 receive repeated-note pairing, sustain/release behavior, polyphony/
voice stealing dan transport ordering **UNKNOWN**. Tidak ada device capture,
firmware-specific oracle atau manual yang diverifikasi untuk klaim itu di S7.
Jangan menyamakan receiver dengan BASS NOTEOFF1 atau dengan model FIFO/LIFO.

Perlu capture input packets dan output audio/time-aligned dari PSR-E343 nyata:
dua same-destination/pitch ON dengan velocity/attack berbeda; OFF pertama dan
kedua; opposite explicit source OFF order; CC64 states; stop CC123; Main/Fill.
Receiver config, local control dan loopback routing harus dicatat. Production
chord input default channel0 berbeda dari style destination11, tetapi setup
hardware dapat mengubah routing; fixture bukan verifikasi konfigurasi perangkat.
Existing missing-port, MIDI OUT disabled dan send-exception controls tetap PASS.
Internal accepted audio tidak berarti external output berhasil, dan sebaliknya.

## Rekomendasi rancangan terkecil yang aman — belum implementation-ready

Pertahankan immutable logical instance identity, original event provenance,
source/key, section/iteration, session epoch, current dst/pitch, transform/RTR
context dan admission state per backend. Per-source collection saja tidak cukup;
per-output destination/key accounting harus mendeteksi competing sources dan
release order yang tidak dapat diekspresikan oleh backend.

Untuk kandidat scope paling kecil, pilih hanya same-source/key overlap dengan
explicit approved pairing dan matching tested receiver contract, lalu nyatakan
cross-source convergence, rejected admission dan unspecified carry di luar
certified scope sampai ada evidence. Scope restriction adalah keputusan desain
yang perlu disetujui, bukan gate/drop baru yang ditambahkan diam-diam di S7.

Jangan memilih refcount/coalescing/retrigger untuk menyembunyikan mismatch.
Jika selective release yang diperlukan tidak dapat diekspresikan oleh backend,
ownership queue saja tidak menyelesaikannya. Backend-aware changes, additional
channels, suppression atau OFF reordering memerlukan proposal/perizinan terpisah.

## Syarat sebelum implementasi F03

1. Persetujuan eksplisit pairing contract dan certified scope; FIFO tidak
   diasumsikan Yamaha. Definisikan rejected ON, orphan OFF dan cross-section carry.
2. Native SDK experiment repeatable, versions/hashes pinned untuk Android target
   juga; extended looped/layered/sustain/release/stealing matrix dan observable
   admission. Software PCM harus dipisahkan dari voice handles dan device audio.
3. Per-output cross-source collision contract atau explicit exclusion approved;
   proof bahwa requested OFF memilih intended voice pada target backend/receiver.
4. Capture PSR-E343 sesungguhnya dan Android real-session Main/Fill sebelum
   mengeklaim hardware duration, voice identity atau audible F03 fix.
5. Deterministic stale-session cancellation tests, natural carry preservation,
   interrupted cleanup semua instances, RTR/chord/ACMP/remap controls.
6. Original `sff1.zip` dengan SHA256
   `a9b89b90e1dee25c714a076a125d6c7173bcca0e8d2029c57092b3295035d465`
   untuk strict rerun503/503; original matrix/root corpus checks yang memerlukannya.
7. Explicit F03-only expected deltas; non-F03 golden/ledger/source guards tetap.
   Tidak ada automatic fixture re-record, regression guard deletion atau Stage3.
8. Full available host/Android unit tests, dua ABI/Stage3 exclusion dan rollback
   ke S6; tidak memakai zero-test/skip/dispatch-only sebagai audio success.

## Status regresi dan CI

Commands yang dijalankan lokal pada repo root:

```sh
python3 tools/test_voice_resolver.py
python3 tools/test_sff1_pipeline.py --existing-regressions --s2 --s3 --s4 --s5
python3 tools/test_s7_f03.py
python3 tools/test_sff1_harness.py
```

| Pemeriksaan | Status lokal | Batas |
| --- | --- | --- |
| Full repository host regression runner | PASS | Mock native/routing scopes tidak menjadi audio claims |
| JVM baseline S1–S5 | PASS 83 tests | 20 golden captures dan 1046 S5 dispatch traces pinned |
| S6 controls + S7 | PASS 11 tests = 4 S6 + 7 S7 | 5 model tests, 1 production serializer test, 1 native recorder/probe orchestration |
| S7 native API recorder | PASS 6 checks | Runtime native voice/PCM UNKNOWN |
| SFF1 fail-closed harness | PASS 7 tests | Bukan corpus503/503 |
| Fixture/source preservation | PASS | 191 guarded files dan seluruh 346 baseline tracked files tetap identik |
| Local real BASS SDK experiment | BLOCKED | HTTP403; SDK files tidak tersedia; tidak ada bypass |
| Original corpus503/503 dan corpus-dependent S2/S3/S4 reruns | BLOCKED | ZIP tidak tersedia; baseline evidence preserved, tidak dites ulang |
| PSR-E343 / Android device / native voice handles | UNRUN / UNKNOWN | Tidak ada device/voice-instance capture |
| Failed available local tests | FAIL: none | UNKNOWN dan BLOCKED tidak dihitung sebagai PASS |

JVM host memakai JDK21/target17 dengan SHA-pinned dependencies baseline.
Workflow lama otomatis menemukan class S7 dan menjalankan native probe pada
mesin CI yang memiliki real SDK; tidak ada workflow atau production edit.
CI commit-specific status, actual native metrics jika dapat diakses, dan
artefact access limitations dicatat pada laporan akhir. Success Build800 tidak
dipakai sebagai hasil CI S7. Identitas commit dokumen ini dapat dibaca lewat
`git log -1 --format='%H %P %T' -- docs/S7_F03_BACKEND_CONTRACT.md`.

**STOP setelah S7. Tidak ada implementasi F03 atau S8 tanpa persetujuan eksplisit.**
