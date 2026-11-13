#!/usr/bin/env python3
"""Turn the APP's evidence JSON (Diagnostics -> 复制证据 JSON) into a
Phase-4 report: U01 UUID inventory, read values, notification frame stats.

Input is the JSON copied out of the app; or a file saved with it.
Frames inside raw_notifications are decoded with the confirmed codec;
anything undecodable is reported as-is (never dropped, never guessed).

Usage:
  evidence_report.py evidence.json [-o report.md] [--json stats.json]
"""
import argparse
import json
import sys
from collections import Counter, defaultdict
from datetime import datetime

sys.path.insert(0, str(__import__('pathlib').Path(__file__).resolve().parents[1] / 'protocol'))
from ibwalk_frame import Frame, StreamDecoder  # noqa: E402


def load(path: str) -> dict:
    with open(path, encoding='utf-8') as fh:
        text = fh.read().strip()
    try:
        return json.loads(text)
    except json.JSONDecodeError:
        # tolerate pasted text with a leading/trailing junk line
        start = text.find('{')
        end = text.rfind('}')
        if start < 0 or end < 0:
            raise SystemExit('input does not contain a JSON object')
        return json.loads(text[start:end + 1])


def decode_notifications(records: list[dict]):
    """Group raw notifications per characteristic and decode frames."""
    per_char = defaultdict(lambda: {'packets': 0, 'bytes': 0, 'frames': [],
                                    'undecoded': [], 'starts_55aa': 0})
    decoders = {}
    for r in records:
        key = r.get('char_uuid') or '(unknown-char)'
        g = per_char[key]
        g['packets'] += 1
        hexs = (r.get('hex') or '').strip()
        try:
            data = bytes.fromhex(hexs)
        except ValueError:
            g['undecoded'].append(hexs)
            continue
        g['bytes'] += len(data)
        if data[:2] == b'\x55\xaa':
            g['starts_55aa'] += 1
        d = decoders.setdefault(key, StreamDecoder())
        try:
            got = d.feed(data)
            g['frames'].extend(got)
            # bytes with no frame header anywhere in them never enter the
            # decoder's buffer; keep them visible as raw evidence
            if not got and b'\x55\xaa' not in data:
                g['undecoded'].append(hexs)
        except Exception as e:  # defensive: report, do not abort
            g['undecoded'].append(f'{hexs} ({e})')
    return per_char


def group_frames(frames: list[Frame]):
    groups = defaultdict(list)
    for f in frames:
        groups[(f.cmd, f.a1, f.a2)].append(f)
    return groups


def payload_offsets(payloads: list[bytes]):
    """Byte-level view only; no units or semantics are asserted."""
    rows = []
    maxlen = max((len(p) for p in payloads), default=0)
    for off in range(min(maxlen, 16)):
        vals = [p[off] for p in payloads if len(p) > off]
        if not vals:
            continue
        distinct = len(set(vals))
        rows.append({
            'offset': off,
            'distinct': distinct,
            'min': min(vals),
            'max': max(vals),
            'constant': distinct == 1,
        })
    return rows


