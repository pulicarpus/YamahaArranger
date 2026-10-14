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
    (root/'evidence').mkdir()
    (root/'evidence/linux-sdk.sha256').write_text('generated checksum')
    (root/'evidence/phase4-gates.log').write_text('generated log')
    verify(root)
    print('POSITIVE GUARD PASS permits known untracked CI artifacts')
    cases=[('app/src/main/java/com/yourapp/yamahaarranger/arranger/ArrangerBrain.kt',lambda s:s+'\n// forbidden\n'),
           ('tools/test_live_reg_phase3_isolation.py',lambda s:s+'\n# forbidden\n'),
           (VM,lambda s:s.replace('mirrorToMidiOut=false','mirrorToMidiOut=true')),
           (WF,lambda s:s.replace('--history --real --fetch-dependencies','--history --fetch-dependencies')),
           ('app/src/main/java/Unapproved.kt',lambda s:'class Unapproved'),
           ('evidence/hidden.kt',lambda s:'class Hidden'),
           ('docs/unapproved.md',lambda s:'unapproved repository addition'),
           ('app/src/main/java/com/yourapp/ui/LiveControlLayout.kt',lambda s:s.replace('val gap = 8f','val gap = 0f'))]
    for path,change in cases:
        p=root/path;existing=p.exists();previous=p.read_text() if existing else ''
        p.parent.mkdir(parents=True,exist_ok=True);p.write_text(change(previous))
        try:verify(root)
        except AssertionError:print('NEGATIVE GUARD PASS rejected '+path)
        else:raise AssertionError('Guard accepted forbidden mutation: '+path)
        finally:
            if existing:p.write_text(previous)
            else:p.unlink()
print('PHASE4_NEGATIVE_GUARD 8/8 PASS')
