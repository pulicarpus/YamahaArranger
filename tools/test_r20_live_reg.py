#!/usr/bin/env python3
"""Unchanged current Phase4 LIVE/REG functional JVM body; historical profile guards stay separate."""
import ast
from pathlib import Path
import subprocess,sys
ROOT=Path(__file__).resolve().parents[1];BASE='163f769bf056f0dd0593f78ab20fbbe700dcb56f'
sys.path.insert(0,str(ROOT/'tools'));import test_voice_release as old
script=ROOT/'tools/test_live_reg_phase3.py';assert script.read_bytes()==subprocess.check_output(['git','show',BASE+':tools/test_live_reg_phase3.py'],cwd=ROOT)
ns={'__file__':str(script),'__name__':'r20_current_phase3_definitions'}
exec(compile(script.read_text().split('\nimport argparse\n')[0],str(script),'exec'),ns)
phase4=ROOT/'tools/test_live_reg_phase4.py';assert phase4.read_bytes()==subprocess.check_output(['git','show',BASE+':tools/test_live_reg_phase4.py'],cwd=ROOT)
function=next(n for n in ast.parse(phase4.read_text()).body if isinstance(n,ast.FunctionDef) and n.name=='augmented')
original=old.run;scope={'ROOT':ROOT,'old':old,'original':original}
exec(compile(ast.Module(body=[function],type_ignores=[]),str(phase4),'exec'),scope)
old.run=scope['augmented']
try:ns['current_jvm'](False)
finally:old.run=original
print('R20 CURRENT LIVE/REG PHASE4 FUNCTIONAL JVM PASS; historical source-profile CI remains BLOCKED')
