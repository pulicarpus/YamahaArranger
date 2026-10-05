#!/usr/bin/env python3
"""Replay real metadata evidence through the production C++ policy; never claim actual PCM/resource readiness."""
from pathlib import Path
import collections,re,zipfile,subprocess,tempfile,json
root=Path(__file__).resolve().parents[1]
# Immutable #769/#770 exported identities, not production selectors.
fingerprints=json.loads((root/'tests/fixtures/percussion_inventory_fingerprints.json').read_text())
pat=re.compile(r"^Z relation=(\S+) bank=(\d+) rawPC=(\d+) displayPC=(\d+) preset='([^']*)' instrument=(\d+):'([^']*)' sample=(\d+):'([^']*)' keys=(-?\d+):(-?\d+) velocities=(\d+):(\d+) eligible=(true|false) originalKey=(\d+) correction=(-?\d+) overrideRoot=(\S+) rate=(\d+) type=(\d+) link=(\d+) frames=(\d+):(\d+) PG=(.*?) IG=(.*?) classification=UNKNOWN$")
def generators(s):
    known='modsKnown=true' in s; empty='mods=none' in s
    return s.split(';')[0]+f',99:{0 if known and empty else 1}'
with tempfile.TemporaryDirectory(prefix='percussion-inventory-') as temp:
    temp=Path(temp);metadata=temp/'metadata.tsv';demands=temp/'demand.tsv';exe=temp/'probe'
    with metadata.open('w') as out,zipfile.ZipFile(root/'app/src/test/resources/global_drum_metadata.zip') as archive:
        for index,name in enumerate(archive.namelist(),1):
            lines=archive.read(name).decode().splitlines();sf2=re.search("file='([^']*)'",lines[0])[1]
            out.write(f'F\t{index}\t{sf2}\t{fingerprints[sf2]}\n')
            for line in lines:
                if not line.startswith('Z '):continue
                m=pat.match(line);assert m,line[:120];v=m.groups()
                if v[13]!='true':continue
                bank=int(v[1]);preset=v[4]
                if bank<126 and not re.search('drum|kit',preset,re.I):continue
                fields=['Z',str(index),v[1],v[2],v[9],v[10],v[11],v[12],v[14],v[15],v[7],v[18],v[19],v[20],v[21],v[17],v[4],v[6],v[8],generators(v[22]),generators(v[23])]
                out.write('\t'.join(fields)+'\n')
    counts=collections.Counter()
    for line in (root/'app/src/test/resources/drum_style_native_replay.tsv').read_text().splitlines():
        p=line.split('\t')
        if p[0]=='C':channel=int(p[2])
        elif p[0]=='E' and channel in (8,9) and int(p[2])&0xf0==0x90 and int(p[4])>0:counts[(channel,int(p[3]))]+=1
    assert sum(counts.values())==1054
    demands.write_text(''.join(f'{ch}\t{k}\t{n}\n' for (ch,k),n in sorted(counts.items())))
    subprocess.run(['g++','-std=c++17','-O2','-I',str(root/'app/src/main/cpp'),str(root/'tests/percussion_inventory_probe.cpp'),'-o',str(exe)],check=True)
    result=subprocess.run([str(exe),str(metadata),str(demands)],check=True,capture_output=True,text=True)
    output=root/'build/percussion-inventory-replay.txt';output.parent.mkdir(exist_ok=True);output.write_text(result.stdout)
    print(result.stdout)
    assert '16|95|LEGACY_ABSTAIN' in result.stdout,'unproved edge must stay UNKNOWN'
    assert '|75|17|COMPATIBLE|' in result.stdout and ('|58|claves 2' in result.stdout or 'Clave(L)1;Clave(R)1' in result.stdout),'cross-key acoustic claves, not bell/808'
    assert '|80|80|COMPATIBLE|' in result.stdout and '|81|36|COMPATIBLE|' in result.stdout,'both triangle articulations'
    assert '|21|47|COMPATIBLE|' in result.stdout and '|44|SC-55_Pedal HiHat' in result.stdout,'audited cross-key pedal'
    assert '|31|48|COMPATIBLE|' in result.stdout and '|40|SC-55 Tight Snare' in result.stdout,'audited snare'
