import sys,os,json,hashlib,collections
sys.path.insert(0,os.path.dirname(os.path.abspath(__file__)))
from sf2parse import SF2
X,TSV,OUT=sys.argv[1:4]
meta=collections.defaultdict(list)
for l in open(TSV,encoding='utf-8'):
    f,i,b,p,n=l.rstrip('\n').split('\t',4); meta[f].append((int(i),int(b),int(p),n))
res={}
for fn in sorted(os.listdir(X)):
    if not fn.endswith('.sf2'): continue
    s=SF2(os.path.join(X,fn))
    real=[(i,r[2],r[1],r[0]) for i,r in enumerate(s.phdr[:-1])]
    m=sorted(meta[fn]); mism=[(a,b) for a,b in zip(real,m) if a!=b]
    # sample data hashes
    h={}
    for si,sh in enumerate(s.shdr[:-1]):
        a=s.smpl_off+2*sh['start']; b=s.smpl_off+2*sh['end']
        h[si]=hashlib.sha1(s.mm[a:b]).hexdigest()
    byname=collections.defaultdict(set); bydata=collections.defaultdict(set)
    for si,sh in enumerate(s.shdr[:-1]): byname[sh['name']].add(h[si]); bydata[h[si]].add(sh['name'])
    samename_diffdata=sum(1 for n,v in byname.items() if len(v)>1)
    satri=[n for n in byname if n.startswith('SATRIOMUSIK')]
    satri_conflict=sum(1 for n in satri if len(byname[n])>1)
    dup_data=sum(1 for d,v in bydata.items() if len(v)>1)
    res[fn]=dict(presets_match=len(mism)==0 and len(real)==len(m),preset_mismatches=mism[:5],
        sample_names=len(byname),names_with_multiple_distinct_data=samename_diffdata,
        satriomusik_names=len(satri),satriomusik_names_with_multiple_data=satri_conflict,
        identical_data_under_different_names=dup_data)
    print(fn,json.dumps(res[fn]))
    json.dump({str(k):v for k,v in h.items()},open(os.path.join(OUT,'samplehash_'+fn.split()[0].split('.')[0]+'.json'),'w'))
json.dump(res,open(os.path.join(OUT,'crosscheck.json'),'w'),indent=1)
