#!/usr/bin/env python3
"""Proves what BassMidiPlayer::normalizeMelodySf2 changes: compares the original SF2 with its '<path>.bassmidi-normalized.sf2' copy chunk by chunk."""
import hashlib, struct, sys
from pathlib import Path


def chunks(path):
    b = Path(path).read_bytes()
    assert b[:4] == b'RIFF' and b[8:12] == b'sfbk'
    out, pos = {}, 12
    while pos + 8 <= len(b):
        tag, size = b[pos:pos + 4].decode('latin1'), struct.unpack('<I', b[pos + 4:pos + 8])[0]
        body = b[pos + 8:pos + 8 + size]
        if tag == 'LIST':
            kind, sub, p = body[:4].decode('latin1'), body[4:], 0
            while p + 8 <= len(sub):
                t2, s2 = sub[p:p + 4].decode('latin1'), struct.unpack('<I', sub[p + 4:p + 8])[0]
                out[f'{kind}/{t2}'] = sub[p + 8:p + 8 + s2]
                p += 8 + s2 + (s2 & 1)
        pos += 8 + size + (size & 1)
    return out


def main(src):
    a, b = chunks(src), chunks(src + '.bassmidi-normalized.sf2')
    ok = True
    for k in sorted(set(a) | set(b)):
        same = k in a and k in b and a[k] == b[k]
        h = lambda x: hashlib.sha256(x).hexdigest()[:12] if x is not None else 'absent'
        print(f"SF2CMP {Path(src).name} {k:12s} bytes={len(a.get(k, b'')):>10d} identical={same} sha={h(a.get(k))}/{h(b.get(k))}")
        if not same and k != 'pdta/phdr': ok = False
    if 'pdta/phdr' in a and 'pdta/phdr' in b and len(a['pdta/phdr']) == len(b['pdta/phdr']):
        pa, pb = a['pdta/phdr'], b['pdta/phdr']
        diff_fields = {i % 38 for i in range(len(pa)) if pa[i] != pb[i]}
        print(f"SF2CMP phdr differing byte offsets within a 38-byte record: {sorted(diff_fields)} (22,23 = wBank)")
        ok = ok and diff_fields <= {22, 23}
    print(f"SF2CMP VERDICT {Path(src).name}: " + ('ONLY the phdr bank fields differ; all samples, generators, modulators, instruments are byte-identical' if ok else 'UNEXPECTED DIFFERENCES'))
    return 0 if ok else 1


if __name__ == '__main__':
    sys.exit(max(main(p) for p in sys.argv[1:]))
