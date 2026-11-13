#!/usr/bin/env python3
"""量化 官方App 查询帧(0x52 WriteCmd) → 车端通知(0x1B) 的往返延迟。"""
import sys, bisect
from collections import defaultdict
from statistics import median
import hci_quicklook as q

PATH = sys.argv[1] if len(sys.argv) > 1 else "snoop_043705_dl1001.log"
datalink = q.read_header(PATH)

tx, rx = [], []          # (sec, acl_handle, attr_handle, cmd, a1, a2, chkok, hex)
streams, t0 = {}, None
for _i, sent, ts, data in q.iter_records(PATH):
    if t0 is None:
        t0 = ts
    sec = (ts - t0) / 1_000_000.0
    for handle, opcode, ah, value in q.att_pdus(datalink, data):
        is_tx = opcode in (q.ATT_WRITE_CMD, q.ATT_WRITE_REQ, q.ATT_WRITE_SIGNED)
        st = streams.setdefault(("tx" if is_tx else "rx", handle), q.StreamState())
        for fr in st.feed(value):
            rep = q.frame_report(fr)
            if rep is None:
                continue
            line, (cmd, a1, a2) = rep
            rec = (sec, handle, ah, cmd, a1, a2, "chk OK" in line, fr.hex())
            (tx if is_tx else rx).append(rec)

rx.sort(); rxs = [r[0] for r in rx]
tx.sort()
tx_ok = sum(1 for t in tx if t[6]); rx_ok = sum(1 for r in rx if r[6])
print(f"查询帧 {len(tx)} (chk OK {tx_ok})   响应帧 {len(rx)} (chk OK {rx_ok})")
print(f"查询 attr handle 分布: " + ", ".join(f"0x{h:04x}×{n}" for h, n in
      sorted(((h, sum(1 for t in tx if t[2] == h)) for h in {t[2] for t in tx}), key=lambda x: -x[1])))
print(f"响应 attr handle 分布: " + ", ".join(f"0x{h:04x}×{n}" for h, n in
      sorted(((h, sum(1 for r in rx if r[2] == h)) for h in {r[2] for r in rx}), key=lambda x: -x[1])))

pairs, misses = [], []
for t in tx:
    lo = bisect.bisect_left(rxs, t[0])
    hit = None
    for k in range(lo, min(lo + 64, len(rx))):
        if rx[k][0] - t[0] > 0.5:
            break
        if rx[k][4] == t[4] and rx[k][5] == t[5]:      # A1+A2 同号
            hit = rx[k]; break
    (pairs if hit else misses).append((t, hit))

print(f"\n同号配对: {len(pairs)}/{len(tx)} 查询在 0.5s 内等到同 (A1,A2) 响应,未配对 {len(misses)}")
if pairs:
    lat = [r[0] - t[0] for t, r in pairs]
    srt = sorted(lat)
    print(f"延迟  中位 {median(lat)*1000:.1f} ms  p90 {srt[int(len(srt)*.9)]*1000:.1f} ms  "
          f"p99 {srt[int(len(srt)*.99)]*1000:.1f} ms  min {min(lat)*1000:.1f}  max {max(lat)*1000:.1f} ms")
    g = defaultdict(list)
    for (t, r) in pairs:
        g[(t[3], t[4], t[5])].append(r[0] - t[0])
    print(f"\n  {'查询CMD':>8} {'A1':>5} {'A2':>5} {'次数':>5}  {'中位延迟':>9} {'p90':>8}  响应CMD")
    for k, v in sorted(g.items(), key=lambda x: -len(x[1]))[:12]:
        vv = sorted(v)
        resp = [r[3] for (t, r) in pairs if (t[3], t[4], t[5]) == k][0]
        print(f"  0x{k[0]:02x}     0x{k[1]:02x}  0x{k[2]:02x}  x{len(v):<4} "
              f"{median(vv)*1000:7.1f} ms {vv[int(len(vv)*.9)]*1000:7.1f} ms  响应=0x{resp:02x}")
if misses:
    from collections import Counter
    c = Counter((t[7], t[3], t[4], t[5]) for t, _ in misses)
    print(f"\n未配对查询 top8:")
    for (hx, cmd, a1, a2), n in c.most_common(8):
        print(f"  {hx}  CMD=0x{cmd:02x} A1=0x{a1:02x} A2=0x{a2:02x}  x{n}")
