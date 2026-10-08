#!/usr/bin/env python3
"""Real vendor SDK positives and fail-closed negatives; no audio/device claims."""
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from audit_sff1_corpus import ConformanceError
from generated_bass_sdk_guard import ROOT, PATHS, verify_generated_sdk
from test_sff_root_selection import portable_production_identity


class GeneratedSdkGuardTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        # CI's existing SDK download step precedes the F04 JVM gate.
        cls.vendor = {p: (ROOT / p).read_bytes() for p in PATHS}
        verify_generated_sdk(ROOT, PATHS)

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        for path, data in self.vendor.items():
            file = self.root / path
            file.parent.mkdir(parents=True, exist_ok=True)
            file.write_bytes(data)

    def test_real_six_vendor_files_pass(self):
        self.assertEqual(PATHS, set(verify_generated_sdk(self.root, PATHS)))

    def test_no_sdk_host_is_supported(self):
        empty = self.root / 'empty-host'
        empty.mkdir()
        self.assertEqual({}, verify_generated_sdk(empty, []))

    def test_extra_unknown_production_files_fail(self):
        for extra in ('app/src/main/cpp/basssdk/extra.h',
                      'app/src/main/jniLibs/x86_64/libbass.so',
                      'app/src/main/java/Unknown.kt'):
            with self.assertRaisesRegex(AssertionError, 'Untracked production source'):
                verify_generated_sdk(self.root, PATHS | {extra})

    def test_every_sdk_path_rejects_changed_bytes(self):
        for path, data in self.vendor.items():
            file = self.root / path
            for corrupt in (data + b'corruption', data[:-1] + bytes([data[-1] ^ 1])):
                file.write_bytes(corrupt)
                with self.assertRaisesRegex(AssertionError, 'identity mismatch'):
                    verify_generated_sdk(self.root, PATHS)
            file.write_bytes(data)

    def test_wrong_abi_library_fails(self):
        (self.root / 'app/src/main/jniLibs/arm64-v8a/libbass.so').write_bytes(
            self.vendor['app/src/main/jniLibs/armeabi-v7a/libbass.so'])
        with self.assertRaisesRegex(AssertionError, 'identity mismatch'):
            verify_generated_sdk(self.root, PATHS)

    def test_partial_set_and_missing_file_fail(self):
        path = sorted(PATHS)[0]
        with self.assertRaisesRegex(AssertionError, 'Incomplete'):
            verify_generated_sdk(self.root, PATHS - {path})
        (self.root / path).unlink()
        with self.assertRaisesRegex(AssertionError, 'regular file'):
            verify_generated_sdk(self.root, PATHS)

    def test_sdk_symlink_fails(self):
        path = sorted(PATHS)[0]
        file = self.root / path
        file.unlink()
        file.symlink_to(ROOT / path)
        with self.assertRaisesRegex(AssertionError, 'regular file'):
            verify_generated_sdk(self.root, PATHS)

    def test_mutated_sdk_manifest_fails(self):
        manifest = self.root / 'manifest.json'
        manifest.write_text('{}')
        with patch('generated_bass_sdk_guard.MANIFEST', manifest):
            with self.assertRaisesRegex(AssertionError, 'manifest identity drift'):
                verify_generated_sdk(self.root, PATHS)

    def test_125_tracked_sources_still_verified_with_real_sdk(self):
        result = portable_production_identity()
        self.assertEqual(125, len(result['tracked_files']))
        path = 'app/src/main/cpp/smf_reader.cpp'
        with self.assertRaises(ConformanceError):
            portable_production_identity(lambda p: (ROOT / p).read_bytes() + (b'\n' if p == path else b''))

    def test_integrated_guard_rejects_unknown_alongside_valid_sdk(self):
        original = subprocess.check_output

        def output(command, **kwargs):
            if command[:3] == ['git', 'ls-files', '--others']:
                return '\n'.join(sorted(PATHS | {'app/src/main/cpp/unknown.cpp'})) + '\n'
            return original(command, **kwargs)

        with patch('test_sff_root_selection.subprocess.check_output', side_effect=output):
            with self.assertRaisesRegex(ConformanceError, 'Untracked production source'):
                portable_production_identity()


if __name__ == '__main__':
    unittest.main()
