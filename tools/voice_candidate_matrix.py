#!/usr/bin/env python3
"""Build a Yamaha voice-resolution candidate matrix from style requests and SF2s.

Input request CSV must contain columns equivalent to:
  bank / msb+lsb / requested_bank, program, voice/name, role (optional)

The parser accepts packed Yamaha bank (MSB*128+LSB) or separate msb/lsb.
Each SF2 is inspected from its pdta/phdr metadata only; sample data is never decoded.

Output:
  --csv   one row per request with exact/MSB/semantic/PC candidate counts
  --json  structured candidate data
  --md    human-readable summary

This is an audit tool. It does not modify engine code or SoundFonts.
"""

from __future__ import annotations

import argparse
import csv
import json
import re
import struct
from collections import Counter
from pathlib import Path


FAMILY_WORDS = {
    "guitar": ("guitar", "gtr"),
    "strings": ("string", "strg", "violin", "viola", "cello", "ensemble", "ens", "orchestra", "orch", "sforz"),
    "bass": ("bass",),
    "piano": ("piano", "grand"),
    "organ": ("organ",),
    "accordion": ("accordion",),
    "brass": ("brass", "trumpet", "trombone"),
    "woodwind": ("sax", "clarinet", "flute", "oboe"),
    "choir": ("choir", "voice"),
    "pad": ("pad",),
    "synth": ("synth",),
}


def family(name: str, program: int | None = None) -> str:
    n = name.lower()
    # Guitar must precede strings: "12 String Guitar" is guitar-family.
    for fam in ("guitar", "strings", "bass", "piano", "organ", "accordion", "brass", "woodwind", "choir", "pad", "synth"):
        if any(w in n for w in FAMILY_WORDS[fam]):
            return fam
    if program is not None:
        if program <= 7: return "piano"
        if program <= 23: return "organ"
        if program <= 31: return "guitar"
        if program <= 39: return "bass"
        if program <= 55: return "strings"
        if program <= 63: return "brass"
        if program <= 71: return "woodwind"
        if program <= 79: return "woodwind"
        if program <= 95: return "synth"
        if program <= 103: return "pad"
        if program <= 111: return "choir"
    return "unknown"


def text(value: str) -> str:
    return re.sub(r"[\r\n\x1e\x1f]+", " ", value or "").strip()


def u16(data: bytes, pos: int) -> int:
    return struct.unpack_from("<H", data, pos)[0]


def phdr_presets(path: Path):
    data = path.read_bytes()
    if len(data) < 12 or data[:4] != b"RIFF" or data[8:12] != b"sfbk":
        raise ValueError(f"invalid SF2: {path}")
    result = []
    pos = 12
    while pos + 8 <= len(data):
        cid = data[pos:pos + 4]
        size = struct.unpack_from("<I", data, pos + 4)[0]
        start = pos + 8
        end = start + size
        if end > len(data): break
        if cid == b"LIST" and start + 4 <= end and data[start:start + 4] == b"pdta":
            p = start + 4
            while p + 8 <= end:
                sid = data[p:p + 4]
                ssize = struct.unpack_from("<I", data, p + 4)[0]
                s0, s1 = p + 8, p + 8 + ssize
                if s1 > end: break
                if sid == b"phdr" and ssize >= 38:
                    for off in range(s0, s1 - 37, 38):
                        name = text(data[off:off + 20].split(b"\0", 1)[0].decode("cp1252", "replace"))
                        program = u16(data, off + 20)
                        bank = u16(data, off + 22)
                        if name and name != "EOP":
                            result.append({"name": name, "program": program, "bank": bank})
                    return result
                p = s1 + (ssize & 1)
        pos = end + (size & 1)
    return result


def first(row, names, default=""):
    lowered = {k.strip().lower(): v for k, v in row.items()}
    for n in names:
        if n in lowered and str(lowered[n]).strip():
            return str(lowered[n]).strip()
    return default


