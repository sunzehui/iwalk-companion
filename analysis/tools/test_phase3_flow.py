#!/usr/bin/env python3
"""阶段三串练：标定工具的输出，必须能被锁闸门判定器直接吃下去。

两个工具是分开写的，但当天是连着跑的：
  受控采集 → speed_calibration.py 出 ω 标定 → 锁实验采集 → lock_gate_check.py 用这份标定判 B/C
中间只要有一处对不上（帧组、字段类型、单位、profile 结构），当场就白跑一次真车。
这组用例把这条链在合成数据上跑通，并用「应该失败的场景」证明它不是只会说 OK：

  1. 标定工具用文档里那串参数跑 → 退出 0、写出含 speed 的 profile（电量映射保留）
  2. 判定器直接用那份文件 → 运动中零锁帧 → 退出 0
  3. 把锁帧挪进运动窗口 → 退出 1 且文案是「闸门失效」
  4. 采集里没出现 >1 km/h 的时段 → 退出 1 且文案是「C 没有被检验」
  5. 标定文件被改坏（帧组错） → 判定器退出 2，而不是拿错标定硬判
"""
from __future__ import annotations

import json
import os
import subprocess
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import test_lock_gate_check as T  # noqa: E402

T0 = '2026-11-20T15:00:00+0800'
DURATION = 122.0
# 收尾必须有静止段：锁车只能在停稳后做，若最后一段还在动，B 永远过不了（第一版就踩了这个）
SEGMENTS = [(0, 20, 0.0), (20, 45, 6.0), (45, 60, 9.5), (60, 80, 0.0), (80, 100, 11.0),
            (100, 110, 7.5), (110, DURATION, 0.0)]
OBS = [
    (10.0, 'stationary', 0.0),
    (30.0, 'moving', 6.0),
    (52.0, 'moving', 9.5),
    (70.0, 'stopped', 0.0),
    (90.0, 'repeat', 11.0),
    (105.0, 'repeat', 7.5),
]


def write_observations(path, rows=OBS, uncertainty_ms=2000):
    with open(path, 'w', encoding='utf-8') as fh:
        fh.write('video_time_s,phone_wall_time_iso8601,phase,official_speed_kmh,'
                 'official_battery_percent,observation,alignment_uncertainty_ms\n')
        for t, phase, v in rows:
            fh.write(f'{t:.1f},,{phase},{v:.1f},,,{uncertainty_ms}\n')


def base_calibration(path):
    """与 downloads/iwk1779887-calibration-partial-v1.json 同构的最小版本。"""
    doc = {'profiles': [{
        'name': 'iWK1779887-battery-only-v1', 'cmd': 13, 'a1': 1, 'a2': 20,
        'evidence': '仅映射电量；速度、电压、温度及其他字段不映射。',
        'fields': [{'field': 'batteryPercent', 'offset': 28, 'type': 'u8', 'scale': 1, 'bias': 0}],
    }]}
    with open(path, 'w', encoding='utf-8') as fh:
        json.dump(doc, fh, ensure_ascii=False)


