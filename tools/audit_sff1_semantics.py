#!/usr/bin/env python3
"""S3 read-only CASM preservation matrix; musical S1 expectations never updated."""
import argparse,hashlib,json,subprocess,zipfile
from collections import Counter,defaultdict
from pathlib import Path
from audit_sff1_corpus import ROOT,ARCHIVE_SHA,file_sha,require,read_smf,read_casm,chunks,expected_projection,raw_note_evidence,digest,production_identity
REFERENCE=ROOT/'tests/fixtures/sff1_semantic_reference_s3.json'
GOLDEN=['Ballad/8BeatPiano1.T107.pcs','Ballad/8BeatSoft.S686.bcs','Movie&Show/BaroqueAir1.S145.sst','Pop&Rock/Unplugged2.T151.prs']
def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--corpus',type=Path,required=True);p.add_argument('--output',type=Path,default=ROOT/'build/s3-semantics');p.add_argument('--record-reference',action='store_true');a=p.parse_args()
 require(file_sha(a.corpus)==ARCHIVE_SHA,'Original corpus SHA mismatch');production_identity()
 require(not REFERENCE.exists() if a.record_reference else REFERENCE.exists(),'Initial reference creation never overwrites existing expectations')
 out=a.output.resolve();out.mkdir(parents=True,exist_ok=True);exe=out/'sff_casm_semantic_test'
 subprocess.run(['g++','-std=c++17','-O2','-DANDROID_LOG_ERROR=6','-I',str(ROOT/'tests/mocks'),'-I',str(ROOT/'app/src/main/cpp'),str(ROOT/'tests/sff_casm_semantic_test.cpp'),str(ROOT/'app/src/main/cpp/style_parser.cpp'),str(ROOT/'app/src/main/cpp/smf_reader.cpp'),'-o',str(exe)],check=True)
 subprocess.run([str(exe),'--self-test'],check=True)
 values=defaultdict(Counter);counts=Counter();style_proofs={};root_examples={};root_styles=set();missing_sections=set();missing_styles=set();protocol_root=out/'protocols';protocol_root.mkdir(exist_ok=True)
 baseline=json.loads((ROOT/'tests/fixtures/sff1_reference.json').read_text())
 with zipfile.ZipFile(a.corpus) as z:
  names=sorted(n for n in z.namelist() if not n.endswith('/'))
  require(len(names)==503,'Exactly 503 files required')
  for name in names:
   require(name.startswith('sff1/') and '..' not in Path(name).parts,'Unsafe corpus path');relative=name[5:];data=z.read(name)
   require(hashlib.sha256(data).hexdigest()==baseline['styles'][relative]['file_sha256'],'Style bytes differ')
   source=out/'input.style';source.write_bytes(data)
   observed=subprocess.check_output([str(exe),str(source)],text=True);rows=[r.split('\t') for r in observed.splitlines()]
   require(rows[0]==['S3','1','1'],relative+': SFF1 identity failed')
   descriptors=[r for r in rows if r[0]=='D'];bindings=[r for r in rows if r[0]=='B'];parts=[r for r in rows if r[0]=='L'];policies=[r for r in rows if r[0]=='P']
   _,events,offsets,_,smf_end=read_smf(data);groups,chunk_counts=read_casm(data,smf_end);projection=expected_projection(events)
   casm=data.find(b'CASM',smf_end);end=casm+8+int.from_bytes(data[casm+4:casm+8],'big');raw_desc=[]
   for ci,tag,off,payload in chunks(data,casm+8,end):
    for si,sub,offset,raw in chunks(data,off,off+len(payload)):
     raw_desc.append([str(len(raw_desc)),str(ci),sub.decode(),str(offset),raw.hex()])
   require([r[1:] for r in descriptors]==raw_desc,relative+': native raw registry/provenance mismatch')
   descriptor_index={int(r[3]):i for i,r in enumerate(raw_desc)}
   actual_policy={tuple(r[1:4]):r[4] for r in policies};actual_binding={tuple(r[1:4]):tuple(map(int,r[4:])) for r in bindings}
   require(len(actual_binding)==len(bindings)==len(actual_policy)==len(policies),'Duplicate/omitted policy/binding')
   part_sources={(r[1],int(r[2])):int(r[3]) for r in parts}
   expected=defaultdict(list);expected_bindings=defaultdict(list);expected_attached=set();raw_registry_index={int(r[3]):i for i,r in enumerate(raw_desc)}
   for group in groups:
    ci=group['ordinal'];cntt_indices={}
    for index,r in enumerate(raw_desc):
     if int(r[1])==ci and r[2]=='Cntt':cntt_indices[int(r[4][:2],16)]=index
    for record in group['records']:
     index=raw_registry_index[record['offset']];raw=bytes.fromhex(raw_desc[index][4]);mapped=record['mapped'];source,destination=mapped[:2]
     for field,value in [('source',raw[0]),('destination',raw[9]),('flag_byte10',raw[10]),('root_word',int.from_bytes(raw[11:13],'big')),('chord_field_hex',raw[13:18].hex()),('source_root',raw[18]),('source_chord_type',raw[19]),('ntr_raw',raw[20]),('ctab_ntt_byte',raw[21]),('effective_ntt',mapped[6]),('effective_bass_on',mapped[11]),('high_key',raw[22]),('note_low',raw[23]),('note_high',raw[24]),('rtr_raw',raw[25]),('tail_hex',raw[26:].hex())]:values[field][str(value)]+=1
     counts['raw_ctab']+=1;counts['ctab_cntt_pairs']+=1;counts['cntt_changes_ntt']+=(raw[21]&127)!=mapped[6];counts['cntt_changes_bass_from_legacy_ctab_default']+=bool(mapped[11]);counts['ctab_high_bit_set']+=bool(raw[21]&128)
     attached=[]
     for section in group['sections']:
      counts['expanded']+=1
      if source in projection[section]['parts']:
       attached.append(section);expected[section,source].append(mapped);expected_bindings[section,source].append((index,cntt_indices[source]));counts['attached']+=1;expected_attached.add(index)
      else:
       counts['SOURCE_ABSENT']+=1;missing_sections.add((relative,section));missing_styles.add(relative)
     if not attached:counts['PARSED_BUT_UNATTACHED']+=1
     if record['root']!=4095:
      counts['nondefault_root']+=1;root_styles.add(relative)
      if record['root'] not in root_examples and attached:
       root_examples[record['root']]={'style':relative,'section':attached[0],'source':source,'destination':destination,'cseg':ci,'payload_offset':record['offset'],'absolute_root_offset':record['offset']+11,'raw_payload_hex':raw.hex(),'root_word_raw':record['root'],'legacy_parsed_root_selection':None,'s3_preserved_root_selection':record['root'],'legacy_source_chord_root':mapped[3],'legacy_consumed_root_selection':None,'first_divergence_legacy':'StyleParser::parseCasm Ctab extraction -> legacy CasmPolicy has no root-selection field; S3 sidecar preserves it but policyScore remains unchanged'}
   for key,source in part_sources.items():
    section,part=key
    for j,mapped in enumerate(expected[section,source]):
     k=(section,str(part),str(j));line=actual_policy[k];f=line.split('|');require(len(f)==15,'15-field legacy protocol changed')
     converted=[int(x) if i!=2 else x.encode().hex() for i,x in enumerate(f)]
     require(converted==mapped,relative+': old effective policy changed');require(actual_binding[k]==expected_bindings[section,source][j],relative+': projection lost descriptor identity')
   require(len(bindings)==sum(len(x) for x in expected.values()),'Attachment multiplicity differs')
   overlaps,maximum=raw_note_evidence(events,offsets);counts['overlap_on']+=len(overlaps);counts['overlap_styles']+=bool(overlaps)
   origins=defaultdict(set)
   for section,plan in projection.items():
    for source,items in plan['origins'].items():
     for ordinal,kind in items:origins[ordinal].add(section)
   for overlap in overlaps:
    current=origins[overlap['raw_event_ordinal']];previous=set().union(*(origins[o] for o in overlap['prior_on_ordinals']))
    counts['overlap_both_on_instances_projected']+=bool(current and previous)
    counts['overlap_same_section']+=bool(current&previous)
   for k,v in chunk_counts.items():counts[k]+=v
   record={'protocol_sha256':hashlib.sha256(observed.encode()).hexdigest(),'raw_registry_sha256':digest(raw_desc),'legacy_policy_rows_sha256':digest(policies),'bindings_sha256':digest(bindings),'descriptors':len(descriptors),'bindings':len(bindings)};style_proofs[relative]=record
   file=protocol_root/(relative+'.tsv');file.parent.mkdir(parents=True,exist_ok=True);file.write_text(observed)
   resource=ROOT/'app/src/test/resources/sff3'/(Path(relative).name+'.tsv')
   if not a.record_reference and resource.exists():require(resource.read_text()==observed,'Committed semantic fixture differs from verified corpus/native parser')
   if a.record_reference and relative in GOLDEN:
    resource=ROOT/'app/src/test/resources/sff3'/Path(relative).name;resource=resource.with_suffix(resource.suffix+'.tsv');resource.parent.mkdir(parents=True,exist_ok=True);require(not resource.exists(),'Golden fixture overwrite forbidden');resource.write_text(observed)
   counts['styles']+=1
 require(counts['styles']==503 and counts['ctab']==counts['cntt']==32913 and counts.get('ctb2',0)==0 and counts['cseg']==counts['sdec']==3584,'Inventory drift')
 require(counts['expanded']==73229 and counts['attached']==71239 and counts['SOURCE_ABSENT']==1990 and counts['PARSED_BUT_UNATTACHED']==157,'Attachment classification drift')
 require(counts['overlap_on']==523 and counts['overlap_styles']==47 and counts['nondefault_root']==257 and len(root_styles)==49,'Root/ownership inventory drift')
 require(len(missing_sections)==1084 and len(missing_styles)==196,'Absent coverage drift')
 summary={'schema':1,'corpus_sha256':ARCHIVE_SHA,'counts':dict(sorted(counts.items())),'missing_sections':len(missing_sections),'missing_styles':len(missing_styles),'nondefault_root_styles':sorted(root_styles),'field_inventory':{k:dict(sorted(v.items())) for k,v in sorted(values.items())},'root_counterexamples':[root_examples[k] for k in sorted(root_examples)],'styles':style_proofs,'SFF2':'UNVERIFIED_NEEDS_FIXTURE','classification_scope':'SOURCE_ABSENT is no parsed source part; authored intent UNKNOWN. PARSED_BUT_FILTERED is chord/event dependent, no invented corpus eligibility count.'}
 summary['semantic_matrix_sha256']=digest(summary)
 (out/'semantic_observed.json').write_text(json.dumps(summary,indent=2,sort_keys=True)+'\n')
 if a.record_reference:REFERENCE.write_text(json.dumps(summary,indent=2,sort_keys=True)+'\n')
 else:require(summary==json.loads(REFERENCE.read_text()),'S3 semantic/reference drift; no auto-update')
 print('S3_SEMANTIC PASS 503/503 raw registry -> native policy/provenance -> section/source attachments; frozen legacy policies')
 print('SEMANTIC_MATRIX_SHA256 '+summary['semantic_matrix_sha256']);print('COUNTS '+json.dumps(summary['counts'],sort_keys=True));print('GENERATED_PROTOCOL_DIRECTORY '+str(protocol_root))
if __name__=='__main__':main()
