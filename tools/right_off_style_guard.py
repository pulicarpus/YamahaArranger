"""Strict reviewed keyboard exception; original SHA guards and style bytes stay frozen."""
import hashlib

def verify_brain(before, after, review):
    digest=lambda b: hashlib.sha256(b).hexdigest()
    assert digest(before)==review['before_sha256'], 'keyboard review baseline mismatch'
    assert digest(after)==review['after_sha256'], 'unreviewed keyboard Brain edit'
    # Even updating a review hash cannot admit edits to style behavior or RELEASE.
    a=before.decode(); b=after.decode()
    first='    // PSR-E343 default split point'
    assert a.split(first)[0]==b.split(first)[0], 'Brain style constructor changed'
    start='    private var appliedChord:'; end='    fun setKeyboardReleaseTime('
    assert a[a.index(start):a.index(end)]==b[b.index(start):b.index(end)], 'Brain style state/load/scope changed'
    tail='    private fun onChordChanged('
    assert a[a.index(tail):]==b[b.index(tail):], 'Brain chord/clock/section/start-stop changed'
    next_method='    fun setKeyboardSustain('
    assert a[a.index(end):a.index(next_method)]==b[b.index(end):b.index(next_method)], 'RELEASE setter changed'
