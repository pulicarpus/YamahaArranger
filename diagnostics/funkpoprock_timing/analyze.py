"""Read-only analysis of a production-parser dump (style.tsv). Usage: analyze.py style.tsv [SECTION ...]"""
import sys
from collections import defaultdict, Counter
path = sys.argv[1]; focus = set(sys.argv[2:])
heads = []; sections = {}; order = []
cur = None; part = None
for line in open(path, encoding='latin-1'):
    f = line.rstrip('\n').split('\t')
    if f[0] in ('S',):
        cur = {'name': f[1], 'len': int(f[2]), 'parts': []}; sections[f[1]] = cur; order.append(f[1]); part = None
    elif f[0] == 'P':
        part = {'name': (f[1] if len(f) > 1 else '').strip(), 'casm': [], 'ev': []}; cur['parts'].append(part)
    elif f[0] == 'C' and part is not None:
        part['casm'].append(dict(src=int(f[1]), dst=int(f[2]), voice=f[3].strip(), root=int(f[4]), type=int(f[5]), ntr=int(f[6]), ntt=int(f[7]),
                                 high=int(f[8]), lo=int(f[9]), hi=int(f[10]), rtr=int(f[11]), bass=f[12], mask=f[13], slo=int(f[14]), shi=int(f[15])))
    elif f[0] == 'E' and part is not None:
        st = int(f[2])
        if 0x80 <= st <= 0xEF: part['ev'].append((int(f[1]), st, int(f[3]), int(f[4])))
    elif cur is None:
        heads.append(line.strip())
print('HEADER', heads[:6])
print('SECTIONS', ' '.join(f"{n}:{sections[n]['len']}" for n in order))
for n in order:
    if focus and n not in focus: continue
    s = sections[n]; L = s['len']
    print(f'\n=== {n} len={L}')
    for p in s['parts']:
        for c in p['casm'] or [None]:
            src = c['src'] if c else (p['ev'][0][1] & 15 if p['ev'] else -1)
            ev = [e for e in p['ev'] if (e[1] & 15) == src]
            t0 = [e for e in ev if e[0] == 0]
            msb = [e[3] for e in t0 if (e[1] & 0xF0) == 0xB0 and e[2] == 0]; lsb = [e[3] for e in t0 if (e[1] & 0xF0) == 0xB0 and e[2] == 32]
            pc = [e[2] for e in t0 if (e[1] & 0xF0) == 0xC0]
            cc7 = [e[3] for e in t0 if (e[1] & 0xF0) == 0xB0 and e[2] == 7]
            ons = [e for e in ev if (e[1] & 0xF0) == 0x90 and e[3] > 0]
            offs = [e for e in ev if (e[1] & 0xF0) == 0x80 or ((e[1] & 0xF0) == 0x90 and e[3] == 0)]
            ccs = Counter(e[2] for e in ev if (e[1] & 0xF0) == 0xB0 and e[0] > 0)
            pcs_later = [(e[0], e[2]) for e in ev if (e[1] & 0xF0) == 0xC0 and e[0] > 0]
            # open notes / overlaps / same tick ON+OFF
            open_ = defaultdict(list); noOff = 0; overlap = 0; maxpoly = 0; cnt = 0
            bytick = defaultdict(list)
            for e in ev: bytick[e[0]].append(e)
            sameTick = 0
            for t in sorted(bytick):
                es = bytick[t]
                onN = {e[2] for e in es if (e[1] & 0xF0) == 0x90 and e[3] > 0}; offN = {e[2] for e in es if (e[1] & 0xF0) == 0x80 or ((e[1] & 0xF0) == 0x90 and e[3] == 0)}
                sameTick += len(onN & offN)
                for e in sorted(es, key=lambda e: 0 if ((e[1] & 0xF0) == 0x80 or e[3] == 0) else 1):
                    if (e[1] & 0xF0) == 0x90 and e[3] > 0:
                        if open_[e[2]]: overlap += 1
                        open_[e[2]].append(t); cnt += 1; maxpoly = max(maxpoly, cnt)
                    elif (e[1] & 0xF0) in (0x80, 0x90):
                        if open_[e[2]]: open_[e[2]].pop(0); cnt -= 1
            noOff = sum(len(v) for v in open_.values())
            notes = [e[2] for e in ons]
            cs = (f"CASM src{c['src']}->dst{c['dst']} '{c['voice']}' srcChord={c['root']}/{c['type']} NTR={c['ntr']} NTT={c['ntt']} high={c['high']} lim={c['lo']}-{c['hi']} RTR={c['rtr']} bass={c['bass']} srcRange={c['slo']}-{c['shi']} mask={c['mask']}"
                  if c else "no CASM")
            print(f"  part '{p['name']}' {cs}\n     bank={msb}/{lsb} PC={pc} CC7={cc7} ON={len(ons)} OFF={len(offs)} noteRange={min(notes) if notes else '-'}..{max(notes) if notes else '-'} "
                  f"peakPoly={maxpoly} srcOverlap={overlap} sameTickOnOff={sameTick} noOffInSection={noOff} laterCC={dict(ccs)} laterPC={pcs_later[:4]}")
