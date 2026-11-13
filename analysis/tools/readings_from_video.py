#!/usr/bin/env python3
"""从官方 App 录屏里 OCR 出带墙钟的速度读数，产出 speed_calibration.py 可直接吃的 CSV。

背景（E70）：官方 App `com.loby.balance.minirobot` 是 Cocos2d 渲染——uiautomator 只能看到
一个 SurfaceView，速度数字抓不到文本节点；但主界面的速度是巨大的青色数字
（如「0.0 Km/h」，约在整幅图的 x 25–75%、y 11–28%），OCR 可靠：
青色掩码（G>100 且 B>100 且 G>R+30）+ 数字白名单 + psm 7 在真机截图上读出 0.0。

输入：`speed_sampler.sh` 录下的分段 mp4 + 同目录 markers.txt（设备端 `date +%s%3N`
写在每段 start/end）。每帧墙钟 = 段 start + 帧序/fps，不再需要录屏 creation_time
锚点，对齐不确定性按 ±1000 ms 记（screenrecord 启动延迟 + 标记精度）。

用法：
  # 先用几帧肉眼确认裁剪与识别（屏幕布局变了就必须先看这个）
  python3 readings_from_video.py --video rec_1.mp4 --markers markers.txt --probe /tmp/probe
  # 正式出 CSV
  python3 readings_from_video.py --video rec_1.mp4 rec_2.mp4 --markers markers.txt \
      --csv speed_observations.csv
  # 第二段骑行读数要当独立检验段时，给墙钟分界：
  python3 readings_from_video.py ... --repeat-from 2026-11-12T21:30:00+08:00

退出码：0 正常；2 输入不可用（帧解析失败比例过高 / 找不到视频）。
"""
import argparse
import csv
import io
import os
import re
import subprocess
import sys
import tempfile
from datetime import datetime, timezone
from pathlib import Path

TOOLS = Path(__file__).resolve().parent
sys.path.insert(0, str(TOOLS))
from speed_calibration import local_tz, parse_time  # noqa: E402

# 速度大数字在整幅图中的位置（相对坐标，按 2026-10-21 真机截图实测）
CROP_DEFAULT = (0.25, 0.11, 0.75, 0.28)
SPEED_RE = re.compile(r'^(\d{1,3})\.(\d)$')
STATIONARY_BELOW = 0.5   # km/h；低于此视为静止，与闸门的 1.0 判据无关
DEFAULT_UNCERTAINTY_MS = 1000
MAX_FAIL_RATIO = 0.5     # 超过一半帧解析失败就别硬出结论


def cyan_mask(crop):
    """速度数字是青色：G、B 都高且明显高于 R。返回黑白掩码，黑底白字。"""
    from PIL import ImageChops
    r, g, b = crop.split()
    hi_g = g.point(lambda v: 255 if v > 100 else 0)
    hi_b = b.point(lambda v: 255 if v > 100 else 0)
    diff = ImageChops.subtract(g, r).point(lambda v: 255 if v > 30 else 0)
    return ImageChops.multiply(ImageChops.multiply(hi_g, hi_b), diff)


def ocr_digits(image) -> str:
    """tesseract 走 stdin：不要把图写到磁盘再传路径——在某些被 shim 包装的环境里，
    「当前进程刚写的文件」传给子进程时路径会被替换成文件内容，tesseract 收到的
    文件名变成 PNG 字节，静默返回空串（本仓库 2026-11-12 实测）。stdin 无此问题。"""
    buf = io.BytesIO()
    image.save(buf, format='PNG')
    proc = subprocess.run(
        ['tesseract', 'stdin', 'stdout', '--psm', '7',
         '-c', 'tessedit_char_whitelist=0123456789.'],
        input=buf.getvalue(), capture_output=True)
    return proc.stdout.decode('utf-8', errors='replace').strip()


def parse_speed(text: str) -> float | None:
    m = SPEED_RE.match(text.replace(' ', ''))
    if not m:
        return None
    v = float(f'{m.group(1)}.{m.group(2)}')
    return v if v < 100 else None


def extract_frames(video: Path, fps: int, tmp: Path) -> list[Path]:
    subprocess.run(
        ['ffmpeg', '-v', 'error', '-i', str(video), '-vf', f'fps={fps}',
         str(tmp / 'f_%06d.png')],
        check=True)
    return sorted(tmp.glob('f_*.png'))


