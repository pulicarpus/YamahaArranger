#!/usr/bin/env python3
"""Compile/test the SAME pure C++ policy included by the Android native engine."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
compiler = os.environ.get("CXX") or shutil.which("c++") or shutil.which("g++")
if not compiler:
    raise SystemExit("C++17 host compiler required for voice resolver regression tests")
with tempfile.TemporaryDirectory(prefix="yamaha-resolver-") as directory:
    executable = Path(directory) / "voice_resolver_test"
    subprocess.run([compiler, "-std=c++17", "-Wall", "-Wextra", "-Werror", "-O2",
                    "-I", str(root / "app/src/main/cpp"),
                    str(root / "tests/voice_resolver_test.cpp"), "-o", str(executable)], check=True)
    subprocess.run([str(executable)], check=True)
    native_executable = Path(directory) / "native_resolver_test"
    subprocess.run([compiler, "-std=c++17", "-Wall", "-Wextra", "-O2", "-pthread",
                    "-I", str(root / "tests/mocks"), "-I", str(root / "app/src/main/cpp"),
                    str(root / "tests/native_resolver_test.cpp"),
                    str(root / "app/src/main/cpp/bassmidi_player.cpp"),
                    "-o", str(native_executable)], check=True)
    subprocess.run([str(native_executable), directory], check=True)
    diagnostic_executable = Path(directory) / "audio_path_diagnostic_test"
    subprocess.run([compiler, "-std=c++17", "-Wall", "-Wextra", "-Werror", "-O2",
                    "-I", str(root / "app/src/main/cpp"),
                    str(root / "tests/audio_path_diagnostic_test.cpp"),
                    "-o", str(diagnostic_executable)], check=True)
    subprocess.run([str(diagnostic_executable)], check=True)
    zone_executable = Path(directory) / "sf2_zone_diagnostic_test"
    subprocess.run([compiler, "-std=c++17", "-Wall", "-Wextra", "-Werror", "-O2",
                    "-I", str(root / "app/src/main/cpp"),
                    str(root / "tests/sf2_zone_diagnostic_test.cpp"),
                    "-o", str(zone_executable)], check=True)
    subprocess.run([str(zone_executable)], check=True)
    kit_executable = Path(directory) / "drum_kit_audit_test"
    subprocess.run([compiler, "-std=c++17", "-Wall", "-Wextra", "-Werror", "-O2",
                    "-I", str(root / "app/src/main/cpp"),
                    str(root / "tests/drum_kit_audit_test.cpp"), "-o", str(kit_executable)], check=True)
    subprocess.run([str(kit_executable)], check=True)
