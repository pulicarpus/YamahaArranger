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
    compact_executable = Path(directory) / "drum_compatibility_report_test"
    subprocess.run([compiler, "-std=c++17", "-Wall", "-Wextra", "-Werror", "-O2",
                    "-I", str(root / "app/src/main/cpp"),
                    str(root / "tests/drum_compatibility_report_test.cpp"), "-o", str(compact_executable)], check=True)
    subprocess.run([str(compact_executable)], check=True)
    comparison_executable = Path(directory) / "drum_compatibility_comparison_test"
    subprocess.run([compiler, "-std=c++17", "-Wall", "-Wextra", "-Werror", "-O2",
                    "-I", str(root / "app/src/main/cpp"),
                    str(root / "tests/drum_compatibility_comparison_test.cpp"), "-o", str(comparison_executable)], check=True)
    subprocess.run([str(comparison_executable), str(root / "tests/fixtures/drum_demand_766.csv")], check=True)
    managed_executable = Path(directory) / "managed_sf2_audition_test"
    subprocess.run([compiler, "-std=c++17", "-Wall", "-Wextra", "-Werror", "-O2", "-pthread",
                    "-I", str(root / "tests/mocks"), "-I", str(root / "app/src/main/cpp"),
                    str(root / "tests/managed_sf2_audition_test.cpp"),
                    str(root / "app/src/main/cpp/managed_sf2_audition.cpp"), "-o", str(managed_executable)], check=True)
    subprocess.run([str(managed_executable), directory], check=True)

    presence_executable = Path(directory) / "accompaniment_presence_test"
    subprocess.run([compiler, "-std=c++17", "-Wall", "-Wextra", "-O2", "-pthread",
                    "-I", str(root / "tests/mocks"), "-I", str(root / "app/src/main/cpp"), "-I", str(root / "tests"),
                    str(root / "tests/accompaniment_presence_test.cpp"),
                    str(root / "app/src/main/cpp/bassmidi_player.cpp"), "-o", str(presence_executable)], check=True)
    subprocess.run([str(presence_executable), directory], check=True)

    shadow_executable = Path(directory) / "drum_shadow_readonly_test"
    subprocess.run([compiler, "-std=c++17", "-Wall", "-Wextra", "-O2", "-pthread", "-DSHADOW_OBSERVATION=1",
                    "-I", str(root / "tests/mocks"), "-I", str(root / "app/src/main/cpp"),
                    str(root / "tests/drum_shadow_readonly_test.cpp"),
                    str(root / "app/src/main/cpp/bassmidi_player.cpp"), "-o", str(shadow_executable)], check=True)
    subprocess.run([str(shadow_executable), directory, str(Path(directory)/"shadow-trace.txt")], check=True)
subprocess.run(["python3",str(root / "tools/test_drum_shadow_guards.py")],check=True)

subprocess.run(["python3",str(root / "tools/test_production_regression_bypass.py")],check=True)
