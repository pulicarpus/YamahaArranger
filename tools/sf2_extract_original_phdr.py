#!/usr/bin/env python3
"""Standalone, read-only original SF2 phdr acquisition. Python 3, standard library only.

Exit 0: all four originals validated and evidence exported; 1: FAIL;
2: WAITING_FOR_ORIGINAL_SF2. Never writes or copies a source SF2.
"""
import argparse
from collections import Counter
import csv
import hashlib
import io
import json
import os
from pathlib import Path
import struct

SOURCES = {
    "ColomboGMGS2_BM.sf2": ("9e4686e4aa7173ffd1baf7ec6f80fcb3fe90880e824cf8c4e1defb5d153d47eb",892),
    "DRUMKIT YAMAHA PSR-SX700 & SX900 PRIME.sf2": ("a72158c09677c585eec348762a33da49509bf338c5b36d0e787af58cca0b9f8a",21),
    "MELODI YAMAHA PSR-SX700 & SX900 PRIME.sf2": ("e8c7356159c200d945f13e113595ff06e7955779b0e9361709d9c7cdba164a82",93),
    "yamaha tyros 4_just_t4_fixed.sf2": ("de5b1404630840a2a897e77c6083e2231c5d1523e6571a8afd972a4f684d36ce",142),
}
HEADER=struct.Struct("<20sHHHIII")
FIELDS=["source_sha256","original_phdr_order","bank","program","name"]

def digest_stream(stream):
    h=hashlib.sha256();stream.seek(0)
    for block in iter(lambda:stream.read(1024*1024),b""):h.update(block)
    return h.hexdigest()

def exact(stream,n):
    b=stream.read(n)
    if len(b)!=n:raise ValueError("truncated RIFF data")
    return b

def chunks(stream,start,end):
    position=start
    while position<end:
        if end-position<8:raise ValueError("truncated chunk header")
        stream.seek(position);tag,size=struct.unpack("<4sI",exact(stream,8));body=position+8
        padded=body+size+(size&1)
        if padded>end:raise ValueError("chunk escapes declared parent or lacks padding")
        yield tag,body,size
        position=padded

