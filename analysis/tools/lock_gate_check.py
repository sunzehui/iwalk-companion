#!/usr/bin/env python3
"""锁车闸门（B/C 两态）的 HCI 侧判定器。

回答一个只靠 grep 数不出来的问题：**滑动到底有没有写出锁帧，写的那一帧车在不在动**。

做法：同一份 btsnoop 里同时取两样东西——
  1. 出站写（ATT 0x52/0x12）→ 解帧 → 分出 `0x0a/0x03/0x70`（关锁）、`0x0a/0x03/0x71`（解锁）；
  2. 通知（ATT 0x1B/0x1D）→ 按标定文件把速度字段算出来，得到随时间变化的速度序列。
然后逐条核对：

  B（静止放行）  关锁/解锁各 1 次，且每次写出时刻的车辆速度 ≤ 阈值
  C（运动中拦截）会话里出现过 > 阈值的时段，且该时段内 **一条锁帧都没有**

C 必须真的被检验过：会话全程速度没超过 1.0 km/h 时，日志无法证明闸门拦住了任何东西，
这时判「未检验」并退出 1，而不是判通过。

不能证明的事：**「当时确实滑了滑块」只能靠 C 段截图与人证**。本工具只能证明
「运动中没有任何锁帧写出」；界面是否变灰、文案是否出现，属于截图证据。

时间基准：btsnoop 的绝对时间戳不可信（这批真机日志与墙钟恒差 378 天），
所以全程只用相对秒，不需要任何墙钟锚点。

用法见 app_kotlin/docs/lock-gate-real-vehicle-plan.md §4。
"""
from __future__ import annotations

import argparse
import bisect
import json
import sys
from dataclasses import dataclass
from datetime import datetime, timedelta
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import speed_calibration as sc  # noqa: E402

LOCK = (0x0A, 0x03, 0x70)
UNLOCK = (0x0A, 0x03, 0x71)
EPOCH = datetime(2000, 1, 1)
TYPE_INFO = {name: (width, signed) for name, width, signed in sc.SCALAR_TYPES}


@dataclass
class Write:
    t: float
    kind: str            # lock / unlock / query / other / undecoded
    hex: str
    speed: float | None = None
    gap_s: float | None = None


def classify(value: bytes) -> tuple[str, list]:
    # 比任何帧都短的写（真机上就是 CCCD 订阅/退订的 2 字节 0100/0000/0200）。
    # 不单独认出来，它会被记成「解不出帧」，在每条正常会话里造出一条假警报。
    # 依据：四份真机日志里所有写都是整帧（9/10 B），没有跨写分片；若将来出现分片写，
    # 会表现为一堆 short 写，报告的清点行会把这件事摊开，不会被当成通过。
    if len(value) < 7:
        return 'cccd', []
    frames = sc.decode_stream([(EPOCH, value)])
    if not frames:
        return 'undecoded', []
    if len(frames) > 1:
        return 'other', frames
    f = frames[0]
    tri = (f.cmd, f.a1, f.a2)
    if tri == LOCK:
        return 'lock', frames
    if tri == UNLOCK:
        return 'unlock', frames
    if tri[:2] == (0x0A, 0x01):
        return 'query', frames
    return 'other', frames


def load_speed_field(path: Path, cmd, a1, a2):
    try:
        doc = json.loads(path.read_text(encoding='utf-8'))
    except (OSError, json.JSONDecodeError) as e:
        raise sc.InputError(f'读不了标定文件 {path}：{e}')
    profiles = doc.get('profiles') or []
    if not profiles:
        raise sc.InputError(f'{path} 里没有 profiles')
    prof = profiles[0]
    field = next((f for f in prof.get('fields', []) if f.get('field') == 'speed'), None)
    if field is None:
        raise sc.InputError(
            f'{path} 里没有 speed 字段——速度还没标定，C 段无从判定。\n'
            '先按 analysis/procedure_speed_calibration.md 跑出标定文件再来。')
    group = (prof.get('cmd'), prof.get('a1'), prof.get('a2'))
    if any(g is None for g in group):
        if None in (cmd, a1, a2):
            raise sc.InputError(
                '标定文件的 profile 没有 cmd/a1/a2（合并式写法），必须用 --cmd/--a1/--a2 指定帧组')
        group = (cmd, a1, a2)
    if field.get('type') not in TYPE_INFO:
        raise sc.InputError(f'不认识的字段类型 {field.get("type")}')
    return group, field


