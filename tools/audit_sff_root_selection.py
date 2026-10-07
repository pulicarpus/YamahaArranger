#!/usr/bin/env python3
"""S4 evidence only: no root-selection semantics are certified by this tool.

Corpus predictions are compared to a *documentary hypothesis*, never to Yamaha
output. Absence of disagreement is not an execution oracle or certification.
"""
import argparse
import csv
import itertools
import json
import zipfile
from collections import Counter, defaultdict
from pathlib import Path

from audit_sff1_corpus import (ROOT, ARCHIVE_SHA, canonical, digest, file_sha,
    require, read_smf, read_casm, expected_projection, production_identity)

REFERENCE = ROOT / 'tests/fixtures/sff1_root_selection_reference_s4.json'
SOURCES = ROOT / 'tests/fixtures/sff1_root_selection_sources_s4.json'
SAMPLES = ROOT / 'app/src/test/resources/sff4/root_records.tsv'
BASELINE = '2781c0a0d16eaed3019e269f5de21ba5175f470d'


def documentary_vector(raw):
    """Literal per-byte map from Wierzba/Bedesem v2.1 pp16-17.

    A supported document claim, not a certified Yamaha behavioral oracle.
    Bits not covered by that document's map remain outside this experiment.
    """
    require(len(raw) >= 27, 'short Ctab')
    return [bool(raw[12] & bit) for bit in (1, 2, 4, 8, 16, 32, 64, 128)] + [
        bool(raw[11] & bit) for bit in (1, 2, 4, 8)]


def candidate_vector(word, source_root, endian, direction, rotation, polarity,
                     origin, zero_default):
    require(0 <= word <= 65535 and 0 <= source_root <= 11, 'invalid hypothesis input')
    value = word if endian == 'BE' else ((word & 255) << 8) | (word >> 8)
    if zero_default and value == 0:
        return [True] * 12
    return [bool((value >> ((direction * (root - (source_root if origin == 'relative' else 0))
                    + rotation) % 12)) & 1) == bool(polarity) for root in range(12)]


def witness(record):
    return {k: record[k] for k in ('style', 'style_sha256', 'cseg', 'descriptor_index',
        'payload_offset', 'absolute_root_offset', 'raw_word_hex', 'source', 'destination',
        'source_root', 'source_chord_type', 'attached_sections', 'raw_payload_hex')}


def falsify(records):
    # Identical (word,source-root) states have identical predictions. Weighting
    # retains ALL 32913 records and all 12 hypothetical input roots, without
    # confusing duplicated Sdec attachments with independent raw evidence.
    states = {}
    for record in records:
        key = record['raw_word_hex'], record['source_root']
        if key not in states:
            states[key] = {'count': 0, 'record': record}
        states[key]['count'] += 1
    tests = []
    for endian, direction, rotation, polarity, origin, zero in itertools.product(
            ('BE', 'LE'), (1, -1), range(12), (1, 0), ('absolute', 'relative'), (False, True)):
        mismatch = 0
        first = None
        for state in states.values():
            record = state['record']
            raw = bytes.fromhex(record['raw_payload_hex'])
            expected = documentary_vector(raw)
            actual = candidate_vector(int(record['raw_word_hex'], 16), record['source_root'],
                endian, direction, rotation, polarity, origin, zero)
            for root, (a, e) in enumerate(zip(actual, expected)):
                if a != e:
                    mismatch += state['count']
                    if first is None:
                        first = {**witness(record), 'hypothetical_input_root': root,
                            'candidate_allows': a, 'document_claim_allows': e,
                            'counterexample_kind': 'DOCUMENT_DISAGREEMENT_NOT_HARDWARE_OUTPUT'}
        tests.append({'id': f'{endian}/dir{direction:+d}/rot{rotation}/one{polarity}/{origin}/zeroDefault{int(zero)}',
            'parameters': [endian, direction, rotation, polarity, origin, zero],
            'record_root_cases': len(records) * 12, 'document_disagreement_cases': mismatch,
            'first_counterexample': first,
            'document_comparison': 'CONTRADICTED' if mismatch else 'SUPPORTED',
            'yamaha_execution_status': 'UNKNOWN', 'certified': False})
    return tests


