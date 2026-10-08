#!/usr/bin/env python3
"""S1: independent bytes vs unmodified production parser; no playback writes.

Counts/digests are baseline observations, not Yamaha musical or PCM oracles.
Use --corpus ZIP_OR_DIRECTORY; proprietary corpus bytes are never committed.
"""
from __future__ import annotations
from f04_source_guard import historical_bytes

import argparse
from collections import Counter, defaultdict, deque
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import shutil
import struct
import subprocess
import sys
import tempfile
import zipfile
from sff_casm_source_guard import FIXTURE as S3_SOURCE_GUARD, verify_metadata_sources as verify_s3_sources
from sff_dialect_source_guard import FIXTURE as S2_SOURCE_GUARD, strip_metadata, verify_metadata_sources

ROOT = Path(__file__).resolve().parents[1]
BASELINE = "05cdb1f0998d082d701bc0d1979580270ae2eefc"
ARCHIVE_SHA = "a9b89b90e1dee25c714a076a125d6c7173bcca0e8d2029c57092b3295035d465"
MANIFEST = ROOT / "tests/fixtures/sff1_reference.json"
LEDGER = ROOT / "tests/fixtures/sff1_known_failures.json"
RESOURCE = ROOT / "app/src/test/resources/sff1_main_d_native.tsv"
EXPECTED = {
    "styles": 503, "parse_success": 503, "sff1_casm": 503,
    "smf0_one_track_ppq1920": 503, "raw_events_verified": 3218027,
    "cseg": 3584, "sdec": 3584, "ctab": 32913, "cntt": 32913, "ctb2": 0,
    "expected_section_policy_copies": 73229, "attached_applicable_copies": 71239,
    "missing_copies": 1990, "missing_sections": 1084, "missing_styles": 196,
    "raw_descriptors_never_attached": 157, "overlapping_on": 523,
    "overlap_styles": 47, "maximum_note_multiplicity": 2,
    "multi_source_destination_groups": 9373, "maximum_sources_per_destination": 11,
    "nondefault_root_records": 257, "nondefault_root_styles": 49,
    "ntt": {"0":15068,"1":4866,"2":10600,"3":760,"4":36,"5":1541,"6":28,"9":14},
    "meter": {"4/4":463,"3/4":39,"2/4":1,"6/8":0},
    "terminal_fill_ba_lengths": {"7681":463,"5761":39,"3841":1},
}
GOLDEN = {
    "Ballad/LoveSong3.S687.prs": (503,483,{8:117,9:80,10:19,11:70,12:192,13:3,14:2}),
    "Latin/Forro.S729.prs": (357,321,{8:105,9:50,10:9,11:72,12:64,14:21}),
    "Ballad/PopWaltz2.S662.bcs": (236,231,{8:69,9:47,10:9,11:14,12:56,13:3,14:33}),
    "Movie&Show/BaroqueAir1.S145.sst": (280,236,{8:36,9:8,10:64,11:72,12:48,15:8}),
    "Ballad/8BeatSoft.S686.bcs": (153,141,{8:26,9:14,10:15,11:41,12:18,13:3,14:24}),
    "Pop&Rock/Unplugged2.T151.prs": (1737,1353,{8:256,9:233,10:48,11:672,14:144}),
    "Ballad/8BeatPiano1.T107.pcs": (72,72,{9:32,10:8,11:24,12:8}),
}
POLICY_COLUMNS = ["id","cseg_ordinal","subchunk_ordinal","payload_byte_offset","source",
                  "destination","raw_sha256","mapped_fields_sha256","root_field_raw",
                  "effective_ntt","bass_on","attached_sections","missing_sections"]
SECTION_NAMES = {f"Main {x}":f"Main{x}" for x in "ABCD"}
SECTION_NAMES.update({f"{kind} {x}":f"{kind}{x}" for kind in ("Intro","Ending") for x in "ABC"})
SECTION_NAMES.update({f"Fill In {x}":f"Fill{x}" for x in ("AA","BB","CC","DD","BA")})


class ConformanceError(RuntimeError):
    pass


def require(condition, message):
    if not condition:
        raise ConformanceError(message)


def canonical(value):
    return json.dumps(value, sort_keys=True, ensure_ascii=True, separators=(",", ":"))


def digest(value):
    return hashlib.sha256(canonical(value).encode()).hexdigest()


