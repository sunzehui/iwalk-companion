#!/usr/bin/env python3
"""Offline decoder for captured iWalk BLE traffic.

Input formats:
  1. Plain hex lines / .hex file: one hex string per line (with or without
     spaces/colons, optional leading "direction " token like `rx 55aa...`).
  2. tshark CSV export with columns ...,btatt.value (header detected by
     looking for a hex-like column; falls back to last column).

Output: JSONL frames to stdout or -o file, plus a summary table.
Unknown/unparseable bytes are preserved as raw entries, never dropped.
"""
import argparse, csv, json, re, sys
from datetime import datetime, timezone

sys.path.insert(0, str(__import__('pathlib').Path(__file__).resolve().parents[1] / 'protocol'))
from ibwalk_frame import StreamDecoder, HEADER  # noqa: E402

HEX_RE = re.compile(r'^[0-9a-fA-F:\s,]+$')


def extract_hex(line: str) -> str | None:
    line = line.strip()
    if not line or line.startswith('#'):
        return None
    m = re.search(r'(?:^|\s)(?:rx|tx|n|w|i)?\s*[:>]?\s*([0-9a-fA-F:\s]{4,})\s*$', line)
    cand = None
    for tok in re.split(r'[,;]', line):
        tok = tok.strip().strip('"')
        if HEX_RE.match(tok) and len(re.sub(r'[^0-9a-fA-F]', '', tok)) >= 4:
            cand = tok
            break
    if cand is None and m:
        cand = m.group(1)
    if cand is None:
        return None
    return re.sub(r'[^0-9a-fA-F]', '', cand)


def frames_to_jsonl(decoder: StreamDecoder, data: bytes, meta: dict, out):
    """Returns (frames, rejected_count) for this chunk."""
    frames = decoder.feed(data)
    for f in frames:
        rec = {
            'ts': datetime.now(timezone.utc).isoformat(),
            **meta,
            'cmd': f'0x{f.cmd:02x}',
            'a1': f'0x{f.a1:02x}',
            'a2': f'0x{f.a2:02x}',
            'payload_hex': f.payload.hex(),
            'frame_hex': f.encode().hex(),
            'parse': 'known_frame',
        }
        out.write(json.dumps(rec, ensure_ascii=False) + '\n')
    rejected = 0
    while decoder.rejected:
        bad = decoder.rejected.pop(0)
        rejected += 1
        out.write(json.dumps({
            **meta,
            'parse': 'checksum_error',
            'hex': bad.hex(),
        }) + '\n')
    return frames, rejected


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('input', help='hex file or tshark csv')
    ap.add_argument('-o', '--output', default=None, help='jsonl output (default stdout)')
    ap.add_argument('--direction', default='unknown')
    ap.add_argument(
        '--wire', choices=('auto', 'plain', 'xor55'), default='auto',
        help='真车通知整帧 XOR 0x55（线上头 00 FF，E46）；auto 按首个数据块判定',
    )
    args = ap.parse_args()

    out = open(args.output, 'w') if args.output else sys.stdout
    decoder = StreamDecoder()
    total_bytes = 0
    total_frames = 0
    wire = None if args.wire == 'auto' else (args.wire == 'xor55')

    with open(args.input) as fh:
        first = True
        for lineno, line in enumerate(fh, 1):
            raw = line.rstrip('\n')
            if first and ',' in raw:
                try:
                    reader = csv.reader([raw])
                    cols = next(reader)
                    if any('value' in c.lower() for c in cols):
                        first = False
                        continue
                except Exception:
                    pass
                first = False
            if ',' in raw:
                parts = [p.strip().strip('"') for p in raw.split(',')]
                cand = None
                for p in reversed(parts):
                    if HEX_RE.match(p) and len(re.sub(r'[^0-9a-fA-F]', '', p)) >= 6:
                        cand = p
                        break
                if cand is None:
                    continue
                hexs = re.sub(r'[^0-9a-fA-F]', '', cand)
            else:
                hexs = extract_hex(raw)
                if hexs is None:
                    continue
            data = bytes.fromhex(hexs if len(hexs) % 2 == 0 else hexs + '0')
            if wire is None:
                # 真车流整帧 XOR 0x55：明文头 55AA 会变成线上头 00FF
                wire = data[:2] == b'\x00\xff'
            if wire:
                data = bytes(b ^ 0x55 for b in data)
                hexs = data.hex()
            direction = args.direction
            dm = re.match(r'\s*(rx|tx)\b', raw)
            if dm:
                direction = dm.group(1)
            meta = {
                'source': args.input,
                'line': lineno,
                'direction': direction,
                'raw_line': raw[:200],
            }
            total_bytes += len(data)
            frames, rejected = frames_to_jsonl(decoder, data, meta, out)
            total_frames += len(frames)
            if not frames and data and rejected == 0:
                out.write(json.dumps({**meta, 'parse': 'unrecognized', 'hex': hexs}) + '\n')

    summary = {
        'summary': True,
        'total_bytes': total_bytes,
        'decoded_frames': total_frames,
        'wire_xor_0x55': bool(wire),
        'pending_buffer_hex': bytes(decoder._buf).hex(),
    }
    out.write(json.dumps(summary) + '\n')
    if args.output:
        out.close()


if __name__ == '__main__':
    main()
