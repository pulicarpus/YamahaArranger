#!/usr/bin/env python3
"""Check actual exported metadata only. No original binary, audio, or complete inventory claim."""
import csv
import hashlib
import json
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]

class PartialOriginalMetadataTests(unittest.TestCase):
    def test_partial_inventory_exactly_derived_from_pinned_export(self):
        provenance=json.loads((ROOT/"tests/fixtures/sf2_partial_original_inventory_provenance.json").read_text())
        raw=(ROOT/provenance["source_artifact"]).read_bytes()
        self.assertEqual(hashlib.sha256(raw).hexdigest(),provenance["artifact_sha256"])
        self.assertEqual(hashlib.sha1(b"blob "+str(len(raw)).encode()+b"\0"+raw).hexdigest(),provenance["artifact_git_blob_sha1"])
        observed=set()
        lines=raw.decode().splitlines()
        self.assertEqual(len(lines),8)
        for line in lines:
            sha,zone=line.split("|",1)
            match=re.search(r"bank=(\d+) rawPC=(\d+) displayPC=(\d+) preset='([^']*)'",zone)
            self.assertIsNotNone(match)
            self.assertEqual(int(match[3]),int(match[2])+1)
            self.assertTrue(zone.endswith("classification=UNKNOWN"))
            observed.add((sha,match[1],match[2],match[4]))
        with (ROOT/"tests/fixtures/sf2_partial_original_inventory.csv").open(newline="") as f:
            actual={(r["source_sha256"],r["original_bank"],r["original_program"],r["original_name"]) for r in csv.DictReader(f)}
        self.assertEqual(actual,observed)
        self.assertEqual(len(actual),2)

    def test_true_velocity_and_key_ranges_remain_metadata_only(self):
        raw=(ROOT/"app/src/test/resources/drum_audit770_zone_rows.tsv").read_text()
        seen=[]
        for line in raw.splitlines():
            m=re.search(r"keys=(\d+):(\d+) velocities=(\d+):(\d+)",line)
            self.assertIsNotNone(m)
            low,high,vlow,vhigh=map(int,m.groups())
            self.assertEqual(low,high)
            self.assertEqual((vlow,vhigh),(0,127))
            seen.append(low)
        self.assertEqual(sorted(set(seen)),[40,42,44,91])
        # Eight exported zones do not supply complete drum context, donor
        # catalog, other-source coverage, or audible interpretation.
        provenance=json.loads((ROOT/"tests/fixtures/sf2_partial_original_inventory_provenance.json").read_text())
        self.assertFalse(provenance["full_inventory"])
        self.assertFalse(provenance["original_binaries_rehashed"])

if __name__ == "__main__":
    unittest.main(verbosity=2)
