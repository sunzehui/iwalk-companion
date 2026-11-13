#!/usr/bin/env python3
"""speed_calibration.py 的合成数据回归。

合成数据的好处是「真值已知」：只有把速度写进某个偏移，工具才应该选中它；
而对于只有零读数、缺重复段、或两个偏移完全重复的情况，工具必须拒绝出标定。
这些用例用假数据锁住判定口径，真车数据回来后才敢信它的结论。
"""
import csv
import json
import os
import subprocess
import sys
import tempfile
import unittest
from datetime import datetime, timedelta, timezone

TOOLS = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(os.path.dirname(TOOLS))
SCRIPT = os.path.join(TOOLS, 'speed_calibration.py')
TZ = timezone(timedelta(hours=8))
T0 = datetime(2026, 11, 20, 15, 0, 0, tzinfo=TZ)

SN = b'024338000000'


def speed_profile(sec: float) -> float:
    """0→12→0（训练），再 0→9→0（repeat）。"""
    if sec < 20:
        return 0.0
    if sec < 25:
        return (sec - 20) / 5 * 12
    if sec < 35:
        return 12.0
    if sec < 40:
        return 12 - (sec - 35) / 5 * 12
    if sec < 60:
        return 0.0
    if sec < 65:
        return (sec - 60) / 5 * 9
    if sec < 75:
        return 9.0
    if sec < 80:
        return 9 - (sec - 75) / 5 * 9
    return 0.0


def payload_for(i: int, sec: float, speed_offsets=(6,)) -> bytes:
    p = bytearray(38)
    p[0:12] = SN
    p[12:16] = (6881).to_bytes(4, 'little')
    p[5] = 0x01                      # 常量：制造 off5 跨字节伪解
    p[18:20] = b'\xc8\x18'           # 电压候选，恒定
    p[20] = 0x22
    raw = int(round(speed_profile(sec) * 10))
    for off in speed_offsets:
        p[off:off + 2] = raw.to_bytes(2, 'little', signed=True)
    # off26 高频抖动但与速度无关：验证工具不会去挑「变化最大的字节」
    p[26:28] = (60 + (i * 37) % 80).to_bytes(2, 'little')
    return bytes(p)


def write_decoded_frames(path, duration=100.0, step=0.11, speed_offsets=(6,)):
    with open(path, 'w', encoding='utf-8') as fh:
        i = 0
        sec = 0.0
        while sec <= duration:
            fh.write(json.dumps({
                't_sec': round(sec, 3),
                'cmd': '0x0d', 'a1': '0x01', 'a2': '0x14',
                'payload_hex': payload_for(i, sec, speed_offsets).hex(),
                'chk_ok': True,
            }) + '\n')
            i += 1
            sec += step


def write_raw_frames(path, duration=100.0, step=0.11, chunk=20):
    """app 导出 raw jsonl：线上 XOR 0x55、20 B 分包、绝对时间戳。"""
    sys.path.insert(0, os.path.join(TOOLS, '..', 'protocol'))
    from ibwalk_frame import Frame

    with open(path, 'w', encoding='utf-8') as fh:
        i = 0
        sec = 0.0
        while sec <= duration:
            frame = Frame(cmd=0x0D, a1=0x01, a2=0x14, payload=payload_for(i, sec)).encode()
            wire = bytes(b ^ 0x55 for b in frame)
            ts = T0 + timedelta(seconds=sec)
            for start in range(0, len(wire), chunk):
                fh.write(json.dumps({
                    'ts': ts.strftime('%Y-%m-%dT%H:%M:%S.%f'),
                    'device': 'EE:F6:9B:2C:98:42',
                    'direction': 'notify',
                    'hex': wire[start:start + chunk].hex(),
                }) + '\n')
            i += 1
            sec += step


def write_btsnoop(path, duration=100.0, step=0.11, chunk=20):
    """手机侧 btsnoop：ACL 收到方向 + XOR-0x55 + 20 B 分片 + 不可信的绝对时间戳。

    时间戳刻意用一个明显偏离真实年分的值（模拟真机日志里那个恒定 378 天偏移），
    用来锁定「工具只用相对间隔、必须靠 --hci-t0/--hci-t1 锚定」这条约定。
    """
    import struct
    sys.path.insert(0, os.path.join(TOOLS, '..', 'protocol'))
    from ibwalk_frame import Frame

    bogus_base_us = 63_960_000_000_000_000  # 与真实墙钟差着好几年，工具不许信它
    recs = []
    i = 0
    sec = 0.0
    while sec <= duration:
        frame = Frame(cmd=0x0D, a1=0x01, a2=0x14, payload=payload_for(i, sec)).encode()
        wire = bytes(b ^ 0x55 for b in frame)
        ts = bogus_base_us + int(sec * 1_000_000)
        for start in range(0, len(wire), chunk):
            att = bytes([0x1B]) + struct.pack('<H', 0x000E) + wire[start:start + chunk]
            l2cap = struct.pack('<HH', len(att), 0x0004) + att
            acl = struct.pack('<HH', 0x0040, len(l2cap)) + l2cap
            packet = b'\x02' + acl
            recs.append(struct.pack('>IIIIq', len(packet), len(packet), 0x0100, 0, ts) + packet)
        i += 1
        sec += step
    with open(path, 'wb') as fh:
        fh.write(b'btsnoop\x00' + struct.pack('>II', 1, 1002))
        for r in recs:
            fh.write(r)


