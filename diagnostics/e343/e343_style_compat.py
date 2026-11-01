#!/usr/bin/env python3
"""Classify what a Style asks the PSR-E343 for (Bank MSB/LSB/Program per destination channel, drum notes) against the
E343 lists extracted from the official Owner's Manual (E343_VOICES.tsv, E343_DRUMKITS.tsv).
Input: the production C++ parser's dump (tools/dump_style_shadow_fixture.cpp). Read-only; it changes nothing.
Usage: python3 -I e343_style_compat.py <voices.tsv> <drumkits.tsv> <style_name> <style_dump.tsv>
Classification (same rules as app E343Compat): EXACT = same MSB/LSB/Program in the list; FALLBACK_CANDIDATE = same MSB+Program, other LSB;
UNSUPPORTED = nothing in the list; plus GM_PROGRAM_ONLY info = the list holds this Program under another bank (what a GM-style module may fall back to)."""
import sys, collections
vo_path, dk_path, style_name, dump = sys.argv[1:5]
voices = []
for line in open(vo_path, encoding='utf-8'):
    if line.startswith('#') or not line.strip(): continue
    f = line.rstrip('\n').split('\t')
    voices.append(dict(name=f[0], msb=int(f[1]), lsb=int(f[2]), pc=int(f[3]) - 1, no=int(f[4]), list=f[5], cat=f[6], kind=f[7]))
kitnotes = collections.defaultdict(dict)
for line in open(dk_path, encoding='utf-8'):
    if line.startswith('#') or not line.strip(): continue
    f = line.rstrip('\n').split('\t')
    kitnotes[int(f[0])][int(f[5])] = (f[9], f[10])
def classify(msb, lsb, pc):
    ex = [v for v in voices if (v['msb'], v['lsb'], v['pc']) == (msb, lsb, pc)]
    if ex: return 'EXACT', ex[0]
    fb = [v for v in voices if v['msb'] == msb and v['pc'] == pc]
    if fb: return 'FALLBACK_CANDIDATE', fb
    return 'UNSUPPORTED', None
def same_program(msb, pc):
    return [v for v in voices if v['pc'] == pc and v['kind'] != 'DRUMKIT' and v['msb'] == 0] if msb < 126 else \
           [v for v in voices if v['kind'] == 'DRUMKIT']
# ---- read the dump ----
sections = collections.OrderedDict(); sec = None; part = None
for raw in open(dump, encoding='latin-1'):
    f = raw.rstrip('\n').split('\t')
    if f[0] == 'S': sec = f[1]; sections[sec] = []
    elif f[0] == 'P': part = dict(name=f[1].strip() if len(f) > 1 else '', pol=[], ev=[]); sections[sec].append(part)
    elif f[0] == 'C': part['pol'].append((int(f[1]), int(f[2]), f[3].strip()))
    elif f[0] == 'E':
        st = int(f[2])
        if 0x80 <= st <= 0xEF: part['ev'].append((int(f[1]), st, int(f[3]), int(f[4])))
requests = collections.OrderedDict()   # (dst, msb, lsb, pc) -> dict(sections, names, roles)
drum = collections.defaultdict(collections.Counter)   # effective (dst, msb, lsb, pc) -> note counts
def add(key, sname, name, role):
    r = requests.setdefault(key, dict(sections=[], names=set(), roles=set()))
    if sname not in r['sections']: r['sections'].append(sname)
    if name: r['names'].add(name)
    r['roles'].add(role)
