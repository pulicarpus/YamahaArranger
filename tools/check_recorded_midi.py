#!/usr/bin/env python3
"""MIDI RECORDER V1B: re-read the recorder's own output files with the INDEPENDENT Python SMF reader (tools/midi_fixture_gen.py),
not with the app's Kotlin parser. Checks the structure a standard MIDI player needs and the expected musical values.
Usage: check_recorded_midi.py DIR   (DIR = build/recorder-evidence written by tests/cpv/MidiRecorderTest.kt)"""
import importlib.util, struct, sys
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('mfg', ROOT / 'tools/midi_fixture_gen.py'); mfg = importlib.util.module_from_spec(spec); spec.loader.exec_module(mfg)

def tracks_end_with_eot(b):
    pos = 14; ok = []
    while pos < len(b):
        clen = struct.unpack('>I', b[pos + 4:pos + 8])[0]
        if b[pos:pos + 4] == b'MTrk': ok.append(b[pos + 8 + clen - 3:pos + 8 + clen] == b'\xff\x2f\x00')
        pos += 8 + clen
    return ok

def balanced(b):
    """every NOTE ON has a later NOTE OFF on the same channel / key (LIFO-free count per key)"""
    import collections
    r = mfg.read(b)  # structure + totals
    # walk again for pairing (the reader returns totals only)
    pos = 14; open_ = collections.Counter(); bad = 0
    while pos < len(b):
        clen = struct.unpack('>I', b[pos + 4:pos + 8])[0]; d = b[pos + 8:pos + 8 + clen]; pos += 8 + clen; p = 0
        while p < len(d):
            while d[p] & 0x80: p += 1
            p += 1; st = d[p]; p += 1
            if st == 0xFF:
                L = d[p + 1]; p += 2 + L; continue
            n = 1 if st & 0xF0 in (0xC0, 0xD0) else 2; a = d[p:p + n]; p += n
            if st & 0xF0 == 0x90 and a[1] > 0: open_[(st & 15, a[0])] += 1
            elif st & 0xF0 == 0x80 or (st & 0xF0 == 0x90 and a[1] == 0):
                if open_[(st & 15, a[0])] == 0: bad += 1
                else: open_[(st & 15, a[0])] -= 1
    return r, bad, sum(open_.values())

EXPECT = {'v1b_keyboard_take': {'format': 1, 'tracks': 4, 'ppq': 960, 'tempos': '0:500000', 'timeSigs': '0:4/4', 'lastTick': 3840, 'durationUs': 2000000, 'noteOns': '1,1,0,1,0,0,0,0,0,0,0,0,0,0,0,0'},
          'v1b_stop_mid_note': {'format': 1, 'tracks': 2, 'lastTick': 1920},
          'v1b_tempo_change': {'format': 1, 'tracks': 2, 'tempos': '0:500000,1920:1000000', 'durationUs': 3500000}}

def main():
    d = Path(sys.argv[1]); files = sorted(d.glob('*.mid')); assert files, f'no recorder output in {d}'
    for f in files:
        b = f.read_bytes(); r, orphan, unclosed = balanced(b)
        eot = tracks_end_with_eot(b)
        assert r['format'] == 1 and all(eot) and len(eot) == r['tracks'], (f.name, r, eot)
        assert orphan == 0 and unclosed == 0, (f.name, 'orphan NOTE OFF' if orphan else 'unclosed NOTE ON', orphan, unclosed)
        for k, v in EXPECT.get(f.stem, {}).items(): assert r[k] == v, (f.name, k, r[k], v)
        print(f'RECORDED MIDI OK {f.name}: Type {r["format"]}, {r["tracks"]} tracks, PPQ {r["ppq"]}, tempos {r["tempos"]}, '
              f'{r["durationUs"] / 1e6:.3f} s, NOTE ON per channel {r["noteOns"]}, every track ends with End of Track, notes paired')
    print(f'RECORDED MIDI CHECK PASS files={len(files)} (independent Python reader)')

if __name__ == '__main__':
    main()
