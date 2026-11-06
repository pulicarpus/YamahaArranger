"""Offline metrics on FunkAuditScratchTest dumps. Usage: runs.py file.tsv [...]"""
import sys
from collections import defaultdict, Counter
def load(p):
    H=None; M=[]; W=[]; E=[]
    for l in open(p):
        f=l.rstrip('\n').split('\t')
        if f[0]=='H': H=f
        elif f[0]=='M': M.append((int(f[1]),f[2],f[3] if len(f)>3 else ''))
        elif f[0]=='W': W.append((int(f[1]),int(f[2]),int(f[3]),int(f[4])))
        elif f[0]=='E': E.append((int(f[1]),f[2],int(f[3]),int(f[4])))
    return H,M,W,E
on=lambda w:(w[1]&0xF0)==0x90 and w[3]>0
off=lambda w:(w[1]&0xF0)==0x80 or ((w[1]&0xF0)==0x90 and w[3]==0)
ms=lambda t,t0:(t-t0)/1e6
for p in sys.argv[1:]:
    H,M,W,E=load(p); t0=next(m[0] for m in M if m[1]=='START'); stop=next(m[0] for m in M if m[1]=='STOP')
    secs=[(m[0],m[2]) for m in M if m[1]=='SECTION']; chords=[(round(ms(m[0],t0)),m[2]) for m in M if m[1]=='CHORD']
    print(f"\n##### {p.split('/')[-1]}  {H[1:]}")
    print(" sections:", [(round(ms(t,t0)),s) for t,s in secs], " chords:", chords)
    # notes
    open_=defaultdict(list); notes=[]; dup=Counter(); act=0; peak=0; peakT=0; peakCh=Counter(); actCh=Counter()
    for w in W:
        ch=w[1]&15
        if on(w):
            if open_[(ch,w[2])]: dup[ch]+=1
            n=[ch,w[2],w[0],None]; open_[(ch,w[2])].append(n); notes.append(n)
            actCh[ch]+=1; peakCh[ch]=max(peakCh[ch],actCh[ch])
            if ch>=10:
                act+=1
                if act>peak: peak=act; peakT=w[0]
        elif off(w) and open_[(ch,w[2])]:
            n=open_[(ch,w[2])].pop(0); n[3]=w[0]; actCh[ch]-=1; act-=(ch>=10)
        elif (w[1]&0xF0)==0xB0 and w[2] in (120,123):
            for k in list(open_):
                if k[0]==ch:
                    for n in open_[k]: n[3]=w[0]; n.append('panic'); act-=(ch>=10); actCh[ch]-=1
                    open_[k]=[]
    stuck=Counter(n[0] for n in notes if n[0]>=10 and (n[3] is None or len(n)>4))
    onc=Counter(w[1]&15 for w in W if on(w)); offc=Counter(w[1]&15 for w in W if off(w))
    eon=Counter(e[2] for e in E if e[1]=='O'); eoff=Counter(e[2] for e in E if e[1]=='F')
    print(" ch  ON  OFF  engON engOFF peakAct dupSameKey closedOnlyByPanic pitches")
    for ch in sorted(onc):
        ps=sorted({n[1] for n in notes if n[0]==ch})
        print(f" {ch+1:2d} {onc[ch]:4d} {offc[ch]:4d} {eon[ch]:6d} {eoff[ch]:6d} {peakCh[ch]:6d} {dup[ch]:6d} {stuck[ch]:5d}  {ps[:3]}..{ps[-3:]} ({len(ps)})")
    # melodic-only peak with drums excluded (drum notes on the E343 are one-shots)
    print(f" peak simultaneous melodic notes (ch11-16) = {peak} at {ms(peakT,t0):.0f} ms")
    # polyphony sampled: max melodic notes sounding over time including drums as 1 tick
    # ctl messages after start
    ctl=Counter(); pcs=[]
    for w in W:
        st=w[1]&0xF0; ch=w[1]&15
        if st==0xB0: ctl[(ch+1,w[2])]+=1
        if st==0xC0: pcs.append((round(ms(w[0],t0)),ch+1,w[2]+1))
    print(" CC counts (ch,cc):", dict(sorted(ctl.items())))
    print(" PC msgs (ms,ch,pc1):", pcs[:40])
    # bursts
    ts=[w[0] for w in W]; mx=0; mxT=0; j=0
    for i in range(len(ts)):
        while ts[i]-ts[j]>5e6: j+=1
        if i-j+1>mx: mx=i-j+1; mxT=ts[i]
    print(f" max wire msgs in 5 ms window = {mx} at {ms(mxT,t0):.0f} ms")
    # fill -> main boundary
    fill=next(((t,s) for t,s in secs if s.startswith('Fill')),None)
    if fill:
        main=next(((t,s) for t,s in secs if t>fill[0] and s.startswith('Main')),None)
        if main:
            first=next((w[0] for w in W if w[0]>=main[0] and on(w)),main[0])
            fn=[n for n in notes if fill[0]<=n[2]<main[0] and n[0]>=10]
            carried=[n for n in fn if (n[3] or stop)>first+20e6]
            sounding=[n for n in notes if n[2]<first and (n[3] or 1e30)>first and n[0]>=10]
            win=[w for w in W if abs(w[0]-first)<=30e6]
            print(f" {fill[1]}->{main[1]}: fill at {ms(fill[0],t0):.0f} ms, main label at {ms(main[0],t0):.0f}, first main ON {ms(first,t0):.0f}; melodic notes in fill={len(fn)}, carried>20ms={len(carried)} "
                  + ",".join(f"ch{n[0]+1}:{n[1]}(+{ms(n[3] or stop,first):.0f}ms)" for n in carried))
            print(f"   melodic notes sounding at first Main ON: {len(sounding)} " + ",".join(f"ch{n[0]+1}:{n[1]}" for n in sounding))
            print(f"   wire msgs within +-30 ms of boundary: {len(win)} ON={sum(on(w) for w in win)} OFF={sum(off(w) for w in win)} CC={sum((w[1]&0xF0)==0xB0 for w in win)} PC={sum((w[1]&0xF0)==0xC0 for w in win)}")