OBS = [
    (8.0, 'stationary', 0.0), (12.0, 'stationary', 0.0), (16.0, 'stationary', 0.0),
    (21.5, 'moving', None), (23.0, 'moving', None), (26.0, 'moving', None), (33.0, 'moving', None),
    (44.0, 'stopped', 0.0), (50.0, 'stopped', 0.0),
    (61.0, 'repeat', None), (66.0, 'repeat', None), (70.0, 'repeat', None), (82.0, 'repeat', 0.0),
]

HEADER = ['video_time_s', 'phone_wall_time_iso8601', 'phase', 'official_speed_kmh',
          'official_battery_percent', 'observation', 'alignment_uncertainty_ms']


def write_observations(path, rows=OBS, wall_time=False, uncertainty_ms=0):
    with open(path, 'w', encoding='utf-8', newline='') as fh:
        w = csv.writer(fh)
        w.writerow(HEADER)
        for sec, phase, speed in rows:
            v = speed_profile(sec) if speed is None else speed
            wall = (T0 + timedelta(seconds=sec)).strftime('%Y-%m-%dT%H:%M:%S.%f') if wall_time else ''
            w.writerow([f'{sec:.1f}', wall, phase, f'{round(v, 1):.1f}', '', '', uncertainty_ms])


class SpeedCalibrationTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix='speedcal_')
        self.frames = os.path.join(self.tmp, 'frames.jsonl')
        self.obs = os.path.join(self.tmp, 'obs.csv')
        self.out = os.path.join(self.tmp, 'cal.json')

    def run_tool(self, *extra):
        return subprocess.run(
            [sys.executable, SCRIPT, '--observations', self.obs, *extra],
            capture_output=True, text=True,
        )

    def test_selects_injected_offset_from_decoded_frames(self):
        write_decoded_frames(self.frames)
        write_observations(self.obs)
        r = self.run_tool('--frames', self.frames,
                          '--t0', T0.strftime('%Y-%m-%dT%H:%M:%S%z'),
                          '--video-anchor', T0.strftime('%Y-%m-%dT%H:%M:%S%z'),
                          '--emit', self.out)
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        with open(self.out, encoding='utf-8') as fh:
            cal = json.load(fh)
        f = cal['profiles'][0]['fields']
        self.assertEqual(len(f), 1)
        self.assertEqual(f[0]['field'], 'speed')
        self.assertEqual(f[0]['offset'], 6)
        self.assertEqual(f[0]['scale'], 0.1)
        self.assertAlmostEqual(f[0]['bias'], 0.0, places=6)
        self.assertEqual(cal['profiles'][0]['cmd'], 13)

    def test_raw_xor55_frames_are_decoded_and_aligned(self):
        raw = os.path.join(self.tmp, 'raw.jsonl')
        write_raw_frames(raw)
        write_observations(self.obs)
        r = self.run_tool('--frames', raw,
                          '--video-anchor', T0.strftime('%Y-%m-%dT%H:%M:%S%z'),
                          '--emit', self.out)
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        with open(self.out, encoding='utf-8') as fh:
            cal = json.load(fh)
        self.assertEqual(cal['profiles'][0]['fields'][0]['offset'], 6)

    def test_wall_clock_rows_need_no_anchor(self):
        write_decoded_frames(self.frames)
        write_observations(self.obs, wall_time=True)
        r = self.run_tool('--frames', self.frames,
                          '--t0', T0.strftime('%Y-%m-%dT%H:%M:%S%z'))
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)

    def test_widens_window_for_known_alignment_error(self):
        # 用录屏文件创建时间做锚点时，真实误差在 1 s 量级：采样窗必须跟着放宽，
        # 否则「对齐误差」会被当成「拟合残差」。
        write_decoded_frames(self.frames)
        write_observations(self.obs, uncertainty_ms=800)
        report = os.path.join(self.tmp, 'report.md')
        r = self.run_tool('--frames', self.frames,
                          '--t0', T0.strftime('%Y-%m-%dT%H:%M:%S%z'),
                          '--video-anchor', T0.strftime('%Y-%m-%dT%H:%M:%S%z'),
                          '--report', report)
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        self.assertIn('放宽到 ±800 ms', r.stderr)
        with open(report, encoding='utf-8') as fh:
            self.assertIn('±800 ms', fh.read())

    def test_refuses_when_alignment_error_absurd(self):
        write_decoded_frames(self.frames)
        write_observations(self.obs, uncertainty_ms=9000)
        r = self.run_tool('--frames', self.frames,
                          '--t0', T0.strftime('%Y-%m-%dT%H:%M:%S%z'),
                          '--video-anchor', T0.strftime('%Y-%m-%dT%H:%M:%S%z'))
        self.assertEqual(r.returncode, 2)
        self.assertIn('对齐不确定性', r.stderr)

    def test_refuses_when_no_anchor(self):
        write_decoded_frames(self.frames)
        write_observations(self.obs)
        r = self.run_tool('--frames', self.frames,
                          '--t0', T0.strftime('%Y-%m-%dT%H:%M:%S%z'))
        self.assertEqual(r.returncode, 2)
        self.assertIn('没有可用行', r.stderr)

    def test_refuses_when_only_zero_readings(self):
        write_decoded_frames(self.frames)
        only_zero = [(s, 'stationary', 0.0) for s in (8.0, 12.0, 16.0, 44.0, 50.0)]
        write_observations(self.obs, rows=only_zero)
        r = self.run_tool('--frames', self.frames,
                          '--t0', T0.strftime('%Y-%m-%dT%H:%M:%S%z'),
                          '--video-anchor', T0.strftime('%Y-%m-%dT%H:%M:%S%z'),
                          '--emit', self.out)
        self.assertEqual(r.returncode, 2)
        self.assertIn('可区分的非零读数', r.stderr)
        self.assertFalse(os.path.exists(self.out))

    def test_refuses_without_repeat_segment(self):
        write_decoded_frames(self.frames)
        no_repeat = [row for row in OBS if row[1] != 'repeat']
        write_observations(self.obs, rows=no_repeat)
        r = self.run_tool('--frames', self.frames,
                          '--t0', T0.strftime('%Y-%m-%dT%H:%M:%S%z'),
                          '--video-anchor', T0.strftime('%Y-%m-%dT%H:%M:%S%z'),
                          '--emit', self.out)
        self.assertEqual(r.returncode, 1, r.stdout + r.stderr)
        self.assertIn('缺独立重复段', r.stdout)
        self.assertFalse(os.path.exists(self.out))

    def test_reports_ambiguity_when_two_offsets_identical(self):
        write_decoded_frames(self.frames, speed_offsets=(6, 30))
        write_observations(self.obs)
        r = self.run_tool('--frames', self.frames,
                          '--t0', T0.strftime('%Y-%m-%dT%H:%M:%S%z'),
                          '--video-anchor', T0.strftime('%Y-%m-%dT%H:%M:%S%z'),
                          '--emit', self.out)
        self.assertEqual(r.returncode, 1, r.stdout + r.stderr)
        self.assertIn('无法区分', r.stdout)
        self.assertFalse(os.path.exists(self.out))

    def test_merging_into_battery_profile_keeps_battery_and_drops_stale_claim(self):
        # 存量电量标定里写着「速度…不映射」；追加速度后那句话必须消失，
        # 否则同一份文件里既有速度映射又说速度没映射。
        base = os.path.join(self.tmp, 'base.json')
        with open(base, 'w', encoding='utf-8') as fh:
            json.dump({'profiles': [{
                'name': 'iWK1779887-battery-only-v1', 'cmd': 13, 'a1': 1, 'a2': 20,
                'evidence': '仅映射电量；速度、电压、温度及其他字段不映射。',
                'fields': [{'field': 'batteryPercent', 'offset': 28, 'type': 'u8', 'scale': 1, 'bias': 0}],
            }]}, fh, ensure_ascii=False)
        write_decoded_frames(self.frames)
        write_observations(self.obs)
        r = self.run_tool('--frames', self.frames,
                          '--t0', T0.strftime('%Y-%m-%dT%H:%M:%S%z'),
                          '--video-anchor', T0.strftime('%Y-%m-%dT%H:%M:%S%z'),
                          '--base-calibration', base, '--emit', self.out)
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        with open(self.out, encoding='utf-8') as fh:
            prof = json.load(fh)['profiles'][0]
        fields = {f['field']: f for f in prof['fields']}
        self.assertEqual(set(fields), {'batteryPercent', 'speed'})
        self.assertEqual(fields['batteryPercent']['offset'], 28)
        self.assertNotIn('速度、电压、温度', prof['evidence'])
        self.assertIn('电压、温度及其他字段不映射', prof['evidence'])
        self.assertIn('速度字段标定', prof['evidence'])

    def test_btsnoop_needs_an_explicit_wall_clock_anchor(self):
        raw = os.path.join(self.tmp, 'log.btsnoop')
        write_btsnoop(raw, duration=30.0)
        write_observations(self.obs)
        r = self.run_tool('--btsnoop', raw,
                          '--video-anchor', T0.strftime('%Y-%m-%dT%H:%M:%S%z'))
        self.assertEqual(r.returncode, 2)
        self.assertIn('绝对时间戳不可信', r.stderr)

    def test_btsnoop_anchored_at_last_record(self):
        # 骑行后立刻 pull：末条记录 ≈ 断开时刻，用它当锚点比首条更稳。
        raw = os.path.join(self.tmp, 'log.btsnoop')
        write_btsnoop(raw)
        write_observations(self.obs)
        end = T0 + timedelta(seconds=100.0)
        r = self.run_tool('--btsnoop', raw,
                          '--video-anchor', T0.strftime('%Y-%m-%dT%H:%M:%S%z'),
                          '--hci-t1', end.strftime('%Y-%m-%dT%H:%M:%S%z'),
                          '--emit', self.out)
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        with open(self.out, encoding='utf-8') as fh:
            self.assertEqual(json.load(fh)['profiles'][0]['fields'][0]['offset'], 6)

    def test_btsnoop_anchored_at_first_record(self):
        raw = os.path.join(self.tmp, 'log.btsnoop')
        write_btsnoop(raw)
        write_observations(self.obs)
        r = self.run_tool('--btsnoop', raw,
                          '--video-anchor', T0.strftime('%Y-%m-%dT%H:%M:%S%z'),
                          '--hci-t0', T0.strftime('%Y-%m-%dT%H:%M:%S%z'))
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)

    def test_app_fixture_matches_tool_output(self):
        """App 侧测试用的标定夹具必须是工具的产物，不许手改。

        `core/src/test/.../SpeedCalibrationContractTest.kt` 读的就是这份文件；如果只有
        Kotlin 端有测试、夹具却是手写的，那它证明的只是「两边想的一样」，证不了
        「工具真的吐出这个结构」。反过来只有 Python 端测试也不够——App 读不进去照样白跑。
        这里重新生成一份逐字段比对，任一侧改了 schema 都会红。
        需要刷新夹具时：`UPDATE_APP_FIXTURE=1 python3 analysis/tools/test_speed_calibration.py`
        """
        fixture = os.path.join(REPO, 'app_kotlin', 'testdata', 'iwk1779887-speed-v1.sample.json')
        base = os.path.join(REPO, 'downloads', 'iwk1779887-calibration-partial-v1.json')
        if not os.path.exists(base):
            self.skipTest('缺少 downloads/iwk1779887-calibration-partial-v1.json，无法比对')
        write_decoded_frames(self.frames)
        write_observations(self.obs)
        fresh = os.path.join(self.tmp, 'fresh.json')
        r = self.run_tool('--frames', self.frames,
                          '--t0', T0.strftime('%Y-%m-%dT%H:%M:%S%z'),
                          '--video-anchor', T0.strftime('%Y-%m-%dT%H:%M:%S%z'),
                          '--base-calibration', base, '--emit', fresh)
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        with open(fresh, encoding='utf-8') as fh:
            produced = json.load(fh)
        if os.environ.get('UPDATE_APP_FIXTURE') == '1':
            os.makedirs(os.path.dirname(fixture), exist_ok=True)
            with open(fixture, 'w', encoding='utf-8') as fh:
                fh.write(json.dumps(produced, ensure_ascii=False, indent=2) + '\n')
            self.skipTest(f'已刷新夹具 {fixture}')
        self.assertTrue(os.path.exists(fixture), f'缺少 App 侧夹具 {fixture}')
        with open(fixture, encoding='utf-8') as fh:
            committed = json.load(fh)
        self.assertEqual(produced, committed,
                         'App 侧标定夹具与工具当前输出不一致；确认改动是有意的，再用 UPDATE_APP_FIXTURE=1 刷新')

    def test_report_never_names_a_field_it_did_not_prove(self):
        write_decoded_frames(self.frames)
        write_observations(self.obs)
        report = os.path.join(self.tmp, 'report.md')
        r = self.run_tool('--frames', self.frames,
                          '--t0', T0.strftime('%Y-%m-%dT%H:%M:%S%z'),
                          '--video-anchor', T0.strftime('%Y-%m-%dT%H:%M:%S%z'),
                          '--report', report)
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        with open(report, encoding='utf-8') as fh:
            text = fh.read()
        self.assertIn('不是语义', text)
        self.assertIn('off6', text)


if __name__ == '__main__':
    unittest.main(verbosity=2)
