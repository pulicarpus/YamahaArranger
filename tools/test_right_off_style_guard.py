import hashlib,json,subprocess,unittest
from pathlib import Path
from right_off_style_guard import verify_brain
root=Path(__file__).resolve().parents[1]
review=json.loads((root/'tests/fixtures/right_off_brain_review.json').read_text())
before=subprocess.check_output(['git','show',review['keyboard_checkpoint']+':'+review['brain_path']],cwd=root)
after=(root/review['brain_path']).read_bytes()
class ReviewedKeyboardGuardTest(unittest.TestCase):
    def test_reviewed_keyboard_patch(self): verify_brain(before,after,review)
    def test_unreviewed_keyboard_change_rejected(self):
        with self.assertRaises(AssertionError): verify_brain(before,after.replace(b'val keyboardLock = Any()',b'val keyboardLock = Unit'),review)
    def test_style_change_rejected_even_with_new_review_hash(self):
        changed=after.replace(b'val style = loadedStyle',b'val style = null')
        updated=dict(review,after_sha256=hashlib.sha256(changed).hexdigest())
        with self.assertRaisesRegex(AssertionError,'start-stop changed'): verify_brain(before,changed,updated)
    def test_release_change_rejected_even_with_new_review_hash(self):
        changed=after.replace(b'keyboardReleaseTime = value.coerceIn(0, 127)',b'keyboardReleaseTime = value.coerceIn(0, 120)')
        updated=dict(review,after_sha256=hashlib.sha256(changed).hexdigest())
        with self.assertRaisesRegex(AssertionError,'RELEASE setter changed'): verify_brain(before,changed,updated)
if __name__=='__main__': unittest.main()
