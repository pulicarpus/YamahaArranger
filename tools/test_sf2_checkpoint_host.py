#!/usr/bin/env python3
"""Run existing frozen routing tests without Gradle, APK, or real SF2 files.

Usage: python3 tools/test_sf2_checkpoint_host.py [checkpoint-tree-root]
The optional tree MUST be a verified read-only snapshot of c75122d; report
fingerprints separately. Tests use existing BASS mocks and synthetic RIFF files.
"""
from pathlib import Path
import hashlib
import json
import subprocess
import sys
import tempfile

root = Path(sys.argv[1]).resolve() if len(sys.argv)>1 else Path(__file__).resolve().parents[1]
snapshot=Path(__file__).resolve().parents[1]/"tests/fixtures/sf2_golden_source_snapshot.json"
for entry in json.loads(snapshot.read_text())["files"]:
    data=(root/entry["path"]).read_bytes()
    if hashlib.sha256(data).hexdigest()!=entry["sha256"]:
        raise SystemExit("checkpoint fingerprint mismatch: "+entry["path"])
print("CHECKPOINT snapshot verified: 84 files",flush=True)
targets = [
    ("voice_resolver_test",False,[]),
    ("native_resolver_test",True,[]),
    ("bank_translation_readback_test",True,[]),
    ("accompaniment_presence_test",True,[]),
    ("percussion_fidelity_test",True,["-DYAMAHA_COMPATIBLE_PERCUSSION=1"]),
    ("f03_native_lifecycle_test",True,["-DYAMAHA_COMPATIBLE_PERCUSSION=1"]),
    ("sf2_zone_diagnostic_test",False,[]),
    ("audio_path_diagnostic_test",False,[]),
    ("drum_kit_audit_test",False,[]),
    ("drum_compatibility_report_test",False,[]),
    ("sf2_golden_boundaries_test",True,[]),
]
with tempfile.TemporaryDirectory(prefix="sf2-golden-host-") as directory:
    for name,native,defines in targets:
        target=Path(directory)/name;fixture_dir=Path(directory)/(name+"-fixtures");fixture_dir.mkdir()
        command=["c++","-std=c++17","-Wall","-Wextra","-O2","-pthread"]+defines
        for include in ("tests/mocks","app/src/main/cpp","tests"):
            command += ["-I",str(root/include)]
        command += [str(root/("tests/"+name+".cpp"))]
        if native: command += [str(root/"app/src/main/cpp/bassmidi_player.cpp")]
        command += ["-o",str(target)]
        subprocess.run(command,check=True)
        subprocess.run([str(target),str(fixture_dir)] if native else [str(target)],check=True)
        print("HOST PASS "+name,flush=True)
print("HOST targets=11; mocked BASS, synthetic fixtures; PCM/device UNVERIFIED")
