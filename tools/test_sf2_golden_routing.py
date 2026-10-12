#!/usr/bin/env python3
"""Synthetic regression fixtures only; never a golden runtime capture."""
import csv
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("routing", ROOT / "tools/sf2_golden_routing.py")
routing = importlib.util.module_from_spec(spec)
spec.loader.exec_module(routing)
HASHES = ["a"*64, "b"*64, "c"*64, "d"*64]  # deliberately synthetic

def row():
    r = dict.fromkeys(routing.FIELDS, "NONE")
    r.update(fixture_id="synthetic/event/000001", style_sha256="e"*64,
             section="SYNTHETIC", beat_tick="0", chord="C", acmp_enabled="true",
             destination_part="Rhythm1", midi_channel_1based="9", request_msb="127",
             request_lsb="0", request_pc_0based="73", source_file_sha256=HASHES[0],
             source_bank="128", source_program_0based="0", native_handle_id="11",
             native_bank="128", native_program="0", fallback_reason="first_available_kit",
             semantic_family="PERCUSSION", drum_key="54", velocity="64",
             volume_trim_db="-6", decision_trace="synthetic_test_only", status="VERIFIED")
    return r

def manifest():
    return [dict(source_sha256=HASHES[0], original_bank=128, original_program=0,
                 master_bank=4, master_program=12, validation_status="VALIDATED_RUNTIME")]

