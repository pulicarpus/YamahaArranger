#!/usr/bin/env python3
"""Checks the report parser/comparator against a real Redmi Pad report block (build 88c3dd2)."""
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'tools'))
import compare_latency as c

report = c.parse_report((ROOT / 'tests/fixtures/cpv/latency_device_88c3dd2.txt').read_text())
s = report['stats']
assert s['sectionVoiceApplyDuration']['meanUs'] == 35860 and s['sectionVoiceApplyDuration']['maxUs'] == 68936
assert s['rightNoteOnSendToAllLayersHandedToNative']['count'] == 186
assert s['callbackTotal']['buckets'] == [2162, 3169, 5020, 8749, 6608, 152, 33, 1, 0, 0] and sum(s['callbackTotal']['buckets']) == 25894
assert s['keyboardNoteQueuedToBufferReady']['count'] == 214
assert report['meta']['LATENCY_NATIVE']['overBudget'] == '116'
assert report['meta']['LATENCY_OUTPUT_LIVE']['xruns'] == '13'
# The old counter's meaning: queued notes = paired + "overwritten" (notes sharing a render window with an earlier one).
assert s['keyboardNoteLockWait']['count'] == 426 == s['keyboardNoteDispatch']['count'] == s['jniKeyboardNoteOn_includesNativeLockWait']['count']
assert 214 + 212 == 426
# Comparison output names both sides and shows a change.
b = c.parse_report((ROOT / 'tests/fixtures/cpv/latency_device_88c3dd2.txt').read_text())
b['stats']['sectionVoiceApplyDuration']['meanUs'] = 10000
text = c.compare(report, b)
assert 'sectionVoiceApplyDuration' in text and '-72%' in text, text
print('compare_latency PASS')
