#!/usr/bin/env python3
"""Disassemble a function from an ELF (AArch64) by virtual address."""
import sys, struct
from capstone import Cs, CS_ARCH_ARM64, CS_MODE_ARM

def load_elf(path):
    data = open(path, 'rb').read()
    assert data[:4] == b'\x7fELF'
    e_phoff = struct.unpack_from('<Q', data, 0x20)[0]
    e_phentsize = struct.unpack_from('<H', data, 0x36)[0]
    e_phnum = struct.unpack_from('<H', data, 0x38)[0]
    segs = []
    for i in range(e_phnum):
        o = e_phoff + i * e_phentsize
        p_type, p_flags, p_offset, p_vaddr, _, p_filesz, p_memsz = struct.unpack_from('<IIQQQQQ', data, o)[0:7] if False else \
            (struct.unpack_from('<I', data, o)[0], struct.unpack_from('<I', data, o+4)[0],
             struct.unpack_from('<Q', data, o+8)[0], struct.unpack_from('<Q', data, o+16)[0],
             None, struct.unpack_from('<Q', data, o+32)[0], struct.unpack_from('<Q', data, o+40)[0])
        if p_type == 1:  # PT_LOAD
            segs.append((p_vaddr, p_offset, p_filesz, p_memsz))
    return data, segs

def vaddr_to_off(segs, va):
    for p_vaddr, p_offset, p_filesz, p_memsz in segs:
        if p_vaddr <= va < p_vaddr + p_memsz:
            return p_offset + (va - p_vaddr)
    return None

def main():
    path, va_hex, size = sys.argv[1], sys.argv[2], int(sys.argv[3], 0)
    start = int(va_hex, 16)
    data, segs = load_elf(path)
    off = vaddr_to_off(segs, start)
    if off is None:
        print('vaddr not in PT_LOAD'); return 1
    code = data[off:off+size]
    md = Cs(CS_ARCH_ARM64, CS_MODE_ARM)
    md.detail = False
    for ins in md.disasm(code, start):
        print(f'0x{ins.address:x}: {ins.mnemonic}\t{ins.op_str}')

if __name__ == '__main__':
    main()