def file_sha(path):
    with Path(path).open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


class Bytes:
    def __init__(self, data, start=0, end=None):
        self.data, self.pos, self.end = data, start, len(data) if end is None else end

    def take(self, count):
        require(count >= 0 and self.pos + count <= self.end, f"truncated bytes at {self.pos}")
        value = self.data[self.pos:self.pos+count]
        self.pos += count
        return value

    def vlq(self):
        value = 0
        for _ in range(4):
            byte = self.take(1)[0]
            value = (value << 7) | (byte & 127)
            if byte < 128:
                return value
        raise ConformanceError("unterminated SMF VLQ")


def read_smf(data):
    """Independent bounded raw decoder. Never makes routing/NTT decisions."""
    reader = Bytes(data)
    require(reader.take(4) == b"MThd", "missing MThd")
    length = int.from_bytes(reader.take(4), "big")
    header = reader.take(length)
    require(length >= 6, "short SMF header")
    fmt, tracks, ppq = struct.unpack(">HHH", header[:6])
    require((fmt, tracks, ppq) == (0, 1, 1920), "reference corpus SMF header changed")
    events, offsets = [], []
    require(reader.take(4) == b"MTrk", "missing MTrk")
    length = int.from_bytes(reader.take(4), "big")
    track = Bytes(data, reader.pos, reader.pos + length)
    require(track.end <= len(data), "truncated MTrk")
    tick, running = 0, None
    track_name = b""
    while track.pos < track.end:
        offsets.append(track.pos)
        tick += track.vlq()
        first = track.take(1)[0]
        if first < 128:
            require(running is not None, "running status without channel status")
            status = running
            track.pos -= 1
        else:
            status = first
        key = velocity = meta = 0
        payload = b""
        if 0x80 <= status <= 0xEF:
            running = status
            key = track.take(1)[0]
            if status & 240 not in (0xC0, 0xD0):
                velocity = track.take(1)[0]
            require(key < 128 and velocity < 128, "invalid channel data byte")
        elif status == 255:
            running = None
            meta = track.take(1)[0]
            payload = track.take(track.vlq())
            if meta == 3:
                track_name = payload
        elif status in (240,247):
            running = None
            payload = track.take(track.vlq())
        else:
            raise ConformanceError(f"unsupported SMF status {status}")
        channel = status & 15 if status < 240 else 0
        events.append([tick,status,channel,key,velocity,meta,payload.hex()])
    return (fmt,tracks,ppq), events, offsets, track_name.hex(), track.end


def chunks(data, start, end):
    ordinal = 0
    while start < end:
        require(start + 8 <= end, "short CASM chunk header")
        tag = data[start:start+4]
        size = int.from_bytes(data[start+4:start+8], "big")
        payload_start, payload_end = start + 8, start + 8 + size
        require(payload_end <= end, "CASM child exceeds parent")
        yield ordinal, tag, payload_start, data[payload_start:payload_end]
        start, ordinal = payload_end, ordinal + 1


def section_name(text):
    name = SECTION_NAMES.get(text.strip())
    require(name is not None, f"unexpected Sdec section {text!r}; investigate rather than relabel")
    return name


def read_casm(data, smf_end):
    start = data.find(b"CASM", smf_end)
    require(start >= 0 and start + 8 <= len(data), "missing bounded CASM after SMF")
    end = start + 8 + int.from_bytes(data[start+4:start+8], "big")
    require(end <= len(data), "truncated CASM")
    groups, counts = [], Counter()
    for ci, tag, payload_offset, payload in chunks(data, start+8, end):
        require(tag == b"CSEG", "unexpected CASM child")
        counts["cseg"] += 1
        sections, tables, overrides = [], [], {}
        for si, sub, offset, raw in chunks(data, payload_offset, payload_offset+len(payload)):
            counts[sub.decode("ascii").lower()] += 1
            if sub == b"Sdec":
                require(not tables, "Sdec after Ctab: unsupported baseline attachment order")
                sections.extend(section_name(x) for x in raw.decode("ascii").split(","))
            elif sub == b"Ctab":
                require(len(raw) >= 27, "short Ctab")
                tables.append((si, offset, raw))
            elif sub == b"Cntt":
                require(len(raw) >= 2 and raw[0] not in overrides, "short/duplicate Cntt")
                overrides[raw[0]] = (raw[1] & 127, int(bool(raw[1] & 128)))
            elif sub == b"Ctb2":
                raise ConformanceError("SFF1 reference corpus unexpectedly includes Ctb2")
            else:
                raise ConformanceError(f"unknown CASM subchunk {sub!r}")
        require(len(set(sections)) == len(sections) and sections, "duplicate/empty Sdec")
        records = []
        for si, offset, raw in tables:
            require(raw[0] in overrides, "Ctab has no authoritative Cntt")
            ntt, bass = overrides[raw[0]]
            mapped = [raw[0],raw[9],raw[1:9].strip().hex(),raw[18],raw[19],raw[20],ntt,
                      raw[22],raw[23],raw[24],raw[25],bass,int.from_bytes(raw[13:18],"big"),0,127]
            records.append({"id":f"cseg{ci}/sub{si}","cseg":ci,"sub":si,"offset":offset,
                            "raw_sha":hashlib.sha256(raw).hexdigest(),"mapped":mapped,
                            "root":int.from_bytes(raw[11:13],"big")})
        groups.append({"ordinal":ci,"sections":sections,"records":records})
    return groups, counts


