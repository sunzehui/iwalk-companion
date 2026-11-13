#!/usr/bin/env python3
"""U03 速度字段标定：把「官方 App 界面上读到的速度」与「同刻报文」对齐，
判断是否存在**唯一**一个 (帧组, 偏移, 类型, 倍率) 能解释这些读数。

这个工具只做判定与拟合，不做命名。它不会因为「某个字节在动」就认定那是速度；
不满足 procedure_speed_calibration.md §4 的通过条件时，它会明确拒绝出标定文件。

输入（四选一）：
  --btsnoop    手机侧 HCI 日志（官方 App 骑行采集的主路径）。文件里的绝对时间戳不可信
               （实测四份真机日志与墙钟恒差 378 天），必须配 --hci-t0 或 --hci-t1 给锚点
  --frames     raw jsonl（app 导出 raw_*.jsonl）或 decode_capture 的 frames.jsonl（需 --t0）
  --evidence   证据包（iwapp-evidence-v1）；evidence_timeline 有 2000 条上限
  --observations  captures/TEMPLATE_speed_observations.csv 的同构 CSV

时间对齐（必须显式给出，绝不假设「视频第 0 秒 = HCI 第 0 秒」）：
  每行填 phone_wall_time_iso8601，或整体给 --video-anchor（视频第 0 秒的手机墙钟）。
  两者都没有时直接报错退出（退出码 2）。

用法：
  python3 analysis/tools/speed_calibration.py \
      --frames captures/20261120_speed/btsnoop_export.jsonl \
      --observations captures/20261120_speed/speed_observations.csv \
      --video-anchor 2026-11-20T15:02:11+08:00 \
      --report captures/20261120_speed/calibration_report.md \
      --emit downloads/iwk1779887-speed-v1.json

退出码：0 = 唯一候选通过且已（可选）出标定；1 = 无候选通过/多个候选同分；
        2 = 输入或时间对齐不可用。
"""
from __future__ import annotations

import argparse
import json
import re
import statistics
import sys
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from pathlib import Path

# 与 analysis/protocol 共用解码器；XOR 还原逻辑与 decode_capture.py --wire auto 一致（E46）
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'protocol'))
from ibwalk_frame import StreamDecoder  # noqa: E402

class InputError(Exception):
    """输入/对齐不可用：一律退出码 2，不给半成品结论。"""


SCALAR_TYPES = (
    ('u8', 1, False),
    ('i8', 1, True),
    ('u16le', 2, False),
    ('i16le', 2, True),
)

# 官方界面常见倍率，用于给出「像设备固件那样圆整」的建议值
ROUND_SCALES = (0.01, 0.05, 0.1, 0.2, 0.5, 1.0, 2.0, 10.0, 0.001)


# ---------------------------------------------------------------- 时间与输入


def parse_time(text: str, assume_tz: timezone) -> datetime:
    """解析 ISO 时间；无时区信息时套用 assume_tz（帧与读数用同一假设）。"""
    s = text.strip()
    if s.endswith('Z'):
        s = s[:-1] + '+00:00'
    dt = datetime.fromisoformat(s)
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=assume_tz)
    return dt


def local_tz() -> timezone:
    off = datetime.now().astimezone().utcoffset() or timedelta(0)
    return timezone(off)


def decode_int(payload: bytes, offset: int, width: int, signed: bool):
    if offset < 0 or offset + width > len(payload):
        return None
    v = int.from_bytes(payload[offset:offset + width], 'little', signed=signed)
    return v


@dataclass(order=True)
class CandFrame:
    t: datetime
    cmd: int
    a1: int
    a2: int
    payload: bytes


def decode_stream(chunks) -> list:
    """把 (时间, 线上字节) 序列解成帧，自动判定是否整帧 XOR 0x55。

    不能用「首包是否以 00ff 开头」判定：真机日志里分片边界与帧边界不对齐，
    第一包可能落在帧中间（20261021_official 就是这样，按首包判定会解出 0 帧）。
    这里对两种假设各跑一遍解码器，取解出合法帧更多的一侧，结果可复核。
    """
    best_frames = []
    for mask in (0x00, 0x55):
        decoder = StreamDecoder()
        frames = []
        for when, raw in chunks:
            data = bytes(b ^ mask for b in raw) if mask else raw
            for f in decoder.feed(data):
                frames.append(CandFrame(when, f.cmd, f.a1, f.a2, bytes(f.payload)))
        if len(frames) > len(best_frames):   # 平手时保留明文假设
            best_frames = frames
    return best_frames


