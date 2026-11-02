#!/usr/bin/env python3
"""Independent SMF writer + reader for the MIDI SONG tests (no shared code with the Kotlin parser).
Writes tests/fixtures/midi/*.mid and tests/fixtures/midi/manifest.tsv (facts computed by THIS reader from the written bytes).
Usage: python3 tools/midi_fixture_gen.py [--check]   (--check: regenerate in memory and compare with the committed files)"""
import struct, sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'tests/fixtures/midi'

# ---------------- writer ----------------
def vlq(v):
    out = [v & 0x7F]; v >>= 7
    while v: out.append((v & 0x7F) | 0x80); v >>= 7
    return bytes(reversed(out))

def track(events, running=False):
    """events: list of (abs_tick, bytes). Channel messages may use running status when running=True."""
    data = bytearray(); last = 0; rs = None
    for tick, ev in sorted(events, key=lambda e: e[0]):
        data += vlq(tick - last); last = tick
        st = ev[0]
        if running and 0x80 <= st <= 0xEF and st == rs: data += ev[1:]
        else: data += ev; rs = st if 0x80 <= st <= 0xEF else None
    data += vlq(0) + b'\xff\x2f\x00'
    return b'MTrk' + struct.pack('>I', len(data)) + bytes(data)

def header(fmt, ntrks, ppq): return b'MThd' + struct.pack('>IHHH', 6, fmt, ntrks, ppq)
def tempo(t, bpm): us = round(60_000_000 / bpm); return (t, b'\xff\x51\x03' + us.to_bytes(3, 'big'))
def timesig(t, n, d): return (t, bytes([0xff, 0x58, 4, n, {1: 0, 2: 1, 4: 2, 8: 3, 16: 4}[d], 24, 8]))
def name(t, s): b = s.encode(); return (t, b'\xff\x03' + vlq(len(b)) + b)
def on(t, ch, n, v): return (t, bytes([0x90 | ch, n, v]))
def off(t, ch, n): return (t, bytes([0x80 | ch, n, 0]))
def on0(t, ch, n): return (t, bytes([0x90 | ch, n, 0]))      # NOTE OFF written as NOTE ON velocity 0
def cc(t, ch, c, v): return (t, bytes([0xB0 | ch, c, v]))
def pc(t, ch, p): return (t, bytes([0xC0 | ch, p]))

