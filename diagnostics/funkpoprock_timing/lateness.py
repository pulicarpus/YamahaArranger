"""Exact lateness of Rhythm (ch9/10, no CASM) NOTE ONs at the engine boundary vs the Style tick grid. Usage: lateness.py style.tsv timingdir"""
import sys,glob,os,statistics
from collections import defaultdict
sty,d=sys.argv[1],sys.argv[2]
src=defaultdict(list); L={}; sec=None
for l in open(sty,encoding='latin-1'):
    f=l.rstrip('\n').split('\t')
    if f[0]=='S': sec=f[1]; L[sec]=int(f[2])
    elif f[0]=='E':
        st=int(f[2])
        if (st&0xF0)==0x90 and int(f[4])>0 and (st&15) in (8,9): src[sec].append((int(f[1]),st&15,int(f[3])))
def pct(a,p): a=sorted(a); return a[min(len(a)-1,int(len(a)*p))] if a else float('nan')
print("run\tN\tmatched\tp50\tp95\tp99\tmax(ms)\tbar0\tbar1\tFill\tMainD\tpeakEng/2ms")
for p in sorted(glob.glob(os.path.join(d,'*.tsv'))):
    H=None;E=[]
    for l in open(p):
        f=l.rstrip('\n').split('\t')
        if f[0]=='H': H=f
        elif f[0]=='E' and f[2]=='O': E.append((int(f[1]),int(f[3]),int(f[4])))
    tempo,ppq,c0=int(H[1]),int(H[3]),int(H[4]); tns=60e9/(tempo*ppq); bar=4*ppq
    exp=[(0+t,ch,n,'C') for t,ch,n in src['MainC'] if t<2*bar]+[(2*bar+t,ch,n,'F') for t,ch,n in src['FillDD']]+[(3*bar+t,ch,n,'D') for t,ch,n in src['MainD'] if t<bar]
    drum=[e for e in E if e[1] in (8,9)]
    used=set(); lat=[]; per=defaultdict(list)
    for tk,ch,n,s in exp:
        due=c0+tk*tns; best=None
        for i,(t,c,nn) in enumerate(drum):
            if i in used or c!=ch or nn!=n: continue
            dl=t-due
            if -5e6<dl<80e6 and (best is None or abs(dl)<abs(best[1])): best=(i,dl)
        if best: used.add(best[0]); ms=best[1]/1e6; lat.append(ms); per[('bar0' if tk<bar else 'bar1') if s=='C' else s].append(ms)
    ts=sorted(e[0] for e in E); mx=0;j=0
    for i in range(len(ts)):
        while ts[i]-ts[j]>2e6: j+=1
        mx=max(mx,i-j+1)
    if not lat: print(os.path.basename(p)[:-4], "no drum events (muted)"); continue
    m=lambda k: f"{statistics.mean(per[k]):.2f}" if per[k] else '-'
    print(f"{os.path.basename(p)[:-4]}\t{len(exp)}\t{len(lat)}\t{pct(lat,.5):.2f}\t{pct(lat,.95):.2f}\t{pct(lat,.99):.2f}\t{max(lat):.2f}\t{m('bar0')}\t{m('bar1')}\t{m('F')}\t{m('D')}\t{mx}")