def relationship_tests(records):
    predicates = {
        'all_records_have_0FFF': lambda r: r['raw_word_hex'] == '0FFF',
        'word_equals_source_chord_root': lambda r: int(r['raw_word_hex'], 16) == r['source_root'],
        'word_equals_one_hot_source_chord_root': lambda r: int(r['raw_word_hex'], 16) == 1 << r['source_root'],
        'word_equals_source_chord_type': lambda r: int(r['raw_word_hex'], 16) == r['source_chord_type'],
        'word_equals_one_hot_source_chord_type': lambda r: int(r['raw_word_hex'], 16) == 1 << r['source_chord_type'],
        'document_mask_always_includes_recorded_source_root': lambda r: documentary_vector(bytes.fromhex(r['raw_payload_hex']))[r['source_root']],
        'word_is_nonzero_whenever_attached': lambda r: not r['attached_sections'] or int(r['raw_word_hex'], 16) != 0,
        'observed_upper_four_bits_zero': lambda r: int(r['raw_word_hex'], 16) & 0xF000 == 0,
    }
    results = []
    for name, predicate in predicates.items():
        failed = [r for r in records if not predicate(r)]
        results.append({'hypothesis': name, 'raw_record_cases': len(records),
            'failed_records': len(failed), 'status': 'CONTRADICTED' if failed else 'PROVEN',
            'first_counterexample': witness(failed[0]) if failed else None,
            'scope': 'literal corpus assertion only; not general Yamaha semantics'})
    seen, collision, collisions = {}, None, 0
    for record in records:
        key = tuple(record[k] for k in ('source_root','source_chord_type','ntr',
            'ctab_ntt_byte','effective_ntt','rtr','high_key','note_low','note_high'))
        prior = seen.setdefault(key, record)
        if prior['raw_word_hex'] != record['raw_word_hex']:
            collisions += 1
            if collision is None:
                collision = {'same_source_fields': list(key), 'first': witness(prior),
                    'second': witness(record)}
    results.append({'hypothesis':'word_is_function_only_of_root_type_NTR_NTT_RTR_highKey_noteRange',
        'raw_record_cases':len(records), 'failed_records':collisions,
        'status':'CONTRADICTED' if collisions else 'SUPPORTED',
        'first_counterexample':collision,
        'scope':'same recorded/transposition/range fields can have distinct root words; no causal/intent claim'})
    return results


def counter_map(counter):
    return {str(k): v for k, v in sorted(counter.items(), key=lambda x: str(x[0]))}


def inventory(records):
    words = defaultdict(list)
    for record in records:
        words[record['raw_word_hex']].append(record)
    rows = []
    fields = ('source', 'destination', 'source_root', 'source_chord_type', 'ntr',
        'ctab_ntt_byte', 'effective_ntt', 'rtr', 'high_key', 'note_low', 'note_high', 'bass_on')
    for word, group in sorted(words.items()):
        row = {'raw_word_hex': word, 'occurrence_count': len(group),
            'style_count': len({r['style'] for r in group}),
            'styles': sorted({r['style'] for r in group}),
            'attachment_count': sum(len(r['attached_sections']) for r in group),
            'expanded_count': sum(len(r['sdec_sections']) for r in group),
            'never_attached_record_count': sum(not r['attached_sections'] for r in group),
            'style_section_count': len({(r['style'], s) for r in group for s in r['attached_sections']}),
            'sdec_section_distribution': counter_map(Counter(s for r in group for s in r['sdec_sections'])),
            'attached_section_distribution': counter_map(Counter(s for r in group for s in r['attached_sections'])),
            'distributions': {f: counter_map(Counter(r[f] for r in group)) for f in fields},
            'source_root_type_joint': counter_map(Counter(f"{r['source_root']}/{r['source_chord_type']}" for r in group)),
            'note_range_joint': counter_map(Counter(f"{r['note_low']}/{r['note_high']}" for r in group)),
            'attached_source_note_pitch_classes': counter_map(sum((Counter(r['source_note_pcs']) for r in group), Counter())),
            'example': witness(group[0]),
            'document_claim_allowed_roots': [i for i, v in enumerate(documentary_vector(bytes.fromhex(group[0]['raw_payload_hex']))) if v],
            'semantic_status': 'SUPPORTED', 'execution_oracle': 'UNKNOWN'}
        rows.append(row)
    return rows


