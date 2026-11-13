#!/usr/bin/env python3
"""Enumerate call sites of Bluetooth send APIs with resolved w1..w5 immediates."""
from capstone import Cs, CS_ARCH_ARM64, CS_MODE_ARM

PATH = '/root/iwalk/downloads/so/lib_arm64-v8a_libcpp_empty_test.so'
TARGETS = {
    0x520290: 'SendReadCmdWithAddr',
    0x520350: 'SendWriteArray',
    0x520364: 'SendReadCmdWithAddr2',
    0x5202ec: 'SendWriteCmd',
    0x520400: 'SendReadBlackBoxWithAddr',
    0x52046c: 'SendReadCmdWithAddr3',
    0x5204c8: 'SendReadCmdWithAddr_HB',
    0x520524: 'SendReadCmdWithAddr_HB_BLE',
    0x5205b4: 'SendWriteCmd2',
    0x520618: 'SendWriteCmd_HB',
    0x520d70: 'SendFramePack_u8u8',
    0x51fd90: 'SendFramePack5',
    0x378b90: 'SendFramePack5_plt',
}
W = ('w1', 'w2', 'w3', 'w4', 'w5')

data = open(PATH, 'rb').read()
md = Cs(CS_ARCH_ARM64, CS_MODE_ARM)
md.skipdata = True


def parse_imm(ops):
    if '#' not in ops:
        return None
    tok = ops[ops.index('#') + 1:]
    shift = 0
    if 'lsl' in tok:
        tok, sh = tok.split('lsl', 1)
        try:
            shift = int(sh.strip().lstrip('#'), 0)
        except ValueError:
            return None
    tok = tok.split(',')[0].strip().lstrip('#')
    try:
        return int(tok, 0) << shift
    except ValueError:
        return None


imm = {}
out = []
prev_addr = None
for ins in md.disasm(data[0x385e70:0x385e70 + 0x6E43A0], 0x385E70):
    m, ops = ins.mnemonic, ins.op_str
    if prev_addr is not None and ins.address != prev_addr:
        pass  # discontinuity from skip; keep going
    prev_addr = ins.address + 4
    dst = ops.split(',')[0].strip() if ops else ''
    if m in ('mov', 'movz', 'orr') and dst in W:
        v = parse_imm(ops)
        if v is not None:
            imm[dst] = v & 0xFFFFFFFF
    elif m == 'movk' and dst in W:
        v = parse_imm(ops)
        if v is not None:
            imm[dst] = (imm.get(dst, 0) | v) & 0xFFFFFFFF
    elif m == 'mov' and dst in W:
        src = ops.split(',', 1)[1].strip()
        if src[0] in 'wx' and src[1:] in [w for w in W]:
            imm[dst] = imm.get('w' + src[1:])
        elif src in ('wzr', 'xzr'):
            imm[dst] = 0
    elif m == 'ldrb' or (m in ('stp', 'str', 'strb') and dst not in W):
        pass
    if m == 'bl':
        try:
            tgt = int(ops.lstrip('#'), 0)
        except ValueError:
            continue
        if tgt in TARGETS:
            out.append((ins.address, TARGETS[tgt], {k: imm.get(k) for k in W}))
            imm = {}
    elif m == 'ret':
        imm = {}

for addr, name, args in out:
    hx = {k: (f'0x{v:x}' if isinstance(v, int) else '?') for k, v in args.items()}
    print(f'0x{addr:x}\t{name}\t{hx}')
print(f'total: {len(out)} call sites')
