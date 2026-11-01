#!/usr/bin/env python3
"""Extract the PSR-E343 Voice List and Drum Kit List from the official Yamaha Owner's Manual PDF (psre343_en_om_b0.pdf).
Usage: python3 -I extract_e343_lists.py <owner_manual.pdf> <out_dir>
Needs: pdftotext (poppler) and pdfplumber. Writes E343_VOICES.tsv and E343_DRUMKITS.tsv. Exits non-zero on any consistency failure."""
import re, subprocess, sys
from pathlib import Path
import pdfplumber

pdf_path, out_dir = Path(sys.argv[1]), Path(sys.argv[2])
NOTE_NAMES = ['C', 'C#', 'D', 'D#', 'E', 'F', 'F#', 'G', 'G#', 'A', 'A#', 'B']
def nn(n, octave_base):  # Yamaha convention: MIDI note 60 = C3 (octave_base -2); the list's keyboard column is MIDI+12
    return f"{NOTE_NAMES[n % 12]}{n // 12 + octave_base}"

# ---------------- Voice List (printed pages 48-52 = PDF pages 48..52 1-based) ----------------
F = r'(?:\d{1,3}|–)'
ENTRY = re.compile(r'(?<!\S)(\d{1,3})\s+(' + F + r')\s+(' + F + r')\s+(' + F + r')\s+(\S+(?: \S+)*)')  # names never contain two spaces
items = []  # (page, col, line, kind, payload)
for page in range(48, 53):
    txt = subprocess.check_output(['pdftotext', '-layout', '-f', str(page), '-l', str(page), str(pdf_path), '-'], text=True)
    lines = txt.splitlines()
    hdr = next(l for l in lines if l.count('Bank Select') == 2)
    boundary = hdr.index('Bank Select', hdr.index('Bank Select') + 1) - 20
    started = False
    for li, line in enumerate(lines):
        if '(0–127) (0–127) (1–128)' in line.replace('  ', ' ') or re.search(r'\(0–127\)\s+\(0–127\)\s+\(1–128\)', line):
            started = True; continue
        if not started or 'Owner’s Manual' in line: continue
        rest = line
        for m in ENTRY.finditer(line):
            col = 1 if m.start() >= boundary else 0
            items.append((page, col, li, 'V', tuple(m.groups())))
            rest = rest.replace(m.group(0), ' ' * len(m.group(0)), 1)
        for m in re.finditer(r'\S+(?: \S+)*', rest):
            t = m.group(0).strip()
            if t and not t.isdigit():
                items.append((page, 1 if m.start() >= boundary else 0, li, 'C', t))
items.sort(key=lambda x: (x[0], x[1], x[2]))
voices, cat, listname = [], None, 'PANEL'
for page, col, li, kind, p in items:
    if kind == 'C':
        cat = p; continue
    no, name = int(p[0]), p[4].strip()
    dual = p[1] == '–'
    assert dual == (p[2] == '–') == (p[3] == '–') == (cat == 'DUAL*'), (p, cat)
    msb, lsb, pc = (None, None, None) if dual else (int(p[1]), int(p[2]), int(p[3]))
    listname = 'PANEL' if no <= 205 else 'XGLITE'
    voices.append(dict(no=no, msb=msb, lsb=lsb, pc=pc, name=name, list=listname, cat=cat.rstrip('*'), page=page,
                       kind='DRUMKIT' if cat == 'DRUM KITS' else 'DUAL' if dual else 'VOICE'))
voices.sort(key=lambda v: v['no'])
nums = [v['no'] for v in voices]
assert nums == list(range(1, 551)), f'voice numbers not 1..550: {len(nums)} found, missing {sorted(set(range(1,551)) - set(nums))[:10]}'
for v in voices:
    if v['kind'] == 'DUAL': continue
    assert 0 <= v['msb'] <= 127 and 0 <= v['lsb'] <= 127 and 1 <= v['pc'] <= 128, v
    assert not re.search(r'[\t;,]', v['name']), ('separator in name', v)  # the app splits on tab ; ,
assert sum(v['kind'] == 'DRUMKIT' for v in voices) == 18

# ---------------- Drum Kit List (PDF pages 53, 54, 55 1-based) ----------------
pdf = pdfplumber.open(str(pdf_path))
def rows(page):
    out = []
    for w in sorted(page.extract_words(x_tolerance=1.5, y_tolerance=2), key=lambda w: (w['top'], w['x0'])):
        if w['x0'] < 70 and re.fullmatch(r'[A-G]#?\d', w['text']): continue  # keyboard-illustration labels (C1, C#1 ...) left of the table
        r = next((r for r in out if abs(r[0] - w['top']) < 2.0), None)
        if r is None: out.append([w['top'], [w]])
        else: r[1].append(w)
    return sorted(out, key=lambda r: r[0])