def read_native(output):
    """Decode the observer protocol, not a second style parser."""
    native = {"raw":[],"sections":{}}
    section = part = None
    for line in output.splitlines():
        fields = line.split("\t")
        tag = fields[0]
        if tag == "HEADER":
            native["header"] = list(map(int,fields[1:4]))
            native["tempo"] = float(fields[4])
        elif tag == "TRACK":
            require(fields[1] == "0", "unexpected native track")
            native["track_name"] = fields[2]
        elif tag == "RAW":
            require(int(fields[2]) == len(native["raw"]) and fields[1] == "0", "raw ordinal changed")
            native["raw"].append(list(map(int,fields[3:9]))+[fields[9]])
        elif tag == "SECTION":
            section = {"length":int(fields[2]),"parts":[]}
            native["sections"][fields[1]] = section
        elif tag == "PART":
            require(int(fields[1]) == len(section["parts"]), "part ordinal changed")
            part = {"index":int(fields[1]),"source":int(fields[2]),"name":fields[3],"policies":[],"events":[]}
            section["parts"].append(part)
        elif tag == "POLICY":
            require(int(fields[1]) == len(part["policies"]), "policy ordinal changed")
            part["policies"].append([int(x) if i != 2 else x for i,x in enumerate(fields[2:])])
        elif tag == "EVENT":
            require(int(fields[1]) == len(part["events"]), "part-event ordinal changed")
            part["events"].append(list(map(int,fields[2:8]))+[fields[8]])
        else:
            raise ConformanceError(f"unknown native protocol row {tag}")
    return native


def expected_projection(events):
    """Reference byte-to-section projection with observer raw-ordinal origins.

    Fixed corpus marker grammar, not the production broad marker heuristics.
    Current terminal+1 and setup copies are labeled baseline conventions.
    """
    boundaries = []
    for ordinal, event in enumerate(events):
        if event[1] == 255 and event[5] in (1,6):
            text = bytes.fromhex(event[6]).decode("ascii", errors="replace")
            if text in SECTION_NAMES:
                boundaries.append((event[0], SECTION_NAMES[text], ordinal))
    require(boundaries and len({x[1] for x in boundaries}) == len(boundaries), "missing/repeated corpus markers")
    setup = defaultdict(list)
    for ordinal, event in enumerate(events):
        if event[0] < boundaries[0][0] and 128 <= event[1] < 240:
            setup[event[2]].append((ordinal,event))
    sections = {}
    for index, (start,name,marker_ordinal) in enumerate(boundaries):
        end = boundaries[index+1][0] if index+1 < len(boundaries) else events[-1][0]+1
        parts, origins = defaultdict(list), defaultdict(list)
        for ordinal, event in enumerate(events):
            if not start <= event[0] < end:
                continue
            if event[1] == 255 and event[5] in (1,6,47):
                continue
            channel = event[2]
            relative = [event[0]-start]+event[1:]
            parts[channel].append(relative)
            origins[channel].append([ordinal,"AUTHORED_SECTION_EVENT"])
        for channel in parts:
            copied = [[0]+event[1:] for _,event in setup[channel]]
            parts[channel] = copied + parts[channel]
            origins[channel] = [[ordinal,"COPIED_PRE_SECTION_CHANNEL_SETUP"] for ordinal,_ in setup[channel]] + origins[channel]
        sections[name] = {"length":end-start,"parts":parts,"origins":origins,"start":start,
                          "end":end,"marker_raw_ordinal":marker_ordinal}
    require(boundaries[-1][1] == "FillBA", "terminal recognized section changed")
    return sections


