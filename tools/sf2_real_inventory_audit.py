#!/usr/bin/env python3
"""Validate uploaded source metadata and simulate the frozen policy, without BASS/audio/SF2 writes."""
import argparse
from collections import Counter, defaultdict
import csv
import hashlib
import io
import json
from pathlib import Path
import subprocess
import tempfile
import sf2_golden_routing as golden

ROOT = Path(__file__).resolve().parents[1]
CHECKPOINT = "c75122daf5d1077db55d50d1c261144fde59af4a"
META_FIELDS = "source_file,source_sha256,original_bank,original_program_0based,preset_name,source_role,original_phdr_order,is_melodic_verified,status".split(",")
COLL_FIELDS = "bank,program_0based,source_count,source_file,source_sha256,preset_name,source_role,runtime_route,gain_trim_db_reference,note".split(",")
ROLES = {"SX_MELODY":0,"COLOMBO_FALLBACK":1,"TYROS_FALLBACK":2,"SX_DRUM":3}
ROLE_FILES = {"SX_MELODY":"MELODI YAMAHA PSR-SX700 & SX900 PRIME.sf2", "COLOMBO_FALLBACK":"ColomboGMGS2_BM.sf2", "TYROS_FALLBACK":"yamaha tyros 4_just_t4_fixed.sf2", "SX_DRUM":"DRUMKIT YAMAHA PSR-SX700 & SX900 PRIME.sf2"}

def sha(path): return hashlib.sha256(Path(path).read_bytes()).hexdigest()

def read(path, fields):
    with Path(path).open(newline="",encoding="utf-8-sig") as f:
        reader=csv.DictReader(f)
        if reader.fieldnames != fields: raise ValueError("schema mismatch: "+Path(path).name)
        rows=list(reader)
    if not rows or any(None in r or any(v is None for v in r.values()) for r in rows): raise ValueError("empty/malformed CSV")
    if any(any(ord(c)<32 for c in v) for r in rows for v in r.values()): raise ValueError("control characters in TSV-bound fields")
    return rows

def identity(r, bank="original_bank", pc="original_program_0based"):
    return r["source_sha256"],int(r[bank]),int(r[pc])

def expected_gain(key):
    melody=golden.SOURCES[ROLE_FILES["SX_MELODY"]][0];tyros=golden.SOURCES[ROLE_FILES["TYROS_FALLBACK"]][0]
    return { (melody,0,0):"-6",(melody,8,1):"-6",(tyros,0,49):"+12" }.get(key,"UNKNOWN_NOT_ZERO")

def validate_inputs(metadata, collisions):
    rows=read(metadata,META_FIELDS); members=read(collisions,COLL_FIELDS)
    fingerprints=json.loads((ROOT/"tests/fixtures/percussion_inventory_fingerprints.json").read_text())
    if fingerprints != {k:v[0] for k,v in golden.SOURCES.items()}: raise ValueError("repository fingerprint/constants mismatch")
    counts=Counter();seen={};addresses=defaultdict(list);orders=defaultdict(set)
    for r in rows:
        role=r["source_role"];name=r["source_file"]
        if role not in ROLES or ROLE_FILES[role]!=name or fingerprints.get(name)!=r["source_sha256"]:raise ValueError("source role/filename/hash mismatch")
        key=identity(r)
        if not 0<=key[1]<=65535 or not 0<=key[2]<=127 or not r["preset_name"]:raise ValueError("invalid preset bank/PC/name")
        if key in seen:raise ValueError("duplicate preset provenance")
        if r["status"]!="ORIGINAL_PROVENANCE_VERIFIED_NOT_RUNTIME":raise ValueError("unexpected metadata runtime claim/status")
        if r["is_melodic_verified"] not in ("UNKNOWN","TRUE","FALSE"):raise ValueError("invalid melodic annotation")
        if r["original_phdr_order"]!="UNKNOWN":
            ordinal=int(r["original_phdr_order"])
            if ordinal<0 or ordinal in orders[name]:raise ValueError("invalid/duplicate phdr ordinal")
            orders[name].add(ordinal)
        counts[name]+=1;seen[key]=r;addresses[key[1:]].append(r)
    if dict(counts)!={k:v[1] for k,v in golden.SOURCES.items()} or len(rows)!=1148:raise ValueError("original preset counts differ from source gate")
    groups={k:v for k,v in addresses.items() if len(v)>1}
    if len(addresses)!=911 or len(groups)!=158 or len(rows)-len(addresses)!=237:raise ValueError("original collision totals mismatch")
    expected={identity(r) for group in groups.values() for r in group};observed=set()
    for r in members:
        key=identity(r,"bank","program_0based");original=seen.get(key)
        if key in observed:raise ValueError("duplicate collision member")
        observed.add(key)
        if not original or key not in expected:raise ValueError("collision member absent from original collision set")
        if any(r[k]!=original[k] for k in ("source_file","source_role","preset_name")):raise ValueError("collision member name/role mismatch")
        if int(r["source_count"])!=len(groups[key[1:]]):raise ValueError("collision source_count mismatch")
        if r["gain_trim_db_reference"]!=expected_gain(key):raise ValueError("gain reference mismatch; unknown is not zero")
        if r["runtime_route"]!="UNRESOLVED_REQUIRES_REFERENCE_CAPTURE" or r["note"]!="SOURCE_PRESENCE_ONLY_NOT_RESOLVER_WINNER":raise ValueError("unexpected collision runtime claim")
    if observed!=expected or len(members)!=395:raise ValueError("incomplete collision membership")
    return sorted(rows,key=lambda r:(ROLES[r["source_role"]],int(r["original_bank"]),int(r["original_program_0based"]))),groups

