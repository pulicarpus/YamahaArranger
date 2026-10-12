#!/usr/bin/env python3
"""Source export identity + fail-closed capture/replay tests; no Android runtime evidence."""
import copy
import hashlib
from pathlib import Path
import struct
import tempfile
import unittest
from unittest.mock import patch
import json
import sf2_lovesong_capture as capture
import sf2_golden_routing as golden

ROOT=Path(__file__).resolve().parents[1]

def bundle(style_sha="a"*64,trace_sha="b"*64):
    sources=[]
    index={"MELODI YAMAHA PSR-SX700 & SX900 PRIME.sf2":0,"ColomboGMGS2_BM.sf2":1,"yamaha tyros 4_just_t4_fixed.sf2":2,"DRUMKIT YAMAHA PSR-SX700 & SX900 PRIME.sf2":3}
    for name,(sha,_) in golden.SOURCES.items():sources.append(dict(source_sha256=sha,source_index=index[name],native_basename=name,load_state="LOADED",load_role="DRUM" if index[name]==3 else "MELODY"))
    return dict(mode="ANDROID_REQUEST_CAPTURE",reference_commit=capture.CHECKPOINT,style_sha256=style_sha,raw_trace_sha256=trace_sha,android_build_identity="SYNTHETIC_TEST_ONLY",bassmidi_build_identity="SYNTHETIC_TEST_ONLY",active_sources=sources,requests=[dict(event_ordinal=0,fixture_id="synthetic/0000",section="MainD",beat_tick=0,chord="C",acmp_enabled=True,destination_part="Bass",midi_channel_1based=11,request_msb=8,request_lsb=1,resolver_bank14=1025,request_pc_0based=17,request_voice_name="Bass",trace_ref="synthetic-test-line-1",observed={"native_handle":"UNKNOWN","source_sha256":"UNKNOWN","donor":"UNKNOWN","gain_trim_db":"UNKNOWN"})])

class LoveSongCaptureTests(unittest.TestCase):
    def test_real_export_hash_and_exact_identity(self):
        report=capture.inspect_export()
        self.assertEqual(report["style_id"],"Ballad/LoveSong3.S687.prs")
        self.assertEqual((report["raw_note_ons"],report["source_events"],report["source_parts"]),(503,1089,10))
        self.assertEqual(report["available_export_sections"],["MainD"])
        self.assertEqual(report["android_bassmidi_runtime"],"UNVERIFIED")

    def test_missing_original_style_cannot_replay(self):
        with tempfile.TemporaryDirectory() as tmp:
            out=Path(tmp)/"out"
            result=capture.replay(Path(tmp)/"absent.prs","Ballad/LoveSong3.S687.prs",Path(tmp)/"absent.json",Path(tmp)/"absent.log",out)
            self.assertEqual(result["status"],"WAITING_FOR_EXACT_STYLE_BYTES")
            self.assertFalse(out.exists())

    def test_similar_name_or_wrong_bytes_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            p=Path(tmp)/"LoveSong3.prs";p.write_bytes(b"not actual style")
            with self.assertRaisesRegex(ValueError,"exact original"):capture.bind_style(p,"Ballad/LoveSong3.S687.prs")
            with self.assertRaisesRegex(ValueError,"do not infer"):capture.bind_style(p,"LoveSong")

    def test_actual_name_and_bank_context_required(self):
        b=bundle();capture.validate_capture(b,"a"*64,"b"*64)
        for key,value in (("resolver_bank14",8),("request_voice_name",None),("acmp_enabled","UNKNOWN"),("section","UNKNOWN"),("event_ordinal",1)):
            changed=copy.deepcopy(b);changed["requests"][0][key]=value
            with self.subTest(key=key),self.assertRaises(ValueError):capture.validate_capture(changed,"a"*64,"b"*64)

    def test_build_trace_commit_and_loaded_source_binding(self):
        b=bundle()
        for key,value in (("mode","TEMPLATE_NOT_CAPTURE"),("raw_trace_sha256","0"*64),("reference_commit","main"),("android_build_identity","UNKNOWN")):
            changed=copy.deepcopy(b);changed[key]=value
            with self.subTest(key=key),self.assertRaises(ValueError):capture.validate_capture(changed,"a"*64,"b"*64)
        changed=copy.deepcopy(b);changed["active_sources"][0]["load_state"]="UNKNOWN"
        with self.assertRaises(ValueError):capture.validate_capture(changed,"a"*64,"b"*64)

    def test_empty_or_duplicate_requests_not_a_capture(self):
        for rows in ([],[bundle()["requests"][0]]*2):
            b=bundle();b["requests"]=rows
            with self.assertRaises(ValueError):capture.validate_capture(b,"a"*64,"b"*64)

    def test_frozen_sources_remain_pinned(self):capture.check_pins()

    def test_synthetic_style_smoke_replay_is_not_native_runtime(self):
        # Test registry is patched only in memory. This cannot pass the CLI's
        # real registry binding for an original LoveSong file.
        track=b"\x00\xff\x06\x06Main D\x00\xba\x00\x08\x00\xba\x20\x01\x00\xca\x11\x00\x9a\x3c\x40\x60\x8a\x3c\x00\x00\xff\x2f\x00"
        data=b"MThd"+struct.pack(">IHHH",6,0,1,96)+b"MTrk"+struct.pack(">I",len(track))+track
        style_sha=hashlib.sha256(data).hexdigest();trace=b"SYNTHETIC SMOKE TEST ONLY\n";trace_sha=hashlib.sha256(trace).hexdigest()
        test_registry={"styles":{"synthetic/style.prs":{"file_sha256":style_sha,"bytes":len(data)}}}
        with tempfile.TemporaryDirectory() as tmp,patch.object(capture,"registry",return_value=test_registry):
            tmp=Path(tmp);style=tmp/"style.prs";style.write_bytes(data);raw=tmp/"raw.log";raw.write_bytes(trace);payload=tmp/"bundle.json";payload.write_text(json.dumps(bundle(style_sha,trace_sha)))
            result=capture.replay(style,"synthetic/style.prs",payload,raw,tmp/"output")
            self.assertEqual(result["status"],"PASS_HOST_POLICY_REPLAY_ONLY")
            self.assertEqual(result["melody_replayed"],1)
            self.assertEqual(result["runtime"],"UNVERIFIED")
            self.assertEqual(result["pcm"],"UNVERIFIED")
            self.assertFalse(result["master_export_go"])
            self.assertEqual(style.read_bytes(),data)

if __name__=="__main__":unittest.main(verbosity=2)