def frames_from_raw_jsonl(path: Path, tz: timezone):
    """app 导出的原始通知行：{"ts": iso, "hex": 线上字节}，每行一个 BLE 分包。"""
    chunks = []
    bad = 0
    with path.open(encoding='utf-8') as fh:
        for line in fh:
            line = line.strip()
            if not line:
                continue
            try:
                rec = json.loads(line)
            except json.JSONDecodeError:
                bad += 1
                continue
            raw_hex = rec.get('hex') or rec.get('value_hex')
            ts = rec.get('ts')
            if not raw_hex or not ts:
                continue
            chunk = bytes.fromhex(raw_hex)
            if chunk:
                chunks.append((parse_time(ts, tz), chunk))
    return decode_stream(chunks), bad


def frames_from_decoded_jsonl(path: Path, t: datetime):
    """decode_capture.py 产物：t_sec 相对日志起点，需要 t0。"""
    out = []
    with path.open(encoding='utf-8') as fh:
        for line in fh:
            line = line.strip()
            if not line:
                continue
            try:
                rec = json.loads(line)
            except json.JSONDecodeError:
                continue
            if rec.get('parse') not in (None, 'known_frame'):
                continue
            if rec.get('chk_ok') is False:
                continue
            try:
                payload = bytes.fromhex(rec['payload_hex'])
                t_sec = float(rec['t_sec'])
                cmd = int(rec['cmd'], 16) if isinstance(rec['cmd'], str) else int(rec['cmd'])
                a1 = int(rec['a1'], 16) if isinstance(rec['a1'], str) else int(rec['a1'])
                a2 = int(rec['a2'], 16) if isinstance(rec['a2'], str) else int(rec['a2'])
            except (KeyError, ValueError):
                continue
            out.append(CandFrame(t + timedelta(seconds=t_sec), cmd, a1, a2, payload))
    return out, 0


def frames_from_evidence(path: Path, tz: timezone):
    d = json.loads(path.read_text(encoding='utf-8'))
    out = []
    for ev in d.get('evidence_timeline') or []:
        try:
            group = ev['group']
            m = re.match(r'0x([0-9a-f]{2})/0x([0-9a-f]{2})/0x([0-9a-f]{2})', group, re.I)
            cmd, a1, a2 = (int(x, 16) for x in m.groups())
            out.append(CandFrame(parse_time(ev['at'], tz), cmd, a1, a2, bytes.fromhex(ev['payload_hex'])))
        except (KeyError, ValueError, AttributeError):
            continue
    return out, 0


ATT_NOTIFY = (0x1B, 0x1D)      # Handle Value Notification / Indication（车 → 手机）
ATT_WRITE = (0x52, 0x12)       # Write Command / Write Request（手机 → 车）


