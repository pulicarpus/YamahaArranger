import subprocess,os,statistics,random,math,json,hashlib
from pathlib import Path
r=Path('/workspace/work/universal-yamaha-u0-u1');out=Path('/workspace/work/r23/overhead-audit');base=Path('/workspace/work/r23/recall-investigation/baseline');mocks=Path('/workspace/work/r23/final/pcm-session/mocks');fixtures=Path('/workspace/work/r23/final/native/golden-fixtures');cpu=sorted(os.sched_getaffinity(0))[1]
for label,src,flags in [('r22',base,[]),('r23',r,['-DR23=1'])]:
 cmd=['g++','-std=c++17','-O2','-pthread','-DYAMAHA_COMPATIBLE_PERCUSSION=1','-DYAMAHA_ROLE_PCM_METERS=1','-DYAMAHA_PCM_HEADROOM=1',*flags,'-I'+str(mocks),'-I'+str(src/'app/src/main/cpp'),str(out/'modes.cpp'),str(src/'app/src/main/cpp/bassmidi_player.cpp'),'-o',str(out/(label+'-modes'))]
 subprocess.run(cmd,check=True)
variants={'r22_legacy':('r22','legacy'),'r23_legacy':('r23','legacy'),'r23_resolved':('r23','resolved')}
def run(label):
 exe,mode=variants[label];t=subprocess.check_output([str(out/(exe+'-modes')),str(fixtures),mode],text=True,preexec_fn=lambda:os.sched_setaffinity(0,{cpu}),timeout=30)
 line=next(l for l in t.splitlines() if l.startswith('MODE ')).split();return {line[i]:int(line[i+1]) for i in range(2,len(line),2)}
for _ in range(5):
 for label in variants:run(label)
rng=random.Random(2345623);blocks=[]
for i in range(31):
 order=list(variants);rng.shuffle(order);blocks.append({'order':order,'values':{k:run(k) for k in order}});print('BLOCK',i,flush=True)
def compare(a,b,metric):
 ratios=[math.log(t['values'][b][metric]/t['values'][a][metric]) for t in blocks];rng=random.Random(2345623)
 boot=sorted(math.expm1(statistics.mean(rng.choices(ratios,k=len(ratios))))*100 for _ in range(10000))
 return {'baseline':a,'candidate':b,'metric':metric,'paired_geomean_percent':math.expm1(statistics.mean(ratios))*100,'paired_bootstrap_95ci_percent':[boot[249],boot[9749]],'ratio_of_medians_percent':(statistics.median(t['values'][b][metric] for t in blocks)/statistics.median(t['values'][a][metric] for t in blocks)-1)*100,'upper95_lt_1percent':boot[9749]<1}
comparisons=[compare(a,b,m) for a,b in [('r22_legacy','r23_legacy'),('r22_legacy','r23_resolved'),('r23_legacy','r23_resolved')] for m in ['WALL','CPU']]
report={'scope':'HOST_MOCK_DECODE_NO_DEVICE_AUDIO','trials':31,'warmups_each':5,'cpu_affinity':[cpu],'flags':'-O2 -pthread COMPATIBLE_PERCUSSION=1 ROLE_PCM_METERS=1 PCM_HEADROOM=1','blocks':blocks,'comparisons':comparisons,'component_medians_ns':{k:{m:statistics.median(t['values'][k][m] for t in blocks) for m in ['WALL','CPU','NOTE_PAIRS_NS','OBSERVATIONS_NS']} for k in variants},'render_blocks':150000,'frames_per_block':128,'note_pairs':2000,'readonly_snapshots':1000,'binary_sha256':{k:hashlib.sha256((out/(k+'-modes')).read_bytes()).hexdigest() for k in ['r22','r23']}}
(out/'controlled-modes.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(comparisons),flush=True)