def speed_series(recs, group, field):
    width, signed = TYPE_INFO[field['type']]
    chunks = [(EPOCH + timedelta(seconds=rel), value)
              for rel, opcode, _h, value in recs if opcode in sc.ATT_NOTIFY]
    frames = [f for f in sc.decode_stream(chunks) if (f.cmd, f.a1, f.a2) == group]
    out = []
    for f in frames:
        raw = sc.decode_int(f.payload, field['offset'], width, signed)
        if raw is None:
            continue
        out.append(((f.t - EPOCH).total_seconds(), raw * field['scale'] + field.get('bias', 0.0)))
    out.sort()
    return out


def runs_above(series, limit):
    """速度幅值超过阈值的连续段。用 abs：App 侧的闸门是 `abs(speed) > 1.0`，
    倒车（标定 bias 导致负值）同样要被拦住，判据必须与代码一致。"""
    runs, cur = [], []
    for t, v in series:
        if abs(v) > limit:
            cur.append((t, v))
        elif cur:
            runs.append(cur)
            cur = []
    if cur:
        runs.append(cur)
    return runs


def speed_at(series, times, t, max_gap_s):
    i = bisect.bisect_right(times, t) - 1
    if i < 0:
        return None, None
    gap = t - times[i]
    if gap > max_gap_s:
        return None, gap
    return series[i][1], gap


def render(report, verdict, exit_hint):
    lines = ['# 锁车闸门 HCI 侧判定', '']
    lines.append(f'- 日志：`{report["source"]}`')
    lines.append(f'- 会话跨度：{report["span_s"]:.1f} s；通知帧 {report["frames"]} 条，'
                 f'出站写 {report["writes_total"]} 条')
    lines.append(f'- 标定：`{report["calibration"]}`（帧组 '
                 f'`0x{report["group"][0]:02x}/0x{report["group"][1]:02x}/0x{report["group"][2]:02x}`，'
                 f'off{report["field"]["offset"]} {report["field"]["type"]} × {report["field"]["scale"]:g}）')
    lines.append(f'- 速度范围：{report["speed_min"]:.2f} – {report["speed_max"]:.2f} km/h')
    lines.append(f'- 结论：**{verdict}**')
    lines.append('')
    lines.append('## 出站写清单')
    lines.append('')
    lines.append(f'查询帧 {report["counts"]["query"]} 条、CCCD {report["counts"]["cccd"]} 条'
                 '（都是正常轮询/订阅，不列表）；下表是其余每一笔：')
    lines.append('')
    lines.append('| 相对时刻 (s) | 类型 | 帧 | 当时速度 (km/h) |')
    lines.append('|---|---|---|---|')
    notable = [w for w in report['writes'] if w['kind'] not in ('query', 'cccd')]
    for w in notable:
        v = '—' if w['speed'] is None else f'{w["speed"]:.2f}'
        lines.append(f'| {w["t"]:.2f} | {w["kind"]} | `{w["hex"]}` | {v} |')
    if not notable:
        lines.append('| — | 无 | — | — |')
    lines.append('')
    lines.append('## B — 静止放行')
    lines.append('')
    for item in report['b_reasons']:
        lines.append(f'- {item}')
    lines.append('')
    lines.append('## C — 运动中拦截')
    lines.append('')
    for item in report['c_reasons']:
        lines.append(f'- {item}')
    lines.append('')
    lines.append('> 本判定只覆盖 HCI 事实：写了什么、写的时候车在不在动。'
                 '「当时确实滑了滑块」「界面变灰并显示阻止原因」必须由 C 段截图与人证补足——'
                 '被闸门挡住的滑动不会进 `commands_sent`，日志里查不到。')
    return '\n'.join(lines)