def read_markers(markers: Path) -> dict[int, dict]:
    """markers.txt → {段号: {'start_ms': …, 'end_ms': …}}。"""
    segs: dict[int, dict] = {}
    for line in markers.read_text(encoding='utf-8').splitlines():
        m = re.match(r'REC (\d+) (start|end) (\d+)', line.strip())
        if not m:
            continue
        seg, kind, ms = int(m.group(1)), m.group(2), int(m.group(3))
        segs.setdefault(seg, {})[f'{kind}_ms'] = ms
    return segs


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description='官方 App 录屏 → 速度读数 CSV（OCR）')
    ap.add_argument('--video', nargs='+', required=True, help='speed_sampler.sh 录的 mp4，可多个')
    ap.add_argument('--markers', help='同目录 markers.txt；多段时必给（每段的墙钟 start）')
    ap.add_argument('--csv', required=True)
    ap.add_argument('--fps', type=int, default=2, help='抽帧率，默认 2（每 0.5 s 一个读数）')
    ap.add_argument('--crop', default=','.join(str(x) for x in CROP_DEFAULT),
                    help='速度数字区域，相对坐标 x0,y0,x1,y1')
    ap.add_argument('--probe', help='把每段前 N 帧的裁剪与掩码存到该目录，肉眼核对用')
    ap.add_argument('--repeat-from', help='该墙钟之后的非零读数标为 repeat（独立检验段）')
    ap.add_argument('--uncertainty-ms', type=int, default=DEFAULT_UNCERTAINTY_MS)
    args = ap.parse_args(argv)

    crop = tuple(float(x) for x in args.crop.split(','))
    if len(crop) != 4 or not all(0.0 <= x <= 1.0 for x in crop):
        print(f'错误：--crop 必须是 4 个 0–1 的相对坐标，收到 {args.crop}', file=sys.stderr)
        return 2

    segs = read_markers(Path(args.markers)) if args.markers else {}
    need_marker = args.markers is not None
    tz = local_tz()
    repeat_from = parse_time(args.repeat_from, tz) if args.repeat_from else None

    rows: list[dict] = []
    probed = 0
    for vi, vpath in enumerate(args.video):
        video = Path(vpath)
        if not video.exists():
            print(f'错误：找不到视频 {video}', file=sys.stderr)
            return 2
        start_ms = segs.get(vi + 1, {}).get('start_ms')
        if start_ms is None:
            if need_marker:
                print(f'错误：markers 里没有第 {vi + 1} 段（{video.name}）的 start；'
                      '段序号按 --video 顺序数', file=sys.stderr)
                return 2
            start_ms = int(video.stat().st_mtime * 1000)   # 无 markers 时退化为 mtime，注明不可靠
        with tempfile.TemporaryDirectory(prefix='readings_') as td:
            frames = extract_frames(video, args.fps, Path(td))
            if not frames:
                print(f'错误：{video.name} 没抽出帧', file=sys.stderr)
                return 2
            from PIL import Image
            fails = 0
            for i, fp in enumerate(frames):
                im = Image.open(fp).convert('RGB')
                w, h = im.size
                box = (int(w * crop[0]), int(h * crop[1]), int(w * crop[2]), int(h * crop[3]))
                region = im.crop(box)
                if args.probe and probed < 6 and vi == 0:
                    os.makedirs(args.probe, exist_ok=True)
                    region.save(os.path.join(args.probe, f'crop_{i:02d}.png'))
                    cyan_mask(region).save(os.path.join(args.probe, f'mask_{i:02d}.png'))
                    probed += 1
                v = parse_speed(ocr_digits(cyan_mask(region)))
                if v is None:
                    fails += 1
                    continue
                wall = datetime.fromtimestamp((start_ms + i * 1000 / args.fps) / 1000.0, tz=tz)
                phase = 'stationary' if v < STATIONARY_BELOW else 'moving'
                if repeat_from is not None and wall >= repeat_from and v >= STATIONARY_BELOW:
                    phase = 'repeat'
                rows.append({
                    'video_time_s': '',
                    'phone_wall_time_iso8601': wall.strftime('%Y-%m-%dT%H:%M:%S.%f') + wall.strftime('%z'),
                    'phase': phase,
                    'official_speed_kmh': f'{v:.1f}',
                    'official_battery_percent': '',
                    'observation': 'ocr',
                    'alignment_uncertainty_ms': str(args.uncertainty_ms),
                })
            ratio = fails / len(frames) if frames else 1.0
            if ratio > MAX_FAIL_RATIO:
                print(f'错误：{video.name} 有 {fails}/{len(frames)} 帧解析不出数字（{ratio:.0%}）。'
                      '先用 --probe 看裁剪/掩码：屏幕布局变了或不在主界面，别硬出 CSV', file=sys.stderr)
                return 2
            print(f'{video.name}: {len(frames) - fails}/{len(frames)} 帧解析成功')

    if not rows:
        print('错误：一个读数都没解析出来', file=sys.stderr)
        return 2
    rows.sort(key=lambda r: r['phone_wall_time_iso8601'])
    header = ['video_time_s', 'phone_wall_time_iso8601', 'phase', 'official_speed_kmh',
              'official_battery_percent', 'observation', 'alignment_uncertainty_ms']
    with open(args.csv, 'w', encoding='utf-8', newline='') as fh:
        w = csv.DictWriter(fh, fieldnames=header)
        w.writeheader()
        w.writerows(rows)
    n_rep = sum(1 for r in rows if r['phase'] == 'repeat')
    print(f'已写 {args.csv}：{len(rows)} 条读数'
          f'（stationary {sum(1 for r in rows if r["phase"] == "stationary")}、'
          f'moving {sum(1 for r in rows if r["phase"] == "moving")}、repeat {n_rep}）')
    print('注意：OCR 只认大数字，加速瞬间的读数会滞后于报文——'
          '标定工具按稳态窗口取中位数，这正是规程 §2 要求读数取稳态的原因。')
    if n_rep == 0:
        print('提醒：没有 repeat 段。要出标定文件必须给 --repeat-from 或手工改 CSV 的 phase 列。')
    return 0


if __name__ == '__main__':
    sys.exit(main())
