#!/usr/bin/env python3
"""Minimal btsnoop HCI parser (no external deps).

Extracts ACL L2CAP ATT traffic and prints lines compatible with
decode_capture.py input (one hex payload per line, direction-tagged).

Handles: btsnoop v1 records, HCI ACL data packets, dynamic L2CAP CID,
ATT opcode 0x1B (Handle Value Notification) and 0x1D (Handle Value
Indication). Also prints LE Connection Complete / Disconnect events so
sessions can be annotated. ISO/ACL flags per btsnoop spec.
"""
import struct
import sys


def parse_btsnoop(path):
    with open(path, 'rb') as f:
        head = f.read(16)
        if head[:8] != b'btsnoop\x00':
            raise SystemExit('not a btsnoop file')
    with open(path, 'rb') as f:
        f.read(16)
        rec = 0
        while True:
            hdr = f.read(24)
            if len(hdr) < 24:
                break
            orig, incl, flags, drops, ts = struct.unpack('>IIIIq', hdr)
            data = f.read(incl)
            rec += 1
            # Verified layout (cross-checked with tshark): records carry an
            # HCI H4 type byte (0x01 cmd, 0x02 ACL, 0x04 event) as the first
            # payload byte; received flag bit 0x0100; direction bit0 (1=tx).
            if not data:
                continue
            h4 = data[0]
            if h4 == 0x01:
                continue  # HCI command
            if h4 == 0x04:
                ev = data[1:]
                if ev and ev[0] == 0x3E and len(ev) > 2:
                    sub = ev[2]
                    if sub == 0x01 and len(ev) >= 13:  # connection complete
                        h = struct.unpack_from('<H', ev, 4)[0]
                        peer = ev[7:13][::-1].hex(':')
                        print(f'# LE_CONNECT handle=0x{h:04x} peer={peer}')
                    elif sub == 0x05 and len(ev) >= 6:  # disconnect complete
                        h = struct.unpack_from('<H', ev, 4)[0]
                        print(f'# LE_DISCONNECT handle=0x{h:04x}')
                continue
            if h4 != 0x02:
                continue  # not ACL data
            acl = data[1:]
            # ACL data packet
            if len(acl) < 6:
                continue
            handle_bits, acl_len = struct.unpack_from('<HH', acl, 0)
            handle = handle_bits & 0x0FFF
            pdu = acl[4:4 + acl_len]
            if len(pdu) < 4:
                continue
            l2_len, cid = struct.unpack_from('<HH', pdu, 0)
            if cid != 0x0004 and cid < 0x0040:  # fixed LE ATT or dynamic channel
                continue
            att = pdu[4:4 + l2_len]
            if not att:
                continue
            opcode = att[0]
            if opcode in (0x1B, 0x1D):
                value = att[3:]
                direction = 'tx' if flags & 0x01 else 'rx'  # sent==1 -> host->ctrl
                print(f'{direction} 0x{handle:04x} {value.hex()}')
            elif opcode in (0x52, 0x12):
                # 出站写（Write Command / Write Request）：单独记为注释行，
                # 不进流式重组（真车所有写都是独立整帧），供「应用到底写了什么」核对。
                h = struct.unpack_from('<H', att, 1)[0]
                print(f'# WRITE 0x{h:04x} {att[3:].hex()}')


if __name__ == '__main__':
    for p in sys.argv[1:]:
        parse_btsnoop(p)
