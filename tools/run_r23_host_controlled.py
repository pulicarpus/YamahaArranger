#!/usr/bin/env python3
"""Pin a CPU and record runner state; retain the original R2-0 baseline and every assertion."""
import argparse,json,os,sys
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
def cpu_stat():
 p=Path('/sys/fs/cgroup/cpu.stat')
 return {k:int(v) for k,v in (line.split() for line in p.read_text().splitlines())} if p.exists() else {}
def main():
 p=argparse.ArgumentParser();p.add_argument('--out',required=True);args=p.parse_args()
 out=Path(args.out).resolve();out.mkdir(parents=True,exist_ok=True)
 allowed=sorted(os.sched_getaffinity(0));chosen=allowed[1] if len(allowed)>1 else allowed[0]
 os.sched_setaffinity(0,{chosen});before=cpu_stat()
 report={'allowed_cpus':allowed,'selected_cpu':chosen,'baseline':'163f769bf056f0dd0593f78ab20fbbe700dcb56f','benchmark':'unchanged tools/test_r20_host.py','threshold_percent':1,'attempts':1,'result':'RUNNING','cpu_stat_before':before}
 try:
  sys.path.insert(0,str(ROOT/'tools'));import test_r20_host
  sys.argv=['test_r20_host.py','--out',str(out)]
  test_r20_host.main();report['result']='PASS'
 except BaseException:
  report['result']='FAIL';raise
 finally:
  after=cpu_stat();report.update(cpu_stat_after=after,cpu_stat_delta={k:after[k]-before[k] for k in before})
  (out/'runner.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report),flush=True)
if __name__=='__main__':main()
