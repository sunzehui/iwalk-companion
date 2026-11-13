#!/usr/bin/env python3
"""一次性侦察脚本:链路生命周期 + 通知按 ACL handle/时间分布(判断多 App 是否共用同一条链路)。"""
import struct
import collections
import sys

path = sys.argv[1]
d = open(path, "rb").read()
off = 16
recs = []
t0 = None
while off + 24 <= len(d):
    orig, incl, flags = struct.unpack(">III", d[off:off + 12])
    ts = struct.unpack(">q", d[off + 12:off + 20])[0]
    data = d[off + 24:off + 24 + incl]
    off += 24 + incl
    if t0 is None:
        t0 = ts
    recs.append((round((ts - t0) / 1e6, 2), flags, data))
print(f"记录数 {len(recs)}  尾对齐EOF {off == len(d)}")

links = []
for t, f, data in recs:
    if not data:
        continue
    if data[0] == 4:
        e = data[1]
        body = data[3:]
        if e == 0x03 and len(body) >= 9:
            h = struct.unpack("<H", body[0:2])[0] & 0xFFF
            a = body[3:9][::-1]
            links.append((t, "BR_CONN_COMPLETE", f"handle=0x{h:04x} addr={a.hex(':')}"))
        if e == 0x05 and len(body) >= 3:
            h, st = struct.unpack("<HB", body[0:3])
            links.append((t, "DISCONNECT", f"handle=0x{h & 0xFFF:04x} status={st}"))
        if e == 0x3E and body:
            sub = body[0]
            if sub == 0x01 and len(body) >= 12:
                h = struct.unpack("<H", body[1:3])[0]
                role = body[3]
                a = body[6:12][::-1]
                links.append((t, "LE_CONN_COMPLETE", f"handle=0x{h:04x} role={role} addr={a.hex(':')}"))
            if sub == 0x05 and len(body) >= 3:
                h = struct.unpack("<H", body[1:3])[0]
                links.append((t, "LE_DISCONNECT", f"handle=0x{h:04x}"))

print("\n链路生命周期:")
for t, k, v in links:
    print(f"  +{t}s {k} {v}")

notif = collections.defaultdict(list)
for t, f, data in recs:
    if data[:1] != b"\x02":
        continue
    hinfo, dlen = struct.unpack("<HH", data[1:5])
    body = data[5:5 + dlen]
    if len(body) < 4:
        continue
    l2, c = struct.unpack("<HH", body[:4])
    if c != 4:
        continue
    att = body[4:4 + l2]
    if att and att[0] == 0x1B:
        notif[hinfo & 0xFFF].append(t)

print("\n车→手机 通知(ATT opcode 0x1b)按 ACL handle:")
for h, ts in sorted(notif.items()):
    print(f"  handle=0x{h:04x}  x{len(ts)}  {ts[0]:+.1f}s → {ts[-1]:+.1f}s")

print("\n每条链路上通知的 60 秒分桶计数:")
for h, ts in sorted(notif.items()):
    buckets = collections.Counter(int(t // 60) for t in ts)
    line = "  ".join(f"{m}min:{buckets[m]:03d}" for m in sorted(buckets))
    print(f"  handle=0x{h:04x} {line}")
