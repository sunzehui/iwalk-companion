#!/usr/bin/env python3
"""Build a synthetic btsnoop file containing LE notifications carrying
iWalk frames, then run it through btsnoop_att.py + decode_capture.py
to validate the whole offline pipeline."""
import struct, subprocess, sys, os, shutil

TOOLS = os.path.dirname(os.path.abspath(__file__))


def record(ts_us, flags, packet):
    return struct.pack('>IIIIq', len(packet), len(packet), flags, 0, ts_us) + packet


def acl_notification(handle, value):
    att = bytes([0x1B]) + struct.pack('<H', handle) + value
    l2cap = struct.pack('<HH', len(att), 0x0004) + att  # fixed LE ATT CID
    acl = struct.pack('<HH', handle | (0 << 12), len(l2cap)) + l2cap
    return b'\x02' + acl  # H4 ACL prefix (tshark-verified layout)


def le_conn_complete(peer_mac_reversed, handle):
    # HCI LE meta event: code 0x3E, param len, subevent, status, handle, role, peer...
    params = bytes([0x01, 0x00]) + struct.pack('<H', handle) + bytes([0x00]) + peer_mac_reversed + struct.pack('<HHB', 24, 0, 0)
    return b'\x04' + bytes([0x3E, len(params)]) + params  # H4 event prefix


def main():
    ts = 1_700_000_000_000_000
    recs = []
    # sent (host->controller) marker record then connect event
    mac = bytes.fromhex('aabbccddeeff00')[::-1]
    ev = le_conn_complete(mac, 0x0040)
    recs.append(record(ts, 0x0100, ev))  # received, H4 prefix carries type
    # note: real Android "Bluetooth HCI snoop log" files set the 0x0100 flag
    # bit on received packets; without it tshark's H4 dissector misreads type.
    frames = [
        bytes.fromhex('55aa02ff0103fafe'),
        bytes.fromhex('55aa03060101') + b'\x01' ,  # crafted below
    ]
    # build proper L=3 frame with checksum via codec
    sys.path.insert(0, os.path.join(TOOLS, '..', 'protocol'))
    from ibwalk_frame import Frame
    f2 = Frame(cmd=0x06, a1=0x01, a2=0x05, payload=bytes([0x2A])).encode()
    # split f2 across two notifications
    recs.append(record(ts + 1, 0x0100, acl_notification(0x0014, frames[0])))
    recs.append(record(ts + 2, 0x0100, acl_notification(0x0014, f2[:5])))
    recs.append(record(ts + 3, 0x0100, acl_notification(0x0014, f2[5:])))

    with open('/tmp/synth_btsnoop.log', 'wb') as f:
        f.write(b'btsnoop\x00' + struct.pack('>II', 1, 1002))
        for r in recs:
            f.write(r)

    att = subprocess.run([sys.executable, os.path.join(TOOLS, 'btsnoop_att.py'),
                          '/tmp/synth_btsnoop.log'], capture_output=True, text=True)
    print('--- btsnoop_att output ---')
    print(att.stdout, att.stderr)
    # cross-check with tshark if available
    if shutil.which('tshark'):
        ts_out = subprocess.run(['tshark', '-r', '/tmp/synth_btsnoop.log',
                                 '-Y', 'btatt.opcode == 0x1b', '-T', 'fields',
                                 '-e', 'btatt.value'], capture_output=True, text=True)
        vals = ts_out.stdout.split()
        assert '55aa02ff0103fafe' in vals, f'tshark cross-check failed: {vals}'
        print(f'--- tshark cross-check OK: {len(vals)} ATT notifications')
    with open('/tmp/synth_att_lines.txt', 'w') as f:
        f.write(att.stdout)
    dec = subprocess.run([sys.executable, os.path.join(TOOLS, 'decode_capture.py'),
                          '/tmp/synth_att_lines.txt'], capture_output=True, text=True)
    print('--- decode_capture output ---')
    print(dec.stdout, dec.stderr)
    assert '"cmd": "0xff"' in dec.stdout, 'FF frame missing'
    assert '"cmd": "0x06"' in dec.stdout, 'split 06 frame not reassembled'
    print('PIPELINE OK')


if __name__ == '__main__':
    main()
