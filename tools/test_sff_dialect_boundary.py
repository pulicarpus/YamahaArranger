#!/usr/bin/env python3
"""Real corpus identity and native metadata gate; no SFF2 fixtures invented."""
import argparse
import shutil
import subprocess
import tempfile
import zipfile
from pathlib import Path
from audit_sff1_corpus import ARCHIVE_SHA, ROOT, file_sha, require, production_identity

p=argparse.ArgumentParser()
p.add_argument("--corpus",required=True,type=Path)
a=p.parse_args()
require(file_sha(a.corpus)==ARCHIVE_SHA,"wrong original corpus SHA")
production_identity()
with tempfile.TemporaryDirectory(prefix="sff-dialect-") as tmp:
    root=Path(tmp)
    with zipfile.ZipFile(a.corpus) as z:
        for name in z.namelist():
            require(not Path(name).is_absolute() and '..' not in Path(name).parts,"unsafe corpus path")
        z.extractall(root)
    exe=root/"boundary"
    subprocess.run([shutil.which("g++"),"-std=c++17","-O2","-DANDROID_LOG_ERROR=6",
                    "-I",str(ROOT/"tests/mocks"),"-I",str(ROOT/"app/src/main/cpp"),
                    str(ROOT/"tests/sff_dialect_boundary_test.cpp"),
                    str(ROOT/"app/src/main/cpp/style_parser.cpp"),
                    str(ROOT/"app/src/main/cpp/smf_reader.cpp"),"-o",str(exe)],check=True)
    styles=sorted((root/"sff1").rglob('*'))
    styles=[x for x in styles if x.is_file()]
    require(len(styles)==503,"503 original styles required")
    for style in styles:
        subprocess.run([str(exe),str(style)],check=True,stdout=subprocess.DEVNULL,stderr=subprocess.PIPE)
    for extension in (".mid",".bin",".unknown"):
        renamed=root/("not-a-style"+extension)
        renamed.write_bytes(styles[0].read_bytes())
        require(file_sha(renamed)==file_sha(styles[0]),"extension test altered bytes")
        subprocess.run([str(exe),str(renamed)],check=True,stdout=subprocess.DEVNULL,stderr=subprocess.PIPE)
print("S2_NATIVE PASS 503/503 exact SFF1 declarations; UNKNOWN; extension independence; load/failure isolation; native section metadata")
print("SFF2 UNVERIFIED_NEEDS_FIXTURE; no positive detector or musical interpretation")
