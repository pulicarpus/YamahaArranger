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
from collections import Counter, defaultdict
from pathlib import Path

UNKNOWN = {"", "UNKNOWN", "UNVERIFIED"}
FIELDS = "fixture_id,style_sha256,section,beat_tick,chord,acmp_enabled,destination_part,midi_channel_1based,request_msb,request_lsb,request_pc_0based,source_file_sha256,source_bank,source_program_0based,native_handle_id,native_bank,native_program,fallback_reason,semantic_family,drum_key,velocity,donor_source_sha256,donor_bank,donor_program,donor_key,volume_trim_db,decision_trace,pcm_reference_sha256,status".split(",")
CONTEXT = FIELDS[:11] + ["drum_key", "velocity"]
SEMANTICS = ["source_file_sha256", "source_bank", "source_program_0based", "fallback_reason", "semantic_family", "donor_source_sha256", "donor_bank", "donor_program", "donor_key", "volume_trim_db"]
SOURCES = {
    "ColomboGMGS2_BM.sf2": ("9e4686e4aa7173ffd1baf7ec6f80fcb3fe90880e824cf8c4e1defb5d153d47eb", 892),
    "DRUMKIT YAMAHA PSR-SX700 & SX900 PRIME.sf2": ("a72158c09677c585eec348762a33da49509bf338c5b36d0e787af58cca0b9f8a", 21),
    "MELODI YAMAHA PSR-SX700 & SX900 PRIME.sf2": ("e8c7356159c200d945f13e113595ff06e7955779b0e9361709d9c7cdba164a82", 93),
    "yamaha tyros 4_just_t4_fixed.sf2": ("de5b1404630840a2a897e77c6083e2231c5d1523e6571a8afd972a4f684d36ce", 142),
}
INVENTORY_FIELDS = ["source_filename", "source_sha256", "original_bank", "original_program", "original_name"]

def inspect_inventory(path=None, scope="ORIGINAL"):
    """Inspect supplied rows only; declared hashes do not rehash source binaries."""
    if path is None:
        return {"status": "UNVERIFIED", "evidence_scope": "ORIGINAL", "rows_observed": 0,
                "reason": "original inventory not supplied", "required_columns": INVENTORY_FIELDS,
                "expected_rows_from_prior_gate": 1148, "conflicts": [], "conflicts_complete": False,
                "master_export_go": False}
    if scope not in ("ORIGINAL", "SYNTHETIC"):
        raise ValueError("invalid inventory scope")
    with Path(path).open(newline="", encoding="utf-8-sig") as f:
        reader = csv.DictReader(f)
        if not reader.fieldnames or not set(INVENTORY_FIELDS) <= set(reader.fieldnames):
            raise ValueError("inventory columns required: " + ",".join(INVENTORY_FIELDS))
        rows = list(reader)
    if not rows:
        raise ValueError("empty inventory")
    seen, addresses, counts, banks = set(), defaultdict(list), Counter(), defaultdict(set)
    for row in rows:
        if None in row or any(row.get(k) in UNKNOWN or row.get(k) is None for k in INVENTORY_FIELDS):
            raise ValueError("malformed/unknown inventory field")
        filename, sha = row["source_filename"], row["source_sha256"]
        if not digest(sha):
            raise ValueError("invalid inventory source hash")
        if scope == "ORIGINAL" and (filename not in SOURCES or sha != SOURCES[filename][0]):
            raise ValueError("source filename/hash does not match verified source gate")
        bank, pc = int(row["original_bank"]), int(row["original_program"])
        if not 0 <= bank <= 65535 or not 0 <= pc <= 127:
            raise ValueError("invalid original bank/program")
        identity = (sha, bank, pc)
        if identity in seen:
            raise ValueError("duplicate original source bank/program provenance")
        seen.add(identity); counts[filename] += 1; banks[sha].add(bank)
        addresses[(bank, pc)].append({"source_filename": filename, "source_sha256": sha,
                                     "original_bank": bank, "original_program": pc,
                                     "original_name": row["original_name"]})
    conflicts = []
    if scope == "ORIGINAL" and any(n > SOURCES[name][1] for name, n in counts.items()):
        raise ValueError("source preset count exceeds verified source gate")
    for (bank, pc), members in sorted(addresses.items()):
        if len({r["source_sha256"] for r in members}) > 1:
            conflicts.append({"bank": bank, "program_0based": pc,
                              "members": sorted(members, key=lambda r: (r["source_filename"], r["source_sha256"])),
                              "resolver_decision": "UNVERIFIED_NEEDS_REQUEST_CONTEXT"})
    complete = scope == "ORIGINAL" and dict(counts) == {k:v[1] for k,v in SOURCES.items()}
    normalized = {}
    for sha, original in sorted(banks.items()):
        melodic = sorted(original - {127, 128})
        if len(melodic) > 127:
            raise ValueError("source exceeds checkpoint normalizer bank capacity")
        normalized[sha] = [{"original_bank": b, "native_virtual_bank": i if complete or scope == "SYNTHETIC" else "UNKNOWN",
                            "observed_subset_virtual_bank": i,
                            "generic_destination_msb": b // 128 if b >= 128 else b,
                            "generic_destination_lsb": b % 128 if b >= 128 else 0}
                           for i, b in enumerate(melodic)]
    if complete and (len(addresses) != 911 or len(conflicts) != 158 or len(rows)-len(addresses) != 237):
        raise ValueError("full inventory collision totals differ from prior gate")
    return {"status": "PASS" if complete or scope == "SYNTHETIC" else "UNVERIFIED",
            "evidence_scope": scope, "rows_observed": len(rows), "counts_by_source": dict(sorted(counts.items())),
            "unique_raw_addresses": len(addresses), "cross_source_conflict_groups": len(conflicts),
            "extra_colliding_entries": len(rows)-len(addresses), "conflicts": conflicts,
            "conflicts_complete": complete, "full_original_inventory_validated": complete,
            "normalization_projection": normalized,
            "normalization_projection_scope": "FULL_ORIGINAL_BANK_SET" if complete else "OBSERVED_SUBSET_ONLY_NOT_RUNTIME",
            "review_banks_126_127_128": {str(b):sum(len(v) for (bank, _),v in addresses.items() if bank == b) for b in (126,127,128)},
            "source_binaries_rehashed": False, "routing_certified": False, "master_export_go": False}

