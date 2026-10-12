# SF2 source verification gate — 2026-10-12

Status: **SOURCE SHA256 VERIFIED / NEXT: ROUTING MANIFEST**. No SF2 merge, loader edit, style edit or APK build is authorized by this document.

The user has 4 original SF2 files in `D:\SF2` and a local inventory `D:\SF2\SF2_INVENTORY_REPORT\SF2_PRESET_INVENTORY.csv`. The assistant provided a read-only Python 3 script `sf2_verify_sources.py` in the conversation for the user to place in `D:\SF2` and run with `py sf2_verify_sources.py`. This script computes SHA256 streaming and re-parses RIFF `pdta/phdr` to compare the exact (source, bank, program, preset name) tuples with the local inventory. Output: `SF2_INVENTORY_REPORT\SF2_SOURCE_VERIFICATION.csv`. **The script is not checked into the repository. The user ran it locally and supplied its CSV output; assistant verified reported hashes against repository fingerprints.**

Expected preset counts: Colombo 892; SX700/SX900 Drum 21; SX700/SX900 Melody 93; Tyros4 142. The 1148-row provisional staging manifest has unique (staging_bank, staging_program) addresses but staging addresses are NOT approved BASSMIDI runtime mappings. 158 cross-file raw bank/PC collision groups exist.

All four rows show `OK` and `inventory_match=YES`; SHA256s match `tests/fixtures/percussion_inventory_fingerprints.json` exactly:

| Source | SHA256 |
|---|---|
| ColomboGMGS2_BM.sf2 | `9e4686e4aa7173ffd1baf7ec6f80fcb3fe90880e824cf8c4e1defb5d153d47eb` |
| DRUMKIT YAMAHA PSR-SX700 & SX900 PRIME.sf2 | `a72158c09677c585eec348762a33da49509bf338c5b36d0e787af58cca0b9f8a` |
| MELODI YAMAHA PSR-SX700 & SX900 PRIME.sf2 | `e8c7356159c200d945f13e113595ff06e7955779b0e9361709d9c7cdba164a82` |
| yamaha tyros 4_just_t4_fixed.sf2 | `de5b1404630840a2a897e77c6083e2231c5d1523e6571a8afd972a4f684d36ce` |

Next gate: reconcile source provenance, bank 126/127/128 drum and SFX semantics, fallback PC73, per-source gain trims, and source-specific voice resolver. Until then, do not create a master SF2 or change `main`.