def render_md(ev: dict, per_char, out) -> None:
    out.write('# 证据 JSON 分析报告\n\n')
    out.write(f"- 生成时间: {datetime.now().isoformat(timespec='seconds')}\n")
    out.write(f"- 会话: {ev.get('session')}\n")
    out.write(f"- 设备: {ev.get('device')}\n")
    out.write(f"- 采集时间: {ev.get('captured_at')}\n\n")

    adv = ev.get('advertisement') or {}
    out.write('## 1. 广播数据（U01 输入）\n\n')
    if adv:
        for k in ('serviceUuids', 'manufacturerData', 'serviceData',
                  'txPowerLevel', 'connectable'):
            if k in adv and adv[k] not in (None, [], {}):
                out.write(f'- **{k}**: `{json.dumps(adv[k], ensure_ascii=False)}`\n')
    else:
        out.write('（未提供广播数据）\n')

    tree = ev.get('gatt_tree') or []
    services = [ln for ln in tree if ln.startswith('S ')]
    chars = [ln for ln in tree if ln.strip().startswith('C ')]
    out.write('\n## 2. GATT 服务树（U01 结论）\n\n')
    out.write(f'服务 {len(services)} 个，特征 {len(chars)} 个。\n\n')
    for ln in tree:
        out.write(f'- `{ln}`\n')

    reads = ev.get('reads') or []
    out.write('\n## 3. 可读特征 Read 结果\n\n')
    if not reads:
        out.write('（无 Read 结果）\n')
    for r in reads:
        status = f"0x{r.get('hex','')}" if r.get('ok') else f"ERR {r.get('error')}"
        out.write(f"- `{r.get('char_uuid')}` (svc `{r.get('service_uuid')}`): {status}\n")

    records = ev.get('raw_notifications') or []
    out.write(f'\n## 4. 通知帧（{len(records)} 包）\n\n')
    if not per_char:
        out.write('（无通知数据）\n')
    for char, g in sorted(per_char.items()):
        out.write(f'### 特征 `{char}`\n\n')
        out.write(f"- 包数 {g['packets']}，累计 {g['bytes']} 字节，"
                  f"解出帧 {len(g['frames'])}，"
                  f"以 55AA 开头的包 {g['starts_55aa']}\n")
        if g['frames']:
            groups = group_frames(g['frames'])
            out.write('\n| CMD | A1 | A2 | 帧数 | payload 长度 |\n'
                      '|---|---|---|---|---|\n')
            for (cmd, a1, a2), fs in sorted(groups.items()):
                lens = sorted({len(f.payload) for f in fs})
                out.write(f'| 0x{cmd:02x} | 0x{a1:02x} | 0x{a2:02x} | '
                          f'{len(fs)} | {lens} |\n')
            out.write('\n<details><summary>字节偏移视图（仅为字节视角，'
                      '单位/语义需与界面读数对照）</summary>\n\n')
            for (cmd, a1, a2), fs in sorted(groups.items()):
                rows = payload_offsets([f.payload for f in fs])
                if not rows:
                    continue
                out.write(f'\n**CMD 0x{cmd:02x} A1 0x{a1:02x} A2 0x{a2:02x}**\n\n')
                out.write('| off | distinct | min | max | constant |\n'
                          '|---|---|---|---|---|\n')
                for row in rows:
                    out.write(f"| {row['offset']} | {row['distinct']} | "
                              f"{row['min']} | {row['max']} | "
                              f"{'yes' if row['constant'] else ''} |\n")
            out.write('\n</details>\n')
        if g['undecoded']:
            out.write(f"\n未解出字节（保留原文，不丢弃）：\n")
            for u in g['undecoded'][:20]:
                out.write(f'- `{u}`\n')
        out.write('\n')

    out.write('## 5. 需要人工补的对照\n\n')
    out.write('- 界面截图各时刻显示的电量/速度/电压，用于把偏移映射成单位（U03）\n')
    out.write('- 若官方 APP 与本次采集同时进行，请标注时间点\n')


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('evidence_json')
    ap.add_argument('-o', '--report', default=None, help='markdown report path')
    ap.add_argument('--json', dest='stats_json', default=None,
                    help='machine-readable stats path')
    args = ap.parse_args()

    ev = load(args.evidence_json)
    per_char = decode_notifications(ev.get('raw_notifications') or [])

    if args.report:
        with open(args.report, 'w', encoding='utf-8') as fh:
            render_md(ev, per_char, fh)
        print(f'report written: {args.report}')
    else:
        render_md(ev, per_char, sys.stdout)

    if args.stats_json:
        stats = {
            'session': ev.get('session'),
            'device': ev.get('device'),
            'services': [ln[2:] for ln in ev.get('gatt_tree') or []
                         if ln.startswith('S ')],
            'characteristics': [ln.strip()[2:] for ln in ev.get('gatt_tree') or []
                                if ln.strip().startswith('C ')],
            'notifications': {
                char: {
                    'packets': g['packets'],
                    'bytes': g['bytes'],
                    'frames': len(g['frames']),
                    'groups': [
                        {'cmd': f'0x{c:02x}', 'a1': f'0x{a1:02x}',
                         'a2': f'0x{a2:02x}', 'count': len(fs)}
                        for (c, a1, a2), fs in sorted(
                            group_frames(g['frames']).items())
                    ],
                }
                for char, g in per_char.items()
            },
        }
        with open(args.stats_json, 'w', encoding='utf-8') as fh:
            json.dump(stats, fh, ensure_ascii=False, indent=2)
        print(f'stats written: {args.stats_json}')


if __name__ == '__main__':
    main()