def att_records(path: Path):
    """btsnoop → (记录列表, 会话跨度秒)。

    记录为 `(相对秒, ATT 操作码, handle, value)`，只保留通知与出站写两类。
    **不信任文件里的绝对时间戳**：这批真机日志的 btsnoop 时间戳与手机墙钟相差一个
    恒定的 378 天（20261021_official 与 20261112_locktest 两份独立日志都是同一个偏移），
    时段对得上、日期对不上。所以对外只用相对秒，由调用方配墙钟锚点。
    """
    import struct

    with path.open('rb') as fh:
        if fh.read(8) != b'btsnoop\x00':
            raise InputError(f'{path} 不是 btsnoop 文件')
        fh.read(8)  # version + datalink
        records = []
        while True:
            hdr = fh.read(24)
            if len(hdr) < 24:
                break
            _orig, incl, flags, _drops, ts = struct.unpack('>IIIIq', hdr)
            data = fh.read(incl)
            if not data:
                continue
            records.append((ts, flags, data))
    if not records:
        raise InputError(f'{path} 里没有记录')

    t_first = records[0][0]
    att = []
    for ts, _flags, data in records:
        if data[0] != 0x02:      # 只要 ACL 数据
            continue
        # 不按 btsnoop 的 flags 方向位过滤：真机日志里收到的通知偏偏带着 0x01
        # （与 btsnoop 规范相反，20261112_locktest 全量统计 6568 条通知都是 0x01），
        # 照规范过滤会把所有通知丢光。通知 0x1B/0x1D 只可能由服务端发出，
        # 写 0x52/0x12 只可能由客户端发出，按操作码判方向足够。
        acl = data[1:]
        if len(acl) < 6:
            continue
        _handle_bits, acl_len = struct.unpack_from('<HH', acl, 0)
        pdu = acl[4:4 + acl_len]
        if len(pdu) < 4:
            continue
        l2_len, cid = struct.unpack_from('<HH', pdu, 0)
        if cid != 0x0004 and cid < 0x0040:
            continue
        packet = pdu[4:4 + l2_len]
        if not packet or packet[0] not in ATT_NOTIFY + ATT_WRITE:
            continue
        handle = struct.unpack_from('<H', packet, 1)[0] if len(packet) >= 3 else 0xFFFF
        value = packet[3:]
        if value:
            att.append(((ts - t_first) / 1_000_000.0, packet[0], handle, value))
    span = (records[-1][0] - t_first) / 1_000_000.0
    return att, span


def frames_from_btsnoop(path: Path, anchor: datetime, anchor_at_end: bool):
    """手机侧 btsnoop HCI：取收到的 ATT 通知，按相对时间锚定到墙钟。

    锚点要求见 `att_records`：`--hci-t0` 对首条记录、`--hci-t1` 对末条记录。
    """
    recs, _span = att_records(path)
    if not recs:
        raise InputError(f'{path} 里没有 ATT 通知/写记录')
    ref = recs[-1][0] if anchor_at_end else recs[0][0]
    chunks = [(anchor + timedelta(seconds=rel - ref), value)
              for rel, opcode, _handle, value in recs if opcode in ATT_NOTIFY]
    if not chunks:
        raise InputError(f'{path} 里没有 ATT 通知（可能只抓到了出站写）')
    return decode_stream(chunks), 0


def load_frames(args, tz):
    if args.btsnoop:
        if args.hci_t0:
            return frames_from_btsnoop(Path(args.btsnoop), parse_time(args.hci_t0, tz), False), 'btsnoop'
        if args.hci_t1:
            return frames_from_btsnoop(Path(args.btsnoop), parse_time(args.hci_t1, tz), True), 'btsnoop'
        raise InputError(
            'btsnoop 的绝对时间戳不可信（实测与手机墙钟差恒定 378 天），必须给锚点：\n'
            '  --hci-t0 <首条记录的手机墙钟>  或  --hci-t1 <末条记录的手机墙钟>\n'
            '建议骑行结束、断开连接后立刻 pull 日志，把 pull 前的 `adb shell date` 当 --hci-t1。')
    path = Path(args.frames or args.evidence)
    if path.suffix == '.json' and path.suffixes[-2:] != ['.jsonl']:
        return frames_from_evidence(path, tz), 'evidence'
    head = ''
    with path.open(encoding='utf-8') as fh:
        for line in fh:
            if line.strip():
                head = line.strip()
                break
    try:
        probe = json.loads(head)
    except json.JSONDecodeError:
        raise InputError(f'无法解析首行 JSON：{path}')
    if 'hex' in probe and 'ts' in probe:
        frames, bad = frames_from_raw_jsonl(path, tz)
        return (frames, bad), 'raw'
    if 'payload_hex' in probe:
        if not args.t0:
            raise InputError('decoded frames.jsonl 的 t_sec 是相对值，必须用 --t0 给出日志起点时刻')
        return frames_from_decoded_jsonl(path, parse_time(args.t0, tz)), 'decoded'
    raise InputError(f'无法识别的 frames 格式（首行键：{sorted(probe)}）')


# ---------------------------------------------------------------- 读数


@dataclass
class Obs:
    t: datetime
    phase: str
    speed: float
    uncertainty_ms: int
    note: str
    predicted: float | None = None