class Phase3FlowTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix='phase3_')
        self.log = os.path.join(self.tmp, 'btsnoop_hci.log')
        self.obs = os.path.join(self.tmp, 'speed_observations.csv')
        self.base = os.path.join(self.tmp, 'base.json')
        self.emit = os.path.join(self.tmp, 'speed-v1.json')
        write_observations(self.obs)
        base_calibration(self.base)

    def calibrate(self):
        return subprocess.run([
            sys.executable, os.path.join(HERE, 'speed_calibration.py'),
            '--btsnoop', self.log,
            '--observations', self.obs,
            '--video-anchor', T0,
            '--hci-t1', '2026-11-20T15:02:02+0800',
            '--report', os.path.join(self.tmp, 'calibration_report.md'),
            '--json', os.path.join(self.tmp, 'calibration_stats.json'),
            '--base-calibration', self.base,
            '--emit', self.emit,
        ], capture_output=True, text=True, timeout=180)

    def gate(self, calibration=None, log=None):
        return subprocess.run([
            sys.executable, os.path.join(HERE, 'lock_gate_check.py'),
            '--btsnoop', log or self.log,
            '--calibration', calibration or self.emit,
            '--json', os.path.join(self.tmp, 'gate.json'),
        ], capture_output=True, text=True, timeout=180)

    def test_calibration_output_feeds_the_gate_checker(self):
        # 一次采集里既有移动（标定用）也有静止锁车（B 用），是当天最省事的采集方式。
        T.write_log(self.log, SEGMENTS, writes=[(114.0, 'lock'), (117.0, 'unlock')], cmd=0x0D)
        r = self.calibrate()
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        with open(self.emit, encoding='utf-8') as fh:
            prof = json.load(fh)['profiles'][0]
        fields = {f['field']: f for f in prof['fields']}
        self.assertEqual(set(fields), {'batteryPercent', 'speed'})
        self.assertEqual((fields['speed']['offset'], fields['speed']['scale']), (6, 0.1))

        g = self.gate()
        self.assertEqual(g.returncode, 0, g.stdout + g.stderr)
        with open(os.path.join(self.tmp, 'gate.json'), encoding='utf-8') as fh:
            doc = json.load(fh)
        self.assertTrue(doc['b_pass'] and doc['c_pass'])
        self.assertEqual(doc['group'], [13, 1, 20])

    def test_gate_checker_catches_a_frame_sent_while_moving(self):
        T.write_log(self.log, SEGMENTS, writes=[(30.0, 'lock'), (117.0, 'unlock')], cmd=0x0D)
        r = self.calibrate()
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        g = self.gate()
        self.assertEqual(g.returncode, 1, g.stdout + g.stderr)
        self.assertIn('闸门失效', g.stdout)

    def test_gate_checker_refuses_to_call_a_session_without_motion_a_pass(self):
        # 真实流程就是这样：标定来自移动采集，锁实验另开一段（全程静止）。
        # 于是判定器必须在「没有运动窗口」时说「C 未被检验」，而不是看到锁帧各 1 次就放行。
        T.write_log(self.log, SEGMENTS, writes=[], cmd=0x0D)
        r = self.calibrate()
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)

        stationary = os.path.join(self.tmp, 'btsnoop_gate.log')
        T.write_log(stationary, [(0, 40, 0.0)], writes=[(20.0, 'lock'), (25.0, 'unlock')], cmd=0x0D)
        g = self.gate(log=stationary)
        self.assertEqual(g.returncode, 1, g.stdout + g.stderr)
        self.assertIn('C 没有被检验', g.stdout)
        with open(os.path.join(self.tmp, 'gate.json'), encoding='utf-8') as fh:
            doc = json.load(fh)
        self.assertTrue(doc['b_pass'])       # 静止放行这半边是成立的
        self.assertFalse(doc['c_pass'])      # 拦截那半边没有证据

    def test_gate_checker_refuses_a_mismatched_calibration(self):
        T.write_log(self.log, SEGMENTS, writes=[(114.0, 'lock'), (117.0, 'unlock')], cmd=0x0D)
        r = self.calibrate()
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        with open(self.emit, encoding='utf-8') as fh:
            doc = json.load(fh)
        doc['profiles'][0]['cmd'] = 0x1A          # 换成诊断应答组，日志里没有
        wrong = os.path.join(self.tmp, 'wrong.json')
        with open(wrong, 'w', encoding='utf-8') as fh:
            json.dump(doc, fh)
        g = self.gate(wrong)
        self.assertEqual(g.returncode, 2, g.stdout + g.stderr)
        self.assertIn('标定文件与本次会话对不上', g.stderr)


if __name__ == '__main__':
    unittest.main(verbosity=2)
