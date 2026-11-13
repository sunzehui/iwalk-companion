#!/usr/bin/env python3
"""Payload inspector for decoded iWalk frames (Phase-4 aid).

Reads frames.jsonl (from run_pipeline.sh) and prints, per distinct
(cmd,a1,a2) group, the observed payload byte values per offset with
candidate integer interpretations. It intentionally makes NO claim
about units or semantics — it only helps correlate byte movement
with observed UI states (doc.md: evidence first).

Usage: payload_inspector.py frames.jsonl [--group-by move]
"""
import argparse, json, struct, sys
from collections import defaultdict


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('frames_jsonl')
    ap.add_argument('-n', '--max-rows', type=int, default=40)
    args = ap.parse_args()

    groups = defaultdict(list)
    with open(args.frames_jsonl) as fh:
        for line in fh:
            try:
                rec = json.loads(line)
            except json.JSONDecodeError:
                continue
            if rec.get('parse') != 'known_frame':
                continue
            key = (rec['cmd'], rec['a1'], rec['a2'])
            groups[key].append(bytes.fromhex(rec['payload_hex']))

    if not groups:
        print('no known_frame records')
        return

    for key in sorted(groups):
        cmd, a1, a2 = key
        payloads = groups[key]
        maxlen = max(len(p) for p in payloads)
        print(f'\n== CMD {cmd} A1 {a1} A2 {a2}  frames={len(payloads)} '
              f'maxPayloadLen={maxlen}')
        for off in range(min(maxlen, 16)):
            vals = sorted({p[off] for p in payloads if len(p) > off})
            if not vals:
                continue
            first = payloads[0]
            i8 = lambda b: b - 256 if b > 127 else b
            line = (f'  off {off:2d}: distinct={len(vals):3d} '
                    f'range {vals[0]:#04x}..{vals[-1]:#04x} '
                    f'(u8 {vals[0]}..{vals[-1]}, i8 {i8(vals[0])}..{i8(vals[-1])})')
            if len(first) > off + 1:
                u16 = struct.unpack('<H', first[off:off + 2])[0]
                i16 = struct.unpack('<h', first[off:off + 2])[0]
                line += f'  first-frame u16le={u16} i16le={i16}'
            print(line)
    print('\nNOTE: 候选解释仅为字节视角，单位/语义需真机对照（U03）后回填表格。')


if __name__ == '__main__':
    main()
