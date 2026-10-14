#!/usr/bin/env python3
"""Run the real isolation guard against independent deliberately corrupted copies."""
import tempfile,shutil
from pathlib import Path
from test_live_reg_phase4_isolation import ROOT,FILES,ADDITIONS,WF,VM,verify
with tempfile.TemporaryDirectory(prefix='phase4-negative-') as d:
    root=Path(d)
    for p in set(FILES)|ADDITIONS|{WF}:
        if (ROOT/p).is_file():
            (root/p).parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(ROOT/p,root/p)
    verify(root)
    cases=[('app/src/main/java/com/yourapp/yamahaarranger/arranger/ArrangerBrain.kt',lambda s:s+'\n// forbidden\n'),
           ('tools/test_live_reg_phase3_isolation.py',lambda s:s+'\n# forbidden\n'),
           (VM,lambda s:s.replace('mirrorToMidiOut=false','mirrorToMidiOut=true')),
           (WF,lambda s:s.replace('--history --real --fetch-dependencies','--history --fetch-dependencies')),
           ('app/src/main/java/Unapproved.kt',lambda s:'class Unapproved')]
    for path,change in cases:
        p=root/path;existing=p.exists();previous=p.read_text() if existing else ''
        p.parent.mkdir(parents=True,exist_ok=True);p.write_text(change(previous))
        try:verify(root)
        except AssertionError:print('NEGATIVE GUARD PASS rejected '+path)
        else:raise AssertionError('Guard accepted forbidden mutation: '+path)
        finally:
            if existing:p.write_text(previous)
            else:p.unlink()
print('PHASE4_NEGATIVE_GUARD 5/5 PASS')
