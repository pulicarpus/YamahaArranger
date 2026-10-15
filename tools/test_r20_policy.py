#!/usr/bin/env python3
"""Unchanged Policy C contracts, true N10 and all per-case golden checks."""
from pathlib import Path
import argparse,subprocess,json,hashlib,concurrent.futures
ROOT=Path(__file__).resolve().parents[1]
def main():
 p=argparse.ArgumentParser();p.add_argument('--out',required=True);a=p.parse_args();out=Path(a.out).resolve();out.mkdir(parents=True,exist_ok=True)
 header=out/'baseline.h';header.write_bytes(subprocess.check_output(['git','show','f4f2c613bebd09a3137bdbe0bc60f0895b597979:app/src/main/cpp/voice_resolver.h'],cwd=ROOT))
 exe=out/'policy';subprocess.run(['g++','-std=c++17','-O2','-Wall','-Wextra','-Werror','-DBASSMIDI_R1_BASELINE_HEADER="'+str(header)+'"','tools/bassmidi_r1_policy_test.cpp','-o',str(exe)],cwd=ROOT,check=True)
 fixture=ROOT/'tests/fixtures/bassmidi_r1';manifest=json.loads((fixture/'manifest.json').read_text())
 def suite(name):
  cmd=[str(exe),str(fixture/'full-presets.tsv'),name]
  if name=='named':cmd.append(str(fixture/'named_inv.out'))
  text=subprocess.check_output(cmd,text=True);(out/(name+'.log')).write_text(text);lines=text.splitlines();assert lines[-1]=='CONTRACT PASS 0'
  assert any(l.startswith('PASS N10 MSB104 actual baseline invariance') for l in lines)
  rows=[l+'\n' for l in lines if l.startswith('AB\t')];assert len(rows)==manifest['counts'][name]
  if name in manifest['golden_ab_sha256']:assert hashlib.sha256(''.join(rows).encode()).hexdigest()==manifest['golden_ab_sha256'][name]
  else:assert any(l.startswith('PASS NAMED all 39936 winners match Claude per-case golden; no extra changes') for l in lines)
  return lines[-2]
 with concurrent.futures.ThreadPoolExecutor(max_workers=2) as ex:
  for result in ex.map(suite,['main','nonzero','named','msb0']):print(result,flush=True)
if __name__=='__main__':main()