def validate_inventory_membership(rows, path):
    with Path(path).open(newline="", encoding="utf-8-sig") as f:
        identities = {(r["source_sha256"], str(int(r["original_bank"])), str(int(r["original_program"]))) for r in csv.DictReader(f)}
    for r in rows:
        for prefix, columns in (("preset", ("source_file_sha256", "source_bank", "source_program_0based")),
                                ("donor", ("donor_source_sha256", "donor_bank", "donor_program"))):
            if prefix == "donor" and r[columns[0]] == "NONE": continue
            if tuple(r[k] for k in columns) not in identities:
                raise ValueError("capture " + prefix + " absent from original inventory")

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
    scope = metadata.get("evidence_scope")
    if scope not in ("SYNTHETIC", "ORIGINAL"):
        raise ValueError("explicit evidence_scope ORIGINAL or SYNTHETIC required")
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
    if scope == "ORIGINAL" and set(hashes) != {v[0] for v in SOURCES.values()}:
        raise ValueError("capture sources do not match four verified original hashes")
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
    comp.add_argument("--inventory", help="required full original inventory for ORIGINAL scope")
    inv = subs.add_parser("inventory")
    inv.add_argument("csv", nargs="?")
    inv.add_argument("--scope", choices=("ORIGINAL", "SYNTHETIC"), default="ORIGINAL")
    args = parser.parse_args()
    try:
        if args.command == "inventory":
            report = inspect_inventory(args.csv, args.scope)
            print(json.dumps(report, sort_keys=True))
            return 0 if report["status"] == "PASS" else 2
        if args.command == "canonicalize":
            print(canonical(read_csv(args.capture)), end="")
            return 0
        a, b = read_csv(args.reference), read_csv(args.master)
        reference_meta = json.loads(Path(args.reference_metadata).read_text())
        master_meta = json.loads(Path(args.master_metadata).read_text())
        validate_evidence(a, reference_meta, "four_original")
        validate_evidence(b, master_meta, "experimental_master")
        scope = reference_meta["evidence_scope"]
        if master_meta["evidence_scope"] != scope or set(reference_meta["source_sha256"]) != set(master_meta["source_sha256"]):
            raise ValueError("capture evidence scope/source sets differ")
        if scope == "ORIGINAL":
            report = inspect_inventory(args.inventory)
            if not report.get("full_original_inventory_validated"):
                raise ValueError("full original inventory required for ORIGINAL comparison")
            validate_inventory_membership(a, args.inventory)
            validate_inventory_membership(b, args.inventory)
        differences = compare(a, b, json.loads(Path(args.manifest).read_text()))
        print(json.dumps({"routing": "MISMATCH" if differences else "EQUIVALENT", "evidence_scope": scope, "differences": differences, "pcm": "UNVERIFIED", "master_export_go": False}, sort_keys=True))
        return int(bool(differences))
    except (ValueError, KeyError, OSError, TypeError) as e:
        print(json.dumps({"routing": "UNVERIFIED", "status": "FAIL" if args.command == "inventory" else "UNVERIFIED", "error": str(e), "master_export_go": False}, sort_keys=True))
        return 2

if __name__ == "__main__":
    raise SystemExit(main())