class GoldenRoutingTests(unittest.TestCase):
    def setUp(self):
        self.a = row()
        self.b = dict(self.a, native_handle_id="99", native_bank="4", native_program="12")
        self.m = manifest()

    def test_projection_preserves_identity_with_new_native_address(self):
        self.assertEqual(routing.compare([self.a], [self.b], self.m), [])

    def test_every_context_and_semantic_change_detected(self):
        for field in routing.CONTEXT + routing.SEMANTICS:
            if field == "fixture_id": continue
            with self.subTest(field=field):
                changed = dict(self.b); changed[field] += "_changed"
                self.assertTrue(routing.compare([self.a], [changed], self.m))

    def test_pc73_exact_kit_regression(self):
        changed = dict(self.b, source_program_0based="73", fallback_reason="exact_kit")
        self.assertTrue(routing.compare([self.a], [changed], self.m))

    def test_native_mapping_mismatch(self):
        self.assertTrue(routing.compare([self.a], [dict(self.b, native_bank="5")], self.m))

    def test_event_order_change_detected(self):
        a2=dict(self.a,fixture_id="synthetic/event/000002")
        b2=dict(self.b,fixture_id="synthetic/event/000002")
        self.assertTrue(routing.compare([self.a,a2],[b2,self.b],self.m))

    def test_conflicting_address_and_staging_rejected(self):
        for other in (dict(self.m[0], original_program=1), dict(self.m[0])):
            with self.assertRaises(ValueError): routing.mapping_index(self.m + [other])
        self.m[0]["validation_status"] = "STAGING"
        with self.assertRaises(ValueError): routing.mapping_index(self.m)

    def test_missing_event_and_donor_mapping(self):
        self.assertTrue(routing.compare([self.a], [], self.m))
        self.a.update(donor_source_sha256=HASHES[1], donor_bank="126", donor_program="1", donor_key="70")
        self.b.update({k:self.a[k] for k in routing.SEMANTICS})
        with self.assertRaises(ValueError): routing.compare([self.a], [self.b], self.m)
        self.m.append(dict(self.m[0],source_sha256=HASHES[1],original_bank=126,original_program=1,master_bank=5))
        self.assertEqual(routing.compare([self.a], [self.b], self.m), [])
        self.b["donor_key"] = "71"
        self.assertTrue(routing.compare([self.a], [self.b], self.m))

    def test_unknown_gain_and_missing_capture_cannot_pass(self):
        meta=dict(evidence_scope="SYNTHETIC",schema_version=1,mode="four_original",reference_commit="c75122daf5d1077db55d50d1c261144fde59af4a",
                  android_build_identity="synthetic",bassmidi_build_identity="synthetic",raw_trace_sha256="f"*64,source_sha256=HASHES)
        routing.validate_evidence([self.a],meta,"four_original")
        for field in ("volume_trim_db","source_file_sha256","native_bank","decision_trace"):
            with self.subTest(field=field), self.assertRaises(ValueError):
                routing.validate_evidence([dict(self.a,**{field:"UNKNOWN"})],meta,"four_original")
        with self.assertRaises(ValueError): routing.validate_evidence([self.a],{},"four_original")
        with self.assertRaises(ValueError): routing.validate_evidence([dict(self.a,status="UNVERIFIED")],meta,"four_original")
        with self.assertRaises(ValueError): routing.validate_evidence([dict(self.a,volume_trim_db="nan")],meta,"four_original")

    def test_canonical_determinism_handles_and_csv_roundtrip(self):
        second = dict(self.a,fixture_id="synthetic/event/000002")
        self.assertEqual(routing.canonical([second,self.a]),routing.canonical([self.a,second]))
        self.assertEqual(routing.canonical([self.a]),routing.canonical([dict(self.a,native_handle_id="1234")]))
        with tempfile.TemporaryDirectory() as tmp:
            p=Path(tmp)/"rows.csv";p.write_text(routing.canonical([self.a]))
            self.assertEqual(len(routing.read_csv(p)),1)
            p.write_text(routing.canonical([self.a,self.a]))
            with self.assertRaises(ValueError): routing.read_csv(p)

    def test_capture_coverage_is_explicitly_unverified(self):
        coverage=json.loads((ROOT/"tests/fixtures/sf2_golden_capture_coverage.json").read_text())
        self.assertEqual(len(coverage["rows"]),84)
        self.assertTrue(all(r["status"]=="UNVERIFIED" for r in coverage["rows"]))

    def test_cli_exit_codes_and_no_master_go(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp=Path(tmp)
            a,b,m,ma,mb=[tmp/x for x in ("a.csv","b.csv","m.json","ma.json","mb.json")]
            def write_csv(path,r):
                with path.open("w",newline="") as f:
                    w=csv.DictWriter(f,routing.FIELDS);w.writeheader();w.writerow(r)
            meta=dict(evidence_scope="SYNTHETIC",schema_version=1,mode="four_original",reference_commit="c75122daf5d1077db55d50d1c261144fde59af4a",
                      android_build_identity="synthetic",bassmidi_build_identity="synthetic",raw_trace_sha256="f"*64,source_sha256=HASHES)
            ma.write_text(json.dumps(meta));mb.write_text(json.dumps(dict(meta,mode="experimental_master")))
            m.write_text(json.dumps(self.m));write_csv(a,self.a);write_csv(b,self.b)
            cmd=["python3",str(ROOT/"tools/sf2_golden_routing.py"),"compare",str(a),str(b),str(m),str(ma),str(mb)]
            result=subprocess.run(cmd,capture_output=True,text=True)
            self.assertEqual(result.returncode,0,result.stdout)
            self.assertFalse(json.loads(result.stdout)["master_export_go"])
            self.assertEqual(json.loads(result.stdout)["evidence_scope"],"SYNTHETIC")
            write_csv(b,dict(self.b,fallback_reason="exact_kit"))
            self.assertEqual(subprocess.run(cmd,capture_output=True).returncode,1)
            write_csv(b,dict(self.b,volume_trim_db="UNKNOWN"))
            self.assertEqual(subprocess.run(cmd,capture_output=True).returncode,2)

    def test_original_scope_rejects_synthetic_source_hashes(self):
        meta=dict(evidence_scope="ORIGINAL",schema_version=1,mode="four_original",reference_commit="c75122daf5d1077db55d50d1c261144fde59af4a",
                  android_build_identity="synthetic",bassmidi_build_identity="synthetic",raw_trace_sha256="f"*64,source_sha256=HASHES)
        with self.assertRaisesRegex(ValueError,"verified original hashes"):
            routing.validate_evidence([self.a],meta,"four_original")

    def test_missing_inventory_explicitly_unverified(self):
        report=routing.inspect_inventory()
        self.assertEqual(report["status"],"UNVERIFIED")
        self.assertEqual(report["rows_observed"],0)
        self.assertFalse(report["conflicts_complete"])

    def test_full_original_scope_cannot_pass_without_inventory(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp=Path(tmp)
            r=dict(self.a,source_file_sha256=routing.SOURCES["ColomboGMGS2_BM.sf2"][0],source_program_0based="2")
            master=dict(r,native_bank="4",native_program="12")
            for name,row in (("a.csv",r),("b.csv",master)):
                with (tmp/name).open("w",newline="") as f:
                    w=csv.DictWriter(f,routing.FIELDS);w.writeheader();w.writerow(row)
            m=[dict(self.m[0],source_sha256=r["source_file_sha256"],original_program=2)]
            (tmp/"m.json").write_text(json.dumps(m))
            meta=dict(evidence_scope="ORIGINAL",schema_version=1,mode="four_original",reference_commit="c75122daf5d1077db55d50d1c261144fde59af4a",
                      android_build_identity="test_only",bassmidi_build_identity="test_only",raw_trace_sha256="f"*64,source_sha256=[v[0] for v in routing.SOURCES.values()])
            for name,mode in (("ma.json","four_original"),("mb.json","experimental_master")):
                (tmp/name).write_text(json.dumps(dict(meta,mode=mode)))
            command=["python3",str(ROOT/"tools/sf2_golden_routing.py"),"compare"]+[str(tmp/n) for n in ("a.csv","b.csv","m.json","ma.json","mb.json")]
            result=subprocess.run(command,capture_output=True,text=True)
            self.assertEqual(result.returncode,2)
            self.assertIn("full original inventory required",result.stdout)
            command += ["--inventory",str(ROOT/"tests/fixtures/sf2_partial_original_inventory.csv")]
            result=subprocess.run(command,capture_output=True,text=True)
            self.assertEqual(result.returncode,2)
            self.assertIn("full original inventory required",result.stdout)

    def test_synthetic_inventory_all_observed_conflicts(self):
        cases=json.loads((ROOT/"tests/fixtures/sf2_golden_policy_cases.json").read_text())
        import hashlib
        rows=[dict(zip(routing.INVENTORY_FIELDS,[c[1],hashlib.sha256(c[1].encode()).hexdigest(),c[2],c[3],c[4]])) for c in cases["candidates"]]
        with tempfile.TemporaryDirectory() as tmp:
            p=Path(tmp)/"inventory.csv"
            def write(data):
                with p.open("w",newline="") as f:
                    w=csv.DictWriter(f,routing.INVENTORY_FIELDS);w.writeheader();w.writerows(data)
            write(rows)
            report=routing.inspect_inventory(p,"SYNTHETIC")
            self.assertEqual(report["rows_observed"],9)
            self.assertEqual(report["unique_raw_addresses"],8)
            self.assertEqual(report["cross_source_conflict_groups"],1)
            self.assertFalse(report["conflicts_complete"])
            self.assertEqual(report["conflicts"][0]["resolver_decision"],"UNVERIFIED_NEEDS_REQUEST_CONTEXT")
            with self.assertRaisesRegex(ValueError,"source filename/hash"):routing.inspect_inventory(p,"ORIGINAL")
            write(rows+[rows[0]])
            with self.assertRaisesRegex(ValueError,"duplicate"):routing.inspect_inventory(p,"SYNTHETIC")
            write([dict(rows[0],original_program=128)])
            with self.assertRaisesRegex(ValueError,"invalid original bank/program"):routing.inspect_inventory(p,"SYNTHETIC")

    def test_original_partial_inventory_not_full_pass(self):
        path=ROOT/"tests/fixtures/sf2_partial_original_inventory.csv"
        report=routing.inspect_inventory(path,"ORIGINAL")
        self.assertEqual(report["status"],"UNVERIFIED")
        self.assertEqual(report["rows_observed"],2)
        self.assertEqual(len(report["counts_by_source"]),2)
        self.assertFalse(report["full_original_inventory_validated"])
        self.assertEqual(report["review_banks_126_127_128"],{"126":1,"127":0,"128":1})
        melody=routing.SOURCES["MELODI YAMAHA PSR-SX700 & SX900 PRIME.sf2"][0]
        self.assertEqual(report["normalization_projection"][melody][0]["native_virtual_bank"],"UNKNOWN")

    def test_capture_preset_and_donor_must_exist_in_inventory(self):
        path=ROOT/"tests/fixtures/sf2_partial_original_inventory.csv"
        a=dict(self.a,source_file_sha256=routing.SOURCES["ColomboGMGS2_BM.sf2"][0],source_program_0based="2")
        routing.validate_inventory_membership([a],path)
        with self.assertRaisesRegex(ValueError,"preset absent"):
            routing.validate_inventory_membership([dict(a,source_program_0based="73")],path)
        with self.assertRaisesRegex(ValueError,"donor absent"):
            routing.validate_inventory_membership([dict(a,donor_source_sha256=a["source_file_sha256"],donor_bank="128",donor_program="73")],path)

    def test_exact_checkpoint_policy_replay(self):
        cases=json.loads((ROOT/"tests/fixtures/sf2_golden_policy_cases.json").read_text())
        with tempfile.TemporaryDirectory() as tmp:
            tmp=Path(tmp);exe=tmp/"replay"
            subprocess.run(["c++","-std=c++17","-Wall","-Wextra","-Werror","-O2",
                            "-I",str(ROOT/"app/src/main/cpp"),str(ROOT/"tools/sf2_resolver_reference.cpp"),"-o",str(exe)],check=True)
            candidates=tmp/"pool.tsv";requests=tmp/"requests.tsv"
            candidates.write_text("".join("\t".join(map(str,c))+"\n" for c in cases["candidates"]))
            requests.write_text("".join("\t".join(map(str,[r["id"],r["bank"],r["pc"],r["name"]]))+"\n" for r in cases["requests"]))
            command=[str(exe),str(candidates),str(requests)]
            first=subprocess.check_output(command);second=subprocess.check_output(command)
            self.assertEqual(first,second)
            rows=list(csv.DictReader(first.decode().splitlines(),delimiter="\t"))
            for r in cases["requests"]:
                selected=[int(x["candidate"]) for x in rows if x["fixture_id"]==r["id"] and x["selected"]=="1"]
                self.assertEqual(selected,[] if r["selected"] is None else [r["selected"]],r["id"])
            print("POLICY_REPLAY deterministic, synthetic requests=9; no runtime capture")

if __name__ == "__main__":
    unittest.main(verbosity=2)
