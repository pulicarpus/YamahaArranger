#!/usr/bin/env python3
"""Fail-closed checks for the S1 observer; never edit production/expected files."""
from pathlib import Path
import copy
import json
import subprocess
import sys
import tempfile
import unittest

from audit_sff1_corpus import (Bytes, ConformanceError, MANIFEST, RESOURCE, ROOT,
                              chunks, file_sha, digest, write_json,
                              compact_reference, verify_compact_reference)


class Sff1HarnessFailureTests(unittest.TestCase):
    def run_failure(self, corpus, reason, *args):
        before = {path: file_sha(path) for path in (MANIFEST, RESOURCE)}
        with tempfile.TemporaryDirectory(prefix="sff1-negative-output-") as output:
            result = subprocess.run(
                [sys.executable, str(ROOT / "tools/audit_sff1_corpus.py"),
                 "--corpus", str(corpus), "--output", output, *args],
                cwd=ROOT, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
            self.assertNotEqual(0, result.returncode, result.stdout)
            self.assertIn(reason, result.stdout)
        self.assertEqual(before, {path: file_sha(path) for path in before},
                         "failure must never rewrite expected fixtures")

    def test_wrong_archive_identity_fails_before_extraction(self):
        with tempfile.TemporaryDirectory(prefix="sff1-negative-input-") as temp:
            archive = Path(temp) / "sff1.zip"
            archive.write_bytes(b"not the audited archive")
            self.run_failure(archive, "archive SHA256 differs from audited corpus")

    def test_incomplete_directory_is_not_a_partial_success(self):
        with tempfile.TemporaryDirectory(prefix="sff1-negative-input-") as temp:
            self.run_failure(Path(temp), "reference corpus must contain exactly 503 files")

    def test_record_never_overwrites_pinned_expected(self):
        self.run_failure(Path("unused-input"), "--record never overwrites expected fixtures", "--record")

    def test_vlq_and_data_readers_enforce_bounds(self):
        for data in (b"\x81", b"\x80\x80\x80\x80"):
            with self.assertRaises(ConformanceError):
                Bytes(data).vlq()
        with self.assertRaises(ConformanceError):
            Bytes(b"\x00").take(2)

    def test_casm_child_cannot_escape_parent_bounds(self):
        # The bytes exist in the file, but the declared parent ends earlier.
        data = b"Ctab" + (4).to_bytes(4, "big") + b"abcd"
        with self.assertRaisesRegex(ConformanceError, "CASM child exceeds parent"):
            list(chunks(data, 0, 10))

    def test_compact_reference_pins_all_provenance_and_generated_bytes(self):
        # Small independent examples exercise omitted event-level fields. Each
        # mutation must fail even when summary acceptance counts stay equal.
        manifest={"aggregate":{"styles":503},"styles":{"example":{
            "file_sha256":"corpus identity","bytes":123,
            "policy_records":[[1,2,3]],"missing_copies":[[4,5]],
            "overlapping_on":[{"raw_event_ordinal":6}],
            "sections":{"MainD":{"native_section_sha256":"plan"}}}}}
        manifest["deterministic_manifest_content_sha256"]=digest(manifest)
        with tempfile.TemporaryDirectory() as temp:
            full=Path(temp)/"full.json"
            write_json(full,manifest)
            reference=compact_reference(manifest,full)
            verify_compact_reference(manifest,full,reference)
            first=full.read_bytes()
            write_json(full,json.loads(first))
            self.assertEqual(first,full.read_bytes(),"serialization must be deterministic")
            for field in ("policy_records","missing_copies","overlapping_on","sections"):
                changed=copy.deepcopy(manifest)
                changed["styles"]["example"][field]=[]
                changed.pop("deterministic_manifest_content_sha256")
                changed["deterministic_manifest_content_sha256"]=digest(changed)
                write_json(full,changed)
                with self.assertRaisesRegex(ConformanceError,"complete manifest/digest drift"):
                    verify_compact_reference(changed,full,reference)
            write_json(full,manifest)
            full.write_bytes(full.read_bytes()+b"\n")
            with self.assertRaisesRegex(ConformanceError,"complete manifest/digest drift"):
                verify_compact_reference(manifest,full,reference)

    def test_compact_reference_rejects_inconsistent_content_digest(self):
        with self.assertRaisesRegex(ConformanceError,"content digest is inconsistent"):
            compact_reference({"deterministic_manifest_content_sha256":"tampered"},Path("unused"))


if __name__ == "__main__":
    unittest.main(verbosity=2)
