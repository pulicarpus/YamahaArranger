#!/usr/bin/env python3
"""S4 CI evidence/negative gates; does not assert unverified musical semantics."""
from f04_source_guard import historical_bytes
import hashlib
import csv
import json
import unittest
import subprocess
from pathlib import Path

from audit_sff_root_selection import (ROOT, REFERENCE, SOURCES, SAMPLES,
    documentary_vector, candidate_vector)
from audit_sff1_corpus import canonical, digest, ConformanceError, require
from sff_casm_source_guard import verify_metadata_sources as verify_s3_sources
from sff_dialect_source_guard import verify_metadata_sources as verify_s2_sources, strip_metadata

S1_REFERENCE_SHA256='2ab43fe0cac6f0aa27be168c227faf51c1b8401f3678c90444f7e03aac13d5ed'
S3_PHYSICAL_SOURCE_SHA256='fd3ec12803ecaebb6f220451d5ccc8fe1f96282ee6c64d4c18076e9958a74dff'


def normalized_tree_oid(files):
    """Reconstruct the same Git tree; no historical object/network required."""
    tree={}
    for path,(mode,data) in files.items():
        parts=path.removeprefix('app/src/main/').split('/')
        node=tree
        for part in parts[:-1]:node=node.setdefault(part,{})
        node[parts[-1]]=(mode,data)
    def oid(kind,data):
        return hashlib.sha1(kind+b' '+str(len(data)).encode()+b'\0'+data).digest()
    def walk(node):
        body=b''
        for name,value in sorted(node.items(),key=lambda x:(x[0]+('/' if isinstance(x[1],dict) else '')).encode()):
            if isinstance(value,dict):mode,child='40000',walk(value)
            else:mode,data=value;child=oid(b'blob',data)
            body+=mode.encode()+b' '+name.encode()+b'\0'+child
        return oid(b'tree',body)
    return walk(tree).hex()


def portable_production_identity(read_bytes=None):
    """Exact S1 content/tree + S2/S3 physical guards in shallow CI checkouts."""
    reference=ROOT/'tests/fixtures/sff1_reference.json'
    require(hashlib.sha256(reference.read_bytes()).hexdigest()==S1_REFERENCE_SHA256,'S1 reference identity drift')
    pinned=json.loads(reference.read_text())['production_source_identity']
    s3=verify_s3_sources();s2=verify_s2_sources()
    indexed=subprocess.check_output(['git','ls-files','-s','--','app/src/main'],cwd=ROOT,text=True).splitlines()
    modes={}
    for row in indexed:
        metadata,path=row.split('\t',1);mode,_,stage=metadata.split()
        require(stage=='0','Unmerged production index');modes[path]=mode
    require(sorted(modes)==sorted(pinned['tracked_files']),'S1 production source-set drift')
    from generated_bass_sdk_guard import verify_generated_sdk
    untracked=subprocess.check_output(['git','ls-files','--others','--exclude-standard','--','app/src/main'],cwd=ROOT,text=True).splitlines()
    try: verify_generated_sdk(ROOT,untracked)
    except AssertionError as error: raise ConformanceError(str(error)) from error
    normalized={};physical={}
    for path,expected in pinned['tracked_files'].items():
        raw=(ROOT/path).read_bytes() if read_bytes is None else read_bytes(path)
        try: raw=historical_bytes(path,raw)
        except AssertionError as error: raise ConformanceError(str(error)) from error
        old=strip_metadata(path,raw.decode()).encode() if path in s2 or path in s3 else raw
        require(hashlib.sha256(old).hexdigest()==expected,'S1 production source changed: '+path)
        normalized[path]=(modes[path],old);physical[path]=hashlib.sha256(raw).hexdigest()
    require(normalized_tree_oid(normalized)==pinned['main_tree_git_oid'],'S1 normalized production Git tree drift')
    require(digest(physical)==S3_PHYSICAL_SOURCE_SHA256,'S3 physical production identity drift')
    return {'main_tree_git_oid':pinned['main_tree_git_oid'],'tracked_files':physical,'verified_working_tree_sha256':digest(physical)}


class RootSelectionEvidenceTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.reference = json.loads(REFERENCE.read_text())
        cls.rows = [line.split('\t') for line in SAMPLES.read_text().splitlines()]

    def test_reference_integrity_and_original_53_words(self):
        r = dict(self.reference)
        expected = r.pop('evidence_sha256')
        self.assertEqual(expected, digest(r))
        self.assertEqual(53, len(self.rows))
        self.assertEqual(53, len({row[0] for row in self.rows}))
        self.assertEqual(r['sample_tsv_sha256'], hashlib.sha256(SAMPLES.read_bytes()).hexdigest())
        self.assertEqual(r['external_source_registry_sha256'], hashlib.sha256(SOURCES.read_bytes()).hexdigest())
        self.assertEqual({'styles':503,'raw_records':32913,'raw_words':53,'attachments':71239,'nondefault_records':257}, r['counts'])
        self.assertEqual(32913, sum(row['occurrence_count'] for row in r['word_inventory']))
        self.assertEqual(71239, sum(row['attachment_count'] for row in r['word_inventory']))
        self.assertEqual(73229, sum(row['expanded_count'] for row in r['word_inventory']))
        self.assertEqual(503, len(r['styles']))
        pop=ROOT/'app/src/test/resources/sff4/PopBallad4.S281.bcs.tsv'
        self.assertEqual(r['styles']['Ballad/PopBallad4.S281.bcs']['protocol_sha256'],hashlib.sha256(pop.read_bytes()).hexdigest())
        self.assertEqual(r['sample_tsv_sha256'],(SAMPLES.parent/'root_records.sha256').read_text().strip())

    def test_original_offsets_and_payloads_do_not_normalize_zero(self):
        for row in self.rows:
            raw = bytes.fromhex(row[6])
            self.assertEqual(27, len(raw))
            self.assertEqual(int(row[0],16), int.from_bytes(raw[11:13], 'big'))
            self.assertGreater(int(row[5]),0)
            self.assertEqual(self.reference['styles'][row[1]]['file_sha256'], row[2])
        cases = self.reference['required_real_examples']
        self.assertEqual(['0000','0008','0555','0AAA','0003'], [r['raw_word_hex'] for r in cases])
        self.assertEqual([18918,24015,26470,26540,85411], [r['payload_offset'] for r in cases])
        self.assertTrue(all(r['absolute_root_offset']==r['payload_offset']+11 for r in cases))

    def test_all_384_hypotheses_have_scoped_results_and_real_first_witness(self):
        cases = self.reference['candidate_falsification']
        self.assertEqual(384, len(cases))
        self.assertEqual(384, len({r['id'] for r in cases}))
        for case in cases:
            self.assertEqual(32913*12, case['record_root_cases'])
            self.assertFalse(case['certified'])
            self.assertEqual('UNKNOWN', case['yamaha_execution_status'])
            first = case['first_counterexample']
            self.assertEqual(bool(case['document_disagreement_cases']), first is not None)
            if first:
                actual = candidate_vector(int(first['raw_word_hex'],16),first['source_root'],*case['parameters'])
                documented = documentary_vector(bytes.fromhex(first['raw_payload_hex']))
                root = first['hypothetical_input_root']
                self.assertEqual(first['candidate_allows'],actual[root])
                self.assertEqual(first['document_claim_allows'],documented[root])
                self.assertNotEqual(actual[root],documented[root])
                self.assertEqual('DOCUMENT_DISAGREEMENT_NOT_HARDWARE_OUTPUT',first['counterexample_kind'])

    def test_documentary_map_and_reversed_polarity_are_distinguishable(self):
        raw = bytearray(27)
        for root in range(12):
            # Separate per-byte bit locations, not the candidate's word formula.
            raw[11] = 1 << (root-8) if root >= 8 else 0
            raw[12] = 1 << root if root < 8 else 0
            expected = [r==root for r in range(12)]
            self.assertEqual(expected,documentary_vector(raw))
            word = int.from_bytes(raw[11:13],'big')
            self.assertEqual(expected,candidate_vector(word,0,'BE',1,0,1,'absolute',False))
            self.assertNotEqual(expected,candidate_vector(word,0,'BE',1,0,0,'absolute',False))
        raw[11]=0;raw[12]=0
        self.assertEqual([False]*12,documentary_vector(raw))
        self.assertNotEqual(documentary_vector(raw),candidate_vector(0,0,'BE',1,0,1,'absolute',True))
        # These are document-hypothesis tests, explicitly not Yamaha output tests.

    def test_bad_raw_and_hypothesis_inputs_fail_closed(self):
        for word,source in [(-1,0),(65536,0),(0,-1),(0,12)]:
            with self.assertRaises(ConformanceError):
                candidate_vector(word,source,'BE',1,0,1,'absolute',False)
        with self.assertRaises(ConformanceError):documentary_vector(bytes(26))

    def test_no_unlabeled_evidence_can_promote_F01_to_certified(self):
        self.assertEqual('UNRESOLVED',self.reference['certification_status'])
        self.assertFalse(self.reference['playback_fix_authorized'])
        sources=json.loads(SOURCES.read_text())
        self.assertFalse(sources['normative_yamaha_oracle'])
        self.assertEqual(0,sources['hardware_input_chord_output_fixtures'])
        self.assertEqual('UNRESOLVED',sources['certification_status'])

    def test_all_word_distribution_columns_have_correct_units(self):
        with (ROOT/'tests/fixtures/sff1_root_word_table_s4.csv').open(newline='') as stream:
            csv_rows=list(csv.DictReader(stream))
        self.assertEqual(53,len(csv_rows))
        for observed,expected in zip(csv_rows,self.reference['word_inventory']):
            for column,value in observed.items():
                self.assertEqual(canonical(expected[column]) if isinstance(expected[column],dict) else str(expected[column]),value)
        for row in self.reference['word_inventory']:
            count=row['occurrence_count']
            self.assertEqual(row['style_count'],len(row['styles']))
            for field,distribution in row['distributions'].items():
                self.assertEqual(count,sum(distribution.values()),field)
            self.assertEqual(row['attachment_count'],sum(row['attached_section_distribution'].values()))
            self.assertEqual(row['expanded_count'],sum(row['sdec_section_distribution'].values()))
            self.assertEqual(count,sum(row['source_root_type_joint'].values()))
            self.assertEqual(count,sum(row['note_range_joint'].values()))
        self.assertEqual(157,sum(row['never_attached_record_count'] for row in self.reference['word_inventory']))

    def test_existing_S1_S2_S3_production_identity_still_passes(self):
        result=portable_production_identity()
        self.assertEqual(125,len(result['tracked_files']))

    def test_portable_guard_rejects_changed_source_bytes(self):
        path='app/src/main/cpp/smf_reader.cpp'
        with self.assertRaises(ConformanceError):
            portable_production_identity(lambda p:(ROOT/p).read_bytes()+(b'\n' if p==path else b''))

    def test_normalized_tree_reconstruction_detects_mode_and_path_changes(self):
        original={'app/src/main/a.txt':('100644',b'x')}
        self.assertNotEqual(normalized_tree_oid(original),normalized_tree_oid({'app/src/main/a.txt':('100755',b'x')}))
        self.assertNotEqual(normalized_tree_oid(original),normalized_tree_oid({'app/src/main/b.txt':('100644',b'x')}))


if __name__=='__main__':
    unittest.main(verbosity=2)
