#!/usr/bin/env python3
"""Per-note drum audit of a Style dump against the official PSR-E343 Drum Kit List (docs/yamaha/E343_DRUMKITS.tsv).
For each Rhythm destination (0-based 8/9) it uses the EFFECTIVE tick-0 kit request (the last Bank/PC of the part, which is what the app sends),
and reports per note: sections, NOTE ON count, velocity range, authored gate length (ticks NOTE ON -> NOTE OFF), the E343 sound in that kit.
Usage: python3 -I e343_drum_notes.py <E343_DRUMKITS.tsv> <E343_VOICES.tsv> <style_dump.tsv> <style_name>"""
import sys, collections
dk, vo, dump, name = sys.argv[1:5]
kits = {}; sounds = collections.defaultdict(dict)
for l in open(vo, encoding='utf-8'):
    if l.startswith('#') or not l.strip(): continue
    f = l.rstrip('\n').split('\t')
    if f[7] == 'DRUMKIT': kits[(int(f[1]), int(f[2]), int(f[3]) - 1)] = (int(f[4]), f[0])
for l in open(dk, encoding='utf-8'):
    if l.startswith('#') or not l.strip(): continue
    f = l.rstrip('\n').split('\t')
    sounds[int(f[0])][int(f[5])] = (f[9], f[10], f[8])
sec = None; parts = []
for raw in open(dump, encoding='latin-1'):
    f = raw.rstrip('\n').split('\t')
    if f[0] == 'S': sec = f[1]
    elif f[0] == 'P': parts.append(dict(sec=sec, pol=[], ev=[]))
    elif f[0] == 'C': parts[-1]['pol'].append((int(f[1]), int(f[2]), f[3].strip()))
    elif f[0] == 'E':
        st = int(f[2])
        if 0x80 <= st <= 0xEF: parts[-1]['ev'].append((int(f[1]), st, int(f[3]), int(f[4])))
stat = collections.OrderedDict()   # (dst, kitkey, note) -> dict
for p in parts:
    for src, dst, vname in p['pol']:
        if dst not in (8, 9): continue
        bank = [0, 0]; kit = None; opened = {}
        evs = [e for e in p['ev'] if e[1] & 15 == src]
        for t, st, d1, d2 in evs:
            k = st & 0xF0
            if t == 0 and k == 0xB0 and d1 == 0: bank[0] = d2
            elif t == 0 and k == 0xB0 and d1 == 32: bank[1] = d2
            elif t == 0 and k == 0xC0: kit = (bank[0], bank[1], d1)
        for t, st, d1, d2 in sorted(evs, key=lambda e: (e[0], 0 if (e[1] & 0xF0 == 0x80 or e[3] == 0) else 1)):
            k = st & 0xF0
            if k == 0x90 and d2 > 0:
                s = stat.setdefault((dst, kit, d1), dict(n=0, vel=[], gate=[], secs=collections.Counter(), parts=set()))
                s['n'] += 1; s['vel'].append(d2); s['secs'][p['sec']] += 1; s['parts'].add(vname); opened[d1] = t
            elif k in (0x80, 0x90) and d1 in opened:
                stat[(dst, kit, d1)]['gate'].append(t - opened.pop(d1))
print(f'## {name}: drum notes vs PSR-E343 Drum Kit List')
print()
print('| Rhythm (ch 1-based) | Kit sent (MSB/LSB/list PC) | E343 kit | MIDI note | E343 sound (keyboard note) | NOTE ON | Velocity | Authored gate (ticks) | Sections (count) |')
print('|---|---|---|---|---|---|---|---|---|')
for (dst, kit, note), s in sorted(stat.items(), key=lambda x: (x[0][0], str(x[0][1]), x[0][2])):
    kk = kits.get(kit) if kit else None
    if kk:
        snd = sounds[kk[0]].get(note)
        sound = f"{snd[0]}{' (= Std Kit 1)' if snd[1] == 'yes' else ''} ({snd[2]})" if snd else '**NO SOUND in this kit**'
        kitname = f"#{kk[0]} {kk[1]}"
    else:
        sound = 'kit not in E343 list'; kitname = 'NOT IN LIST' if kit else 'no Bank/PC in part'
    g = s['gate']; gate = f"{min(g)}-{max(g)}" if g else 'no NOTE OFF in part'
    secs = ', '.join(f"{k}({v})" for k, v in s['secs'].items())
    kittxt = f"{kit[0]}/{kit[1]}/{kit[2] + 1}" if kit else '-'
    print(f"| R{dst - 7} ({', '.join(sorted(s['parts']))}) ch{dst + 1} | {kittxt} | {kitname} | {note} | {sound} | {s['n']} | {min(s['vel'])}-{max(s['vel'])} | {gate} | {secs} |")
