#!/usr/bin/env python3
import os
import sys
import zipfile
from pathlib import Path

KEYWORDS = [
    "midi", "sysex", "system exclusive", "yamaha", "smart pianist",
    "chord", "style", "accompan", "backing", "casm", "sff", "ntr",
    "ntt", "rtr", "high key", "note limit", "program change", "bank select",
    "usb", "bluetooth", "ble", "midiport", "midimanager", "song", "voice"
]


def print_header(title):
    print("\n" + "=" * 78)
    print(title)
    print("=" * 78)


def main(root):
    root = Path(root)
    apks = sorted(root.rglob("*.apk"))
    if not apks:
        raise SystemExit("No APK files found in XAPK")

    print_header("SMART PIANIST STATIC INVENTORY")
    print(f"Root: {root}")
    print(f"APK count: {len(apks)}")

    for apk in apks:
        print(f"\nAPK: {apk} ({apk.stat().st_size:,} bytes)")
        try:
            with zipfile.ZipFile(apk) as z:
                names = z.namelist()
                print(f"  ZIP entries: {len(names)}")
                dex = [n for n in names if n.endswith(".dex")]
                native = [n for n in names if n.startswith("lib/") and n.endswith(".so")]
                assets = [n for n in names if n.startswith("assets/")]
                print(f"  DEX: {len(dex)} -> {', '.join(dex[:20])}")
                print(f"  Native .so: {len(native)}")
                for n in native[:80]:
                    info = z.getinfo(n)
                    print(f"    {n}\t{info.file_size:,} bytes")
                print(f"  Assets: {len(assets)}")
                for n in assets[:80]:
                    print(f"    {n}")

                interesting = []
                for name in dex + assets:
                    try:
                        data = z.read(name)
                    except Exception:
                        continue
                    text = data.decode("utf-8", errors="ignore")
                    low = text.lower()
                    hits = [k for k in KEYWORDS if k in low]
                    if hits:
                        interesting.append((name, hits))
                print("  Keyword-bearing DEX/assets:")
                for name, hits in interesting[:200]:
                    print(f"    {name}: {', '.join(hits)}")
        except zipfile.BadZipFile as exc:
            print(f"  ERROR: {exc}")

    print_header("ANALYSIS PRIORITIES FOR YAMAHAARRANGER")
    print("1. MIDI transport and System Exclusive message handling")
    print("2. Yamaha-specific command/parameter tables")
    print("3. Chord detection / chord display / backing accompaniment paths")
    print("4. Style or song playback control and section handling")
    print("5. Voice/program/bank selection and instrument state")
    print("6. USB/Bluetooth MIDI device discovery and port handling")
    print("7. Native libraries containing Yamaha/MIDI/audio engines")
    print("\nThis report is an investigative index; proprietary source code is not redistributed.")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit(f"usage: {sys.argv[0]} XAPK_EXTRACTED_DIR")
    main(sys.argv[1])
