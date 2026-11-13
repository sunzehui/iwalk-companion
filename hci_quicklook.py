#!/usr/bin/env python3
"""btsnoop 快速侦察:找官方 App 发了什么帧、车端推什么帧、周期多少。

零依赖,python3 直接跑:

    python3 hci_quicklook.py btsnoop_hci.log

典型来源:
  - Android 开发者选项「Bluetooth HCI snoop log」→「导出日志文件」/ bugreport
  - nRF Connect 报文采集导出的 .cfa/.log 不支持,只吃 btsnoop v1

输出:
  1. SENT 方向的应用层帧(app→车);凑不成帧的原始写字节也会打印
     (CCCD enable 之类的描述符写会在这里显形,而不是被静默吞掉)
  2. RECV 方向重组后的帧(车→app),按 (CMD,A1,A2) 聚合出现次数与间隔
     中位数——车端主动推周期在这里读
  3. SENT 帧同样按 hex 聚合,给出次数与间隔中位数(心跳/查询周期)

编码模式(每方向、每连接自动检测,互不干扰):
  - 明文:   帧头 55 AA
  - XOR0x55: 真车报文采集实证(2026-10)通知载荷整体 ⊕0x55,
             线上帧头呈 00 FF,补零段呈 5555…;检出后整流转明再重组
  检测只看每个 ATT PDU 值的头 2 字节;若某包从帧中间切开且此前未定型,
  该包按原样缓冲(后续包会定型,残留字节计入统计)。

帧规则 (analysis/01_static_analysis_report.md,真车 XOR 还原后 chk 全对):
  55 AA | L | CMD | A1 | A2 | payload(L-2B) | chk_lo chk_hi,总长 L+6
  chk = (~uint16(sum(bytes[2 .. L+3]))) & 0xFFFF 小端

注:btsnoop 时间戳纪元是 2000-01-01 而非 Unix;本脚本只用相对时间,无影响。
"""

import struct
import sys
from collections import defaultdict
from statistics import median

ATT_WRITE_REQ = 0x12
ATT_WRITE_RSP = 0x13
ATT_NOTIFY = 0x1B
ATT_INDICATE = 0x1D
ATT_WRITE_SIGNED = 0x51
ATT_WRITE_CMD = 0x52
L2CAP_ATT_CID = 0x0004

PLAIN_HEADER = b"\x55\xaa"
XOR_HEADER = b"\x00\xff"  # PLAIN_HEADER ⊕ 0x55
CCCD_VALUES = {b"\x01\x00": "CCCD enable(notify)",
               b"\x02\x00": "CCCD enable(indicate)",
               b"\x00\x00": "CCCD disable"}


def frames_from(buf):
    """55AA 滑窗重组,镜像 combiningFrame。返回 (帧列表, 剩余缓冲)。"""
    out = []
    while True:
        if len(buf) < 2:
            return out, buf
        if buf[0] != 0x55 or buf[1] != 0xAA:
            buf = buf[1:]
            continue
        if len(buf) < 3:
            return out, buf
        total = buf[2] + 6
        if len(buf) < total:
            return out, buf
        frame, buf = buf[:total], buf[total:]
        out.append(frame)


def frame_report(frame):
    if len(frame) < 6:
        return None
    l = frame[2]
    if l + 6 != len(frame):
        return None
    cmd, a1, a2 = frame[3], frame[4], frame[5]
    payload = frame[6:4 + l]
    chk_recv = frame[l + 4] | (frame[l + 5] << 8)
    chk_calc = (~sum(frame[2:4 + l])) & 0xFFFF
    ok = "chk OK" if chk_recv == chk_calc else f"chk BAD calc=0x{chk_calc:04x} recv=0x{chk_recv:04x}"
    pl = payload.hex() if payload else "-"
    return (f"CMD=0x{cmd:02x} A1=0x{a1:02x} A2=0x{a2:02x} "
            f"payload={pl} | {ok}"), (cmd, a1, a2)


