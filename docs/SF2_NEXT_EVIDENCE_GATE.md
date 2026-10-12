# SF2 next gate — original phdr and LoveSong golden capture

Date: 2026-10-12. Planning branch: `plan/sf2-single-master`.
Reference: `c75122daf5d1077db55d50d1c261144fde59af4a`. Input audit: `docs/SF2_REAL_INVENTORY_ROUTING_AUDIT.md` and commit `373949d2c9b4f4d7822c02f1ef750a70848a772b`.

**Decision: GO for read-only evidence acquisition; NO-GO for SF2 master export, loader edits, style edits, or merges.**

## Gate A — Original preset header order
Source binaries are not in GitHub. All 1,148 `original_phdr_order` values remain UNKNOWN. The metadata CSV is sorted/normalized and cannot reconstruct binary order.

Create a read-only local extractor that reads the `phdr` list from each of four ORIGINAL SF2 binary files and outputs `source_sha256,original_phdr_order,bank,program,name`. Verify source SHA256 against `tests/fixtures/percussion_inventory_fingerprints.json`; verify each source's preset count and multiset of `bank,program,name` against existing 1,148 metadata rows. Handle SF2 EOP terminal header explicitly and reject malformed/duplicate/missing entries. Preserve original ordering; do not sort until after recording ordinals. Never copy or commit copyrighted/large original SF2 binaries.

If original binaries unavailable, implement and test parser with small synthetic fixtures, mark **WAITING_FOR_ORIGINAL_SF2**; do not fabricate phdr order or first drum fallback. Output a standalone script and Android/Windows-friendly usage instructions; do not require the user to turn on laptop immediately.

## Gate B — LoveSong routing evidence
Identify whether exact LoveSong style fixture exists in repository; do not infer from similarly named files. Define deterministic capture of original resolver request (destination, MIDI MSB/LSB/PC, family/name, section, tick, chord, fallback, source SHA, native bank/PC, per-key donor and gain) for Main/Fill/Intro/Ending where available. Read-only host replay may be built only if it uses frozen production logic and exact style fixture. Label mock/synthetic separately. Actual Android/BASSMIDI runtime/PCM remains UNVERIFIED until collected. Do not modify production/style/loader or create APK.

## Guard investigation
Historical guard failures are known checkpoint mismatches, not new evidence of playback failure. Document exact old expected vs checkpoint blob SHA and scope, without editing guard baselines.

## Acceptance and handoff
- Preserve existing CSV hashes and all 158 collision reports.
- New scripts/tests must be deterministic and fail closed on missing binaries/fixtures.
- Report PASS/FAIL/UNVERIFIED separately for original binary order, resolver host replay, Android/BASSMIDI PCM, drum donor.
- No SF2 master build, no workflow/build changes, no production edits, no change to `main`, READY, style checkpoint, Voice & RELEASE.
