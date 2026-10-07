#!/usr/bin/env python3
import subprocess,tempfile
from pathlib import Path
root=Path(__file__).resolve().parents[1]
with tempfile.TemporaryDirectory() as tmp:
 exe=Path(tmp)/'semantic'
 subprocess.run(['g++','-std=c++17','-O2','-DANDROID_LOG_ERROR=6','-I',str(root/'tests/mocks'),'-I',str(root/'app/src/main/cpp'),str(root/'tests/sff_casm_semantic_test.cpp'),str(root/'app/src/main/cpp/style_parser.cpp'),str(root/'app/src/main/cpp/smf_reader.cpp'),'-o',str(exe)],check=True)
 subprocess.run([str(exe),'--self-test'],check=True)