def raw_note_evidence(events, offsets):
    active, overlaps, maximum = defaultdict(deque), [], 0
    for ordinal, event in enumerate(events):
        tick,status,channel,key,velocity,_,_ = event
        high = status & 240
        identity = (channel,key)
        if high == 144 and velocity > 0:
            if active[identity]:
                overlaps.append({"raw_event_ordinal":ordinal,"byte_offset":offsets[ordinal],"tick":tick,
                                 "source":channel,"note":key,"prior_on_ordinals":list(active[identity]),
                                 "status":"BASELINE_KNOWN_FAILURE","reason":"ORIGINAL_ORDER_OVERLAP_CANDIDATE_NOT_PCM_FAILURE_COUNT"})
            active[identity].append(ordinal)
            maximum = max(maximum,len(active[identity]))
        elif high == 128 or (high == 144 and velocity == 0):
            if active[identity]:
                active[identity].popleft()
    return overlaps, maximum


def observe_file(path, relative, executable):
    data = path.read_bytes()
    header, raw, offsets, track_name, smf_end = read_smf(data)
    native_output = subprocess.run([str(executable),str(path)],check=True,text=True,capture_output=True).stdout
    native = read_native(native_output)
    require(native["header"] == list(header), f"{relative}: header mismatch")
    require(native["raw"] == raw and native["track_name"] == track_name, f"{relative}: production raw SMF mismatch")
    require(any(event[6] == b"SFF1".hex() for event in raw), f"{relative}: no SFF1 marker")
    meters = [bytes.fromhex(e[6]) for e in raw if e[1]==255 and e[5]==88]
    require(meters and len(meters[0])==4, f"{relative}: invalid time signature")
    meter = f"{meters[0][0]}/{1 << meters[0][1]}"
    groups, counts = read_casm(data,smf_end)
    projection = expected_projection(raw)
    require(set(projection)==set(native["sections"]), f"{relative}: section set mismatch")
    section_results = {}
    origin_digest = hashlib.sha256()
    for name, section in native["sections"].items():
        expected = projection[name]
        require(section["length"]==expected["length"], f"{relative}/{name}: duration mismatch")
        require([p["source"] for p in section["parts"]]==sorted(expected["parts"]), f"{relative}/{name}: source part mismatch")
        for part in section["parts"]:
            channel = part["source"]
            require(part["events"]==expected["parts"][channel], f"{relative}/{name}/src{channel}: projected event mismatch")
            expected_name = track_name or f"Ch{channel}".encode().hex()
            require(part["name"]==expected_name, f"{relative}/{name}: part name mismatch")
            for ordinal, origin in enumerate(expected["origins"][channel]):
                origin_digest.update((canonical([name,part["index"],channel,ordinal,*origin])+"\n").encode())
        section_results[name] = {"length_ticks":section["length"],"marker_raw_ordinal":expected["marker_raw_ordinal"],
                                 "start_raw_tick":expected["start"],"end_exclusive_raw_tick":expected["end"],
                                 "native_section_sha256":digest(section),"raw_on":sum(e[1]&240==144 and e[4]>0 for p in section["parts"] for e in p["events"]),
                                 "sources":[{"source":p["source"],"part_index":p["index"],"events":len(p["events"]),
                                             "raw_on":sum(e[1]&240==144 and e[4]>0 for e in p["events"]),
                                             "policy_copies":len(p["policies"]),"event_sha256":digest(p["events"])} for p in section["parts"]]}
    expected_policies = defaultdict(lambda:defaultdict(list))
    records, missing, ntt, multi, max_sources, never, nondefault = [], [], Counter(), 0, 0, 0, 0
    cseg_summaries = []
    for group in groups:
        destinations = defaultdict(set)
        for record in group["records"]:
            mapped = record["mapped"]
            source,destination = mapped[:2]
            ntt[str(mapped[6])] += 1
            destinations[destination].add(source)
            attached, absent = [], []
            for name in group["sections"]:
                require(name in projection, f"{relative}: Sdec has no parsed section {name}")
                expected_policies[name][source].append(mapped)
                if source in projection[name]["parts"]:
                    attached.append(name)
                else:
                    absent.append(name)
                    missing.append([record["id"],name,source,destination,"NO_PARSED_SOURCE_PART_IN_SECTION"])
            never += not attached
            nondefault += record["root"] != 4095
            records.append([record["id"],record["cseg"],record["sub"],record["offset"],source,destination,
                            record["raw_sha"],digest(mapped),record["root"],mapped[6],mapped[11],attached,absent])
        multi += sum(len(sources)>1 for sources in destinations.values())
        max_sources = max(max_sources,max(map(len,destinations.values()),default=0))
        cseg_summaries.append({"ordinal":group["ordinal"],"sections":group["sections"],
                               "policy_ids":[r["id"] for r in group["records"]],
                               "destination_sources":{str(k):sorted(v) for k,v in sorted(destinations.items())}})
    attached_count = 0
    for name, section in native["sections"].items():
        for part in section["parts"]:
            require(part["policies"]==expected_policies[name][part["source"]], f"{relative}/{name}/src{part['source']}: Ctab/Cntt mismatch")
            attached_count += len(part["policies"])
    overlap, maximum = raw_note_evidence(raw, offsets)
    result = {"relative_path":relative,"file_sha256":hashlib.sha256(data).hexdigest(),"bytes":len(data),
              "format":header[0],"tracks":header[1],"ppq":header[2],"meter":meter,
              "production_parse":"PROVEN_PRESERVED","raw_events":len(raw),"raw_event_sha256":digest(raw),
              "native_plan_sha256":digest(native["sections"]),"projection_origin_sha256":origin_digest.hexdigest(),
              "projection_origin_scope":"section/part/source/part-event-ordinal/raw-event-ordinal/copy-kind; observer-side only",
              "chunks":dict(counts),"sections":section_results,"cseg":cseg_summaries,"policy_records":records,
              "policy_registry_sha256":digest(records),"missing_copies":missing,"missing_reason":"NO_PARSED_SOURCE_PART_IN_SECTION",
              "attached_applicable_copies":attached_count,"expected_section_policy_copies":attached_count+len(missing),
              "raw_descriptors_never_attached":never,"overlapping_on":overlap,"maximum_note_multiplicity":maximum,
              "multi_source_destination_groups":multi,"maximum_sources_per_destination":max_sources,
              "nondefault_root_records":nondefault,"root_field_status":"BASELINE_KNOWN_FAILURE_RAW_FIELD_NOT_IN_PRODUCTION_MODEL",
              "effective_ntt":dict(ntt),"eligible_events":"NOT_EVALUATED_NO_YAMAHA_ORACLE",
              "native_acceptance":"NOT_MEASURED","pcm_presence":"NOT_MEASURED"}
    if relative == "Ballad/LoveSong3.S687.prs":
        density={str(p["source"]):sum(e[1]&240==144 and e[4]>0 for e in p["events"]) for p in native["sections"]["MainD"]["parts"]}
        density={k:v for k,v in density.items() if v}
        require(density=={"2":19,"3":70,"4":1,"5":192,"6":19,"8":117,"9":80,"10":3,"12":2}, "LoveSong3 MainD source density mismatch")
        result["main_d_note_source_density"] = density
    golden = ""
    if relative in GOLDEN:
        raw_on, forwarded, destinations = GOLDEN[relative]
        require(section_results["MainD"]["raw_on"]==raw_on, f"{relative}: raw golden count mismatch")
        result["golden_baseline_expectation"]={"section":"MainD","chord":"C_MAJOR","raw_on":raw_on,
                                               "dispatched_on":forwarded,"destinations":{str(k):v for k,v in sorted(destinations.items())},
                                               "status":"BASELINE_OBSERVATION_NOT_MUSICAL_ORACLE"}
        keep = False
        golden_lines=[f"STYLE\t{relative}\t{result['file_sha256']}\t{section_results['MainD']['native_section_sha256']}"]
        for line in native_output.splitlines():
            if line.startswith("HEADER\t"):
                golden_lines.append(line)
            elif line.startswith("SECTION\t"):
                keep = line.split("\t")[1]=="MainD"
                if keep:
                    golden_lines.append(line)
            elif keep and line.startswith(("PART\t","POLICY\t","EVENT\t")):
                golden_lines.append(line)
        for part in native["sections"]["MainD"]["parts"]:
            for ordinal,(raw_ordinal,kind) in enumerate(projection["MainD"]["origins"][part["source"]]):
                golden_lines.append(f"ORIGIN\t{part['index']}\t{ordinal}\t{raw_ordinal}\t{kind}")
        golden="\n".join(golden_lines)+"\n"
        result["golden_main_d_protocol_sha256"]=hashlib.sha256(golden.encode()).hexdigest()
    return result,golden


