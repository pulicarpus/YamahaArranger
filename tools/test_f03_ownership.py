#!/usr/bin/env python3
"""S5 read-only corpus/reference/source negative gates; no playback repair."""
from f04_source_guard import historical_bytes
import copy
import hashlib
import json
from pathlib import Path
import unittest
from audit_sff1_corpus import ROOT,digest,require,ConformanceError
from test_sff_root_selection import portable_production_identity
from summarize_f03_ownership import summarize

def sha(data):return hashlib.sha256(data).hexdigest()

def validate_inventory(data):
    require(data['styles']==503 and data['overlapping_on']==523 and data['overlap_styles']==47,'523/47 corpus overlap baseline')
    require(data['maximum_multiplicity']==2 and len(data['all_cases'])==523,'Raw overlap count/multiplicity')
    require(len(data['by_style'])==47 and sum(data['by_style'].values())==523,'Style inventory counts')
    expected=data['evidence_sha256'];body={k:v for k,v in data.items() if k!='evidence_sha256'}
    require(digest(body)==expected,'Evidence digest mismatch')
    for case in data['all_cases']:
        require(case['classification']=='SOURCE_OVERLAP' and case['audible_failure']=='UNKNOWN','Raw overlap must not become PCM failure claim')
        require(case['pairing']=='FIFO_OBSERVER_CONVENTION_NOT_YAMAHA_ORACLE','Pairing assumption must remain explicit')
        require(case['ownership_key']==f"{case['source']}:{case['note']}",'Source ownership identity')
        require(all(e['byte_offset']>=0 and e['ordinal']>=0 for e in case['events']),'Original provenance required')

class F03EvidenceTests(unittest.TestCase):
    def test_summary_joins_all_real_provenance_and_actual_dispatch(self):
        expected=json.loads((ROOT/'tests/fixtures/f03_dispatch_summary_s5.json').read_text())
        self.assertEqual(expected,summarize())

    def test_exact_S4_sources_existing_tests_workflow_refs_golden_and_ledger(self):
        portable_production_identity()
        ref=json.loads((ROOT/'tests/fixtures/f03_preservation_reference_s5.json').read_text())
        self.assertEqual('98a9d2a79b6226bf14b0a4be624f494a878b2ac5',ref['baseline_commit'])
        self.assertEqual(191,len(ref['files']))
        for path,expected in ref['files'].items():self.assertEqual(expected,sha(historical_bytes(path)),path)

    def test_all523_original_counterexamples_and_offsets(self):
        data=json.loads((ROOT/'tests/fixtures/f03_ownership_reference_s5.json').read_text())
        validate_inventory(data)
        self.assertEqual(10,data['cross_section_windows'])
        self.assertEqual(523,sum(data['by_section'].values()))

    def test_JVM_fixture_and_CSV_exact_hashes(self):
        ref=json.loads((ROOT/'tests/fixtures/f03_ownership_reference_s5.json').read_text())
        fixture=ROOT/'app/src/test/resources/sff5/overlap_cases.tsv'
        self.assertEqual(ref['JVM_fixture_sha256'],sha(fixture.read_bytes()))
        self.assertEqual(ref['JVM_fixture_sha256'],fixture.with_suffix('.sha256').read_text().strip())
        self.assertEqual(ref['inventory_csv_sha256'],sha((ROOT/'tests/fixtures/f03_overlap_inventory_s5.csv').read_bytes()))
        self.assertEqual(523,fixture.read_text().count('CASE\t'))

    def test_all1046_dispatch_transcripts_remain_pinned(self):
        trace=(ROOT/'tests/fixtures/f03_dispatch_trace_s5.txt').read_bytes()
        self.assertEqual((ROOT/'app/src/test/resources/sff5/overlap_dispatch.sha256').read_text().strip(),sha(trace))
        self.assertEqual(1046,trace.decode().count('CASE '))
        self.assertIn('nativeAcceptance=NOT_MEASURED',(ROOT/'app/src/test/java/com/yourapp/yamahaarranger/arranger/F03NoteOwnershipInvestigationTest.kt').read_text())

    def test_negative_corrupt_overlap_count_fails(self):
        data=json.loads((ROOT/'tests/fixtures/f03_ownership_reference_s5.json').read_text());data['overlapping_on']=522
        with self.assertRaises(ConformanceError):validate_inventory(data)

    def test_negative_changed_event_provenance_fails(self):
        data=json.loads((ROOT/'tests/fixtures/f03_ownership_reference_s5.json').read_text())
        data['all_cases'][0]['events'][0]['ordinal']+=1
        with self.assertRaises(ConformanceError):validate_inventory(data)

if __name__=='__main__':unittest.main()
