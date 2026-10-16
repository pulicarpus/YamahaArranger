#!/usr/bin/env python3
"""Parse LATENCY_* blocks from Inspector reports and compare two runs (A/B).

Usage:
  compare_latency.py parse REPORT.txt [-o OUT.json]
  compare_latency.py compare A.txt|A.json B.txt|B.json

Statistics are exactly what the app printed. Percentiles are bucket upper
bounds (microseconds). "Better" only means smaller mean/max in this run pair;
it is not a statistical test, and a single short session is anecdotal.
"""
import argparse
import json
import re
import sys
from pathlib import Path

KV = re.compile(r'(\w+)=("[^"]*"|\S+)')


def parse_line(line):
    return {k: v.strip('"') for k, v in KV.findall(line)}


def parse_report(text):
    out = {'stats': {}, 'meta': {}}
    for line in text.splitlines():
        if line.startswith('LATENCY_KOTLIN stat=') or line.startswith('LATENCY_NATIVE stat='):
            f = parse_line(line)
            name = f.pop('stat')
            entry = {k: (int(v) if v.isdigit() else v) for k, v in f.items() if k != 'buckets'}
            if 'buckets' in f:
                entry['buckets'] = [int(x) for x in f['buckets'].split(',')]
            out['stats'][name] = entry
        elif line.startswith('LATENCY_'):
            key = line.split()[0]
            f = parse_line(line)
            out['meta'].setdefault(key, {}).update(f)
    return out


def load(path):
    p = Path(path)
    return json.loads(p.read_text()) if p.suffix == '.json' else parse_report(p.read_text())


def fmt_us(v):
    return '-' if v is None else (f'{v/1000:.2f}ms' if v >= 1000 else f'{v}us')


def compare(a, b):
    names = sorted(set(a['stats']) | set(b['stats']))
    rows = [f"{'stat':58} {'A count':>8} {'A mean':>9} {'A max':>9} | {'B count':>8} {'B mean':>9} {'B max':>9} | mean change"]
    for n in names:
        sa, sb = a['stats'].get(n, {}), b['stats'].get(n, {})
        ma, mb = sa.get('meanUs'), sb.get('meanUs')
        change = '' if not ma or mb is None else f'{(mb - ma) / ma * 100:+.0f}%'
        rows.append(f"{n:58} {sa.get('count', 0):>8} {fmt_us(ma):>9} {fmt_us(sa.get('maxUs')):>9} | "
                    f"{sb.get('count', 0):>8} {fmt_us(mb):>9} {fmt_us(sb.get('maxUs')):>9} | {change}")
    for key in ('LATENCY_NATIVE', 'LATENCY_OUTPUT_LIVE', 'LATENCY_TUNING'):
        ma, mb = a['meta'].get(key, {}), b['meta'].get(key, {})
        if ma or mb:
            rows.append(f'{key}: A={ma}')
            rows.append(f'{key}: B={mb}')
    return '\n'.join(rows)


def main():
    p = argparse.ArgumentParser()
    sub = p.add_subparsers(dest='cmd', required=True)
    s = sub.add_parser('parse'); s.add_argument('report'); s.add_argument('-o')
    c = sub.add_parser('compare'); c.add_argument('a'); c.add_argument('b')
    args = p.parse_args()
    if args.cmd == 'parse':
        text = json.dumps(parse_report(Path(args.report).read_text()), indent=1, sort_keys=True)
        if args.o: Path(args.o).write_text(text + '\n')
        else: print(text)
    else:
        print(compare(load(args.a), load(args.b)))


if __name__ == '__main__':
    sys.exit(main())