def phrases(ws, gap=4.0):
    out = []
    for w in sorted(ws, key=lambda w: w['x0']):
        if out and w['x0'] - out[-1]['x1'] < gap: out[-1]['text'] += ' ' + w['text']; out[-1]['x1'] = w['x1']
        else: out.append(dict(text=w['text'], x0=w['x0'], x1=w['x1']))
    return out
CODE = re.compile(r'^(\d{3})/(\d{3})/(\d{3,4})$')
kits = {}   # voice_no -> dict(name, msb, lsb, pc, page, notes{midi: sound})
spans = []
row_note = {}  # row top on the spread pages -> (keyboard note, midi note)
for pi in (52, 53, 54):   # 0-based PDF page index
    pg = pdf.pages[pi]; rr = rows(pg)
    nos_row = next(r for r in rr if sum(re.fullmatch(r'\d{3}', w['text']) is not None for w in r[1]) >= 6)
    code_row = next(r for r in rr if sum(CODE.match(w['text']) is not None for w in r[1]) >= 6)
    nos = [int(w['text']) for w in sorted(nos_row[1], key=lambda w: w['x0']) if re.fullmatch(r'\d{3}', w['text'])]
    codes = [CODE.match(w['text']).groups() for w in sorted(code_row[1], key=lambda w: w['x0']) if CODE.match(w['text'])]
    assert len(nos) == len(codes), (pi, nos, codes)
    has_note_cols = pi in (52, 53)
    name_x = 160.0 if has_note_cols else 0.0
    # column anchors: left edges of the name columns, from where the sound names actually start on this page
    from collections import Counter
    starts = Counter()
    body = [r for r in rr if r[0] > code_row[0] + 12 and r[0] < 700]
    for top, ws in body:
        for ph in phrases([w for w in ws if w['x0'] >= name_x]): starts[round(ph['x0'])] += 1
    anchors = sorted(x for x, c in starts.items() if c >= 2)
    merged = []
    for x in anchors:
        if not merged or x - merged[-1] > 6: merged.append(x)
    hdr_x = [w['x0'] for w in sorted(code_row[1], key=lambda w: w['x0']) if CODE.match(w['text'])]
    # pair each kit with the nearest anchor at or left of its header (+/- 15 pt)
    col_x = []
    for hx in hdr_x:
        cands = [a for a in merged if a <= hx + 15]
        col_x.append(max(cands) if cands else hx)
    assert len(set(col_x)) == len(col_x), (pi, merged, hdr_x, col_x)
    # kit names: row(s) between code row and first note row
    for k, (no, code) in enumerate(zip(nos, codes)):
        if no not in kits:
            kits[no] = dict(msb=int(code[0]), lsb=int(code[1]), pc=int(code[2]), page=pi + 1, notes={}, name_parts=[])
    hdr_rows = [r for r in rr if code_row[0] < r[0] < code_row[0] + 14]
    for top, ws in hdr_rows:
        for ph in phrases([w for w in ws if w['x0'] >= name_x - 10 and w['text'] not in ('Note#', 'Note', 'MIDI', 'Keyboard')]):
            k = max((i for i, x in enumerate(col_x) if x <= ph['x0'] + 15), default=None)
            if k is not None and kits[nos[k]]['page'] == pi + 1: kits[nos[k]]['name_parts'].append(ph['text'])
    for top, ws in body:
        if has_note_cols:
            left = sorted([w for w in ws if 75 <= w['x0'] < 160], key=lambda w: w['x0'])
            kbw = [w for w in left if 75 <= w['x0'] < 95 and re.fullmatch(r'\d{1,3}', w['text'])]
            mw = [w for w in left if 120 <= w['x0'] < 140 and re.fullmatch(r'\d{1,3}', w['text'])]
            if not kbw and not mw: continue
            assert len(kbw) == 1 and len(mw) == 1, (pi, top, [w['text'] for w in left])
            kb, midi = int(kbw[0]['text']), int(mw[0]['text'])
            assert kb == midi + 12, (pi, top, kb, midi)
            key = round(top)
            if pi == 53: row_note[key] = (kb, midi)
        else:
            key = next((k for k in row_note if abs(k - top) <= 1.5), None)
            if key is None:   # a label printed between two note rows (a merged cell spanning several keys): keep it, but with no note claim
                above = max((k for k in row_note if k < top), default=None); below = min((k for k in row_note if k > top), default=None)
                for ph in phrases(ws):
                    k = max((i for i, x in enumerate(col_x) if x <= ph['x0'] + 2), default=None)
                    spans.append((nos[k], ph['text'], row_note[above][1] if above else None, row_note[below][1] if below else None))
                continue
            kb, midi = row_note[key]
        for ph in phrases([w for w in ws if w['x0'] >= name_x]):
            k = max((i for i, x in enumerate(col_x) if x <= ph['x0'] + 2), default=None)
            assert k is not None, (pi, top, ph)
            d = kits[nos[k]]['notes']
            assert midi not in d or d[midi] == ph['text'], (nos[k], midi, d.get(midi), ph['text'])
            d[midi] = ph['text']