def summarize(styles):
    total = Counter()
    ntt,meter,fill = Counter(),Counter({"6/8":0}),Counter()
    for style in styles:
        total["styles"]+=1;total["parse_success"]+=1;total["sff1_casm"]+=1;total["smf0_one_track_ppq1920"]+=1
        total["raw_events_verified"]+=style["raw_events"]
        for tag in ("cseg","sdec","ctab","cntt","ctb2"):
            total[tag]+=style["chunks"].get(tag,0)
        for key in ("attached_applicable_copies","expected_section_policy_copies","raw_descriptors_never_attached","multi_source_destination_groups","nondefault_root_records"):
            total[key]+=style[key]
        total["missing_copies"]+=len(style["missing_copies"])
        total["missing_sections"]+=len({x[1] for x in style["missing_copies"]})
        total["missing_styles"]+=bool(style["missing_copies"])
        total["overlapping_on"]+=len(style["overlapping_on"])
        total["overlap_styles"]+=bool(style["overlapping_on"])
        total["nondefault_root_styles"]+=bool(style["nondefault_root_records"])
        for key in ("maximum_note_multiplicity","maximum_sources_per_destination"):
            total[key]=max(total[key],style[key])
        ntt.update(style["effective_ntt"]);meter[style["meter"]]+=1
        fill[str(style["sections"]["FillBA"]["length_ticks"])]+=1
    result=dict(total)
    result.update(ntt=dict(ntt),meter=dict(meter),terminal_fill_ba_lengths=dict(fill))
    require(result==EXPECTED, "corpus aggregate differs from audit; expected must not be auto-updated:\n"+canonical({"expected":EXPECTED,"observed":result}))
    return result


