#!/usr/bin/env python3
"""readings_from_video.py 的端到端回归：合成一段「骑行录屏」，验证 OCR → CSV 全链路。

不需要真车：用 PIL 在 1080×2400 深底上画青色大数字（0.0 → 6.0 → 9.5 → 0.0 → 11.0），
ffmpeg 编成 mp4，配 markers.txt（设备端同款 `date +%s%3N` 格式），跑工具后逐条断言。
真机字体上的 OCR 已在 2026-10-21 截图（shot_03_baseline20s.png → 「0.0」）上单独验证过（E70）。
"""
import csv
import json
import os
import subprocess
import sys
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path

TOOLS = os.path.dirname(os.path.abspath(__file__))
SCRIPT = os.path.join(TOOLS, 'readings_from_video.py')
TZ = timezone(timedelta(hours=8))
START_MS = 1_794_500_000_000   # 任意固定设备钟时刻（≈2026-11-12）

FPS = 2
# (起始秒, 持续秒, 显示值)
PLAN = [(0, 15, '0.0'), (15, 15, '6.0'), (30, 5, '9.5'), (35, 10, '0.0'), (45, 15, '11.0')]
DURATION = 60
FRAME_COUNT = DURATION * FPS


def find_bold_font() -> str | None:
    for p in (
        '/System/Library/Fonts/Supplemental/Arial Bold.ttf',
        '/System/Library/Fonts/Supplemental/Arial.ttf',
        '/System/Library/Fonts/Helvetica.ttc',
        '/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf',
    ):
        if os.path.exists(p):
            return p
    return None


def draw_frame(value: str, font) -> 'Image.Image':
    from PIL import Image, ImageDraw
    im = Image.new('RGB', (1080, 2400), (18, 22, 26))
    d = ImageDraw.Draw(im)
    # 背景网格线：模拟真机的干扰纹理（OCR 必须无视它们）
    for x in range(0, 1080, 90):
        d.line([(x, 0), (x, 2400)], fill=(30, 38, 44), width=1)
    for y in range(0, 2400, 90):
        d.line([(0, y), (1080, y)], fill=(30, 38, 44), width=1)
    d.text((400, 330), value, fill=(0, 220, 235), font=font)   # 青色，落在默认裁剪区内
    return im


def encode_video(frames_dir: Path, out: Path):
    subprocess.run(['ffmpeg', '-v', 'error', '-y', '-framerate', str(FPS),
                    '-i', str(frames_dir / 'f_%06d.png'), '-pix_fmt', 'yuv420p',
                    str(out)], check=True)


def expected_rows():
    # 每个编码帧一条真值：帧 i 的显示时刻是 i/FPS 秒（与工具的墙钟换算同一口径）
    rows = []
    for i in range(FRAME_COUNT):
        t = i / FPS
        rows.append(next(pv for (s0, dur, pv) in PLAN if s0 <= t < s0 + dur))
    return rows