def load_observations(path: Path, anchor: datetime | None, tz: timezone) -> list[Obs]:
    import csv

    rows: list[Obs] = []
    with path.open(encoding='utf-8') as fh:
        for rec in csv.DictReader(fh):
            def cell(k):
                return (rec.get(k) or '').strip()

            speed_txt = cell('official_speed_kmh')
            if speed_txt == '':
                continue  # 看不清就留空，不拿 0 顶替（规程 §2）
            try:
                speed = float(speed_txt)
            except ValueError:
                continue
            wall = cell('phone_wall_time_iso8601')
            if wall:
                t = parse_time(wall, tz)
            elif anchor is not None and cell('video_time_s'):
                t = anchor + timedelta(seconds=float(cell('video_time_s')))
            else:
                continue
            try:
                unc = int(float(cell('alignment_uncertainty_ms') or 0))
            except ValueError:
                unc = 0
            rows.append(Obs(t, cell('phase') or 'unspecified', speed, unc, cell('observation')))
    rows.sort(key=lambda r: r.t)
    return rows


# ---------------------------------------------------------------- 统计


def fit_affine(xs, ys):
    """最小二乘 y = a*x + b；返回 (a, b)。"""
    n = len(xs)
    if n < 2:
        return None, None
    mx = sum(xs) / n
    my = sum(ys) / n
    sxx = sum((x - mx) ** 2 for x in xs)
    if sxx == 0:
        return None, None
    sxy = sum((x - mx) * (y - my) for x, y in zip(xs, ys))
    a = sxy / sxx
    return a, my - a * mx


def fit_quality(xs, ys, a, b):
    if a is None or not xs:
        return float('nan'), float('inf')
    pred = [a * x + b for x in xs]
    my = sum(ys) / len(ys)
    ss_tot = sum((y - my) ** 2 for y in ys)
    ss_res = sum((y - p) ** 2 for y, p in zip(ys, pred))
    r = float('nan') if ss_tot == 0 else 1 - ss_res / ss_tot
    return r, max(abs(y - p) for y, p in zip(ys, pred))


def best_round_scale(xs, ys, a):
    """在固件常见的圆整倍率里挑一个，偏置用均值残差；返回 (scale, bias, max_err)。"""
    best = (None, None, float('inf'))
    for s in ROUND_SCALES:
        if a and a * s < 0:
            continue
        b = sum(y - s * x for x, y in zip(xs, ys)) / len(ys)
        err = max(abs(y - (s * x + b)) for x, y in zip(xs, ys))
        if err < best[2]:
            best = (s, b, err)
    return best


# ---------------------------------------------------------------- 判定


@dataclass
class Candidate:
    cmd: int
    a1: int
    a2: int
    offset: int
    type_name: str
    width: int
    signed: bool
    train_r2: float
    held_r2: float
    max_err: float
    slope: float
    scale: float | None
    bias: float | None
    scale_err: float | None
    pairs: int
    passed: bool
    reason: str

    @property
    def label(self):
        return f'0x{self.cmd:02x}/0x{self.a1:02x}/0x{self.a2:02x} off{self.offset} {self.type_name}'

    def score(self):
        held = self.held_r2 if self.held_r2 == self.held_r2 else -9
        return (held, -self.max_err)


def sample_series(series, t, window_ms):
    lo = t - timedelta(milliseconds=window_ms)
    hi = t + timedelta(milliseconds=window_ms)
    vals = series(lo, hi)
    if not vals:
        return None
    return statistics.median(vals)


