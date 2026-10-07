#!/usr/bin/env python3
import unittest
import os
import tempfile
from pathlib import Path
from unittest.mock import patch
import sff_dialect_source_guard as guard
from sff_dialect_source_guard import ROOT, source_rules, strip_metadata, validate_source


class DialectSourceGuardTests(unittest.TestCase):
    def test_exact_new_and_recovered_old_profiles_are_pinned(self):
        for path in source_rules():
            data=(ROOT/path).read_bytes()
            self.assertTrue(validate_source(path,data))
            old=strip_metadata(path,data.decode()).encode()
            self.assertFalse(validate_source(path,old))

    def test_any_source_mutation_even_a_comment_is_rejected(self):
        for path in source_rules():
            data=(ROOT/path).read_bytes()
            with self.assertRaisesRegex(AssertionError,"source identity drift"):
                validate_source(path,data+b"\n// unauthorized\n")

    def test_old_profile_requires_explicit_baseline_selection(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp)
            for path in source_rules():
                destination=root/path
                destination.parent.mkdir(parents=True,exist_ok=True)
                destination.write_text(strip_metadata(path,(ROOT/path).read_text()))
            with patch.object(guard,"ROOT",root):
                with patch.dict(os.environ,{"SFF_DIALECT_SOURCE_PROFILE":"S2"}):
                    with self.assertRaisesRegex(AssertionError,"Unexpected source profile"):
                        guard.verify_metadata_sources()
                with patch.dict(os.environ,{"SFF_DIALECT_SOURCE_PROFILE":"S1_BASELINE"}):
                    self.assertEqual({},guard.verify_metadata_sources())

    def test_unlisted_metadata_block_is_rejected(self):
        with self.assertRaisesRegex(AssertionError,"Unapproved dialect metadata"):
            strip_metadata("not-authorized.kt","/* SFF_DIALECT_METADATA_BEGIN */x/* SFF_DIALECT_METADATA_END */")


if __name__=="__main__":
    unittest.main(verbosity=2)