def observe(corpus, protocols):
    require(file_sha(corpus) == ARCHIVE_SHA, 'STOP: original corpus SHA mismatch')
    production_identity()
    s1 = json.loads((ROOT / 'tests/fixtures/sff1_reference.json').read_text())
    s3 = json.loads((ROOT / 'tests/fixtures/sff1_semantic_reference_s3.json').read_text())
    records, style_proofs = [], {}
    samples = {}
    with zipfile.ZipFile(corpus) as archive:
        names = sorted(n for n in archive.namelist() if not n.endswith('/'))
        require(len(names) == 503, 'exactly 503 original styles required')
        for name in names:
            require(name.startswith('sff1/'), 'invalid corpus layout')
            style = name[5:]
            raw_style = archive.read(name)
            import hashlib
            style_sha = hashlib.sha256(raw_style).hexdigest()
            require(style_sha == s1['styles'][style]['file_sha256'], 'style identity mismatch')
            protocol = (protocols / (style + '.tsv')).read_text()
            require(hashlib.sha256(protocol.encode()).hexdigest() == s3['styles'][style]['protocol_sha256'], 'S3 protocol not frozen')
            if style == 'Ballad/PopBallad4.S281.bcs':
                require(protocol == (ROOT/'app/src/test/resources/sff4/PopBallad4.S281.bcs.tsv').read_text(),
                    'Committed S4 native fixture differs from original corpus/S3 protocol')
            rows = [r.split('\t') for r in protocol.splitlines()]
            descriptors = {int(r[4]): r for r in rows if r[0] == 'D' and r[3] == 'Ctab'}
            bindings = defaultdict(list)
            policies = {tuple(r[1:4]): r[4] for r in rows if r[0] == 'P'}
            for r in rows:
                if r[0] == 'B':
                    bindings[int(r[4])].append((r[1], policies[tuple(r[1:4])]))
            _, events, _, _, smf_end = read_smf(raw_style)
            groups, _ = read_casm(raw_style, smf_end)
            projection = expected_projection(events)
            for group in groups:
                for record in group['records']:
                    offset = record['offset']
                    raw = raw_style[offset:offset+27]
                    d = descriptors[offset]
                    require(d[5] == raw.hex() and int(d[2]) == group['ordinal'], 'descriptor provenance mismatch')
                    word = f'{int.from_bytes(raw[11:13], "big"):04X}'
                    attached = [s for s in group['sections'] if raw[0] in projection[s]['parts']]
                    bs = bindings[int(d[1])]
                    require(sorted(s for s, _ in bs) == sorted(attached), 'S3 attachment mismatch')
                    require(all(tuple(line.split('|')[i] for i in (0,1,3,4)) == tuple(str(x) for x in (raw[0],raw[9],raw[18],raw[19])) for _,line in bs), 'effective policy/source fields differ')
                    pcs = Counter()
                    for section in attached:
                        for event in projection[section]['parts'][raw[0]]:
                            if event[1] & 240 == 144 and event[4] > 0:
                                pcs[str(event[3] % 12)] += 1
                    mapped = record['mapped']
                    row = {'style': style, 'style_sha256': style_sha, 'cseg': group['ordinal'],
                        'descriptor_index': int(d[1]), 'payload_offset': offset,
                        'absolute_root_offset': offset+11, 'raw_word_hex': word,
                        'raw_payload_hex': raw.hex(), 'source': raw[0], 'destination': raw[9],
                        'source_root': raw[18], 'source_chord_type': raw[19], 'ntr': raw[20],
                        'ctab_ntt_byte': raw[21], 'effective_ntt': mapped[6], 'rtr': raw[25],
                        'high_key': raw[22], 'note_low': raw[23], 'note_high': raw[24],
                        'bass_on': mapped[11], 'sdec_sections': group['sections'],
                        'attached_sections': attached, 'source_note_pcs': dict(pcs)}
                    records.append(row)
                    if word not in samples and bs:
                        samples[word] = [word,style,style_sha,str(group['ordinal']),d[1],str(offset),raw.hex(),bs[0][0],bs[0][1]]
            style_proofs[style] = {'file_sha256': style_sha, **s3['styles'][style]}
    require(len(records) == 32913 and len(samples) == 53, 'raw corpus inventory drift')
    require(sum(len(r['attached_sections']) for r in records) == 71239, 'attachment count drift')
    summary = {'schema': 1, 'baseline_commit': BASELINE, 'corpus_sha256': ARCHIVE_SHA,
        'certification_status': 'UNRESOLVED', 'playback_fix_authorized': False,
        'external_source_registry_sha256': file_sha(SOURCES),
        'S3_semantic_matrix_sha256': s3['semantic_matrix_sha256'],
        'counts': {'styles':503, 'raw_records':len(records), 'raw_words':len(samples),
            'attachments':71239, 'nondefault_records':sum(r['raw_word_hex'] != '0FFF' for r in records)},
        'word_inventory': inventory(records), 'styles': style_proofs,
        'candidate_falsification': falsify(records), 'relationship_tests': relationship_tests(records),
        'comparison_scope': '384 finite mask hypotheses vs supported community document, NOT Yamaha execution; corpus has no input-chord/output labels',
        'source_note_distribution_scope': 'section-projected NOTE_ON pitch classes weighted once per attached descriptor; not current input-chord roots or unique raw event counts',
        'required_real_examples': [witness(next(r for r in records if r['style']==style and r['payload_offset']==off)) for style,off in (
            ('Country/CowboyBoogie1.S626.bcs',18918),('Ballrom/Rhumba.T006.bcs',24015),
            ('Ballad/PopBallad4.S281.bcs',26470),('Ballad/PopBallad4.S281.bcs',26540),
            ('Pop&Rock/Unplugged2.T151.prs',85411))]}
    sample_text = '\n'.join('\t'.join(samples[w]) for w in sorted(samples))+'\n'
    summary['sample_tsv_sha256'] = hashlib.sha256(sample_text.encode()).hexdigest()
    summary['evidence_sha256'] = digest(summary)
    return summary, sample_text


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--corpus',type=Path,required=True)
    parser.add_argument('--protocols',type=Path,required=True,help='freshly verified S3 native protocol directory')
    parser.add_argument('--output',type=Path,default=ROOT/'build/s4-root-selection')
    parser.add_argument('--record-initial',action='store_true',help='new S4 references only; never overwrites')
    args = parser.parse_args()
    if args.record_initial:
        require(not REFERENCE.exists() and not SAMPLES.exists(), 'initial recording cannot overwrite evidence')
    summary, samples = observe(args.corpus,args.protocols)
    args.output.mkdir(parents=True,exist_ok=True)
    (args.output/'observed_root_selection.json').write_text(json.dumps(summary,indent=2,sort_keys=True)+'\n')
    (args.output/'root_records.tsv').write_text(samples)
    columns = ['raw_word_hex','occurrence_count','style_count','attachment_count','expanded_count',
        'style_section_count','sdec_section_distribution','attached_section_distribution','distributions',
        'source_root_type_joint','note_range_joint','example']
    with (args.output/'root_word_table.csv').open('w',newline='') as stream:
        writer=csv.DictWriter(stream,fieldnames=columns,lineterminator='\n');writer.writeheader()
        writer.writerows({k:canonical(row[k]) if isinstance(row[k],dict) else row[k] for k in columns} for row in summary['word_inventory'])
    if args.record_initial:
        REFERENCE.write_text(json.dumps(summary,indent=2,sort_keys=True)+'\n')
        SAMPLES.parent.mkdir(parents=True,exist_ok=True);SAMPLES.write_text(samples)
    else:
        require(summary == json.loads(REFERENCE.read_text()), 'S4 evidence drift; no auto-update')
        require(samples == SAMPLES.read_text(), 'original 53-word samples differ')
    print('S4_EVIDENCE PASS 503 styles / 32913 raw Ctab / 53 words / 71239 attachments; F01 UNRESOLVED; no playback fix')
    print('EVIDENCE_SHA256 '+summary['evidence_sha256'])
    print('MASK_HYPOTHESES '+str(len(summary['candidate_falsification'])))


if __name__ == '__main__':
    main()
