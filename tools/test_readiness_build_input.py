#!/usr/bin/env python3
"""Exercise new checkout audit with real pinned SDK bytes; no audio claim."""
import shutil, subprocess, tempfile, unittest
from pathlib import Path
from unittest.mock import patch
import check_readiness_build_input as audit
from generated_bass_sdk_guard import PATHS, ROOT as REPO, verify_generated_sdk

class BuildInputTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        verify_generated_sdk(REPO, PATHS)
        cls.sdk = {p:(REPO/p).read_bytes() for p in PATHS}
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup)
        self.root=Path(self.tmp.name)
        subprocess.run(['git','init','-q',str(self.root)],check=True)
        self.source=self.root/'app/src/main/Test.kt';self.source.parent.mkdir(parents=True)
        self.source.write_text('test-only input\n')
        (self.root/'.gitignore').write_text('app/src/main/ignored.kt\n')
        subprocess.run(['git','add','.'],cwd=self.root,check=True)
        subprocess.run(['git','-c','user.name=Test','-c','user.email=test@example.invalid','commit','-qm','fixture'],cwd=self.root,check=True)
        self.sha=subprocess.check_output(['git','rev-parse','HEAD'],cwd=self.root,text=True).strip()
        for p,data in self.sdk.items():
            f=self.root/p;f.parent.mkdir(parents=True,exist_ok=True);f.write_bytes(data)
        self.patch=patch.object(audit,'ROOT',self.root);self.patch.start();self.addCleanup(self.patch.stop)
    def test_real_six_sdk_and_repeat_observation_pass(self):
        before=audit.capture(self.sha)
        self.assertEqual(PATHS,set(before['sdk']));self.assertEqual(before,audit.capture(self.sha))
    def test_wrong_commit_rejected(self):
        with self.assertRaisesRegex(AssertionError,'SHA mismatch'):audit.capture('0'*40)
    def test_tracked_source_mutation_rejected(self):
        self.source.write_text('mutation')
        with self.assertRaisesRegex(AssertionError,'Tracked build input'):audit.capture(self.sha)
    def test_unknown_untracked_build_script_rejected(self):
        (self.root/'build.gradle').write_text('untracked injected build code')
        with self.assertRaisesRegex(AssertionError,'Unknown untracked'):audit.capture(self.sha)
    def test_ignored_unknown_source_rejected(self):
        (self.source.parent/'ignored.kt').write_text('ignored injected source')
        with self.assertRaisesRegex(AssertionError,'Untracked production'):audit.capture(self.sha)
    def test_sdk_bytes_mutation_rejected(self):
        p=self.root/sorted(PATHS)[0];p.write_bytes(p.read_bytes()+b'X')
        with self.assertRaisesRegex(AssertionError,'identity mismatch'):audit.capture(self.sha)
    def test_symlink_source_rejected(self):
        (self.source.parent/'linked.kt').symlink_to(self.source)
        with self.assertRaises(AssertionError):audit.capture(self.sha)
    def test_missing_sdk_rejected(self):
        (self.root/sorted(PATHS)[0]).unlink()
        with self.assertRaisesRegex(AssertionError,'Incomplete'):audit.capture(self.sha)
    def test_post_observation_mutation_cannot_equal_record(self):
        before=audit.capture(self.sha)
        self.source.write_text('after change')
        with self.assertRaises(AssertionError):audit.capture(self.sha)
        self.assertEqual(self.sha,before['commit'])

if __name__=='__main__':unittest.main()