assert all(sp[0] == 205 for sp in spans), spans   # only the Sound Effect Kit has merged cells
std = kits[188]['notes']
assert std[36] == 'Bass Drum' and std[38] == 'Snare M' and std[42] == 'Hi-Hat Closed', std
# check kit headers against the Panel Voice List
for no, k in kits.items():
    v = voices[no - 1]
    assert (v['msb'], v['lsb'], v['pc']) == (k['msb'], k['lsb'], k['pc']) or (no == 200 and k['pc'] == 115), (no, v, k)
    k['name'] = v['name']; k['header_name'] = ' '.join(k['name_parts'])
assert sorted(kits) == list(range(188, 206)), sorted(kits)

out_dir.mkdir(parents=True, exist_ok=True)
src = f'{pdf_path.name}'
with open(out_dir / 'E343_VOICES.tsv', 'w', encoding='utf-8') as f:
    f.write('# PSR-E343 Voice List (Panel 1-205 incl. 18 drum kits, XGlite 206-550), extracted from the official Yamaha Owner\'s Manual ' + src + ', pages 48-52.\n')
    f.write('# Program is 1-based exactly as printed (send PC = program - 1). Columns after the 4th are informational; the app reads name, MSB, LSB, program.\n')
    f.write('#name\tmsb\tlsb\tprogram_1_128\tvoice_no\tlist\tcategory\tkind\tsource_page\n')
    f.write('# DUAL voices (153-179) have no Bank/Program in the manual ("–"; they layer two voices) and cannot be selected over MIDI: listed as comments only.\n')
    for v in voices:
        if v['kind'] == 'DUAL':
            f.write(f"#DUAL\t{v['name']}\t–\t–\t–\t{v['no']}\t{v['list']}\t{v['cat']}\t{v['kind']}\t{v['page']}\n"); continue
        f.write(f"{v['name']}\t{v['msb']}\t{v['lsb']}\t{v['pc']}\t{v['no']}\t{v['list']}\t{v['cat']}\t{v['kind']}\t{v['page']}\n")
with open(out_dir / 'E343_DRUMKITS.tsv', 'w', encoding='utf-8') as f:
    f.write('# PSR-E343 Drum Kit List, extracted from the official Yamaha Owner\'s Manual ' + src + ', pages 53-55.\n')
    f.write('# midi_note = the note number on MIDI (what the app sends); keyboard_note = MIDI + 12 (the E343 panel keyboard, per the manual footnote). Note names use Yamaha C3 = 60.\n')
    f.write('# same_as_std1 = blank cell in the manual ("same as Standard Kit 1"). Notes not listed for a kit have no sound in that kit.\n')
    f.write('# same_as_std1=footnote_only (kits 199-205, page 55): the cell is blank and the footnote on page 53 says blank = same as Standard Kit 1, but page 55 does not show Standard Kit 1 beside these kits; treat as UNVERIFIED until heard on the instrument.\n')
    f.write('# SFX Kit 1/2 (203, 204): the manual says their actual KEYBOARD notes are one octave lower than listed; midi_note is given as listed.\n')
    f.write('#voice_no\tkit\tmsb\tlsb\tprogram_1_128\tmidi_note\tmidi_note_name\tkeyboard_note\tkeyboard_note_name\tsound\tsame_as_std1\tsource_page\n')
    for no, label, a, b in spans:
        f.write(f"# MERGED CELL in {no} Sound Effect Kit: '{label}' is printed across the rows between MIDI notes {a} and {b}; the manual does not state the exact keys, so no note row is claimed for it.\n")
    all_midi = sorted({m for k in kits.values() for m in k['notes']} | set(std))
    for no in sorted(kits):
        k = kits[no]
        is_std_family = no <= 198   # pages 53-54: printed next to Standard Kit 1, blank = "same as Standard Kit 1"
        for m in range(13, 92):
            if m in k['notes']: s, same = k['notes'][m], 'no'
            elif m in std: s, same = std[m], ('yes' if is_std_family else 'footnote_only')
            else: continue
            if no == 188: same = 'n/a'
            f.write(f"{no}\t{k['name']}\t{k['msb']}\t{k['lsb']}\t{k['pc']}\t{m}\t{nn(m, -2)}\t{m + 12}\t{nn(m + 12, -2)}\t{s}\t{same}\t{k['page']}\n")
print('spans', spans); print('voices', len(voices), 'kits', len(kits), {no: len(k['notes']) for no, k in kits.items()})
for no, k in kits.items(): print(no, k['name'], '| header:', k['header_name'])
