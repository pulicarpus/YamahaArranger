# SF2 source verification gate — 2026-10-12

Status: **WAITING FOR FOUR ORIGINAL FILE SHA256S**. No SF2 merge, loader edit, style edit or APK build is authorized by this document.

The user has 4 original SF2 files in `D:\SF2` and a local inventory `D:\SF2\SF2_INVENTORY_REPORT\SF2_PRESET_INVENTORY.csv`. The assistant provided a read-only Python 3 script `sf2_verify_sources.py` in the conversation for the user to place in `D:\SF2` and run with `py sf2_verify_sources.py`. This script computes SHA256 streaming and re-parses RIFF `pdta/phdr` to compare the exact (source, bank, program, preset name) tuples with the local inventory. Output: `SF2_INVENTORY_REPORT\SF2_SOURCE_VERIFICATION.csv`. **The script is not yet checked into the repository and has not been run against the user's original binary files by the assistant.**

Expected preset counts: Colombo 892; SX700/SX900 Drum 21; SX700/SX900 Melody 93; Tyros4 142. The 1148-row provisional staging manifest has unique (staging_bank, staging_program) addresses but staging addresses are NOT approved BASSMIDI runtime mappings. 158 cross-file raw bank/PC collision groups exist.

Next gate: require all four rows to show `OK`, record the hashes, compare hashes to existing repository fingerprint metadata, then reconcile source provenance, bank 126/127/128 drum and SFX semantics, fallback PC73, per-source gain trims, and source-specific voice resolver. Until then, do not create a master SF2 or change `main`.