def evaluate(frames, obs, window_ms, min_distinct, require_repeat):
    groups: dict[tuple[int, int, int], list[CandFrame]] = {}
    for f in frames:
        groups.setdefault((f.cmd, f.a1, f.a2), []).append(f)
    for v in groups.values():
        v.sort(key=lambda f: f.t)

    results: list[Candidate] = []
    for key, flist in groups.items():
        if len(flist) < 10:
            continue  # 帧太少，任何拟合都是噪声
        lengths = {len(f.payload) for f in flist}
        if len(lengths) != 1:
            continue  # 载荷长度不稳，偏移语义不可能固定
        maxlen = lengths.pop()

        for offset in range(maxlen):
            for type_name, width, signed in SCALAR_TYPES:
                if offset + width > maxlen:
                    continue

                def series(lo, hi, _fl=flist, _o=offset, _w=width, _s=signed):
                    out = []
                    for f in _fl:
                        if f.t > hi:
                            break
                        if f.t < lo:
                            continue
                        v = decode_int(f.payload, _o, _w, _s)
                        if v is not None:
                            out.append(v)
                    return out

                pairs = []
                missing = 0
                for o in obs:
                    v = sample_series(series, o.t, window_ms)
                    if v is None:
                        missing += 1
                        continue
                    pairs.append((o, v))
                if len(pairs) < 4 or missing > len(obs) // 2:
                    continue

                zero_rows = [(o, v) for o, v in pairs if o.speed == 0]
                move_rows = [(o, v) for o, v in pairs if abs(o.speed) >= 0.5]
                distinct_move = {(round(o.speed, 1), v) for o, v in move_rows}
                if not zero_rows or len(move_rows) < 2 or len(distinct_move) < min_distinct:
                    continue
                raw_move = {v for _, v in move_rows}
                if len(raw_move) < min_distinct:
                    continue  # 读数在变而字节不变 → 不是它

                # 训练段：所有非 repeat 行（含静止与移动）；repeat 段只用于检验，
                # 绝不参与挑选候选或拟合倍率（规程 §4.5）。
                train = [(o, v) for o, v in pairs if o.phase != 'repeat']
                held = [(o, v) for o, v in pairs if o.phase == 'repeat']
                if not train:
                    continue

                a, b = fit_affine([v for _, v in train], [o.speed for o, _ in train])
                if a is None:
                    continue
                train_r2, train_err = fit_quality([v for _, v in train], [o.speed for o, _ in train], a, b)
                if held:
                    held_r2, held_err = fit_quality([v for _, v in held], [o.speed for o, _ in held], a, b)
                    max_err = max(train_err, held_err)
                else:
                    held_r2, max_err = float('nan'), train_err

                scale, bias, scale_err = best_round_scale([v for _, v in train], [o.speed for o, _ in train], a)
                zero_pred = max((abs(a * v + b) for _, v in zero_rows), default=float('inf'))
                max_speed = max((abs(o.speed) for o, _ in pairs), default=0.0)
                tol = max(2.0, 0.2 * max_speed)

                reasons = []
                if a <= 0:
                    reasons.append(f'斜率为负（{a:.4g}），速度不应随字节增大而减小')
                if zero_pred > 1.0:
                    reasons.append(f'静止段预测 |v| 最大 {zero_pred:.2f} km/h > 1.0')
                if not (train_r2 == train_r2) or train_r2 < 0.90:
                    reasons.append(f'训练段 R² {train_r2:.3f} < 0.90')
                if require_repeat and not held:
                    reasons.append('缺独立重复段（phase=repeat），按规程 §4 不能定标')
                if held and (held_r2 != held_r2 or held_r2 < 0.85):
                    reasons.append(f'重复段 R² {held_r2:.3f} < 0.85')
                if max_err > tol:
                    reasons.append(f'最大残差 {max_err:.2f} km/h > 容差 {tol:.2f}')
                if scale is None:
                    reasons.append(f'斜率 {a:.4g} 与任何圆整倍率方向冲突')
                elif scale_err > tol:
                    # 固件倍率是圆整的（×0.1、×0.01…）。只能靠 1/256 这类非整倍率拟合上的
                    # 候选，多半是把相邻常量字节一起读进来的「跨字节伪解」。
                    reasons.append(f'找不到圆整倍率：最接近的 ×{scale:g} 残差 {scale_err:.2f} km/h > 容差 {tol:.2f}')

                results.append(Candidate(
                    key[0], key[1], key[2], offset, type_name, width, signed,
                    train_r2, held_r2, max_err, a, scale, bias, scale_err,
                    len(pairs), not reasons, '；'.join(reasons) or '通过',
                ))
    return results


# ---------------------------------------------------------------- 输出