class ReadingsFromVideoTest(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        font_path = find_bold_font()
        if font_path is None:
            raise unittest.SkipTest('找不到可用的粗体 TTF，跳过 OCR 端到端测试')
        from PIL import ImageFont
        font = ImageFont.truetype(font_path, 150)
        cls.tmp = tempfile.mkdtemp(prefix='readings_')
        frames = Path(cls.tmp) / 'frames'
        frames.mkdir()
        for i, v in enumerate(expected_rows()):
            draw_frame(v, font).save(frames / f'f_{i:06d}.png')
        cls.video = Path(cls.tmp) / 'rec_1.mp4'
        encode_video(frames, cls.video)
        # markers 与 speed_sampler.sh 落盘格式逐字节同构；段时长 = 帧数 / fps
        dur_ms = int(FRAME_COUNT * 1000 / FPS)
        cls.markers = Path(cls.tmp) / 'markers.txt'
        cls.markers.write_text(
            f'REC 1 start {START_MS}\nREC 1 end {START_MS + dur_ms + 480}\n'
            f'LOOP end {START_MS + dur_ms + 500}\n', encoding='utf-8')
        cls.csv_path = str(Path(cls.tmp) / 'obs.csv')

    def run_tool(self, *extra):
        return subprocess.run(
            [sys.executable, SCRIPT, '--video', self.video, '--markers', self.markers,
             '--csv', self.csv_path, *extra], capture_output=True, text=True)

    def test_ocr_reads_every_frame_and_values_match_truth(self):
        r = self.run_tool()
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        with open(self.csv_path, encoding='utf-8') as fh:
            rows = list(csv.DictReader(fh))
        truth = [float(v) for v in expected_rows()]
        self.assertEqual(len(rows), len(truth), f'行数 {len(rows)} ≠ 帧数 {len(truth)}')
        got = [float(x['official_speed_kmh']) for x in rows]
        for i, (g, e) in enumerate(zip(got, truth)):
            self.assertEqual(g, e, f'第 {i} 帧读数 {g} ≠ 真值 {e}')
        # 墙钟 = 段 start + 帧序/fps；首帧必须落在 start 之后 0.5 s 内
        first = datetime.strptime(rows[0]['phone_wall_time_iso8601'][:26], '%Y-%m-%dT%H:%M:%S.%f')
        self.assertLessEqual(first.timestamp() * 1000 - START_MS, 500)
        # 末帧 ≈ start + 段时长，墙钟跨度必须覆盖整个合成「骑行」
        last = datetime.strptime(rows[-1]['phone_wall_time_iso8601'][:26], '%Y-%m-%dT%H:%M:%S.%f')
        self.assertGreaterEqual(last.timestamp() * 1000 - START_MS, FRAME_COUNT * 1000 / FPS - 500)
        # 相位自动标注
        self.assertEqual(rows[0]['phase'], 'stationary')    # t=0s, 0.0
        self.assertEqual(rows[40]['phase'], 'moving')       # t=20s, 6.0
        self.assertEqual(rows[0]['alignment_uncertainty_ms'], '1000')
        self.assertEqual(rows[0]['observation'], 'ocr')

    def test_repeat_from_marks_second_ride_segment(self):
        # 第二段骑行（45 s 起，11.0）应标为 repeat；工具必须在墙钟分界上标，不靠猜
        mark = datetime.fromtimestamp((START_MS + 45 * 1000) / 1000, tz=TZ)
        r = self.run_tool('--repeat-from', mark.strftime('%Y-%m-%dT%H:%M:%S%z'))
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        with open(self.csv_path, encoding='utf-8') as fh:
            rows = list(csv.DictReader(fh))
        rep = [x for x in rows if x['phase'] == 'repeat']
        self.assertTrue(rep, 'repeat 行为空')
        self.assertTrue(all(float(x['official_speed_kmh']) == 11.0 for x in rep))
        self.assertTrue(all(x['phone_wall_time_iso8601'] >= mark.strftime('%Y-%m-%dT%H:%M:%S')
                            for x in rep))

    def test_garbage_frames_are_skipped_not_faked(self):
        # 部分帧画干扰（模拟离开主界面/弹窗）：这些帧必须缺行，而不是拿 0 或上一个值顶替
        from PIL import ImageDraw
        import PIL.ImageFont as ImageFont
        font = ImageFont.truetype(find_bold_font(), 150)
        frames = Path(self.tmp) / 'noisy'
        frames.mkdir()
        truth = expected_rows()
        for i, v in enumerate(truth):
            im = draw_frame(v, font)
            if i % 7 == 3:                       # 每 7 帧损坏 1 帧：画满杂线盖掉数字
                d = ImageDraw.Draw(im)
                for k in range(40):
                    d.line([(k * 30, 300), (k * 30 + 20, 700)], fill=(0, 220, 235), width=9)
            im.save(frames / f'f_{i:06d}.png')
        video = Path(self.tmp) / 'rec_noise.mp4'
        encode_video(frames, video)
        r = subprocess.run(
            [sys.executable, SCRIPT, '--video', str(video), '--markers', self.markers,
             '--csv', str(Path(self.tmp) / 'noise.csv')], capture_output=True, text=True)
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        with open(Path(self.tmp) / 'noise.csv', encoding='utf-8') as fh:
            rows = list(csv.DictReader(fh))
        self.assertEqual(len(rows), len(truth) - len(truth[3::7]),
                         '坏帧必须整行缺失，不得用 0 顶替')

    def test_refuses_when_most_frames_unreadable(self):
        # 整段都在非主界面（连接页/弹窗）：不许硬出 CSV，提示先 --probe
        frames = Path(self.tmp) / 'blank'
        frames.mkdir()
        import PIL.ImageFont as ImageFont
        from PIL import ImageDraw
        font = ImageFont.truetype(find_bold_font(), 150)
        for i in range(10):
            im = draw_frame('0.0', font)
            d = ImageDraw.Draw(im)
            for k in range(40):   # 全部盖掉
                d.line([(k * 30, 300), (k * 30 + 20, 700)], fill=(0, 220, 235), width=9)
            im.save(frames / f'f_{i:06d}.png')
        video = Path(self.tmp) / 'rec_blank.mp4'
        encode_video(frames, video)
        r = subprocess.run(
            [sys.executable, SCRIPT, '--video', str(video), '--markers', self.markers,
             '--csv', str(Path(self.tmp) / 'blank.csv')], capture_output=True, text=True)
        self.assertEqual(r.returncode, 2)
        self.assertIn('--probe', r.stderr)

    def test_missing_marker_segment_is_rejected(self):
        r = subprocess.run(
            [sys.executable, SCRIPT, '--video', self.video, self.video,
             '--markers', self.markers, '--csv', str(Path(self.tmp) / 'x.csv')],
            capture_output=True, text=True)
        self.assertEqual(r.returncode, 2)
        self.assertIn('没有第 2 段', r.stderr)


if __name__ == '__main__':
    unittest.main()