def write_csv(path, rows, fields=None):
    with Path(path).open("w",newline="",encoding="utf-8") as f:
        w=csv.DictWriter(f,fields or list(rows[0]),lineterminator="\n");w.writeheader();w.writerows(rows)

def audit(metadata,collisions,output):
    output=Path(output);output.mkdir(parents=True,exist_ok=True)
    rows,groups=validate_inputs(metadata,collisions)
    snapshot=json.loads((ROOT/"tests/fixtures/sf2_golden_source_snapshot.json").read_text())
    pin=next(x for x in snapshot["files"] if x["path"]=="app/src/main/cpp/voice_resolver.h")
    if sha(ROOT/pin["path"])!=pin["sha256"]:raise ValueError("checkpoint policy fingerprint mismatch")
    byid={};bykey={};pool=[];normalized=[]
    for i,r in enumerate(rows):
        p="p%04d"%i;byid[p]=r;bykey[identity(r)]=p
        cache=r["source_role"]!="SX_DRUM" and int(r["original_bank"]) not in (127,128)
        pool.append([p,ROLES[r["source_role"]],r["source_file"],r["original_bank"],r["original_program_0based"],r["preset_name"],int(cache)])
        normalized.append(dict(source_filename=r["source_file"],source_sha256=r["source_sha256"],original_bank=r["original_bank"],original_program=r["original_program_0based"],original_name=r["preset_name"]))
    write_csv(output/"normalized_original_inventory.csv",normalized)
    inventory=golden.inspect_inventory(output/"normalized_original_inventory.csv")
    (output/"inventory_validation.json").write_text(json.dumps(inventory,indent=2,sort_keys=True)+"\n")
    requests=[]
    def add(kind,origin,bank,pc,name,mode="FULL",group="NONE"):
        requests.append(dict(case_id="r%05d"%len(requests),kind=kind,origin_preset_id=origin,collision_group=group,pool=mode,request_bank14=bank,request_pc_0based=pc,request_name=name))
    for p,r in byid.items():
        bank=int(r["original_bank"]);pc=int(r["original_program_0based"]);generic=bank*128 if bank<128 else bank
        group="%d:%d"%(bank,pc) if (bank,pc) in groups else "NONE"
        add("RAW_UI_NAMED_MELODY_PROBE",p,bank,pc,r["preset_name"],group=group)
        add("GENERIC_MIDI_NAMED_MELODY_PROBE",p,generic,pc,r["preset_name"],group=group)
        add("GENERIC_MIDI_UNNAMED_MELODY_PROBE",p,generic,pc,"",group=group)
    for bank,pc in sorted(groups):
        group="%d:%d"%(bank,pc);generic=bank*128 if bank<128 else bank
        for name in ("","Bass","Strings1","Warm Pad","Grand Piano","A.Guitar"):
            add("COLLISION_SEMANTIC_PROBE","NONE",generic,pc,name,group=group)
        for mode in ("NO_COLOMBO","NO_TYROS"):
            add("OPTIONAL_POOL_SENSITIVITY","NONE",generic,pc,"",mode,group)
    for lsb in (1,2,5):
        for pc,name in ((0,"Piano"),(1,"A.Guitar"),(17,"Bass"),(49,"Strings1"),(89,"Warm Pad")):
            add("YAMAHA_LSB_PROBE","NONE",8*128+lsb,pc,name)
    for msb in (104,126,127):
        for name in ("","Bass","Strings1","Warm Pad"):
            add("SPECIAL_MSB_PROBE","NONE",msb*128,49,name)
    write_csv(output/"simulated_requests.csv",requests)
    with tempfile.TemporaryDirectory(prefix="sf2-real-policy-") as tmp:
        tmp=Path(tmp);exe=tmp/"batch";sources=tmp/"pool.tsv";req=tmp/"requests.tsv";classes=tmp/"classes.tsv"
        sources.write_text("".join("\t".join(map(str,r))+"\n" for r in pool))
        req.write_text("".join("\t".join(map(str,[r["case_id"],r["pool"],r["request_bank14"],r["request_pc_0based"],r["request_name"]]))+"\n" for r in requests))
        subprocess.run(["c++","-std=c++17","-Wall","-Wextra","-Werror","-O2","-I",str(ROOT/"app/src/main/cpp"),str(ROOT/"tools/sf2_real_resolver_batch.cpp"),"-o",str(exe)],check=True)
        command=[str(exe),str(sources),str(req),str(classes)]
        first=subprocess.check_output(command);second=subprocess.check_output(command)
        if first!=second:raise ValueError("non-deterministic policy replay")
        classifications=list(csv.DictReader(classes.read_text().splitlines(),delimiter="\t"))
        decisions=list(csv.DictReader(io.StringIO(first.decode()),delimiter="\t"))
    if len(decisions)!=len(requests) or len(classifications)!=1148:raise ValueError("incomplete resolver output")
    classmap={r["preset_id"]:r for r in classifications};decisionmap={r["case_id"]:r for r in decisions}
    enriched=[];ambiguities=[]
    for req in requests:
        d=decisionmap[req["case_id"]];selection=byid.get(d["selected_id"])
        record={**req,**d,"selected_source":selection["source_role"] if selection else "NONE","scope":"SIMULATED_REQUEST_ORIGINAL_METADATA_POOL","runtime":"UNVERIFIED"}
        enriched.append(record)
        flags=[]
        if d["selected_id"]=="NONE":flags.append("POLICY_REJECT_NOT_MISSING_PRESET_PROOF")
        if int(d["top_score_ties"])>1:flags.append("SCORE_TIE_RESOLVED_BY_STABLE_POLICY")
        if req["origin_preset_id"]!="NONE" and d["selected_id"]!=req["origin_preset_id"]:flags.append("SOURCE_SELECTION_DIFFERS_FROM_ORIGIN_ROW")
        if flags:ambiguities.append(dict(case_id=req["case_id"],flags=";".join(flags),selected_id=d["selected_id"],runtime="UNVERIFIED"))
    write_csv(output/"resolver_simulations.csv",enriched)
    write_csv(output/"ambiguities.csv",ambiguities,["case_id","flags","selected_id","runtime"])
    presets=[]
    for p,r in byid.items():
        presets.append({**r,**classmap[p],"gain_trim_db_reference":expected_gain(identity(r)),"runtime":"UNVERIFIED"})
    write_csv(output/"preset_policy_classification.csv",presets)
    conflict_reports=[]
    for address,members in sorted(groups.items()):
        group="%d:%d"%address;cases=[r for r in enriched if r["collision_group"]==group]
        families=sorted({classmap[bykey[identity(r)]]["family_by_checkpoint"] for r in members})
        flags=[]
        if len(families)>1:flags.append("MEMBER_POLICY_FAMILY_DISAGREEMENT")
        if "UNKNOWN" in families:flags.append("MEMBER_POLICY_FAMILY_UNKNOWN")
        if address[0] in (127,128):flags.append("DRUM_PHDR_AND_FONTEX2_PRIORITY_REQUIRE_CAPTURE")
        elif address[0]==126:flags.append("RAW126_SFX_PERCUSSION_ROLE_REVIEW_REQUIRED")
        elif address[0]>0:flags.append("RAW_UI_BANK_DIFFERS_FROM_GENERIC_MIDI_NAMESPACE")
        if any(expected_gain(identity(r))!="UNKNOWN_NOT_ZERO" for r in members):flags.append("SOURCE_HASH_KEYED_GAIN_PROFILE_MUST_SURVIVE")
        conflict_reports.append(dict(group_id=group,bank=address[0],program_0based=address[1],members=[dict(preset_id=bykey[identity(r)],source_role=r["source_role"],source_sha256=r["source_sha256"],name=r["preset_name"],family_by_policy=classmap[bykey[identity(r)]]["family_by_checkpoint"],gain=expected_gain(identity(r))) for r in members],case_ids=[r["case_id"] for r in cases],simulation_selected_presets=sorted({r["selected_id"] for r in cases}),simulation_rejects=sum(r["selected_id"]=="NONE" for r in cases),simulation_score_ties=sum(int(r["top_score_ties"])>1 for r in cases),ambiguity_flags=flags,metadata="PASS_CROSS_CSV_CONSISTENCY",runtime="UNVERIFIED",recommendation="PRESERVE_ALL_PROVENANCE;NO_FIRST_LAST_COLLAPSE;CAPTURE_REQUEST_AND_NATIVE_BINDING"))
    (output/"per_conflict_report.json").write_text(json.dumps(conflict_reports,indent=2,sort_keys=True)+"\n")
    drum_rows=[r for r in rows if int(r["original_bank"]) in (127,128)]
    exact73=[dict(source=r["source_role"],bank=int(r["original_bank"]),name=r["preset_name"]) for r in drum_rows if int(r["original_program_0based"])==73]
    summary=dict(reference_commit=CHECKPOINT,metadata_status="PASS",source_fingerprint_declarations="PASS_MATCH_REPOSITORY_NOT_BINARY_REHASH",input_sha256={Path(metadata).name:sha(metadata),Path(collisions).name:sha(collisions)},presets=1148,unique_addresses=911,conflict_groups=158,collision_members=395,extra_colliding_entries=237,phdr_order_unknown=sum(r["original_phdr_order"]=="UNKNOWN" for r in rows),melodic_verified_unknown=sum(r["is_melodic_verified"]=="UNKNOWN" for r in rows),source_counts=dict(Counter(r["source_role"] for r in rows)),source_bank_counts={s:len({r["original_bank"] for r in rows if r["source_role"]==s}) for s in ROLES},policy_classification_counts=dict(Counter(r["family_by_checkpoint"] for r in classifications)),melody_cache_members=sum(r["melody_cache_member"]=="1" for r in classifications),simulation_requests=len(requests),simulation_status="PASS_DETERMINISTIC_POLICY_EXECUTION_NOT_RUNTIME_EQUIVALENCE",selection_counts=dict(Counter(r["selected_source"] for r in enriched)),rejects=sum(r["selected_id"]=="NONE" for r in enriched),score_tie_cases=sum(int(r["top_score_ties"])>1 for r in enriched),ambiguity_rows=len(ambiguities),drum_exact_pc73=exact73,sx_drum_pc73_present=any(r["source"]=="SX_DRUM" for r in exact73),drum_fallback_first_entry="UNKNOWN_PHDR_ORDER",per_key_donor="UNVERIFIED_NO_FULL_ZONE_AND_LIVE_CAPTURE",runtime="UNVERIFIED",original_binaries_rehashed=False,master_export_go=False,policy_header_sha256=pin["sha256"])
    (output/"summary.json").write_text(json.dumps(summary,indent=2,sort_keys=True)+"\n")
    return summary

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("metadata");parser.add_argument("collisions");parser.add_argument("output")
    args=parser.parse_args()
    try:print(json.dumps(audit(args.metadata,args.collisions,args.output),sort_keys=True));return 0
    except (ValueError,KeyError,OSError,subprocess.CalledProcessError) as e:
        print(json.dumps({"status":"FAIL","error":str(e),"runtime":"UNVERIFIED","master_export_go":False}));return 2

if __name__=="__main__":raise SystemExit(main())
