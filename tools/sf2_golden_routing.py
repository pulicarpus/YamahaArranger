#!/usr/bin/env python3
"""Offline golden CSV canonicalizer/comparator. No audio or engine mutations.

Exit 0: routing equivalent; 1: mismatch; 2: invalid/incomplete evidence.
Native addresses MUST differ only as projected by an explicitly validated
provenance mapping; this tool does not approve that mapping or prove PCM parity.
"""
import argparse
import csv
import io
import json
import math
from pathlib import Path

UNKNOWN = {"", "UNKNOWN", "UNVERIFIED"}
FIELDS = "fixture_id,style_sha256,section,beat_tick,chord,acmp_enabled,destination_part,midi_channel_1based,request_msb,request_lsb,request_pc_0based,source_file_sha256,source_bank,source_program_0based,native_handle_id,native_bank,native_program,fallback_reason,semantic_family,drum_key,velocity,donor_source_sha256,donor_bank,donor_program,donor_key,volume_trim_db,decision_trace,pcm_reference_sha256,status".split(",")
CONTEXT = FIELDS[:11] + ["drum_key", "velocity"]
SEMANTICS = ["source_file_sha256", "source_bank", "source_program_0based", "fallback_reason", "semantic_family", "donor_source_sha256", "donor_bank", "donor_program", "donor_key", "volume_trim_db"]

def digest(value):
    return len(value) == 64 and all(c in "0123456789abcdef" for c in value)

def read_csv(path):
    with Path(path).open(newline="", encoding="utf-8") as f:
        reader = csv.DictReader(f)
        if reader.fieldnames != FIELDS:
            raise ValueError("CSV header must match capture contract exactly")
        rows = list(reader)
    ids = set()
    for row in rows:
        if None in row or any(v is None for v in row.values()):
            raise ValueError("malformed CSV row")
        if not row["fixture_id"] or row["fixture_id"] in ids:
            raise ValueError("missing/duplicate fixture_id; each event needs its own ID")
        ids.add(row["fixture_id"])
    if not rows:
        raise ValueError("empty capture")
    return rows

def canonical(rows):
    out = io.StringIO(newline="")
    writer = csv.DictWriter(out, FIELDS, lineterminator="\n")
    writer.writeheader()
    # Stable fixture IDs must include event order. Native handles remain in raw
    # rows but canonical output uses a stable provenance alias, never handle IDs.
    for original in sorted(rows, key=lambda r: r["fixture_id"]):
        row = dict(original)
        if row["native_handle_id"] not in UNKNOWN:
            row["native_handle_id"] = "source:" + row["source_file_sha256"]
        writer.writerow(row)
    return out.getvalue()

def validate_evidence(rows, metadata, mode):
    if metadata.get("schema_version") != 1 or metadata.get("mode") != mode:
        raise ValueError("missing schema version/mode")
    if metadata.get("reference_commit") != "c75122daf5d1077db55d50d1c261144fde59af4a":
        raise ValueError("wrong golden reference")
    for field in ("android_build_identity", "bassmidi_build_identity", "raw_trace_sha256"):
        if metadata.get(field, "UNKNOWN") in UNKNOWN:
            raise ValueError("missing capture identity: " + field)
    if not digest(metadata["raw_trace_sha256"]):
        raise ValueError("invalid raw trace digest")
    hashes = metadata.get("source_sha256", [])
    if len(set(hashes)) != 4 or not all(digest(x) for x in hashes):
        raise ValueError("four source hashes required")
    for r in rows:
        if r["status"] != "VERIFIED":
            raise ValueError("row not runtime verified: " + r["fixture_id"])
        for key in CONTEXT + SEMANTICS + ["native_handle_id", "native_bank", "native_program", "decision_trace"]:
            if r[key] in UNKNOWN:
                raise ValueError("unknown field: " + r["fixture_id"] + "/" + key)
        if not digest(r["style_sha256"]) or r["source_file_sha256"] not in hashes:
            raise ValueError("missing style/source provenance")
        for key, lo, hi in (("midi_channel_1based",1,16),("request_msb",0,127),("request_lsb",0,127),("request_pc_0based",0,127),("velocity",0,127)):
            if not lo <= int(r[key]) <= hi:
                raise ValueError("out of range " + key)
        for key in ("source_bank", "source_program_0based", "native_bank", "native_program", "beat_tick"):
            if int(r[key]) < 0:
                raise ValueError("negative " + key)
        if not math.isfinite(float(r["volume_trim_db"])):
            raise ValueError("non-finite trim")
        if r["donor_source_sha256"] != "NONE" and r["donor_source_sha256"] not in hashes:
            raise ValueError("unknown donor provenance")
        if r["decision_trace"] == "NONE":
            raise ValueError("missing decision trace")

