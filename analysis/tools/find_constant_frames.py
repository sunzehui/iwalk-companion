#!/usr/bin/env python3
"""Mine a binary for byte-contiguous, checksum-valid 55AA iWalk frames.

Usage: find_constant_frames.py <file.so|file.bin> [--max-payload 40]

Purpose: any hit is an independent test vector for the frame/checksum
rule (currently exactly one known vector: 55aa02ff0103fafe @0xa6dca0
in libcpp_empty_test.so arm64). Absence of hits does NOT contradict
the rule — compilers embed constants as immediates, and frames with
L!=2 were not stored contiguously in the tested binaries.
"""
import argparse, re, sys

sys.path.insert(0, '/root/iwalk/analysis/protocol')
from ibwalk_frame import checksum  # noqa: E402


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('path')
    ap.add_argument('--max-payload', type=int, default=40)
    args = ap.parse_args()
    d = open(args.path, 'rb').read()
    n = 0
    for m in re.finditer(rb'\x55\xaa', d):
        i = m.start()
        if i + 6 > len(d):
            continue
        L = d[i + 2]
        if L < 2 or L > args.max_payload or i + L + 6 > len(d):
            continue
        cand = d[i:i + L + 6]
        if int.from_bytes(cand[L + 4:L + 6], 'little') == checksum(cand[2:4 + L]):
            print(f'@0x{i:x}: cmd=0x{cand[3]:02x} a1=0x{cand[4]:02x} '
                  f'a2=0x{cand[5]:02x} L={L} payload={cand[6:i+L+4-i].hex()}')
            n += 1
    print(f'# {n} checksum-valid candidates (FP rate per byte pair is low but nonzero; '
          f'verify each against disasm context)')


if __name__ == '__main__':
    main()
