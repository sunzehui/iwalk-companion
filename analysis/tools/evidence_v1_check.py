#!/usr/bin/env python3
"""校验 iwapp-evidence-v1 证据包（evidence_*.json + raw_*.jsonl）。

旧版 `evidence_report.py` 吃的是取证 APP「复制证据 JSON」的老格式
（raw_notifications/services/reads）。伴侣应用从 v0.3 起导出的是
`iwapp-evidence-v1`：证据清单 + 一份逐行 JSONL。本工具只做「对得上」的核对，
不解释字段语义（U03 未关闭前不猜单位）。

用法：
  evidence_v1_check.py <evidence.json> [--raw raw.jsonl] [--json out.json]

核对项：
1. schema 必须是 iwapp-evidence-v1；app/source 是否一致。
2. raw_packets == raw 文件行数；raw 行 session 与证据 session 相同。
3. frame_groups 的 frames 之和 == frames_total。
4. 每条 raw 行 hex 能被 StreamDecoder 重组出帧，且重组的帧总数 >= frames_total
   （时间线按上限截断时允许 frames_total < 重组数，不允许反向）。
5. 逐帧校验和（在重组时由 StreamDecoder 校验），统计坏帧。
6. transport 必备键、commands_sent 每条必带 vehicle_state=unconfirmed。
"""
import argparse
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'protocol'))
from ibwalk_frame import StreamDecoder  # noqa: E402

REQUIRED_TRANSPORT = (
    'id', 'label', 'is_demo', 'kind', 'mtu_now', 'max_payload_per_packet',
    'transport_group', 'service_uuid', 'write_uuid', 'notify_uuid',
)


def check(evidence_path: Path, raw_path: Path | None) -> dict:
    ev = json.loads(evidence_path.read_text(encoding='utf-8'))
    res = {'evidence': str(evidence_path), 'problems': [], 'info': {}}

    def bad(msg: str) -> None:
        res['problems'].append(msg)

    if ev.get('schema') != 'iwapp-evidence-v1':
        bad(f"schema 不是 iwapp-evidence-v1: {ev.get('schema')!r}")
    if not ev.get('app'):
        bad('缺少 app 字段')
    if not ev.get('session'):
        bad('缺少 session')

    if raw_path is None:
        # 证据与 raw 同一导出批次，文件名只差前缀：evidence_ 与 raw_
        evid_name = evidence_path.name
        raw_name = evid_name.replace('evidence_', 'raw_', 1).replace('.json', '.jsonl')
        cand = evidence_path.parent / raw_name
        if not cand.exists():
            cands = sorted(evidence_path.parent.glob(f"raw_{ev.get('session', '')}*.jsonl"))
            cand = cands[0] if cands else None
        raw_path = cand
    res['info']['raw'] = str(raw_path) if raw_path else None

    frames_total = ev.get('frames_total')
    raw_packets = ev.get('raw_packets')

    rows = []
    if raw_path and raw_path.exists():
        rows = [json.loads(line) for line in raw_path.read_text(encoding='utf-8').splitlines() if line.strip()]
        if raw_packets != len(rows):
            bad(f'raw_packets={raw_packets} 与 raw 行数 {len(rows)} 不等')
        for r in rows:
            if r.get('session') and r['session'] != ev.get('session'):
                bad(f"raw 行 session={r['session']} 与证据 session={ev.get('session')} 不同")
                break
        sources = {r.get('source') for r in rows}
        if ev.get('app') and sources - {ev.get('app')}:
            bad(f'raw 行 source 与 app 不一致: {sorted(sources)}')
        res['info']['raw_rows'] = len(rows)
        res['info']['raw_bytes'] = sum(len((r.get('hex') or '')) // 2 for r in rows)
    else:
        bad('找不到 raw JSONL（未传 --raw 且同目录无匹配文件）')

    groups = ev.get('frame_groups') or []
    group_frames = sum(int(g.get('frames', 0)) for g in groups)
    res['info']['groups'] = len(groups)
    res['info']['group_frames_sum'] = group_frames
    if frames_total != group_frames:
        bad(f'frames_total={frames_total} 与帧组之和 {group_frames} 不等')
    res['info']['frames_total'] = frames_total
    timeline = len(ev.get('evidence_timeline') or [])
    res['info']['timeline'] = timeline
    if timeline > int(frames_total or 0):
        bad(f'时间线 {timeline} 条多于 frames_total={frames_total}')

    transport = ev.get('transport') or {}
    # 演示传输没有真实 MTU/传输组，只要求最小键；真车链路必须是完整键集
    required = ('id', 'label', 'is_demo', 'kind') if transport.get('is_demo') else REQUIRED_TRANSPORT
    missing = [k for k in required if k not in transport]
    if missing:
        bad(f'transport 缺键: {missing}')
    res['info']['transport_group'] = transport.get('transport_group')
    res['info']['mtu_now'] = transport.get('mtu_now')
    res['info']['is_demo'] = transport.get('is_demo')

    for i, cmd in enumerate(ev.get('commands_sent') or []):
        if cmd.get('vehicle_state') != 'unconfirmed':
            bad(f"commands_sent[{i}] vehicle_state={cmd.get('vehicle_state')!r}")

    # 重组与校验和。raw 存的是线上字节：整个通知流 XOR 0x55（线上头 00 FF），
    # 先按首行判定编码，再交给 StreamDecoder。
    wire = None
    for r in rows:
        h = (r.get('hex') or '')
        if len(h) >= 4:
            wire = not h.lower().startswith('55aa')
            break
    res['info']['wire_xor_0x55'] = wire
    decoder = StreamDecoder()
    assembled = 0
    for r in rows:
        try:
            data = bytes.fromhex(r.get('hex') or '')
        except ValueError:
            bad(f"raw 行 hex 非法: {r.get('hex')!r}")
            continue
        if wire:
            data = bytes(b ^ 0x55 for b in data)
        assembled += len(decoder.feed(data))
    bad_frames = len(decoder.rejected)
    res['info']['frames_assembled'] = assembled
    res['info']['bad_frames'] = bad_frames
    if assembled < int(frames_total or 0):
        bad(f'重组帧 {assembled} 少于 frames_total={frames_total}')
    if bad_frames:
        bad(f'存在 {bad_frames} 个校验/解析失败片段')
    return res


def main() -> int:
    ap = argparse.ArgumentParser(description='校验 iwapp-evidence-v1 证据包')
    ap.add_argument('evidence')
    ap.add_argument('--raw', default=None)
    ap.add_argument('--json', default=None, help='把核对结果写成 JSON')
    args = ap.parse_args()

    res = check(Path(args.evidence), Path(args.raw) if args.raw else None)
    print(json.dumps(res['info'], ensure_ascii=False, indent=2))
    if res['problems']:
        print('PROBLEMS:')
        for p in res['problems']:
            print(' -', p)
        print('RESULT: FAIL')
    else:
        print('RESULT: OK')
    if args.json:
        Path(args.json).write_text(json.dumps(res, ensure_ascii=False, indent=2), encoding='utf-8')
    return 0 if not res['problems'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