def parse_phdr(stream):
    stream.seek(0,2);physical=stream.tell();stream.seek(0)
    tag,size,kind=struct.unpack("<4sI4s",exact(stream,12))
    if tag!=b"RIFF" or kind!=b"sfbk" or size<4 or size+8>physical:raise ValueError("invalid RIFF/sfbk size/type")
    end=size+8;tables=[];pdta_count=0
    for tag,body,length in chunks(stream,12,end):
        if tag!=b"LIST":continue
        if length<4:raise ValueError("LIST lacks type")
        stream.seek(body);list_type=exact(stream,4)
        if list_type!=b"pdta":continue
        pdta_count+=1
        for child,offset,n in chunks(stream,body+4,body+length):
            if child==b"phdr":tables.append((offset,n))
    if pdta_count!=1 or len(tables)!=1:raise ValueError("exactly one pdta and phdr required")
    offset,n=tables[0]
    if n<2*HEADER.size or n%HEADER.size or n>65536*HEADER.size:raise ValueError("invalid phdr record count/size")
    stream.seek(offset);data=exact(stream,n);records=[];seen=set();previous_bag=-1
    for ordinal in range(n//HEADER.size):
        raw,pc,bank,bag,library,genre,morphology=HEADER.unpack_from(data,ordinal*HEADER.size)
        # SF2 fixed names are byte strings. Latin-1 is lossless; no trimming,
        # ASCII-ignore, spelling repair or metadata-name substitution occurs.
        name=raw.split(b"\0",1)[0].decode("latin-1")
        terminal=ordinal==n//HEADER.size-1
        if terminal:
            if raw!=b"EOP"+b"\0"*17 or pc or bank or library or genre or morphology:raise ValueError("invalid/missing terminal EOP header")
            if bag<previous_bag:raise ValueError("terminal bag index precedes preset bags")
            continue
        if name=="EOP":raise ValueError("EOP occurs before terminal record")
        if not name or any(ord(c)<32 for c in name) or pc>127:raise ValueError("invalid preset name/program")
        if (bank,pc) in seen:raise ValueError("duplicate bank/program in source phdr")
        if bag<previous_bag:raise ValueError("nonmonotonic preset bag indices")
        seen.add((bank,pc));previous_bag=bag
        records.append(dict(original_phdr_order=ordinal,bank=bank,program=pc,name=name))
    return records,{"eop_verified":True,"terminal_phdr_order":len(records),"terminal_bag_index":bag,"phdr_offset":offset,"trailing_bytes_hashed":physical-end,"name_encoding":"lossless_latin1_no_trim"}

def extract_file(path,expected_sha,expected_entries):
    with Path(path).open("rb") as stream:
        before=os.fstat(stream.fileno());actual=digest_stream(stream)
        if actual!=expected_sha:raise ValueError("source SHA256 mismatch")
        rows,details=parse_phdr(stream);after=os.fstat(stream.fileno())
        if (before.st_size,before.st_mtime_ns,before.st_ctime_ns)!=(after.st_size,after.st_mtime_ns,after.st_ctime_ns):raise ValueError("source changed during read")
    observed=Counter((r["bank"],r["program"],r["name"]) for r in rows)
    if observed!=Counter(expected_entries):raise ValueError("preset count or bank/program/name multiset mismatch")
    first=next((r for r in rows if r["bank"] in (127,128)),None)
    return [dict(source_sha256=actual,**r) for r in rows],dict(details,source_sha256=actual,preset_count=len(rows),first_drum_phdr_entry=first if first else "NONE")

def metadata_entries(metadata,fingerprints):
    fp=json.loads(Path(fingerprints).read_text(encoding="utf-8-sig"))
    if fp!={k:v[0] for k,v in SOURCES.items()}:raise ValueError("fingerprints do not match pinned repository identities")
    entries={name:[] for name in SOURCES};seen=set()
    with Path(metadata).open(newline="",encoding="utf-8-sig") as f:
        reader=csv.DictReader(f)
        required=["source_file","source_sha256","original_bank","original_program_0based","preset_name"]
        if not reader.fieldnames or len(reader.fieldnames)!=len(set(reader.fieldnames)) or not set(required)<=set(reader.fieldnames):raise ValueError("metadata schema missing/duplicate columns")
        for row in reader:
            if None in row or any(row.get(k) is None for k in required):raise ValueError("malformed metadata row")
            name=row["source_file"]
            if name not in SOURCES or row["source_sha256"]!=fp[name]:raise ValueError("metadata source/hash mismatch")
            bank,pc=int(row["original_bank"]),int(row["original_program_0based"])
            if not 0<=bank<=65535 or not 0<=pc<=127 or not row["preset_name"]:raise ValueError("invalid metadata identity")
            key=(name,bank,pc)
            if key in seen:raise ValueError("duplicate metadata bank/program")
            seen.add(key);entries[name].append((bank,pc,row["preset_name"]))
    if any(len(entries[n])!=SOURCES[n][1] for n in SOURCES):raise ValueError("metadata source counts mismatch")
    return entries

def acquire(sf2_dir,metadata,fingerprints,output_dir):
    entries=metadata_entries(metadata,fingerprints);rows=[];reports=[]
    for name in sorted(SOURCES):
        path=Path(sf2_dir)/name
        if not path.is_file():reports.append(dict(source_filename=name,status="WAITING_FOR_ORIGINAL_SF2"));continue
        try:
            extracted,details=extract_file(path,SOURCES[name][0],entries[name]);rows.extend(extracted)
            reports.append(dict(source_filename=name,status="PASS_ORIGINAL_BINARY",**details))
        except (ValueError,OSError,struct.error) as e:reports.append(dict(source_filename=name,status="FAIL",error=str(e)))
    status="FAIL" if any(r["status"]=="FAIL" for r in reports) else "WAITING_FOR_ORIGINAL_SF2" if len(rows)!=1148 else "PASS_ORIGINAL_PHDR"
    report=dict(schema_version=1,status=status,sources=reports,original_order_rows_exported=1148 if status=="PASS_ORIGINAL_PHDR" else 0,runtime="UNVERIFIED",master_export_go=False)
    if status=="PASS_ORIGINAL_PHDR":
        target=Path(output_dir)
        # Use a fresh output directory: no source/metadata/report can be overwritten.
        target.mkdir(parents=True,exist_ok=False)
        out=io.StringIO(newline="");writer=csv.DictWriter(out,FIELDS,lineterminator="\n");writer.writeheader();writer.writerows(rows)
        data=out.getvalue().encode("utf-8");report["phdr_csv_sha256"]=hashlib.sha256(data).hexdigest()
        report["metadata_sha256"]=hashlib.sha256(Path(metadata).read_bytes()).hexdigest()
        (target/"SF2_ORIGINAL_PHDR_ORDER.csv").write_bytes(data)
        (target/"SF2_ORIGINAL_PHDR_VERIFICATION.json").write_text(json.dumps(report,indent=2,sort_keys=True)+"\n",encoding="utf-8")
    return report

def main():
    root=Path(__file__).resolve().parents[1];parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sf2-dir",required=True);parser.add_argument("--output-dir",required=True)
    parser.add_argument("--metadata",default=str(root/"tests/fixtures/sf2_real/SF2_ORIGINAL_PRESET_METADATA.csv"))
    parser.add_argument("--fingerprints",default=str(root/"tests/fixtures/percussion_inventory_fingerprints.json"))
    args=parser.parse_args()
    try:report=acquire(args.sf2_dir,args.metadata,args.fingerprints,args.output_dir)
    except (ValueError,OSError,struct.error,KeyError) as e:report=dict(status="FAIL",error=str(e),original_order_rows_exported=0,master_export_go=False)
    print(json.dumps(report,sort_keys=True))
    return 0 if report["status"]=="PASS_ORIGINAL_PHDR" else 2 if report["status"]=="WAITING_FOR_ORIGINAL_SF2" else 1

if __name__=="__main__":raise SystemExit(main())