def fixtures():
    f = {}
    # 1. Type 0, PPQ 480, 120 BPM, 4/4, running status, NOTE ON vel 0 as NOTE OFF
    ev = [tempo(0, 120), timesig(0, 4, 4), name(0, 'Type0 test'), pc(0, 0, 0), cc(0, 0, 7, 100)]
    for i, n in enumerate([60, 62, 64, 65, 67, 69, 71, 72]): ev += [on(i * 480, 0, n, 90), on0(i * 480 + 400, 0, n)]
    f['type0_basic'] = header(0, 1, 480) + track(ev, running=True)
    # 2. Type 1, PPQ 960, conductor + 16 channel tracks, tempo change, time-signature change, sustain, bank/program,
    #    drums on channel 10, pitch bend, channel + poly pressure, SysEx (GM System On)
    cond = [tempo(0, 100), timesig(0, 4, 4), name(0, 'Conductor'), tempo(2 * 4 * 960, 140), timesig(4 * 4 * 960, 3, 4)]
    trks = [track(cond)]
    for ch in range(16):
        ev = [name(0, 'Drums' if ch == 9 else f'Part {ch + 1}'), cc(0, ch, 0, 0 if ch != 9 else 127), cc(0, ch, 32, 0), pc(0, ch, ch * 7 % 128), cc(0, ch, 7, 100)]
        base = 36 if ch == 9 else 48 + ch
        for bar in range(6):
            t = bar * 4 * 960 if bar < 4 else 4 * 4 * 960 + (bar - 4) * 3 * 960
            ev += [on(t, ch, base, 64 + ch), off(t + 900, ch, base)]
        if ch == 0: ev += [cc(960, 0, 64, 127), cc(3 * 960, 0, 64, 0)]
        if ch == 1: ev += [(1000, bytes([0xE1, 0x00, 0x50])), (2000, bytes([0xE1, 0x00, 0x40]))]
        if ch == 2: ev += [(1200, bytes([0xD2, 0x40]))]
        if ch == 3: ev += [(1300, bytes([0xA3, base, 0x30]))]
        if ch == 4: ev += [(10, b'\xf0\x05\x7e\x7f\x09\x01\xf7')]
        trks.append(track(ev, running=(ch % 2 == 0)))
    f['type1_16ch'] = header(1, 17, 960) + b''.join(trks)
    # 3. damaged files
    good = f['type1_16ch']
    f['bad_truncated'] = good[: len(good) // 2]
    f['bad_type2'] = header(2, 1, 480) + track([on(0, 0, 60, 90), off(480, 0, 60)])
    bad_vlq = bytearray(header(0, 1, 480) + track([on(0, 0, 60, 90), off(480, 0, 60)]))
    # replace the first delta-time (0x00 after the MTrk header) with a 5-byte VLQ
    tr = bad_vlq.index(b'MTrk') + 8
    f['bad_vlq'] = bytes(bad_vlq[:tr]) + b'\x81\x81\x81\x81\x01' + bytes(bad_vlq[tr + 1:])
    f['bad_no_status'] = header(0, 1, 480) + b'MTrk' + struct.pack('>I', 7) + b'\x00\x3c\x40\x00\xff\x2f\x00'
    f['bad_not_midi'] = b'RIFF\x00\x00\x00\x00WAVEfmt '
    return f

# ---------------- independent reader ----------------
def read(b):
    if b[:4] != b'MThd': raise ValueError('no MThd')
    hlen, fmt, ntrks, ppq = struct.unpack('>IHHH', b[4:14])
    if fmt == 2: raise ValueError('type 2')
    pos = 8 + hlen; tracks = []
    while pos < len(b) and len(tracks) < ntrks:
        cid = b[pos:pos + 4]; clen = struct.unpack('>I', b[pos + 4:pos + 8])[0]
        if pos + 8 + clen > len(b): raise ValueError('truncated')
        if cid == b'MTrk': tracks.append(b[pos + 8:pos + 8 + clen])
        pos += 8 + clen
    if len(tracks) < ntrks: raise ValueError('truncated')
    events = []
    for ti, d in enumerate(tracks):
        p = 0; t = 0; rs = None
        while p < len(d):
            v = 0
            for k in range(5):
                if k == 4: raise ValueError('vlq')
                c = d[p]; p += 1; v = (v << 7) | (c & 0x7F)
                if not c & 0x80: break
            t += v
            st = d[p]
            if st < 0x80:
                if rs is None: raise ValueError('no status')
                st = rs
            else: p += 1
            if 0x80 <= st <= 0xEF:
                rs = st; n = 1 if st & 0xF0 in (0xC0, 0xD0) else 2
                events.append((t, ti, 'ch', st, d[p:p + n])); p += n
            elif st == 0xFF:
                rs = None; typ = d[p]; p += 1; L = 0
                while True:
                    c = d[p]; p += 1; L = (L << 7) | (c & 0x7F)
                    if not c & 0x80: break
                events.append((t, ti, 'meta', typ, d[p:p + L])); p += L
                if typ == 0x2F: break
            else:
                rs = None; L = 0
                while True:
                    c = d[p]; p += 1; L = (L << 7) | (c & 0x7F)
                    if not c & 0x80: break
                events.append((t, ti, 'sysex', st, d[p:p + L])); p += L
    tempos = sorted({e[0]: int.from_bytes(e[4][:3], 'big') for e in events if e[2] == 'meta' and e[3] == 0x51}.items())
    if not tempos or tempos[0][0] != 0: tempos = [(0, 500000)] + tempos
    last = max(e[0] for e in events)
    us = 0; prev_t, prev_v = tempos[0]
    for t, v in tempos[1:]:
        if t > last: break
        us += (t - prev_t) * prev_v // ppq; prev_t, prev_v = t, v
    us += (last - prev_t) * prev_v // ppq
    ch = [e for e in events if e[2] == 'ch']
    noteons = [0] * 16
    for e in ch:
        if e[3] & 0xF0 == 0x90 and e[4][1] > 0: noteons[e[3] & 15] += 1
    sigs = [f"{e[0]}:{e[4][0]}/{2 ** e[4][1]}" for e in events if e[2] == 'meta' and e[3] == 0x58]
    return {'format': fmt, 'ppq': ppq, 'tracks': len(tracks), 'channelEvents': len(ch), 'sysex': sum(1 for e in events if e[2] == 'sysex'),
            'noteOns': ','.join(map(str, noteons)), 'tempos': ','.join(f'{t}:{v}' for t, v in tempos), 'timeSigs': ','.join(sigs),
            'lastTick': last, 'durationUs': us}

def main():
    check = '--check' in sys.argv
    OUT.mkdir(parents=True, exist_ok=True)
    rows = []
    for nm, data in fixtures().items():
        path = OUT / f'{nm}.mid'
        if check: assert path.read_bytes() == data, f'{path} differs from the generator'
        else: path.write_bytes(data)
        try:
            facts = read(data)
            for k, v in facts.items(): rows.append(f'{nm}\t{k}\t{v}')
        except Exception as e:
            rows.append(f'{nm}\terror\t{e}')
    manifest = '\n'.join(rows) + '\n'
    if check: assert (OUT / 'manifest.tsv').read_text() == manifest, 'manifest differs'
    else: (OUT / 'manifest.tsv').write_text(manifest)
    print('MIDI FIXTURES ' + ('CHECK OK' if check else 'WRITTEN') + f': {len(fixtures())} files')

if __name__ == '__main__':
    main()