def read_header(path):
    with open(path, "rb") as f:
        head = f.read(16)
    if len(head) < 16 or not head.startswith(b"btsnoop"):
        sys.exit("不是 btsnoop v1 文件(magic 不匹配)。确认导出的是 HCI snoop log。")
    version, datalink = struct.unpack(">II", head[8:16])
    if version != 1:
        sys.exit(f"btsnoop version={version},只支持 v1。")
    return datalink


def iter_records(path):
    with open(path, "rb") as f:
        f.seek(16)
        i = 0
        while True:
            rec = f.read(24)
            if len(rec) < 24:
                return
            orig, total_, flags, drops, ts = struct.unpack(">IIIIq", rec)
            data = f.read(total_)
            if len(data) < total_:
                return
            i += 1
            if drops:
                continue
            sent = (flags & 1) == 0  # btsnoop: bit0=0 sent(host→controller), 1 received
            yield i, sent, ts, data


H4_TYPES = (0x01, 0x02, 0x03, 0x04, 0x05)


def h4_payload(data, datalink):
    """返回 H4 包体(首字节=包类型),兼容带 4 字节伪头的变体。

    datalink=1002 在不同报文采集来源下两种都见过:Android AOSP bugreport 直接给裸 H4
    (首字节 01/02/04),另一些工具带 4B 伪头。按首字节是否为合法 H4 类型判别。
    """
    if datalink in (1002, 2012) and (not data or data[0] not in H4_TYPES):
        return data[4:]
    return data


def att_pdus(datalink, data):
    """从 HCI 记录抽 ATT PDU,产出 (acl_handle, opcode, attr_handle, value)。"""
    p = h4_payload(data, datalink)
    if not p or p[0] != 0x02:  # 只看 ACL Data
        return
    acl = p[1:]
    if len(acl) < 4:
        return
    hinfo, dlen = struct.unpack("<HH", acl[:4])
    handle = hinfo & 0x0FFF
    body = acl[4:4 + dlen]
    if len(body) < 4:
        return
    l2len, cid = struct.unpack("<HH", body[:4])
    if cid != L2CAP_ATT_CID:
        return
    att = body[4:4 + l2len]
    if not att:
        return
    opcode = att[0]
    if opcode in (ATT_WRITE_CMD, ATT_WRITE_REQ, ATT_WRITE_SIGNED, ATT_NOTIFY, ATT_INDICATE):
        if len(att) >= 4:
            ah = struct.unpack("<H", att[1:3])[0]
            yield handle, opcode, ah, att[3:]


class StreamState:
    """一条 (方向, ACL handle) 流的重组状态:编码模式 + 残料缓冲。"""

    def __init__(self):
        self.mode = None  # None=未定型, 0=明文, 0x55=XOR 混淆
        self.buf = b""
        self.bytes_pending = 0
        self.bytes_dropped = 0  # 滑窗丢掉的失步字节(非帧垃圾)

    def feed(self, value):
        if self.mode is None and len(value) >= 2:
            if value[:2] == XOR_HEADER:
                self.mode = 0x55
            elif value[:2] == PLAIN_HEADER:
                self.mode = 0
        data = bytes(b ^ self.mode for b in value) if self.mode else value
        prev_len = len(self.buf)
        self.buf += data
        done, rest = frames_from(self.buf)
        # 滑窗跳过的字节 = 输入增量 - 保留残料
        self.bytes_dropped += prev_len + len(data) - len(rest) - sum(len(f) for f in done)
        self.bytes_pending = len(rest)
        self.buf = rest
        return done


def summarize(title, groups, fmt_key):
    print(f"\n# ===== {title} ===", file=sys.stderr)
    if not groups:
        print("#   (无)", file=sys.stderr)
        return
    for key, occ in sorted(groups.items(), key=lambda kv: -len(kv[1])):
        ivs = [iv for _, iv in occ if iv is not None]
        period = f"间隔中位数 {median(ivs):.2f}s" if ivs else "单次"
        span = f"{occ[0][0]:+.1f}s→{occ[-1][0]:+.1f}s"
        print(f"  {fmt_key(key)}  x{len(occ)}  {span}  {period}", file=sys.stderr)