def render_report(results: list[Candidate], obs, verdict, source, window_ms) -> str:
    lines = ['# 速度字段标定分析（U03）', '']
    lines.append(f'- 帧来源：`{source}`')
    lines.append(f'- 读数行数：{len(obs)}（其中 0.0 读数 {sum(1 for o in obs if o.speed == 0)}）')
    lines.append(f'- 采样窗：±{window_ms} ms（窗内取中位数）')
    lines.append(f'- 结论：**{verdict}**')
    lines.append('')
    lines.append('## 候选排名（前 20）')
    lines.append('')
    lines.append('| # | 帧组/偏移/类型 | 训练 R² | 重复 R² | 最大残差 | 斜率 | 圆整倍率 | 判定 |')
    lines.append('|---|---|---|---|---|---|---|---|')
    ranked = sorted(results, key=lambda c: c.score(), reverse=True)[:20]
    for i, c in enumerate(ranked, 1):
        held = '—' if c.held_r2 != c.held_r2 else f'{c.held_r2:.3f}'
        sc = '—' if c.scale is None else f'{c.scale:g}（bias {c.bias:.3g}）'
        mark = '✅ 通过' if c.passed else f'✗ {c.reason}'
        lines.append(f'| {i} | `{c.label}` | {c.train_r2:.3f} | {held} | {c.max_err:.2f} | '
                     f'{c.slope:.4g} | {sc} | {mark} |')
    lines.append('')
    lines.append('> 排名只是拟合优度，不是语义。表里没有出现「速度」字样，是因为工具不认字段名，只认能否被读数解释。')
    return '\n'.join(lines)


def emit_v1(best: Candidate, obs, base_path: Path | None, vehicle: str) -> dict:
    fields = [{
        'field': 'speed',
        'offset': best.offset,
        'type': best.type_name,
        'scale': best.scale,
        'bias': round(best.bias, 6),
    }]
    name = f'{vehicle}-speed-v1'
    cmd, a1, a2 = (13, 1, 20) if base_path is None else (None, None, None)
    evidence = (
        f'{vehicle} 速度字段标定：应答组 0x{best.cmd:02x}/0x{best.a1:02x}/0x{best.a2:02x} '
        f'payload off{best.offset} {best.type_name} × {best.scale:g}'
        f'{" + " + format(best.bias, ".3g") if abs(best.bias) > 1e-9 else ""} → km/h。'
        f'依据：{len(obs)} 条官方界面读数与同刻报文对齐，训练段 R² {best.train_r2:.3f}，'
        f'独立重复段 R² {best.held_r2:.3f}，最大残差 {best.max_err:.2f} km/h。'
        '工具只据读数拟合，未使用任何字段名假设；不保证适用于其他车型或固件。'
    )
    if base_path:
        base = json.loads(base_path.read_text(encoding='utf-8'))
        profiles = base.get('profiles') or []
        if not profiles:
            raise InputError(f'{base_path} 里没有 profiles')
        prof = dict(profiles[0])
        prof['name'] = name
        prof['fields'] = [f for f in prof.get('fields', []) if f.get('field') != 'speed'] + fields
        # 原文件写着「速度…不映射」时，那句话在加入速度映射后就变成假话。
        # 不改原文的事实部分（电量锚点），只去掉这一处已被推翻的断言。
        text = (prof.get('evidence') or '').replace('速度、电压、温度及其他字段不映射', '电压、温度及其他字段不映射')
        prof['evidence'] = (text.strip() + ' / ' + evidence).strip(' /')
        return {'profiles': [prof]}
    return {'profiles': [{'name': name, 'cmd': cmd, 'a1': a1, 'a2': a2,
                          'evidence': evidence, 'fields': fields}]}


def collapse_spans(results: list[Candidate]) -> list[Candidate]:
    """把同一字节跨度的有/无符号解释合并成一个候选。

    `u8` 与 `i8` 读的是同一字节，只是在 ≥128 时解释不同；全为正的读数无法区分二者，
    这不是「两个假设」，而是同一个映射的两种等价写法。按 R² 更高者保留，持平则留无符号，
    避免把等价解释误报成歧义。
    """
    by_span: dict[tuple, Candidate] = {}
    for c in results:
        key = (c.cmd, c.a1, c.a2, c.offset, c.width)
        cur = by_span.get(key)
        if cur is None or c.train_r2 > cur.train_r2 + 1e-9:
            by_span[key] = c
        elif abs(c.train_r2 - cur.train_r2) <= 1e-9 and cur.type_name.startswith('i') \
                and not c.type_name.startswith('i'):
            by_span[key] = c
    return list(by_span.values())