def mapping_index(manifest):
    index, addresses = {}, set()
    for row in manifest:
        if row.get("validation_status") != "VALIDATED_RUNTIME":
            raise ValueError("staging mapping is not approved runtime mapping")
        key = (row["source_sha256"], str(row["original_bank"]), str(row["original_program"]))
        address = (int(row["master_bank"]), int(row["master_program"]))
        if key in index or address in addresses:
            raise ValueError("duplicate provenance or conflicting master address")
        if not digest(key[0]) or not 0 <= address[0] <= 128 or not 0 <= address[1] <= 127:
            raise ValueError("invalid provenance/native address")
        index[key] = tuple(map(str, address))
        addresses.add(address)
    if not index:
        raise ValueError("empty mapping")
    return index

def compare(reference, master, manifest):
    mapping = mapping_index(manifest)
    ref, new = ({r["fixture_id"]: r for r in rows} for rows in (reference, master))
    differences = []
    if [r["fixture_id"] for r in reference] != [r["fixture_id"] for r in master]:
        differences.append("event ordering changed (compare raw captures before canonicalization)")
    for key in sorted(ref.keys() | new.keys()):
        if key not in ref or key not in new:
            differences.append(key + ": missing/extra event")
            continue
        a, b = ref[key], new[key]
        for field in CONTEXT + SEMANTICS:
            if a[field] != b[field]:
                differences.append(key + ": " + field)
        provenance = (a["source_file_sha256"], a["source_bank"], a["source_program_0based"])
        if provenance not in mapping:
            raise ValueError("unmapped source provenance: " + key)
        if (b["native_bank"], b["native_program"]) != mapping[provenance]:
            differences.append(key + ": native mapping")
        if a["donor_source_sha256"] != "NONE":
            donor = (a["donor_source_sha256"], a["donor_bank"], a["donor_program"])
            if donor not in mapping:
                raise ValueError("unmapped donor provenance: " + key)
    return differences

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    subs = parser.add_subparsers(dest="command", required=True)
    canon = subs.add_parser("canonicalize")
    canon.add_argument("capture")
    comp = subs.add_parser("compare")
    for name in ("reference", "master", "manifest", "reference_metadata", "master_metadata"):
        comp.add_argument(name)
    args = parser.parse_args()
    try:
        if args.command == "canonicalize":
            print(canonical(read_csv(args.capture)), end="")
            return 0
        a, b = read_csv(args.reference), read_csv(args.master)
        validate_evidence(a, json.loads(Path(args.reference_metadata).read_text()), "four_original")
        validate_evidence(b, json.loads(Path(args.master_metadata).read_text()), "experimental_master")
        differences = compare(a, b, json.loads(Path(args.manifest).read_text()))
        print(json.dumps({"routing": "MISMATCH" if differences else "EQUIVALENT", "differences": differences, "pcm": "UNVERIFIED", "master_export_go": False}, sort_keys=True))
        return int(bool(differences))
    except (ValueError, KeyError, OSError) as e:
        print(json.dumps({"routing": "UNVERIFIED", "error": str(e), "master_export_go": False}, sort_keys=True))
        return 2

if __name__ == "__main__":
    raise SystemExit(main())
