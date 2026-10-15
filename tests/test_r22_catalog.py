#!/usr/bin/env python3
import sys,unittest,json,hashlib,gzip
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1];sys.path.insert(0,str(ROOT/'tools'))
from generate_r22_catalog import generate
class CatalogTests(unittest.TestCase):
 def test_original_four_pdta_and_determinism(self):
  source=ROOT/'tests/fixtures/sf2_pdta/export.zip';a,am=generate(source);b,bm=generate(source);self.assertEqual(a,b);self.assertEqual(am,bm)
  asset=ROOT/'app/src/main/assets/r22';self.assertFalse((asset/'kits.tsv.bin').name.endswith('.gz'));self.assertTrue((asset/'kits.tsv.bin').read_bytes().startswith(b'\x1f\x8b')); self.assertEqual(gzip.decompress((asset/'kits.tsv.bin').read_bytes()),a);self.assertEqual(json.loads((asset/'kit_identity.json').read_text()),am)
  self.assertEqual(gzip.compress(a,mtime=0),(asset/'kits.tsv.bin').read_bytes())
  self.assertEqual(am['catalogUse'],'OFF_PLAYBACK_SHADOW_READ_ONLY')
  for kit in am['kits']:
   self.assertEqual(len(bytes.fromhex(kit['nameHex'])),20)
   self.assertIn(hashlib.sha256(bytes.fromhex(kit['nameHex'])).hexdigest(),kit['id'])
   if kit['font']=='PRIME_DRUM':self.assertEqual(kit['namespace'],'CUSTOM')
   if kit['font']=='COLOMBO' and kit['bank']==127:self.assertEqual(kit['namespace'],'YAMAHA_XG')
   if kit['font']=='TYROS4' and kit['bank']==128:self.assertEqual(kit['namespace'],'GS')
 def test_inspector_provenance(self):
  evidence=json.loads((ROOT/'tests/fixtures/r22/inspector_evidence.json').read_text())
  self.assertEqual(evidence['S1']['noteOnSentDelta'],[0]*8+[36,215]+[0]*6)
  self.assertEqual(evidence['S2']['noteOnSentDelta'],[0]*10+[35,66,66,18,60,0])
  self.assertEqual(evidence['S1']['muteChangesSinceMark'],0);self.assertEqual(evidence['S2']['muteChangesSinceMark'],0)
if __name__=='__main__':unittest.main()
