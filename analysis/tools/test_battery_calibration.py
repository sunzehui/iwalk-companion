#!/usr/bin/env python3
"""Offline regression for the iWK1779887 battery-only calibration artifact.

Uses existing Python HCI/frame parsers and local captures; no Flutter, device,
network, or BLE writes. This does NOT execute the Dart importer or UI.
"""
import json
from pathlib import Path
import sys
import unittest
from collections import Counter, defaultdict

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'analysis' / 'protocol'))
sys.path.insert(0, str(ROOT / 'analysis' / 'tools'))
from ibwalk_frame import Frame, StreamDecoder  # noqa: E402
from hci_quicklook import (  # noqa: E402
    ATT_INDICATE, ATT_NOTIFY, att_pdus, iter_records, read_header,
)

PROFILE = ROOT / 'downloads' / 'iwk1779887-calibration-partial-v1.json'
E63 = ROOT / 'captures' / 'app_output'
E63_SUFFIX = 'ble-1792540522129_20261021T075530410030'


def battery_value(profile, frame):
    """Reference evaluation of this artifact only, not a Dart reimplementation."""
    if (frame.cmd, frame.a1, frame.a2) != (
        profile['cmd'], profile['a1'], profile['a2']
    ):
        return None
    mapping, = profile['fields']
    if mapping['type'] != 'u8':
        raise ValueError('This regression only supports the proven u8 mapping')
    offset = mapping['offset']
    if not 0 <= offset < len(frame.payload):
        return None
    return frame.payload[offset] * mapping['scale'] + mapping['bias']


class BatteryCalibrationTests(unittest.TestCase):
    def setUp(self):
        self.assertTrue(PROFILE.is_file(), f'Missing calibration artifact: {PROFILE}')
        self.document = json.loads(PROFILE.read_text(encoding='utf-8'))
        self.profile, = self.document['profiles']

    def test_exact_import_shape_and_only_proven_field(self):
        self.assertEqual(set(self.document), {'profiles'})
        self.assertEqual(set(self.profile), {
            'name', 'cmd', 'a1', 'a2', 'evidence', 'fields',
        })
        self.assertIn('iWK1779887', self.profile['name'])
        self.assertEqual(
            [self.profile[k] for k in ('cmd', 'a1', 'a2')], [13, 1, 20]
        )
        for key in ('cmd', 'a1', 'a2'):
            self.assertIs(type(self.profile[key]), int)
        self.assertEqual(self.profile['fields'], [{
            'field': 'batteryPercent', 'offset': 28, 'type': 'u8',
            'scale': 1, 'bias': 0,
        }])
        self.assertIn('E57', self.profile['evidence'])
        self.assertIn('E64', self.profile['evidence'])

    def test_official_hci_reassembles_and_replays_83_percent(self):
        path = ROOT / 'captures/20261021_official/btsnoop_hci.log'
        datalink = read_header(path)
        streams = defaultdict(StreamDecoder)
        frames = []
        for _, sent, _, data in iter_records(path):
            for acl, opcode, attr, value in att_pdus(datalink, data):
                # This session's documented target: ACL 0x0202, notify 0x000e.
                if sent or opcode not in (ATT_NOTIFY, ATT_INDICATE):
                    continue
                if (acl, attr) != (0x0202, 0x000e):
                    continue
                frames.extend(streams[(acl, attr)].feed(
                    bytes(byte ^ 0x55 for byte in value)
                ))
        self.assertEqual(len(frames), 2573)
        self.assertEqual(sum(len(d.rejected) for d in streams.values()), 0)
        values = [battery_value(self.profile, f) for f in frames]
        self.assertEqual(Counter(v for v in values if v is not None), {83: 2368})
        self.assertEqual(values.count(None), 205)  # 0xbc sentinel, not battery.

    def test_oct20_derived_payloads_replay_99_percent(self):
        # The original matching raw HCI file is not available in this workspace.
        # chk_ok is historical metadata, NOT a checksum independently rerun here.
        path = ROOT / 'captures/20261020/frames.jsonl'
        records = [json.loads(line) for line in path.read_text().splitlines()]
        self.assertEqual(len(records), 4032)
        values = []
        for record in records:
            self.assertIs(record['chk_ok'], True)
            frame = Frame(
                cmd=int(record['cmd'], 16), a1=int(record['a1'], 16),
                a2=int(record['a2'], 16),
                payload=bytes.fromhex(record['payload_hex']),
            )
            value = battery_value(self.profile, frame)
            if value is not None:
                values.append(value)
        self.assertEqual(values[0], 99)
        self.assertEqual(Counter(values), {99: 44, 95: 804, 90: 170, 89: 92, 88: 706})

    def test_e63_raw_replays_79_percent_and_snapshot_counts_match(self):
        evidence = json.loads((E63 / f'evidence_{E63_SUFFIX}.json').read_text())
        records = [json.loads(line) for line in
                   (E63 / f'raw_{E63_SUFFIX}.jsonl').read_text().splitlines()]
        decoder = StreamDecoder()
        frames = []
        for record in records:
            self.assertEqual(record['direction'], 'notify')
            frames.extend(decoder.feed(
                bytes(byte ^ 0x55 for byte in bytes.fromhex(record['hex']))
            ))
        self.assertEqual(evidence['app_version'], '0.8.6 (14)')
        self.assertEqual(len(records), 177)
        self.assertEqual(len(records), evidence['raw_packets'])
        self.assertEqual(len(frames), 61)
        self.assertEqual(decoder.rejected, [])
        self.assertEqual(len(frames), evidence['frames_total'])
        self.assertEqual(len(frames), sum(g['frames'] for g in evidence['frame_groups']))
        self.assertEqual(len(frames), len(evidence['evidence_timeline']))
        query = evidence['read_query']
        self.assertEqual(sum(x['sent'] for x in query['sent_by_register']), 67)
        self.assertEqual(query['sent_this_session'], 67)
        values = [battery_value(self.profile, f) for f in frames]
        self.assertEqual(Counter(v for v in values if v is not None), {79: 58})
        self.assertEqual(values.count(None), 3)

    def test_queries_and_other_frame_groups_do_not_match(self):
        payload = bytearray(38)
        payload[28] = 83
        for group in [(0x0a, 1, 0x14), (0x0d, 2, 0x14), (0x0d, 1, 0xbc)]:
            with self.subTest(group=group):
                self.assertIsNone(battery_value(self.profile, Frame(*group, payload)))

    def test_short_payload_is_unknown_not_zero(self):
        for length in (0, 1, 28):
            with self.subTest(length=length):
                self.assertIsNone(battery_value(
                    self.profile, Frame(13, 1, 20, bytes(length))
                ))
        self.assertEqual(battery_value(self.profile, Frame(13, 1, 20, bytes(29))), 0)

    def test_unproven_fields_remain_unmapped(self):
        mapped = {field['field'] for field in self.profile['fields']}
        self.assertEqual(mapped, {'batteryPercent'})
        self.assertTrue(mapped.isdisjoint({
            'speed', 'batteryVoltage', 'temperature', 'current', 'odometer',
            'workMode', 'warningCode', 'errorCode', 'motorPower', 'pitch', 'roll',
        }))


if __name__ == '__main__':
    unittest.main(verbosity=2)