def compile_driver(output):
    compiler=os.environ.get("CXX") or shutil.which("g++") or shutil.which("c++")
    require(compiler is not None,"C++17 compiler required")
    executable=output/"sff1_parser_conformance_test"
    # Frozen parser has chained one-line ifs. Keep other warnings fatal rather
    # than formatting production source just to satisfy a new host driver.
    subprocess.run([compiler,"-std=c++17","-O2","-Wall","-Wextra","-Werror","-Wno-error=misleading-indentation","-DANDROID_LOG_ERROR=6",
                    "-I",str(ROOT/"tests/mocks"),"-I",str(ROOT/"app/src/main/cpp"),
                    str(ROOT/"tests/sff1_parser_conformance_test.cpp"),str(ROOT/"app/src/main/cpp/style_parser.cpp"),
                    str(ROOT/"app/src/main/cpp/smf_reader.cpp"),"-o",str(executable)],check=True)
    return executable


def production_identity():
    s3_sources=verify_s3_sources()
    metadata_sources=verify_metadata_sources()
    names=subprocess.check_output(["git","ls-tree","-r","--name-only",BASELINE,"--","app/src/main"],cwd=ROOT,text=True).splitlines()
    require(names,"missing baseline source tree")
    indexed=subprocess.check_output(["git","ls-files","--","app/src/main"],cwd=ROOT,text=True).splitlines()
    require(sorted(indexed)==sorted(names),"S1 production file set changed from baseline")
    files={name:file_sha(ROOT/name) for name in names}
    # This catches working-tree changes as well as committed modifications.
    for name in names:
        expected=subprocess.check_output(["git","show",f"{BASELINE}:{name}"],cwd=ROOT)
        observed=(strip_metadata(name,(ROOT/name).read_text()).encode()
                  if name in metadata_sources else historical_bytes(name))
        require(hashlib.sha256(expected).hexdigest()==hashlib.sha256(observed).hexdigest(),f"S1 production source changed beyond pinned metadata: {name}")
    untracked=subprocess.check_output(["git","ls-files","--others","--exclude-standard","--","app/src/main"],cwd=ROOT,text=True)
    from generated_bass_sdk_guard import verify_generated_sdk
    try: verify_generated_sdk(ROOT,untracked.splitlines())
    except AssertionError as error: raise ConformanceError(str(error)) from error
    result={"baseline_commit":BASELINE,"main_tree_git_oid":subprocess.check_output(["git","rev-parse",f"{BASELINE}:app/src/main"],cwd=ROOT,text=True).strip(),
            "tracked_files":files,"verified_working_tree_sha256":digest(files)}
    if metadata_sources:
        result["s2_metadata_source_guard_sha256"]=file_sha(S2_SOURCE_GUARD)
    if s3_sources:
        result["s3_metadata_source_guard_sha256"]=file_sha(S3_SOURCE_GUARD)
    return result


