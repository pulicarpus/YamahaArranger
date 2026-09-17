#!/usr/bin/env python3
"""Inspect SoundFont 2 metadata without decoding/loading sample data."""

from __future__ import annotations

import argparse
import struct
from dataclasses import dataclass
from pathlib import Path


@dataclass
class Preset:
    name: str
    program: int
    bank: int


def u16(data: bytes, p: int) -> int:
    return struct.unpack_from("<H", data, p)[0]


def read_text(data: bytes, start: int, length: int) -> str:
    raw = data[start : start + length]
    raw = raw.split(b"\0", 1)[0]
    return raw.decode("cp1252", errors="replace").strip()


def walk_chunks(data: bytes, start: int, end: int):
    p = start
    while p + 8 <= end:
        cid = data[p : p + 4]
        size = struct.unpack_from("<I", data, p + 4)[0]
        payload = p + 8
        chunk_end = payload + size
        if chunk_end > end or chunk_end > len(data):
            break
        if cid == b"LIST" and payload + 4 <= chunk_end:
            yield from walk_chunks(data, payload + 4, chunk_end)
        else:
            yield cid, payload, chunk_end
        p = chunk_end + (size & 1)


def inspect(path: Path):
    size = path.stat().st_size
    data = path.read_bytes()
    valid = len(data) >= 12 and data[:4] == b"RIFF" and data[8:12] == b"sfbk"
    presets: list[Preset] = []
    instruments = 0
    samples = 0
    info: dict[str, str] = {}

    if valid:
        for cid, start, end in walk_chunks(data, 12, len(data)):
            if cid == b"phdr":
                for p in range(start, end - 37, 38):
                    name = read_text(data, p, 20)
                    program = u16(data, p + 20)
                    bank = u16(data, p + 22)
                    if name and name != "EOP":
                        presets.append(Preset(name, program, bank))
            elif cid == b"inst ":
                instruments = max(0, (end - start) // 22 - 1)
            elif cid == b"shdr":
                samples = max(0, (end - start) // 46 - 1)
            elif cid in (b"INAM", b"ISFT", b"ICMT", b"IENG", b"IPRD"):
                info[cid.decode("ascii")] = read_text(data, start, end - start)

    relevant_words = ("bass", "piano", "guitar", "string", "pad", "kit", "drum", "percussion")
    relevant = [
        p for p in presets
        if p.bank == 128 or any(word in p.name.lower() for word in relevant_words)
    ]

    print("YamahaArranger SF2 Inspector")
    print("============================")
    print(f"File: {path.name}")
    print(f"Size: {size / 1024 / 1024:.2f} MB")
    print(f"Valid SF2: {valid}")
    for key in ("INAM", "IPRD", "IENG", "ISFT", "ICMT"):
        if info.get(key):
            print(f"{key}: {info[key]}")
    print(f"Presets: {len(presets)}")
    print(f"Instruments: {instruments}")
    print(f"Samples: {samples}")
    print()
    print("Relevant presets")
    print("-----------------")
    if not relevant:
        print("(none found)")
    else:
        for p in relevant:
            print(f"bank={p.bank:03d} program={p.program:03d}  {p.name}")
    print()
    print(f"All presets (first {min(len(presets), 500)})")
    print("-----------------")
    for p in presets[:500]:
        print(f"bank={p.bank:03d} program={p.program:03d}  {p.name}")
    if len(presets) > 500:
        print(f"... {len(presets) - 500} more")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("sf2", type=Path)
    args = parser.parse_args()
    inspect(args.sf2)
