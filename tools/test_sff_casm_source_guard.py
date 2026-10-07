#!/usr/bin/env python3
import os,tempfile,unittest
from pathlib import Path
from unittest.mock import patch
import sff_casm_source_guard as g
class SourceGuardTests(unittest.TestCase):
 def test_exact_s3_and_s2_recovery(self):
  for path,r in g.source_rules().items():
   data=(g.ROOT/path).read_bytes();old=g.validate_source(path,data)
   self.assertNotEqual(old,data);self.assertEqual(g.validate_source(path,old),old)
 def test_any_byte_mutation_is_rejected(self):
  for path in g.source_rules():
   with self.assertRaisesRegex(AssertionError,'identity drift'):g.validate_source(path,(g.ROOT/path).read_bytes()+b'\n// unapproved\n')
 def test_unlisted_metadata_is_rejected(self):
  with self.assertRaisesRegex(AssertionError,'Unapproved S3'):g.normalize_s3('other.cpp',b'/* SFF_CASM_METADATA_BEGIN */x/* SFF_CASM_METADATA_END */')
 def test_old_profile_requires_explicit_baseline_selection(self):
  with tempfile.TemporaryDirectory() as tmp:
   root=Path(tmp)
   for path in g.source_rules():
    old=g.validate_source(path,(g.ROOT/path).read_bytes());dest=root/path;dest.parent.mkdir(parents=True,exist_ok=True);dest.write_bytes(old)
   with patch.object(g,'ROOT',root):
    with patch.dict(os.environ,{'SFF_CASM_SOURCE_PROFILE':'S3'}):
     with self.assertRaisesRegex(AssertionError,'Unexpected S3'):g.verify_metadata_sources()
    with patch.dict(os.environ,{'SFF_CASM_SOURCE_PROFILE':'S2_BASELINE'}):self.assertEqual({},g.verify_metadata_sources())
if __name__=='__main__':unittest.main(verbosity=2)