def main(argv=None):
    ap = argparse.ArgumentParser(
        description='锁车闸门 HCI 侧判定：运动中是否一条锁帧都没写出、静止时是否恰好各写一次',
        epilog='退出码：0 两态均通过；1 未通过或 C 未被检验；2 输入不可用（不改判）')
    ap.add_argument('--btsnoop', required=True, help='手机侧 HCI 日志（唯一来源：raw 导出不含出站写）')
    ap.add_argument('--calibration', required=True, help='含 speed 字段的标定文件（必备前置）')
    ap.add_argument('--cmd', type=int, help='标定文件未写帧组时手工指定（如 10）')
    ap.add_argument('--a1', type=int)
    ap.add_argument('--a2', type=int)
    ap.add_argument('--limit', type=float, default=1.0, help='拦截阈值 km/h（默认 1.0）')
    ap.add_argument('--expect-lock', type=int, default=1, help='期望关锁写出次数（默认 1）')
    ap.add_argument('--expect-unlock', type=int, default=1, help='期望解锁写出次数（默认 1）')
    ap.add_argument('--min-moving-samples', type=int, default=5,
                    help='判定「确实动过」所需的最少超阈值帧数（默认 5）')
    ap.add_argument('--min-moving-seconds', type=float, default=2.0,
                    help='判定「确实动过」所需的超阈值持续时长（默认 2 s）')
    ap.add_argument('--max-plausible', type=float, default=60.0,
                    help='超过这个速度视为标定不适用于本会话（默认 60 km/h）')
    ap.add_argument('--max-gap-ms', type=float, default=1500.0,
                    help='写时刻与最近速度帧的最大间隔（默认 1500 ms，超出即判速度未知）')
    ap.add_argument('--report', help='输出 Markdown 报告')
    ap.add_argument('--json', dest='json_out', help='输出结构化结果')
    args = ap.parse_args(argv)

    path = Path(args.btsnoop)
    try:
        if path.suffix.lower() in ('.jsonl', '.json'):
            raise sc.InputError(
                '锁闸门判定需要出站写记录，而 app 导出的 raw/evidence 只有通知、没有写；'
                '请用手机侧 btsnoop（开发者选项「启用蓝牙 HCI 信息收集日志」）')
        if not path.exists():
            raise sc.InputError(f'{path} 不存在')
        recs, span = sc.att_records(path)
        if not recs:
            raise sc.InputError(f'{path} 里没有 ATT 通知/写记录')
    except sc.InputError as e:
        print(f'错误：{e}', file=sys.stderr)
        return 2

    # 先把出站写清点出来：这一步只依赖日志本身，不依赖标定。
    # 标定缺失时也要把清点结果打出来——否则用户拿不到「这份日志到底写了什么」这条事实。
    writes: list[Write] = []
    for rel, opcode, _handle, value in recs:
        if opcode not in sc.ATT_WRITE:
            continue
        kind, _frames = classify(value)
        writes.append(Write(rel, kind, value.hex()))
    counts = {k: sum(1 for w in writes if w.kind == k)
              for k in ('lock', 'unlock', 'query', 'other', 'cccd', 'undecoded')}
    print(f'出站写清点：{counts}（会话 {span:.1f} s）')

    try:
        group, field = load_speed_field(Path(args.calibration), args.cmd, args.a1, args.a2)
    except sc.InputError as e:
        print(f'错误：{e}', file=sys.stderr)
        return 2

    series = speed_series(recs, group, field)
    if not series:
        print(f'错误：日志里没有帧组 0x{group[0]:02x}/0x{group[1]:02x}/0x{group[2]:02x} 的通知，'
              '标定文件与本次会话对不上', file=sys.stderr)
        return 2
    times = [t for t, _ in series]
    vmax = max(v for _, v in series)
    vmin = min(v for _, v in series)
    if vmax > args.max_plausible:
        print(f'错误：解出的最大速度 {vmax:.1f} km/h 超过 {args.max_plausible:g}，'
              '标定字段大概率不适用于本会话，拒绝下结论', file=sys.stderr)
        return 2

    for w in writes:
        if w.kind != 'undecoded':
            w.speed, w.gap_s = speed_at(series, times, w.t, args.max_gap_ms / 1000.0)

    moving = runs_above(series, args.limit)
    qualified = [r for r in moving
                 if len(r) >= args.min_moving_samples and (r[-1][0] - r[0][0]) >= args.min_moving_seconds]

    b_reasons, c_reasons = [], []
    b_ok = True
    if counts['undecoded']:
        b_ok = False
        b_reasons.append(f'✗ 有 {counts["undecoded"]} 条出站写解不出帧——无法排除它是锁帧，'
                         '不能判通过（先查是不是分片写或换了协议版本）')
    if counts['lock'] != args.expect_lock:
        b_ok = False
        b_reasons.append(f'✗ 关锁写出 {counts["lock"]} 次，期望 {args.expect_lock} 次')
    else:
        b_reasons.append(f'✓ 关锁写出 {counts["lock"]} 次')
    if counts['unlock'] != args.expect_unlock:
        b_ok = False
        b_reasons.append(f'✗ 解锁写出 {counts["unlock"]} 次，期望 {args.expect_unlock} 次')
    else:
        b_reasons.append(f'✓ 解锁写出 {counts["unlock"]} 次')
    for w in writes:
        if w.kind not in ('lock', 'unlock'):
            continue
        if w.speed is None:
            b_ok = False
            gap = '无速度帧' if w.gap_s is None else f'间隔 {w.gap_s:.2f} s'
            b_reasons.append(f'✗ {w.kind}（{w.t:.2f} s）当时速度未知（{gap}），无法证明车辆静止')
        elif abs(w.speed) > args.limit:
            b_ok = False
            b_reasons.append(f'✗ {w.kind}（{w.t:.2f} s）写出时速度 {w.speed:.2f} km/h > {args.limit:g}')
        else:
            b_reasons.append(f'✓ {w.kind}（{w.t:.2f} s）写出时速度 {w.speed:.2f} km/h ≤ {args.limit:g}')

    if not moving:
        c_ok = False
        c_reasons.append(f'✗ 会话全程速度 ≤ {args.limit:g} km/h，**C 没有被检验**——'
                         '日志证明不了闸门拦住过任何东西')
    elif not qualified:
        c_ok = False
        best = max(moving, key=len)
        c_reasons.append(f'✗ 超阈值时段最长仅 {len(best)} 帧 / {best[-1][0] - best[0][0]:.2f} s，'
                         f'低于「{args.min_moving_samples} 帧且 ≥{args.min_moving_seconds:g} s」的门槛，'
                         '不足以当作一次推行')
    else:
        c_ok = True
        for i, run in enumerate(qualified, 1):
            inside = [w for w in writes if w.kind in ('lock', 'unlock') and run[0][0] <= w.t <= run[-1][0]]
            dur = run[-1][0] - run[0][0]
            span_txt = f'{run[0][0]:.2f}–{run[-1][0]:.2f} s（{dur:.2f} s，峰值 {max(v for _, v in run):.2f} km/h）'
            if inside:
                c_ok = False
                c_reasons.append(f'✗ 第 {i} 段运动窗口 {span_txt} 内有 '
                                 f'{len(inside)} 条锁帧写出（{", ".join(f"{w.kind}@{w.t:.2f}s" for w in inside)}）'
                                 '——闸门失效')
            else:
                c_reasons.append(f'✓ 第 {i} 段运动窗口 {span_txt} 内锁帧写出 0 条')

    verdict = '通过（B/C 两态均有证据）' if (b_ok and c_ok) else (
        '未通过' if (not b_ok) else 'B 通过，C 未检验')
    payload = {
        'verdict': 'pass' if (b_ok and c_ok) else 'fail',
        'b_pass': b_ok, 'c_pass': c_ok,
        'span_s': round(span, 2), 'frames': len(series), 'writes_total': len(writes),
        'counts': counts, 'speed_min': round(vmin, 2), 'speed_max': round(vmax, 2),
        'source': str(path), 'calibration': args.calibration,
        'group': list(group), 'field': field,
        'writes': [{'t': round(w.t, 3), 'kind': w.kind, 'hex': w.hex,
                    'speed': None if w.speed is None else round(w.speed, 2)} for w in writes],
        'moving_windows': [{'start': round(r[0][0], 2), 'end': round(r[-1][0], 2),
                            'samples': len(r), 'peak': round(max(v for _, v in r), 2)} for r in qualified],
        'b_reasons': b_reasons, 'c_reasons': c_reasons,
    }
    if args.report:
        Path(args.report).write_text(render(payload, verdict, None), encoding='utf-8')
    if args.json_out:
        Path(args.json_out).write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding='utf-8')

    print(f'速度范围 {vmin:.2f}–{vmax:.2f} km/h；运动窗口 {len(qualified)} 段')
    for line in b_reasons + c_reasons:
        print('  ' + line)
    print(f'结论：{verdict}')
    return 0 if (b_ok and c_ok) else 1


if __name__ == '__main__':
    sys.exit(main())