def pick_unique(passers: list[Candidate]):
    """同分候选的确定性取舍，返回 (best, twins)。

    真实数据里「跨字节读」必然产生同分候选：若速度字段两侧是常量字节，
    在这个字段前面多读一个字节等价于 `v' = v + k*256`，仍是一条直线。
    因此按「倍率误差 → |bias| → 重复段 R² → 更宽 → 偏移」排序：真正占用独立字节的
    映射偏置应当接近 0；同偏移下取更宽的跨度（见下方注释）。只有**不同偏移**且
    倍率与偏置都持平时才判为歧义——同偏移换宽度不是新假设，是同一字段的两种读法。
    """
    def held(c):
        return c.held_r2 if c.held_r2 == c.held_r2 else -9.0

    # 同偏移下优先最宽：更宽的跨度为「速度超过 255 时仍正确」留了余量，而它既然能通过
    # R²/残差检验，就说明多读进来的高位字节在本次读数范围内没有引入噪声。
    ranked = sorted(passers, key=lambda c: (c.scale_err, abs(c.bias or 0.0), -held(c), -c.width, c.offset))
    top = ranked[0]
    twins = [c for c in ranked[1:]
             if c.offset != top.offset
             and abs(c.scale_err - top.scale_err) <= 0.25
             and abs(abs(c.bias or 0.0) - abs(top.bias or 0.0)) <= 0.25]
    return top, twins