def main(path):
    datalink = read_header(path)
    print(f"# btsnoop datalink={datalink}", file=sys.stderr)
    streams = {}                      # (dir, handle) -> StreamState
    announced = set()                 # 已播报过编码定型的 key
    tx_groups = defaultdict(list)     # frame hex -> [(sec, interval|None)]
    rx_groups = defaultdict(list)     # (cmd,a1,a2)   -> [(sec, interval|None)]
    prev_tx = {}
    prev_rx = {}
    t0 = None
    tx_frames = rx_frames = rx_chk_ok = 0

    for _idx, sent, ts_us, data in iter_records(path):
        if t0 is None:
            t0 = ts_us
        sec = (ts_us - t0) / 1_000_000.0
        for handle, opcode, attr_handle, value in att_pdus(datalink, data):
            tag = "SEND" if sent else "RECV"
            op = {ATT_WRITE_CMD: "WriteCmd", ATT_WRITE_REQ: "WriteReq",
                  ATT_WRITE_SIGNED: "WriteSigned",
                  ATT_NOTIFY: "Notify", ATT_INDICATE: "Indicate"}[opcode]
            is_tx = opcode in (ATT_WRITE_CMD, ATT_WRITE_REQ, ATT_WRITE_SIGNED)
            key = ("tx" if is_tx else "rx", handle)
            st = streams.setdefault(key, StreamState())
            if st.mode is not None and key not in announced:
                announced.add(key)
                label = "明文 55AA" if st.mode == 0 else "XOR 0x55(线上头 00ff)"
                print(f"# [{tag}] ACL handle=0x{handle:04x} 编码定型: {label}",
                      file=sys.stderr)
            frames = st.feed(value)

            if not frames:
                note = CCCD_VALUES.get(value, "")
                note = f"  ← {note}" if note else ""
                print(f"[{sec:+9.3f}s {tag}] ATT {op} char=0x{attr_handle:04x} "
                      f"(未成帧) {value.hex()}{note}")
                continue

            for fr in frames:
                rep = frame_report(fr)
                if rep is None:
                    continue
                line, (cmd, a1, a2) = rep
                if is_tx:
                    tx_frames += 1
                    hx = fr.hex()
                    print(f"[{sec:+9.3f}s {tag}] ATT {op} "
                          f"char=0x{attr_handle:04x} {hx}  {line}")
                    prev = prev_tx.get(hx)
                    tx_groups[hx].append((sec, sec - prev if prev is not None else None))
                    prev_tx[hx] = sec
                else:
                    rx_frames += 1
                    if "chk OK" in line:
                        rx_chk_ok += 1
                    print(f"[{sec:+9.3f}s {tag}] ATT {op} "
                          f"char=0x{attr_handle:04x} {fr.hex()}  {line}")
                    rk = (cmd, a1, a2)
                    prev = prev_rx.get(rk)
                    rx_groups[rk].append((sec, sec - prev if prev is not None else None))
                    prev_rx[rk] = sec

    summarize("车→app 接收帧聚合(车端主动推周期看这里)", rx_groups,
              lambda k: f"CMD=0x{k[0]:02x} A1=0x{k[1]:02x} A2=0x{k[2]:02x}")
    summarize("app→车 发送帧聚合(触发/心跳/查询)", tx_groups,
              lambda k: k)

    print(f"\n# 统计: app→车 帧 {tx_frames};车→app 帧 {rx_frames}"
          f"(chk OK {rx_chk_ok});残料未成帧 "
          f"{sum(s.bytes_pending for s in streams.values())} B,"
          f"失步丢弃 {sum(s.bytes_dropped for s in streams.values())} B",
          file=sys.stderr)
    if tx_frames == 0 and rx_frames > 0:
        print("# app→车没有任何应用层帧,车端仍在推通知 → 车端主动周期推送,"
              "不依赖 App 提问。", file=sys.stderr)


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit("用法: python3 hci_quicklook.py btsnoop_hci.log")
    main(sys.argv[1])
