#!/usr/bin/env python3
"""Bind exact style bytes and captured resolver requests before frozen-policy host replay.

No Android instrumentation, MIDI sends, PCM rendering, or production writes.
Prepared/exported source evidence is never promoted to runtime capture.
"""
import argparse
import csv
import hashlib
import io
import json
from pathlib import Path
import subprocess
import tempfile
import sf2_golden_routing as golden

ROOT=Path(__file__).resolve().parents[1]
CHECKPOINT="c75122daf5d1077db55d50d1c261144fde59af4a"
DATA=ROOT/"tests/fixtures/sf2_evidence"

def sha(path):
    h=hashlib.sha256()
    with Path(path).open("rb") as f:
        for block in iter(lambda:f.read(1024*1024),b""):h.update(block)
    return h.hexdigest()

def registry():return json.loads((DATA/"lovesong_fixture_registry.json").read_text())

def inspect_export():
    reg=registry();entry=reg["identified_export"];path=ROOT/entry["path"]
    if sha(path)!=entry["sha256"]:raise ValueError("source export fingerprint mismatch")
    lines=path.read_text().splitlines();header=lines[0].split("\t")
    if header[:2]!=["STYLE",entry["style_id"]] or header[2]!=reg["styles"][entry["style_id"]]["file_sha256"]:raise ValueError("source export/style identity mismatch")
    sections=[l.split("\t")[1] for l in lines if l.startswith("SECTION\t")]
    events=[l.split("\t") for l in lines if l.startswith("EVENT\t")]
    count=sum(int(e[3])&240==144 and int(e[6])>0 for e in events)
    if sections!=["MainD"] or count!=503:raise ValueError("pinned MainD export coverage mismatch")
    return {"status":"PASS_PINNED_SOURCE_EXPORT_ONLY","style_id":entry["style_id"],"original_style_sha256":header[2],"original_style_bytes":reg["styles"][entry["style_id"]]["bytes"],"export_sha256":entry["sha256"],"export_origin_baseline":reg["export_origin_baseline"],"available_export_sections":sections,"source_parts":sum(l.startswith("PART\t") for l in lines),"policy_records":sum(l.startswith("POLICY\t") for l in lines),"source_events":len(events),"raw_note_ons":count,"checkpoint_parser_reparse":"UNVERIFIED_ORIGINAL_STYLE_BYTES_NOT_AVAILABLE","host_replay":"WAITING_FOR_EXACT_STYLE_AND_CAPTURE","android_bassmidi_runtime":"UNVERIFIED","pcm":"UNVERIFIED","master_export_go":False}

def bind_style(path,style_id):
    reg=registry()
    if style_id not in reg["styles"]:raise ValueError("style_id not in exact LoveSong registry; do not infer from similar names")
    if not Path(path).is_file():return {"status":"WAITING_FOR_EXACT_STYLE_BYTES","style_id":style_id,"expected_sha256":reg["styles"][style_id]["file_sha256"]}
    expected=reg["styles"][style_id]
    if sha(path)!=expected["file_sha256"] or Path(path).stat().st_size!=expected["bytes"]:raise ValueError("exact original style bytes/hash mismatch")
    return {"status":"PASS_EXACT_STYLE_BYTES","style_id":style_id,"style_sha256":expected["file_sha256"]}

