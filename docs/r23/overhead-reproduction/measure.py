import os,subprocess,random,statistics,json,math,hashlib,platform,time
from pathlib import Path
root=Path('/workspace/work/universal-yamaha-u0-u1');out=Path('/workspace/work/r23/overhead-audit');old=Path('/workspace/work/r23/final/pcm-session');base=Path('/workspace/work/r23/recall-investigation/baseline')
assert (base/'app/src/main/cpp/bassmidi_player.cpp').read_bytes()==subprocess.check_output(['git','show','d456d733c788b46ae06fe09c8b687eeb4915d554:app/src/main/cpp/bassmidi_player.cpp'],cwd=root)
cmd=['g++','-std=c++17','-O2','-pthread','-DR20_CANDIDATE=1','-DYAMAHA_ROLE_PCM_METERS=1','-DYAMAHA_PCM_HEADROOM=1','-I'+str(old/'mocks'),'-I'+str(base/'app/src/main/cpp'),'-I'+str(base/'tests'),str(old/'native-driver.cpp'),str(base/'app/src/main/cpp/bassmidi_player.cpp'),'-o',str(out/'r22')]
subprocess.run(cmd,check=True)
executables={'historic163':old/'native-before','r22':out/'r22','r23_legacy':old/'native-after'}
fixture=old/'fixture';cpu=sorted(os.sched_getaffinity(0))[1];env={**os.environ,'R20_BENCH':'1'}
def run(label):
 t=subprocess.check_output([str(executables[label]),str(fixture)],cwd=root,env=env,text=True,preexec_fn=lambda:os.sched_setaffinity(0,{cpu}),timeout=30)
 return int(next(l.split()[1] for l in t.splitlines() if l.startswith('BENCH_NS ')))
# Fixed sample count and seed, all trials retained. No optional stopping/retry.
for _ in range(5):
 for label in executables:run(label)
rng=random.Random(20261016);blocks=[]
for i in range(31):
 order=list(executables);rng.shuffle(order);values={label:run(label) for label in order};blocks.append({'order':order,'ns':values})
 (out/'partial.json').write_text(json.dumps(blocks))
 print('BLOCK',i,values,flush=True)
def compare(a,b):
 ratios=[math.log(x['ns'][b]/x['ns'][a]) for x in blocks];rng=random.Random(16345623)
 boot=sorted(math.expm1(statistics.mean(rng.choices(ratios,k=len(ratios))))*100 for _ in range(10000))
 return {'baseline':a,'candidate':b,'paired_geomean_percent':math.expm1(statistics.mean(ratios))*100,'paired_bootstrap_95ci_percent':[boot[249],boot[9749]],'ratio_of_medians_percent':(statistics.median(x['ns'][b] for x in blocks)/statistics.median(x['ns'][a] for x in blocks)-1)*100,'strict_point_lt_1':math.expm1(statistics.mean(ratios))*100<1,'upper_95ci_lt_1':boot[9749]<1}
report={'scope':'HOST_MOCK_DECODE_NOT_DEVICE','cpu_affinity':[cpu],'trials':31,'warmup_runs_each':5,'seed':20261016,'compiler':subprocess.check_output(['g++','--version'],text=True).splitlines()[0],'host':platform.platform(),'flags':cmd[1:8],'executables_sha256':{k:hashlib.sha256(v.read_bytes()).hexdigest() for k,v in executables.items()},'blocks':blocks,'comparisons':[compare('historic163','r22'),compare('historic163','r23_legacy'),compare('r22','r23_legacy')]}
(out/'controlled-render.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report['comparisons']),flush=True)
