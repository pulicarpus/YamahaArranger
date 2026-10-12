#!/usr/bin/env python3
"""Original CSV consistency tests + explicitly simulated requests, never runtime certification."""
import csv
import json
from pathlib import Path
import tempfile
import unittest
import sf2_real_inventory_audit as audit

ROOT=Path(__file__).resolve().parents[1]
DATA=ROOT/"tests/fixtures/sf2_real"

class RealInventoryTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp=tempfile.TemporaryDirectory(prefix="real-inventory-tests-")
        cls.output=Path(cls.temp.name)/"results"
        cls.summary=audit.audit(DATA/"SF2_ORIGINAL_PRESET_METADATA.csv",DATA/"SF2_REAL_COLLISION_GROUPS.csv",cls.output)
        cls.metadata=audit.read(DATA/"SF2_ORIGINAL_PRESET_METADATA.csv",audit.META_FIELDS)
        cls.collisions=audit.read(DATA/"SF2_REAL_COLLISION_GROUPS.csv",audit.COLL_FIELDS)
        with (cls.output/"resolver_simulations.csv").open(newline="") as f:cls.sim=list(csv.DictReader(f))

    @classmethod
    def tearDownClass(cls):cls.temp.cleanup()

    def changed(self, metadata=None, collisions=None):
        with tempfile.TemporaryDirectory() as tmp:
            tmp=Path(tmp);a=tmp/"metadata.csv";b=tmp/"collision.csv"
            audit.write_csv(a,metadata or self.metadata,audit.META_FIELDS)
            audit.write_csv(b,collisions or self.collisions,audit.COLL_FIELDS)
            return audit.validate_inputs(a,b)

    def test_original_metadata_totals_and_complete_membership(self):
        self.assertEqual((self.summary["presets"],self.summary["unique_addresses"],self.summary["conflict_groups"],self.summary["collision_members"]),(1148,911,158,395))

    def test_uploaded_files_exact_digests(self):
        self.assertEqual(audit.sha(DATA/"SF2_ORIGINAL_PRESET_METADATA.csv"),"4caa6a18582deb5ec4ddf867c3be518029af0a0e87ce079e764da594d7cac495")
        self.assertEqual(audit.sha(DATA/"SF2_REAL_COLLISION_GROUPS.csv"),"f4d16e764e36fbadc6acfefb7ee65bdf9df72330ff0daacca55d119d60612bde")

    def test_wrong_source_hash_rejected(self):
        rows=[dict(r) for r in self.metadata];rows[0]["source_sha256"]="0"*64
        with self.assertRaisesRegex(ValueError,"source role/filename/hash"):self.changed(metadata=rows)

    def test_wrong_source_role_rejected(self):
        rows=[dict(r) for r in self.metadata];rows[0]["source_role"]="SX_DRUM"
        with self.assertRaisesRegex(ValueError,"source role/filename/hash"):self.changed(metadata=rows)

    def test_duplicate_and_missing_metadata_rejected(self):
        with self.assertRaisesRegex(ValueError,"duplicate preset"):self.changed(metadata=self.metadata+[self.metadata[0]])
        with self.assertRaisesRegex(ValueError,"preset counts"):self.changed(metadata=self.metadata[:-1])

    def test_missing_or_duplicate_collision_rejected(self):
        with self.assertRaisesRegex(ValueError,"incomplete collision"):self.changed(collisions=self.collisions[:-1])
        with self.assertRaisesRegex(ValueError,"duplicate collision"):self.changed(collisions=self.collisions+[self.collisions[0]])

    def test_conflict_count_name_and_gain_mutations_rejected(self):
        for key,value,reason in (("source_count","99","source_count"),("preset_name","Fabricated","name/role"),("gain_trim_db_reference","0","gain reference")):
            with self.subTest(key=key):
                rows=[dict(r) for r in self.collisions];rows[0][key]=value
                with self.assertRaisesRegex(ValueError,reason):self.changed(collisions=rows)

    def test_simulation_covers_every_original_preset(self):
        origins={r["origin_preset_id"] for r in self.sim if r["origin_preset_id"]!="NONE"}
        self.assertEqual(len(origins),1148)
        self.assertEqual(len(self.sim),4735)
        self.assertTrue(all(r["runtime"]=="UNVERIFIED" and r["scope"]=="SIMULATED_REQUEST_ORIGINAL_METADATA_POOL" for r in self.sim))

    def test_pc73_source_presence_not_legacy_winner(self):
        self.assertFalse(self.summary["sx_drum_pc73_present"])
        self.assertEqual(self.summary["drum_exact_pc73"],[{"source":"COLOMBO_FALLBACK","bank":127,"name":"XG Alternative"},{"source":"COLOMBO_FALLBACK","bank":128,"name":"Alternative"}])
        self.assertEqual(self.summary["drum_fallback_first_entry"],"UNKNOWN_PHDR_ORDER")

    def test_no_drum_cache_preset_selected_in_melodic_probes(self):
        with (self.output/"preset_policy_classification.csv").open(newline="") as f:presets={r["preset_id"]:r for r in csv.DictReader(f)}
        for r in self.sim:
            if r["selected_id"]!="NONE":self.assertEqual(presets[r["selected_id"]]["melody_cache_member"],"1")

    def test_special_msb126_127_hard_rejection(self):
        probes=[r for r in self.sim if r["kind"]=="SPECIAL_MSB_PROBE" and int(r["request_bank14"])//128 in (126,127)]
        self.assertEqual(len(probes),8)
        self.assertTrue(all(r["selected_id"]=="NONE" for r in probes))

    def test_optional_pool_exclusion_is_respected(self):
        for r in self.sim:
            if r["pool"]=="NO_COLOMBO":self.assertNotEqual(r["selected_source"],"COLOMBO_FALLBACK")
            if r["pool"]=="NO_TYROS":self.assertNotEqual(r["selected_source"],"TYROS_FALLBACK")

    def test_all_conflicts_preserve_provenance_and_unverified_runtime(self):
        groups=json.loads((self.output/"per_conflict_report.json").read_text())
        self.assertEqual(len(groups),158)
        self.assertEqual(sum(len(r["members"]) for r in groups),395)
        self.assertTrue(all(r["runtime"]=="UNVERIFIED" and r["case_ids"] for r in groups))

    def test_no_unknown_annotations_promoted_to_runtime_or_master_go(self):
        self.assertEqual(self.summary["phdr_order_unknown"],1148)
        self.assertEqual(self.summary["melodic_verified_unknown"],1148)
        self.assertFalse(self.summary["master_export_go"])
        self.assertFalse(self.summary["original_binaries_rehashed"])

if __name__=="__main__":unittest.main(verbosity=2)