def serialized_json(value):
    # Descriptor arrays use schema columns and one record/line, keeping the
    # complete 32913-record provenance readable without a multi-megabyte line.
    def pretty(item,level=0,key=""):
        pad="  "*level
        if isinstance(item,dict):
            return "{\n"+",\n".join("  "*(level+1)+json.dumps(k)+": "+pretty(v,level+1,k) for k,v in sorted(item.items()))+"\n"+pad+"}"
        if isinstance(item,list):
            if not item or all(not isinstance(v,(list,dict)) for v in item):
                return canonical(item)
            rows=[canonical(v) if key in ("policy_records","missing_copies") else pretty(v,level+1) for v in item]
            return "[\n"+",\n".join("  "*(level+1)+row for row in rows)+"\n"+pad+"]"
        return canonical(item)
    text=pretty(value)
    return (text+"\n").encode("utf-8")


def write_json(path,value):
    Path(path).write_bytes(serialized_json(value))


def compact_reference(manifest, generated_path):
    """Pin every full observation via canonical hashes, without storing its rows.

    The generated JSON retains the original schema and byte serialization. A
    per-style hash covers all raw/policy/projection/overlap provenance, including
    fields that are not repeated in the human-readable compact summary.
    """
    require(manifest["deterministic_manifest_content_sha256"] ==
            digest({k:v for k,v in manifest.items() if k != "deterministic_manifest_content_sha256"}),
            "full manifest content digest is inconsistent")
    reference={k:v for k,v in manifest.items() if k != "styles"}
    reference["compact_reference_schema"]=1
    reference["generated_full_manifest_sha256"]=file_sha(generated_path)
    reference["generated_full_manifest_bytes"]=Path(generated_path).stat().st_size
    reference["pipeline_capture_resource_sha256"]=file_sha(ROOT/"app/src/test/resources/sff1_pipeline_digests.tsv")
    reference["styles"]={relative:{
        "file_sha256":style["file_sha256"],"bytes":style["bytes"],
        "full_style_content_sha256":digest(style),
        **{k:v for k,v in style.items() if k.startswith("golden_")}
    } for relative,style in manifest["styles"].items()}
    return reference


def verify_compact_reference(manifest, generated_path, reference):
    require(Path(generated_path).read_bytes()==serialized_json(manifest),
            "complete manifest/digest drift: generated bytes differ from deterministic serialization")
    actual=compact_reference(manifest,generated_path)
    if S2_SOURCE_GUARD.exists():
        verify_metadata_sources()
        # Only source-containing envelope hashes may differ. Per-style full
        # hashes, aggregates, golden resources, ledger and captures stay pinned.
        envelope={"production_source_identity","deterministic_manifest_content_sha256",
                  "generated_full_manifest_sha256","generated_full_manifest_bytes"}
        actual={k:v for k,v in actual.items() if k not in envelope}
        reference={k:v for k,v in reference.items() if k not in envelope}
    require(actual==reference,
            "complete manifest/digest drift against compact reference")