for sname, parts in sections.items():
    for p in parts:
        def dst_of(ch):
            m = [d for s_, d, n in p['pol'] if s_ == ch]
            return (m[0] if m else (p['pol'][0][1] if p['pol'] else ch))
        def vname(ch):
            m = [n for s_, d, n in p['pol'] if s_ == ch]
            return m[0] if m else (p['pol'][0][2] if p['pol'] else '')
        bank = collections.defaultdict(lambda: [0, 0]); tick0 = collections.defaultdict(list); cur = {}
        for t, st, d1, d2 in p['ev']:          # file order (stable), ticks ascending within a part
            ch = st & 15; kind = st & 0xF0
            if kind == 0xB0 and d1 == 0: bank[ch][0] = d2
            elif kind == 0xB0 and d1 == 32: bank[ch][1] = d2
            elif kind == 0xC0:
                key = (dst_of(ch), bank[ch][0], bank[ch][1], d1)
                if t == 0: tick0[ch].append(key)
                else: add(key, sname, vname(ch), 'SENT (mid-pattern Program Change)')
                cur[ch] = key
            elif kind == 0x90 and d2 > 0 and dst_of(ch) in (8, 9) and ch in cur:
                drum[cur[ch]][d1] += 1
        for ch, keys in tick0.items():
            has_casm = any(s_ == ch for s_, d, n in p['pol'])
            for k in keys[:-1]: add(k, sname, vname(ch) if has_casm else '', 'OVERRIDDEN at tick 0 (not sent)')
            add(keys[-1], sname, vname(ch) if has_casm else '', 'SENT (section setup)' if has_casm else
                f'UNPROVEN: this part has no CASM entry for its source ch {ch + 1}; the setup path (applyVoicesFromCasm) only sends parts named by CASM')
print(f'## {style_name}')
print()
print('| Dst ch (0-based / 1-based) | Bank MSB | LSB | PC on the wire (0-based) / list Program (1-based) | Style voice name(s) | Sections | Sent by the app? | E343 status | E343 list entry / nearest |')
print('|---|---|---|---|---|---|---|---|---|')
for (dst, msb, lsb, pc), r in sorted(requests.items()):
    st, hit = classify(msb, lsb, pc)
    if st == 'EXACT': info = f"#{hit['no']} {hit['name']} ({hit['list']}, {hit['kind']})"
    elif st == 'FALLBACK_CANDIDATE': info = 'same MSB+Program, other LSB: ' + '; '.join(f"#{v['no']} {v['name']} {v['msb']}/{v['lsb']}/{v['pc']+1}" for v in hit)
    else:
        alt = same_program(msb, pc)
        info = ('NOT in the list. Same Program under MSB 0: ' + '; '.join(f"#{v['no']} {v['name']} 0/{v['lsb']}/{v['pc']+1}" for v in alt)) if msb < 126 else \
               'NOT in the list. E343 drum kits: ' + ', '.join(f"{v['msb']}/{v['lsb']}/{v['pc']+1} {v['name']}" for v in alt if v['msb'] == msb)
    secs = ', '.join(r['sections']) if len(r['sections']) < 6 else f"{len(r['sections'])} sections"
    print(f"| {dst} / {dst+1} | {msb} | {lsb} | {pc} / {pc+1} | {', '.join(sorted(r['names']))} | {secs} | {'; '.join(sorted(r['roles']))} | **{st}** | {info} |")
print()
for key, notes in sorted(drum.items()):
    dst, msb, lsb, pc = key
    st, hit = classify(msb, lsb, pc)
    kit = hit['no'] if st == 'EXACT' and hit['kind'] == 'DRUMKIT' else None
    print(f"Drum notes on dst ch {dst+1} (1-based) with request {msb}/{lsb}/PC{pc} (list {pc+1}) -> {('kit #%d %s' % (kit, hit['name'])) if kit else 'no E343 kit with this number (' + st + ')'}:")
    if kit:
        ok = [n for n in notes if n in kitnotes[kit]]; miss = [n for n in notes if n not in kitnotes[kit]]
        print('  ' + ', '.join(f"{n}={kitnotes[kit][n][0]}{'' if kitnotes[kit][n][1] in ('no','n/a') else ' (std1)'} x{notes[n]}" for n in sorted(ok)))
        print(f"  notes with NO sound in this kit: {sorted(miss) if miss else 'none'}")
    else:
        std = kitnotes[188]
        print('  as Standard Kit 1 would play them (only IF the E343 fell back to it; not documented): ' + ', '.join(f"{n}={std.get(n, ('(no sound)',''))[0]} x{notes[n]}" for n in sorted(notes)))
    print()