def validate_capture(bundle,style_sha,raw_trace_sha):
    if bundle.get("mode")!="ANDROID_REQUEST_CAPTURE":raise ValueError("template/synthetic data is not original capture")
    for key,value in (("reference_commit",CHECKPOINT),("style_sha256",style_sha),("raw_trace_sha256",raw_trace_sha)):
        if bundle.get(key)!=value:raise ValueError("capture binding mismatch: "+key)
    for key in ("android_build_identity","bassmidi_build_identity"):
        if not bundle.get(key) or bundle[key] in ("UNKNOWN","UNVERIFIED"):raise ValueError("missing real capture build identity")
    sources=bundle.get("active_sources",[])
    if len(sources)!=4 or {s["source_sha256"] for s in sources}!={v[0] for v in golden.SOURCES.values()}:raise ValueError("four original loaded sources required")
    if {s["source_index"] for s in sources}!={0,1,2,3}:raise ValueError("capture source indices must match frozen primary0/secondary1,2/dedicated-drum3 contract")
    for s in sources:
        if s.get("load_state")!="LOADED" or not s.get("native_basename") or any(ord(c)<32 for c in s["native_basename"]):raise ValueError("missing actual loaded-source basename/state")
        if s["source_index"]==3 and s.get("load_role")!="DRUM":raise ValueError("source3 must be dedicated drum; record actual import contract")
        if s["source_index"]<3 and s.get("load_role")!="MELODY":raise ValueError("source0..2 must be melody pools")
    rows=bundle.get("requests",[]);seen=set()
    if not rows:raise ValueError("no observed resolver requests")
    for ordinal,r in enumerate(rows):
        if r.get("event_ordinal")!=ordinal or not r.get("fixture_id") or r["fixture_id"] in seen:raise ValueError("missing/duplicate/out-of-order event identifier")
        seen.add(r["fixture_id"])
        for key in ("section","chord","destination_part","trace_ref"):
            if not r.get(key) or r[key] in ("UNKNOWN","UNVERIFIED"):raise ValueError("missing original request context: "+key)
        if type(r.get("acmp_enabled")) is not bool:raise ValueError("explicit ACMP state required")
        for key,low,high in (("midi_channel_1based",1,16),("request_msb",0,127),("request_lsb",0,127),("request_pc_0based",0,127),("resolver_bank14",0,16383)):
            if type(r.get(key)) is not int or not low<=r[key]<=high:raise ValueError("invalid request namespace/range: "+key)
        if type(r.get("beat_tick")) is not int or r["beat_tick"]<0:raise ValueError("invalid beat tick")
        if "request_voice_name" not in r or not isinstance(r["request_voice_name"],str) or any(ord(c)<32 for c in r["request_voice_name"]):raise ValueError("record actual name, including explicit empty unnamed request")
        if r["midi_channel_1based"] not in (9,10) and r["resolver_bank14"]!=r["request_msb"]*128+r["request_lsb"]:raise ValueError("melody bank namespaces conflated")
        # Observation fields are deliberately not filled by host inference.
        if "observed" not in r or not isinstance(r["observed"],dict):raise ValueError("preserve observed values or explicit UNKNOWN object")
    return rows

def check_pins():
    pins=json.loads((ROOT/"tests/fixtures/sf2_golden_source_snapshot.json").read_text())
    needed={"app/src/main/cpp/voice_resolver.h","app/src/main/cpp/style_parser.cpp","app/src/main/cpp/style_parser.h","app/src/main/cpp/smf_reader.cpp","app/src/main/cpp/smf_reader.h","tests/sff1_parser_conformance_test.cpp"}
    for entry in pins["files"]:
        if entry["path"] in needed:
            if sha(ROOT/entry["path"])!=entry["sha256"]:raise ValueError("frozen source pin mismatch: "+entry["path"])
            needed.remove(entry["path"])
    if needed:raise ValueError("incomplete frozen source pin set")

