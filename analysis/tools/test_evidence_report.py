#!/usr/bin/env python3
"""Regression test for evidence_report.py using a synthetic app export."""
import json
import subprocess
import sys
import tempfile
import os

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, '..', 'protocol'))
from ibwalk_frame import Frame  # noqa: E402

f1 = Frame(cmd=0x06, a1=0x01, a2=0x05,
           payload=bytes.fromhex('01000e001200')).encode().hex()
f2 = Frame(cmd=0x06, a1=0x01, a2=0x06,
           payload=bytes.fromhex('0100 1100 1400'.replace(' ', ''))).encode().hex()
split_at = 6

evidence = {
    'session': 'S1',
    'device': 'AA:BB:CC:DD:EE:FF',
    'captured_at': '2026-10-19T21:00:00+08:00',
    'advertisement': {
        'serviceUuids': ['0000fff0-0000-1000-8000-00805f9b34fb'],
        'manufacturerData': {'76': '0102'},
        'serviceData': {},
        'txPowerLevel': -4,
        'connectable': True,
    },
    'gatt_tree': [
        'D AA:BB:CC:DD:EE:FF mtu~23',
        'S 0000fff0-0000-1000-8000-00805f9b34fb',
        '  C 0000fff1-0000-1000-8000-00805f9b34fb [RN] desc=[00002902-0000-1000-8000-00805f9b34fb]',
    ],
    'reads': [
        {'service_uuid': '0000fff0-0000-1000-8000-00805f9b34fb',
         'char_uuid': '0000fff2-0000-1000-8000-00805f9b34fb',
         'ok': True, 'hex': 'aa55'},
    ],
    'raw_notifications': [
        {'ts': '2026-10-19T21:00:01+08:00', 'hex': f1,
         'service_uuid': '0000fff0-0000-1000-8000-00805f9b34fb',
         'char_uuid': '0000fff1-0000-1000-8000-00805f9b34fb'},
        {'ts': '2026-10-19T21:00:02+08:00', 'hex': f2[:split_at],
         'service_uuid': '0000fff0-0000-1000-8000-00805f9b34fb',
         'char_uuid': '0000fff1-0000-1000-8000-00805f9b34fb'},
        {'ts': '2026-10-19T21:00:02+08:00', 'hex': f2[split_at:],
         'service_uuid': '0000fff0-0000-1000-8000-00805f9b34fb',
         'char_uuid': '0000fff1-0000-1000-8000-00805f9b34fb'},
        {'ts': '2026-10-19T21:00:03+08:00', 'hex': 'deadbeef',
         'char_uuid': '0000fff1-0000-1000-8000-00805f9b34fb'},
    ],
}

with tempfile.TemporaryDirectory() as td:
    ev_path = os.path.join(td, 'evidence.json')
    md_path = os.path.join(td, 'report.md')
    js_path = os.path.join(td, 'stats.json')
    with open(ev_path, 'w') as fh:
        json.dump(evidence, fh)

    r = subprocess.run([sys.executable, os.path.join(HERE, 'evidence_report.py'),
                        ev_path, '-o', md_path, '--json', js_path],
                       capture_output=True, text=True)
    assert r.returncode == 0, r.stderr
    md = open(md_path, encoding='utf-8').read()
    stats = json.load(open(js_path, encoding='utf-8'))

    assert '0000fff1-0000-1000-8000-00805f9b34fb' in md
    assert '00002902-0000-1000-8000-00805f9b34fb' in md  # descriptor kept
    assert '0xaa55' in md                                # read value
    assert 'deadbeef' in md                              # undecoded preserved

    notes = stats['notifications']['0000fff1-0000-1000-8000-00805f9b34fb']
    assert notes['packets'] == 4
    cmds = {(g['cmd'], g['a1'], g['a2']): g['count'] for g in notes['groups']}
    assert cmds[('0x06', '0x01', '0x05')] == 1, cmds
    assert cmds[('0x06', '0x01', '0x06')] == 1, cmds  # reassembled split frame
    print('EVIDENCE REPORT OK')


print('stats groups:', json.dumps(stats['notifications'], ensure_ascii=False)[:200])