def main(argv=None):
    ap = argparse.ArgumentParser(description='U03 速度字段标定分析')
    src = ap.add_mutually_exclusive_group(required=True)
    src.add_argument('--frames', help='raw_*.jsonl（app 导出）或 frames.jsonl（decode_capture）')
    src.add_argument('--evidence', help='iwapp-evidence-v1 证据包（时间线有 2000 条上限）')
    src.add_argument('--btsnoop', help='手机侧 HCI 日志（官方 App 骑行采集的主路径），需 --hci-t0/--hci-t1')
    ap.add_argument('--observations', required=True, help='读数 CSV')
    ap.add_argument('--video-anchor', help='视频第 0 秒的手机墙钟时间（ISO）')
    ap.add_argument('--t0', help='decoded frames.jsonl 的日志起点时刻（ISO）')
    ap.add_argument('--hci-t0', help='btsnoop 首条记录的手机墙钟（ISO）')
    ap.add_argument('--hci-t1', help='btsnoop 末条记录的手机墙钟（ISO）；骑行后立刻 pull 时更好用')
    ap.add_argument('--window-ms', type=int, default=400, help='采样窗半宽，默认 400 ms')
    ap.add_argument('--min-distinct', type=int, default=3, help='至少几个可区分的非零读数，默认 3')
    ap.add_argument('--allow-no-repeat', action='store_true',
                    help='允许缺独立重复段（结果标为暂定，且仍不出标定文件）')
    ap.add_argument('--base-calibration', help='在该 profile 上追加 speed 映射（保留电量等）')
    ap.add_argument('--vehicle', default='iWK1779887', help='车辆序列号，用于 profile 名')
    ap.add_argument('--report', help='把 markdown 报告写到该路径')
    ap.add_argument('--emit', help='判定通过时把标定 JSON 写到该路径')
    ap.add_argument('--json', help='把机器可读结果写到该路径')
    args = ap.parse_args(argv)

    tz = local_tz()
    anchor = parse_time(args.video_anchor, tz) if args.video_anchor else None

    obs_all = load_observations(Path(args.observations), anchor, tz)
    if not obs_all:
        print('错误：读数 CSV 里没有可用行（需要 official_speed_kmh 且能定位到手机时刻）', file=sys.stderr)
        return 2
    unc = max((o.uncertainty_ms for o in obs_all), default=0)
    window = args.window_ms
    if unc > window:
        if unc > 3000:
            print(f'错误：对齐不确定性 {unc} ms 过大，先补证对齐（视频锚点/墙钟）再分析', file=sys.stderr)
            return 2
        print(f'注：读数对齐不确定性 {unc} ms，采样窗由 ±{window} ms 放宽到 ±{unc} ms；'
              '加速/减速瞬间的读数会被这段时间差放大，稳态段读数才是主证据', file=sys.stderr)
        window = unc
    distinct = {round(o.speed, 1) for o in obs_all if abs(o.speed) >= 0.5}
    if len(distinct) < args.min_distinct:
        print(f'错误：只有 {len(distinct)} 个可区分的非零读数（{sorted(distinct)}），'
              f'按规程 §4 不能定标；速度未知就继续未知', file=sys.stderr)
        return 2

    try:
            (frames, bad), source = load_frames(args, tz)
    except InputError as e:
        print(f'错误：{e}', file=sys.stderr)
        return 2
    if args.btsnoop:
        # 报告里必须写清锚点来自哪一端，否则别人无法复核对齐误差
        source += f'（墙钟锚点：{"末条 --hci-t1" if args.hci_t1 else "首条 --hci-t0"}）'
    if not frames:
        print('错误：没解出任何帧；确认日志方向/特征与 XOR 还原（E46）', file=sys.stderr)
        return 2

    results = evaluate(frames, obs_all, window, args.min_distinct, not args.allow_no_repeat)
    results = collapse_spans(results)
    if not results:
        print('结论：没有任何候选能同时满足「静止≈0、非零读数可变、斜率同向」；速度继续未知')
        if args.report:
            Path(args.report).write_text(
                render_report([], obs_all, '无候选通过（速度继续未知）', source, window) + '\n',
                encoding='utf-8')
        return 1

    passers = [c for c in results if c.passed]
    verdict: str
    best = None
    if not passers:
        verdict = '无候选通过（速度继续未知）'
    else:
        best, twins = pick_unique(passers)
        if twins:
            verdict = (f'{1 + len(twins)} 个候选无法区分（{best.label} 与 '
                       f'{"、".join(c.label for c in twins[:2])} 的 R² 与偏置都持平）→ 速度继续未知')
            best = None
        else:
            same_off = [c for c in passers if c.offset == best.offset and c.width != best.width]
            if same_off:
                # 读数全为正、且高位字节在采样点上恒定时，u8 与 u16le 的预测完全一致。
                # 这不是歧义（同一偏移），但结论不能写成「唯一候选」——宽度没被数据定住。
                names = '、'.join(sorted({c.type_name for c in same_off + [best]}))
                verdict = (f'偏移唯一（off{best.offset}），宽度未被数据区分（{names} 同分，'
                           f'高位字节在读数范围内恒定），取更宽解释：{best.label}')
            else:
                verdict = f'唯一候选通过：{best.label}'

    if best is not None and best.held_r2 != best.held_r2:
        verdict += '（仅训练段，缺独立重复段 → 暂定，不出标定文件）'
        emit_ok = False
    else:
        emit_ok = best is not None

    print(verdict)
    for c in sorted(results, key=lambda c: c.score(), reverse=True)[:5]:
        held = '—' if c.held_r2 != c.held_r2 else f'{c.held_r2:.3f}'
        mark = ('PASS ← 选定' if c is best else 'PASS') if c.passed else 'reject: ' + c.reason
        print(f'  {c.label:38s} trainR2={c.train_r2:6.3f} heldR2={held:>6s} '
              f'maxErr={c.max_err:6.2f} slope={c.slope:9.4g} {mark}')
    if bad:
        print(f'  注：{bad} 行原始包无法解析（已跳过，不影响拟合，但请复核日志完整性）')

    if args.report:
        Path(args.report).write_text(render_report(results, obs_all, verdict, source, window) + '\n',
                                     encoding='utf-8')
    if args.json:
        Path(args.json).write_text(json.dumps({
            'verdict': verdict,
            'source': source,
            'observations': len(obs_all),
            'frames': len(frames),
            'candidates': [c.__dict__ | {'label': c.label} for c in
                           sorted(results, key=lambda c: c.score(), reverse=True)[:50]],
        }, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    if args.emit and emit_ok:
        payload = emit_v1(best, obs_all,
                          Path(args.base_calibration) if args.base_calibration else None,
                          args.vehicle)
        Path(args.emit).write_text(json.dumps(payload, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
        print(f'  已写标定：{args.emit}')
    elif args.emit:
        print('  未写标定：判定不满足出标定的条件', file=sys.stderr)

    return 0 if emit_ok else 1


if __name__ == '__main__':
    sys.exit(main())