def main(argv=None):
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--corpus",required=True,type=Path,help="original sff1.zip or extracted sff1 directory")
    parser.add_argument("--output",type=Path,default=ROOT/"build/sff1",help="temporary observed reports/build output")
    parser.add_argument("--manifest",type=Path,default=MANIFEST,help="pinned compact reference, not the generated full manifest")
    parser.add_argument("--record",action="store_true",help="create initial compact reference/resource; refuses to overwrite either")
    args=parser.parse_args(argv)
    output=args.output.resolve();output.mkdir(parents=True,exist_ok=True)
    ledger=json.loads(LEDGER.read_text())
    require([x["id"] for x in ledger["findings"]]==[f"F{x:02}" for x in range(1,16)],"ledger F01-F15 incomplete")
    expected=None if args.record else json.loads(args.manifest.read_text())
    if args.record:
        require(not args.manifest.exists() and not RESOURCE.exists(),"--record never overwrites expected fixtures; investigate/review changes explicitly")
    identity=production_identity()
    executable=compile_driver(output)
    with tempfile.TemporaryDirectory(prefix="sff1-corpus-") as temp:
        corpus=args.corpus.resolve()
        if corpus.is_file():
            require(file_sha(corpus)==ARCHIVE_SHA,"archive SHA256 differs from audited corpus")
            with zipfile.ZipFile(corpus) as archive:
                for member in archive.infolist():
                    name=PurePosixPath(member.filename)
                    require(not name.is_absolute() and ".." not in name.parts,"unsafe archive path")
                    require(name.parts and name.parts[0]=="sff1","unexpected archive top-level folder")
                    require((member.external_attr>>16)&0o170000 != 0o120000,"archive symlinks unsupported")
                archive.extractall(temp)
            corpus=Path(temp)/"sff1"
        elif (corpus/"sff1").is_dir():
            corpus=corpus/"sff1"
        require(corpus.is_dir(),"missing corpus directory")
        paths=sorted(path for path in corpus.rglob("*") if path.is_file())
        require(len(paths)==503,"reference corpus must contain exactly 503 files")
        styles,golden=[],[]
        for index,path in enumerate(paths,1):
            relative=path.relative_to(corpus).as_posix()
            if expected:
                expected_style=expected["styles"].get(relative)
                require(expected_style is not None and file_sha(path)==expected_style["file_sha256"],f"corpus style identity changed: {relative}")
            result,fixture=observe_file(path,relative,executable)
            if expected:
                require(digest(result)==expected["styles"][relative]["full_style_content_sha256"],f"style conformance/provenance differs: {relative}")
            styles.append(result)
            if fixture:
                golden.append(fixture)
            if index%50==0 or index==len(paths):
                print(f"SFF1 corpus {index}/{len(paths)} verified",flush=True)
    totals=summarize(styles)
    fixture="".join(golden).encode()
    observed={"schema":1,"baseline_commit":BASELINE,"audited_archive_sha256":ARCHIVE_SHA,
              "corpus_identity_mode":"archive-sha-or-exact-relative-path-file-sha-set",
              "production_source_identity":identity,"known_failure_ledger_sha256":file_sha(LEDGER),
              "policy_record_columns":POLICY_COLUMNS,"aggregate":totals,
              "terms":{"raw_record":"one Ctab/Cntt in original CSEG","section_policy_copy":"one raw policy expanded via Sdec",
                       "eligible_event":"not claimed without Yamaha selection oracle","dispatched_event":"production output call, golden JVM tests only",
                       "native_acceptance":"NOT_MEASURED","pcm_presence":"NOT_MEASURED"},
              "styles":{style["relative_path"]:style for style in styles},
              "golden_resource_sha256":hashlib.sha256(fixture).hexdigest()}
    observed["deterministic_manifest_content_sha256"]=digest(observed)
    write_json(output/"observed_manifest.json",observed)
    (output/"sff1_main_d_native.tsv").write_bytes(fixture)
    if args.record:
        write_json(args.manifest,compact_reference(observed,output/"observed_manifest.json"))
        RESOURCE.parent.mkdir(parents=True,exist_ok=True);RESOURCE.write_bytes(fixture)
    else:
        verify_compact_reference(observed,output/"observed_manifest.json",expected)
        require(RESOURCE.read_bytes()==fixture,"committed golden parser output differs from current production parser")
    print("SFF1_CORPUS PASS 503/503 rawSMF/CASM/applicable-policy/projection verified; playback unchanged; nativeAcceptance/PCM NOT_MEASURED")
    print("MANIFEST_DIGEST "+observed["deterministic_manifest_content_sha256"])
    print("GENERATED_MANIFEST_SHA256 "+file_sha(output/"observed_manifest.json"))
    return 0


if __name__=="__main__":
    try:
        sys.exit(main())
    except (ConformanceError,subprocess.CalledProcessError,OSError,ValueError) as error:
        print(f"SFF1_CONFORMANCE FAIL: {error}",file=sys.stderr)
        sys.exit(1)
