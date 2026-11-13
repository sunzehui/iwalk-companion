#!/usr/bin/env python3
"""锁闸门判定器的合成数据回归：既要抓得住失效，也要拒绝在没证据时判通过。"""
from __future__ import annotations

import json
import os
import struct
import subprocess
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(HERE, '..', 'protocol'))
from ibwalk_frame import Frame  # noqa: E402

SPEED_OFF = 6
BOGUS_BASE_US = 63_960_000_000_000_000   # 真机日志里的时间戳就长这样，工具不许信它


def payload_for(speed_kmh: float) -> bytes:
    raw = int(round(speed_kmh / 0.1))
    p = bytearray(38)
    p[SPEED_OFF:SPEED_OFF + 2] = struct.pack('<h', max(-32768, min(32767, raw)))
    p[20] = 200          # 常量字段，凑近真机载荷形态
    p[28] = 69           # 电量
    return bytes(p)


def att_notify(handle: int, value: bytes) -> bytes:
    return bytes([0x1B]) + struct.pack('<H', handle) + value


def att_write(handle: int, value: bytes) -> bytes:
    return bytes([0x52]) + struct.pack('<H', handle) + value


def hci_record(rel_s: float, att: bytes) -> bytes:
    l2cap = struct.pack('<HH', len(att), 0x0004) + att
    acl = struct.pack('<HH', 0x0040, len(l2cap)) + l2cap
    packet = b'\x02' + acl
    ts = BOGUS_BASE_US + int(rel_s * 1_000_000)
    return struct.pack('>IIIIq', len(packet), len(packet), 0x0100, 0, ts) + packet


def write_log(path, segments, writes=(), step=0.11, cmd=0x0A):
    """segments: [(start_s, end_s, speed_kmh)]；writes: [(t, kind)]。"""
    events = []
    t = 0.0
    while segments and t <= segments[-1][1]:
        speed = 0.0
        for a, b, v in segments:
            if a <= t < b:
                speed = v
        events.append((round(t, 3), att_notify(0x000F, (bytes(b ^ 0x55 for b in Frame(
            cmd=cmd, a1=0x01, a2=0x14, payload=payload_for(speed)).encode())[:20]))))
        # 剩余分片按真机节奏紧随其后（同一毫秒内），保证 20 B 重组路径被走到
        wire = bytes(b ^ 0x55 for b in Frame(
            cmd=cmd, a1=0x01, a2=0x14, payload=payload_for(speed)).encode())
        for off in range(20, len(wire), 20):
            events.append((round(t, 3), att_notify(0x000F, wire[off:off + 20])))
        t += step
    for t_w, kind in writes:
        if kind == 'garbage':
            value = bytes(range(0x10, 0x24))
        elif kind == 'cccd':
            events.append((round(t_w, 3), att_write(0x000F, b'\x01\x00')))
            continue
        else:
            tri = {'lock': (0x0A, 0x03, 0x70), 'unlock': (0x0A, 0x03, 0x71),
                   'query': (0x0A, 0x01, 0x14)}[kind]
            value = bytes(b ^ 0x55 for b in Frame(cmd=tri[0], a1=tri[1], a2=tri[2],
                                                  payload=b'\x26' if tri[1] == 0x01 else b'\x01\x00').encode())
        events.append((round(t_w, 3), att_write(0x000E, value)))
    events.sort(key=lambda e: e[0])
    with open(path, 'wb') as fh:
        fh.write(b'btsnoop\x00' + struct.pack('>II', 1, 1002))
        for rel, att in events:
            fh.write(hci_record(rel, att))


def write_calibration(path, scale=0.1, offset=SPEED_OFF):
    doc = {'profiles': [{
        'name': 'test-speed-v1', 'cmd': 10, 'a1': 1, 'a2': 20, 'evidence': '',
        'fields': [{'field': 'speed', 'offset': offset, 'type': 'i16le', 'scale': scale, 'bias': 0}],
    }]}
    with open(path, 'w', encoding='utf-8') as fh:
        json.dump(doc, fh, ensure_ascii=False)


class LockGateCheckTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix='lockgate_')
        self.log = os.path.join(self.tmp, 'btsnoop_hci.log')
        self.cal = os.path.join(self.tmp, 'cal.json')
        write_calibration(self.cal)

    def run_tool(self, *args):
        return subprocess.run(
            [sys.executable, os.path.join(HERE, 'lock_gate_check.py'), *args],
            capture_output=True, text=True, timeout=120)

    def test_passes_when_lock_frames_only_leave_at_standstill(self):
        # 0–6 静止 → 6–14 推行 3.5 → 14–26 静止；关锁 18、解锁 20
        write_log(self.log, [(0, 6, 0.0), (6, 14, 3.5), (14, 26, 0.0)],
                  writes=[(18.0, 'lock'), (20.0, 'unlock')])
        r = self.run_tool('--btsnoop', self.log, '--calibration', self.cal,
                          '--json', os.path.join(self.tmp, 'out.json'))
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        with open(os.path.join(self.tmp, 'out.json'), encoding='utf-8') as fh:
            doc = json.load(fh)
        self.assertTrue(doc['b_pass'] and doc['c_pass'])
        self.assertEqual(doc['counts']['lock'], 1)
        self.assertEqual(doc['counts']['unlock'], 1)
        self.assertEqual(len(doc['moving_windows']), 1)

    def test_fails_when_a_lock_frame_leaves_during_motion(self):
        # 这是闸门失效的唯一可靠证据：运动中写出了锁帧。工具必须抓住，不能四舍五入成通过。
        write_log(self.log, [(0, 6, 0.0), (6, 14, 3.5), (14, 26, 0.0)],
                  writes=[(9.0, 'lock'), (20.0, 'unlock')])
        r = self.run_tool('--btsnoop', self.log, '--calibration', self.cal)
        self.assertEqual(r.returncode, 1, r.stdout + r.stderr)
        self.assertIn('闸门失效', r.stdout)
        self.assertIn('第 1 段运动窗口', r.stdout)

    def test_negative_speed_still_counts_as_motion(self):
        # 标定 bias 可能让倒推呈现负值；App 侧闸门用的是 abs(speed) > 1.0，
        # 判定器若按有符号比较，就会把「倒着推」当成静止，正好放过最该拦的一幕。
        write_log(self.log, [(0, 6, 0.0), (6, 14, -3.5), (14, 26, 0.0)],
                  writes=[(18.0, 'lock'), (20.0, 'unlock')])
        r = self.run_tool('--btsnoop', self.log, '--calibration', self.cal,
                          '--json', os.path.join(self.tmp, 'out.json'))
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        with open(os.path.join(self.tmp, 'out.json'), encoding='utf-8') as fh:
            self.assertEqual(len(json.load(fh)['moving_windows']), 1)

    def test_fails_when_session_never_moved_because_c_was_not_tested(self):
        # 全程静止：B 可以过，但 C 一点证据都没有。判通过就等于把「没检验」当「通过」。
        write_log(self.log, [(0, 20, 0.0)], writes=[(10.0, 'lock'), (12.0, 'unlock')])
        r = self.run_tool('--btsnoop', self.log, '--calibration', self.cal)
        self.assertEqual(r.returncode, 1, r.stdout + r.stderr)
        self.assertIn('C 没有被检验', r.stdout)
        self.assertIn('C 未检验', r.stdout)

    def test_fails_when_short_blip_does_not_count_as_motion(self):
        # 0.5 s 的尖峰（1 帧超阈值）不足以当一次推行，否则「窗口里没写」变成侥幸通过。
        write_log(self.log, [(0, 8, 0.0), (8.0, 8.12, 2.0), (8.12, 20, 0.0)],
                  writes=[(14.0, 'lock'), (16.0, 'unlock')])
        r = self.run_tool('--btsnoop', self.log, '--calibration', self.cal)
        self.assertEqual(r.returncode, 1, r.stdout + r.stderr)
        self.assertIn('门槛', r.stdout)

    def test_fails_when_unlock_count_differs(self):
        write_log(self.log, [(0, 6, 0.0), (6, 12, 3.0), (12, 26, 0.0)],
                  writes=[(16.0, 'lock'), (18.0, 'unlock'), (19.0, 'unlock')])
        r = self.run_tool('--btsnoop', self.log, '--calibration', self.cal)
        self.assertEqual(r.returncode, 1, r.stdout + r.stderr)
        self.assertIn('解锁写出 2 次，期望 1 次', r.stdout)

    def test_cccd_subscribe_is_not_mistaken_for_garbage(self):
        # 2 字节的 CCCD 写在每条真机日志里都有一条（真机实测 4/4），它必须先被认出来，
        # 否则「解不出帧」这条判断会在每个正常会话里误报。
        write_log(self.log, [(0, 6, 0.0), (6, 14, 3.5), (14, 26, 0.0)],
                  writes=[(1.0, 'cccd'), (18.0, 'lock'), (20.0, 'unlock')])
        r = self.run_tool('--btsnoop', self.log, '--calibration', self.cal,
                          '--json', os.path.join(self.tmp, 'out.json'))
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        with open(os.path.join(self.tmp, 'out.json'), encoding='utf-8') as fh:
            self.assertEqual(json.load(fh)['counts']['cccd'], 1)

    def test_fails_when_a_write_cannot_be_decoded(self):
        # 解不出来的写不能当成「不是锁帧」放过——那正是闸门失效时最可能出现的样子。
        write_log(self.log, [(0, 6, 0.0), (6, 12, 3.0), (12, 26, 0.0)],
                  writes=[(16.0, 'lock'), (18.0, 'unlock'), (9.5, 'garbage')])
        r = self.run_tool('--btsnoop', self.log, '--calibration', self.cal)
        self.assertEqual(r.returncode, 1, r.stdout + r.stderr)
        self.assertIn('解不出帧', r.stdout)

    def test_refuses_without_calibration(self):
        write_log(self.log, [(0, 6, 0.0), (6, 12, 3.0)], writes=[(16.0, 'lock')])
        bare = os.path.join(self.tmp, 'bare.json')
        with open(bare, 'w', encoding='utf-8') as fh:
            json.dump({'profiles': [{'name': 'x', 'fields': [
                {'field': 'batteryPercent', 'offset': 28, 'type': 'u8', 'scale': 1, 'bias': 0}]}]}, fh)
        r = self.run_tool('--btsnoop', self.log, '--calibration', bare)
        self.assertEqual(r.returncode, 2)
        self.assertIn('速度还没标定', r.stderr)

    def test_refuses_implausible_speed_series(self):
        # 倍率错 50 倍时会把 3.5 km/h 算成 175 km/h；这种「到处都是运动」不能拿来做拦截证据。
        write_log(self.log, [(0, 6, 0.0), (6, 12, 3.0), (12, 26, 0.0)],
                  writes=[(16.0, 'lock'), (18.0, 'unlock')])
        wrong = os.path.join(self.tmp, 'wrong.json')
        write_calibration(wrong, scale=5.0)
        r = self.run_tool('--btsnoop', self.log, '--calibration', wrong)
        self.assertEqual(r.returncode, 2)
        self.assertIn('拒绝下结论', r.stderr)

    def test_refuses_app_export_without_writes(self):
        # raw 导出只有通知，判断不了「有没有写出锁帧」，必须挡回去而不是给个空通过。
        raw = os.path.join(self.tmp, 'raw_ble-1.jsonl')
        with open(raw, 'w', encoding='utf-8') as fh:
            fh.write('{"ts":"2026-11-12T20:00:00+08:00","hex":"00ff"}\n')
        r = self.run_tool('--btsnoop', raw, '--calibration', self.cal)
        self.assertEqual(r.returncode, 2)
        self.assertIn('只有通知', r.stderr)

    def test_report_states_what_it_cannot_prove(self):
        write_log(self.log, [(0, 6, 0.0), (6, 14, 3.5), (14, 26, 0.0)],
                  writes=[(18.0, 'lock'), (20.0, 'unlock')])
        report = os.path.join(self.tmp, 'report.md')
        r = self.run_tool('--btsnoop', self.log, '--calibration', self.cal, '--report', report)
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        with open(report, encoding='utf-8') as fh:
            text = fh.read()
        self.assertIn('当时确实滑了滑块', text)
        self.assertIn('commands_sent', text)


if __name__ == '__main__':
    unittest.main(verbosity=2)
