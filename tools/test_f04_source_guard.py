#!/usr/bin/env python3
"""Negative F04 physical-source and unchanged-baseline integrity checks."""
import unittest
from f04_source_guard import SOURCE, ROOT, historical_bytes, profile, verify_profile

class F04SourceGuardTest(unittest.TestCase):
    def test_exact_candidate_and_all_historical_files(self):
        p=verify_profile()
        self.assertEqual(380,len(p['baseline_files']))
    def test_mutated_candidate_fails(self):
        with self.assertRaises(AssertionError):historical_bytes(SOURCE,(ROOT/SOURCE).read_bytes()+b'\n')
    def test_baseline_recovery_is_exact(self):
        data=historical_bytes(SOURCE)
        self.assertNotIn(b'hasOnlyMelodicDeclarations',data)
        self.assertIn(b'!isRhythmSource(s.event.channel))',data)
    def test_partial_or_broadened_guard_fails(self):
        for data in [(ROOT/SOURCE).read_bytes().replace(b'in 10..15',b'in 8..15'),
                     (ROOT/SOURCE).read_bytes().replace(b'it.sourceChannel == sourceChannel && ',b'')]:
            with self.assertRaises(AssertionError):historical_bytes(SOURCE,data)
    def test_mutated_harness_is_not_normalized(self):
        path='tools/test_sff1_pipeline.py'
        with self.assertRaises(AssertionError):historical_bytes(path,(ROOT/path).read_bytes()+b'\n')

if __name__=='__main__':unittest.main()
