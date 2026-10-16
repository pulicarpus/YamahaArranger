import os,subprocess,tarfile,io,json,random,itertools,math,statistics,hashlib,platform,time
from pathlib import Path
R=Path('/workspace/work/universal-yamaha-u0-u1');O=Path('/workspace/work/r23/precision-runner');old=Path('/workspace/work/r23/final/pcm-session');base=Path('/workspace/work/r23/recall-investigation/baseline');historical=O/'historic163';historical.mkdir(exist_ok=True)
with tarfile.open(fileobj=io.BytesIO(subprocess.check_output(['git','archive','163f769bf056f0dd0593f78ab20fbbe700dcb56f','app/src/main/cpp','tests'],cwd=R))) as t:t.extractall(historical,filter='data')
CPU=1;TRIALS=48;CHUNKS=150;N=1000;SEED=20261016
protocol=r'''
    auto cpuNs=[](){timespec t{};clock_gettime(CLOCK_PROCESS_CPUTIME_ID,&t);return uint64_t(t.tv_sec)*1000000000+t.tv_nsec;};
    for(int warm=0;warm<10000;++warm)player.render(block,128);
    std::cout<<"READY"<<std::endl;
    unsigned count;
    while(std::cin>>count){
      const auto start=std::chrono::steady_clock::now();const auto cpuStart=cpuNs();
      for(unsigned i=0;i<count;++i)player.render(block,128);
      const auto used=cpuNs()-cpuStart;
      const auto wall=std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now()-start).count();
      std::cout<<"EPOCH "<<wall<<" "<<used<<std::endl;
    }
'''
h=(old/'native-driver.cpp').read_text();i=h.index('    if(std::getenv("R20_BENCH"))');j=h.index('    }\n',i)+6
h=h[:i]+protocol+h[j:];h='#include <ctime>\n'+h;(O/'historical-driver.cpp').write_text(h)
def compile_(label,source,driver,macros):
 target=O/(label+'.exe');cmd=['g++','-std=c++17','-O2','-pthread','-DYAMAHA_ROLE_PCM_METERS=1','-DYAMAHA_PCM_HEADROOM=1',*macros,'-I'+str(old/'mocks'),'-I'+str(source/'app/src/main/cpp'),'-I'+str(source/'tests'),str(driver),str(source/'app/src/main/cpp/bassmidi_player.cpp'),'-o',str(target)]
 subprocess.run(cmd,cwd=R,check=True);return target
bins={}
for label,source,defs in [('historic163',historical,[]),('r22',base,['-DR20_CANDIDATE=1']),('r23',R,['-DR20_CANDIDATE=1'])]:bins[label]=compile_(label,source,O/'historical-driver.cpp',defs)
def cgroup():
 return {k:int(v) for k,v in (line.split() for line in Path('/sys/fs/cgroup/cpu.stat').read_text().splitlines())}
def sample(name,commands):
 children={};initial={};before=cgroup()
 for label,command in commands.items():
  child=subprocess.Popen(command,cwd=R,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True,bufsize=1,preexec_fn=lambda:os.sched_setaffinity(0,{CPU}));children[label]=child;prefix=[]
  while True:
   line=child.stdout.readline()
   if line.strip()=='READY':break
   if not line:raise RuntimeError((label,child.poll(),child.stderr.read()))
   prefix.append(line)
  initial[label]=''.join(prefix)
 permutations=list(itertools.permutations(commands));rng=random.Random(SEED);blocks=[]
 # Every trial has all six orders exactly 25 times. No trimming/optional stop.
 for trial in range(TRIALS):
  orders=permutations*(CHUNKS//len(permutations));rng.shuffle(orders);totals={k:{'wall':0,'cpu':0} for k in commands}
  for order in orders:
   for label in order:
    child=children[label];child.stdin.write(str(N)+'\n');child.stdin.flush();line=child.stdout.readline().split();assert line[0]=='EPOCH',(label,line)
    totals[label]['wall']+=int(line[1]);totals[label]['cpu']+=int(line[2])
  blocks.append(totals);(O/(name+'-partial.json')).write_text(json.dumps(blocks));print(name,'TRIAL',trial,totals,flush=True)
 for label,child in children.items():
  child.stdin.close();tail=child.stdout.read();err=child.stderr.read();assert child.wait(timeout=30)==0,(label,err);(O/(name+'-'+label+'-process.log')).write_text(initial[label]+tail+err)
 after=cgroup()
 def compare(a,b,metric):
  ratios=[math.log(t[b][metric]/t[a][metric]) for t in blocks];rng=random.Random(SEED+1)
  bootstrap=sorted(math.expm1(statistics.mean(rng.choices(ratios,k=len(ratios))))*100 for _ in range(10000))
  median=(statistics.median(t[b][metric] for t in blocks)/statistics.median(t[a][metric] for t in blocks)-1)*100
  return dict(baseline=a,candidate=b,metric=metric,median_delta_percent=median,paired_geomean_percent=math.expm1(statistics.mean(ratios))*100,paired_bootstrap95_percent=[bootstrap[249],bootstrap[9749]],median_lt_1=median<1,upper95_lt_1=bootstrap[9749]<1)
 labels=list(commands);comparisons=[compare(a,b,m) for a,b in [(labels[0],labels[1]),(labels[0],labels[2]),(labels[1],labels[2])] for m in ['wall','cpu']]
 report=dict(protocol='persistent processes; 1000 render-block epochs; six orders exactly 25 times per trial; fixed N=48; all samples retained',trials=TRIALS,epochs_per_trial=CHUNKS,render_blocks_per_epoch=N,frames_per_block=128,warmup_blocks_per_process=10000,seed=SEED,cpu_affinity=[CPU],cgroup_before=before,cgroup_after=after,cgroup_delta={k:after[k]-before[k] for k in before},shared_vm_not_exclusive=True,governor_control=False,host=platform.platform(),blocks=blocks,comparisons=comparisons,commands=commands,binary_sha256={label:hashlib.sha256(Path(command[0]).read_bytes()).hexdigest() for label,command in commands.items()})
 (O/(name+'.json')).write_text(json.dumps(report,indent=2)+'\n');print(name,'RESULT',json.dumps(comparisons),flush=True)
sample('historical',{k:[str(v),str(old/'fixture')] for k,v in bins.items()})
# Same golden font/input/configuration as preceding debug-mode experiment.
s=(Path('/workspace/work/r23/overhead-audit/modes.cpp')).read_text();a=s.index(' p.markPcmSession({});');b=s.index(' // NOTE dispatch cost',a)
q=protocol.replace('player.render','p.render');s=s[:a]+' p.markPcmSession({});float block[256];\n'+q+'}\n';(O/'modes-driver.cpp').write_text(s)
for label,source,defs in [('r22',base,[]),('r23',R,['-DR23=1'])]:compile_(label+'-debug',source,O/'modes-driver.cpp',['-DYAMAHA_COMPATIBLE_PERCUSSION=1',*defs])
fixture='/workspace/work/r23/final/native/golden-fixtures'
sample('debug-modes',{'r22_legacy':[str(O/'r22-debug.exe'),fixture,'legacy'],'r23_legacy':[str(O/'r23-debug.exe'),fixture,'legacy'],'r23_resolved':[str(O/'r23-debug.exe'),fixture,'resolved']})