def replay(style_file,style_id,bundle_file,trace_file,output_dir):
    binding=bind_style(style_file,style_id)
    if binding["status"]!="PASS_EXACT_STYLE_BYTES":return dict(binding,host_replay="UNVERIFIED",runtime="UNVERIFIED",master_export_go=False)
    bundle=json.loads(Path(bundle_file).read_text());rows=validate_capture(bundle,binding["style_sha256"],sha(trace_file));check_pins()
    inventory=ROOT/"tests/fixtures/sf2_real/results/normalized_original_inventory.csv"
    report=golden.inspect_inventory(inventory)
    if not report["full_original_inventory_validated"]:raise ValueError("complete original inventory required")
    with inventory.open(newline="") as f:presets=list(csv.DictReader(f))
    source_by_sha={s["source_sha256"]:s for s in bundle["active_sources"]}
    ids={};pool=[]
    for i,p in enumerate(presets):
        key="p%04d"%i;ids[key]=p;s=source_by_sha[p["source_sha256"]];bank=int(p["original_bank"])
        pool.append([key,s["source_index"],s["native_basename"],bank,p["original_program"],p["original_name"],int(s["source_index"]!=3 and bank not in (127,128))])
    melodic=[r for r in rows if r["midi_channel_1based"] not in (9,10)]
    with tempfile.TemporaryDirectory(prefix="lovesong-frozen-replay-") as tmp:
        tmp=Path(tmp);parser=tmp/"style-parser";batch=tmp/"resolver"
        subprocess.run(["c++","-std=c++17","-O2","-DANDROID_LOG_ERROR=6","-I",str(ROOT/"tests/mocks"),"-I",str(ROOT/"app/src/main/cpp"),str(ROOT/"tests/sff1_parser_conformance_test.cpp"),str(ROOT/"app/src/main/cpp/style_parser.cpp"),str(ROOT/"app/src/main/cpp/smf_reader.cpp"),"-o",str(parser)],check=True)
        parsed=subprocess.check_output([str(parser),str(style_file)],text=True)
        sections={l.split("\t")[1] for l in parsed.splitlines() if l.startswith("SECTION\t")}
        if any(r["section"] not in sections for r in rows):raise ValueError("captured section absent in exact style checkpoint parse")
        subprocess.run(["c++","-std=c++17","-Wall","-Wextra","-Werror","-O2","-I",str(ROOT/"app/src/main/cpp"),str(ROOT/"tools/sf2_real_resolver_batch.cpp"),"-o",str(batch)],check=True)
        p=tmp/"pool.tsv";q=tmp/"requests.tsv";classes=tmp/"classes.tsv"
        p.write_text("".join("\t".join(map(str,x))+"\n" for x in pool))
        q.write_text("".join("\t".join(map(str,[r["fixture_id"],"FULL",r["resolver_bank14"],r["request_pc_0based"],r["request_voice_name"]]))+"\n" for r in melodic))
        command=[str(batch),str(p),str(q),str(classes)];first=subprocess.check_output(command);second=subprocess.check_output(command)
        if first!=second:raise ValueError("non-deterministic resolver replay")
        decisions={r["case_id"]:r for r in csv.DictReader(io.StringIO(first.decode()),delimiter="\t")}
    results=[]
    for r in rows:
        if r["midi_channel_1based"] in (9,10):
            results.append(dict(fixture_id=r["fixture_id"],host_policy="UNVERIFIED_DRUM_REQUIRES_PHDR_AND_FULL_ZONE_CAPTURE",observed=r["observed"],runtime="UNVERIFIED"));continue
        d=decisions[r["fixture_id"]];selected=ids.get(d["selected_id"])
        results.append(dict(fixture_id=r["fixture_id"],decision=d,selected_original=selected,observed=r["observed"],scope="FROZEN_HOST_POLICY_REPLAY_CAPTURED_REQUEST_NOT_NATIVE_BINDING",original_order_binding="UNVERIFIED_PHDR_ORDER",selection_certification="UNVERIFIED_ORIGINAL_ORDER" if int(d["top_score_ties"])>1 else "HOST_POLICY_UNIQUE_TOP_SCORE_ONLY",runtime="UNVERIFIED"))
    output=Path(output_dir);output.mkdir(parents=True,exist_ok=False)
    result=dict(status="PASS_HOST_POLICY_REPLAY_ONLY",binding=binding,sections=sorted(sections),requests=len(rows),melody_replayed=len(melodic),drum_unverified=len(rows)-len(melodic),rows=results,runtime="UNVERIFIED",pcm="UNVERIFIED",master_export_go=False)
    (output/"host_policy_replay.json").write_text(json.dumps(result,indent=2,sort_keys=True)+"\n")
    return result

def main():
    parser=argparse.ArgumentParser(description=__doc__);sub=parser.add_subparsers(dest="command",required=True)
    sub.add_parser("prepare")
    r=sub.add_parser("replay")
    for key in ("style-file","style-id","bundle","raw-trace","output-dir"):r.add_argument("--"+key,required=True)
    args=parser.parse_args()
    try:
        result=inspect_export() if args.command=="prepare" else replay(args.style_file,args.style_id,args.bundle,args.raw_trace,args.output_dir)
        print(json.dumps(result,sort_keys=True));return 2 if result["status"].startswith("WAITING") else 0
    except (ValueError,KeyError,OSError,subprocess.CalledProcessError) as e:
        print(json.dumps(dict(status="FAIL",error=str(e),runtime="UNVERIFIED",master_export_go=False)));return 1

if __name__=="__main__":raise SystemExit(main())
