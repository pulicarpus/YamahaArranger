# Bukti pengujian golden routing preparation — 2026-10-12

**PASS host preparation; NO-GO master export.** Reference `c75122daf5d1077db55d50d1c261144fde59af4a`; planning parent `942ab1ffb9fe27767ace34ed60678fa0807699ee`. Pengujian pada snapshot scratch terpisah; 84 text files cocok Git blob checkpoint/planning parent dan SHA256 sebelum compile. Runner menolak perubahan fingerprint. Tidak ada APK/Gradle atau SF2 master dibangun.

| Perintah | Hasil final |
|---|---|
| `python3 tools/test_sf2_golden_routing.py` | PASS12 tests; pure checkpoint C++ policy replay9 synthetic requests byte-identical pada2 replay |
| `python3 tools/test_sf2_checkpoint_host.py` | PASS11 host targets,761 checks; unchanged production BassMidiPlayer dengan existing BASS mocks |

| Host target | Checks | Bukti / batas |
|---|---:|---|
| voice_resolver_test | 210 | role/family/source priority/tie/semantic fallback; output “Love Song” adalah synthetic pool |
| native_resolver_test | 70 | channel FONTEX2/resolution/fail-closed/optional source refresh |
| bank_translation_readback_test | 63 | normalized vs original bank/PC, original file tidak ditulis, destination LSB |
| accompaniment_presence_test | 32 | admitted/suppressed/absent decision; mock |
| percussion_fidelity_test | 238 | PC73 donor key/velocity/articulation safety, ownership/choke/generation/reload/failure paths |
| f03_native_lifecycle_test | 8 | NOTE ownership/lifecycle; PCM/native SDK tidak diukur |
| sf2_zone_diagnostic_test | 30 | key/velocity/layer metadata |
| audio_path_diagnostic_test | 48 | observational diagnostics |
| drum_kit_audit_test | 15 | kit metadata |
| drum_compatibility_report_test | 30 | compact export metadata |
| sf2_golden_boundaries_test | 17 | PC73 absent/present, phdr fallback order, bank126/127/128 conflict,3 gain profiles/hash change |
| Total | 761 | host/mocked API; tidak ada klaim original PCM |

Twelve diagnostic tests memeriksa event/context/source/trim/donor mutations, extra/missing event, event ordering, staging/duplicate/conflict mapping rejection, master native address projection, UNKNOWN gain/build/status rejection, nonfinite trim, duplicate CSV ID, canonical process-handle normalization,84 unverified coverage tasks, deterministic policy replay, serta CLI exit0/1/2. Bahkan synthetic equality exit0 tetap mengeluarkan `pcm=UNVERIFIED` dan `master_export_go=false`.

Raw outputs: `tests/fixtures/sf2_golden_diagnostic_test_output.txt` dan `tests/fixtures/sf2_golden_checkpoint_test_output.txt`; log SHA256, compiler/Python dan jumlah hasil tersedia pada `tests/fixtures/sf2_golden_test_results.json`. Data VERIFIED dalam unit-test dibuat in-memory memakai hashes sintetis a/b/c/d; tidak ada observed runtime row yang dimasukkan ke template CSV (header saja).

Selama persiapan, `s8_native_voice_test` sempat dicoba dengan mocks dan gagal link karena membutuhkan real SDK/wrapped native calls. Target itu dikeluarkan dari runner host dan **tidak diklaim PASS**. Diagnostic boundary fixture sempat gagal compile karena include JNI belum ditambahkan; sudah dikoreksi hanya pada fixture, lalu seluruh runner final dijalankan ulang dan lulus. Real BASS/PCM tests (`test_percussion_real_bass.py`, `test_measured_balance.py`, `test_s8_native_voice.py`) membutuhkan SDK/libraries yang tidak tersedia; belum dijalankan pada sesi ini. Full aggregate `tools/test_voice_resolver.py` dan JVM/Android suite tidak dijalankan; subset terkait routing di atas dijalankan secara langsung untuk menghindari build APK/workflow dan dependency yang belum tersedia.

Sumber SF2 laptop, full1,148-row manifest, style LoveSong dan actual2/4,3/4,4/4,6/8 fixtures, reference/candidate raw runtime trace, real Android/BASSMIDI build identity, PCM listening/reference, master bank constraints dan preservation/RAM/PSS masih belum tersedia atau belum tervalidasi. Source verification dari gate adalah bukti user sebelumnya, bukan rehash binary pada sesi ini.

**Keputusan:** GO untuk persiapan capture; NO-GO untuk pembuatan master. Golden capture rows=0. Lihat `docs/SF2_GOLDEN_ROUTING_AUDIT.md` untuk routing paths, namespace bank, risks PC73/127–128/donor/hash trim, cara menjalankan alat dan daftar capture berikutnya.