def parse_bank(row):
    packed = first(row, ["bank", "requested_bank", "yamaha_bank", "bank14"])
    msb = first(row, ["msb", "bank_msb", "requested_msb"])
    lsb = first(row, ["lsb", "bank_lsb", "requested_lsb"], "0")
    if packed:
        return int(packed)
    if msb:
        return int(msb) * 128 + int(lsb or 0)
    raise ValueError("request row has no bank/msb field")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--requests", type=Path, required=True)
    ap.add_argument("--sf2", type=Path, action="append", required=True)
    ap.add_argument("--csv", type=Path, required=True)
    ap.add_argument("--json", type=Path, required=True)
    ap.add_argument("--md", type=Path, required=True)
    args = ap.parse_args()

    fonts = []
    for path in args.sf2:
        presets = phdr_presets(path)
        fonts.append({"file": path.name, "presets": presets})

    rows = []
    with args.requests.open(newline="", encoding="utf-8-sig") as f:
        for idx, row in enumerate(csv.DictReader(f), 1):
            bank = parse_bank(row)
            program = int(first(row, ["program", "pc", "program_change"]))
            name = text(first(row, ["voice", "voice_name", "name", "instrument", "requested_voice"], f"PC {program}"))
            role = text(first(row, ["role", "part_role"], "MELODY")).upper()
            req_msb, req_lsb = divmod(bank, 128)
            wanted = family(name, program)

            exact, same_msb, semantic, same_pc = [], [], [], []
            for font in fonts:
                for p in font["presets"]:
                    if role == "DRUM":
                        if p["bank"] not in (127, 128):
                            continue
                    else:
                        if p["bank"] in (127, 128):
                            continue
                    item = {"sf2": font["file"], **p, "family": family(p["name"], p["program"])}
                    if p["bank"] == bank and p["program"] == program:
                        exact.append(item)
                    if p["bank"] == req_msb and p["program"] == program:
                        same_msb.append(item)
                    if p["program"] == program:
                        same_pc.append(item)
                    if wanted != "unknown" and item["family"] == wanted:
                        semantic.append(item)

            # Deduplicate semantic candidates that are already exact/MSB candidates.
            semantic_keys = {(x["sf2"], x["bank"], x["program"], x["name"]) for x in exact + same_msb}
            semantic = [x for x in semantic if (x["sf2"], x["bank"], x["program"], x["name"]) not in semantic_keys]

            status = "exact" if exact else "same_msb" if same_msb else "semantic" if semantic else "same_pc" if same_pc else "none"
            rows.append({
                "row": idx, "bank": bank, "msb": req_msb, "lsb": req_lsb, "program": program,
                "voice": name, "role": role, "family": wanted, "status": status,
                "exact_count": len(exact), "same_msb_count": len(same_msb),
                "semantic_count": len(semantic), "same_pc_count": len(same_pc),
                "exact": exact, "same_msb": same_msb, "semantic": semantic, "same_pc": same_pc,
            })

    counts = Counter(r["status"] for r in rows)
    with args.csv.open("w", newline="", encoding="utf-8") as f:
        fields = ["row", "bank", "msb", "lsb", "program", "voice", "role", "family", "status", "exact_count", "same_msb_count", "semantic_count", "same_pc_count"]
        w = csv.DictWriter(f, fieldnames=fields)
        w.writeheader()
        for r in rows:
            w.writerow({k: r[k] for k in fields})

    args.json.write_text(json.dumps({"requests": rows, "summary": dict(counts), "sf2": [f["file"] for f in fonts]}, ensure_ascii=False, indent=2), encoding="utf-8")

    lines = ["# Voice Candidate Matrix", "", f"Requests: **{len(rows)}**", f"SF2 files: **{len(fonts)}**", "", "## Coverage", ""]
    for key in ("exact", "same_msb", "semantic", "same_pc", "none"):
        lines.append(f"- {key}: {counts.get(key, 0)}")
    lines += ["", "## Family counts", ""]
    fam_counts = Counter(r["family"] for r in rows)
    for k, v in sorted(fam_counts.items()):
        lines.append(f"- {k}: {v}")
    lines += ["", "## High-risk requests", "", "Rows needing semantic or same-PC fallback are listed here for review.", ""]
    for r in rows:
        if r["status"] in ("semantic", "same_pc", "none"):
            lines.append(f"- bank {r['bank']} ({r['msb']}:{r['lsb']}) PC {r['program']} — {r['voice']} — {r['status']} — exact={r['exact_count']} same-msb={r['same_msb_count']} semantic={r['semantic_count']} same-pc={r['same_pc_count']}")
    args.md.write_text("\n".join(lines) + "\n", encoding="utf-8")

    print(f"requests={len(rows)} status={dict(counts)}")


if __name__ == "__main__":
    main()
