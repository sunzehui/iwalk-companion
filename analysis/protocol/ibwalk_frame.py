"""iWalk (Lebid/LBtech) BLE protocol reference codec.

Frame layout (confirmed statically from libcpp_empty_test.so v5.4.2):

    [0] 0x55          header low
    [1] 0xAA          header high
    [2] L             = payload_len + 2
    [3] CMD
    [4] A1            (address/subtype arg)
    [5] A2            (address/subtype arg)
    [6..]             payload (L-2 bytes, may be empty)
    [L+4] chk_lo      checksum little-endian
    [L+5] chk_hi      total frame length = L + 6

    checksum = (~uint16(sum(bytes[2 : L+4]))) & 0xFFFF

Verified against two hardcoded frames from the binary:
    55 aa 02 ff 01 03 fa fe
    (55 aa f1 02 cmd arg chk16) generator in SendFramePack(u8,u8)
"""
from __future__ import annotations

from dataclasses import dataclass, field


HEADER = bytes([0x55, 0xAA])


def checksum(payload_covered: bytes) -> int:
    s = 0
    for b in payload_covered:
        s = (s + b) & 0xFFFF
    return (~s) & 0xFFFF


@dataclass
class Frame:
    cmd: int
    a1: int
    a2: int
    payload: bytes = field(default_factory=bytes)

    @property
    def length_field(self) -> int:
        return len(self.payload) + 2

    def encode(self) -> bytes:
        body = bytes([self.length_field, self.cmd, self.a1, self.a2]) + self.payload
        if len(body) > 255:
            raise ValueError("payload too long for 8-bit length field")
        chk = checksum(body)
        return HEADER + body + bytes([chk & 0xFF, chk >> 8])


def decode(buf: bytes) -> tuple[Frame | None, int]:
    """Decode one frame from the front of buf.

    Returns (frame, consumed). (None, 0) if more data needed or invalid header.
    """
    if len(buf) < 3 or buf[0] != 0x55 or buf[1] != 0xAA:
        return None, 0
    L = buf[2]
    total = L + 6
    if len(buf) < total:
        return None, 0
    body = buf[2 : L + 4]
    chk_recv = buf[L + 4] | (buf[L + 5] << 8)
    if checksum(body) != chk_recv:
        raise ValueError(
            f"checksum mismatch: calc=0x{checksum(body):04x} recv=0x{chk_recv:04x}"
        )
    payload = buf[6 : L + 4]
    return Frame(cmd=buf[3], a1=buf[4], a2=buf[5], payload=payload), total


class StreamDecoder:
    """Sliding-window reassembler mirroring Bluetooth::combiningFrame.

    Checksum failures are recorded in ``rejected`` and the candidate header
    is dropped so resynchronisation can continue; the buffer must never
    stall on one bad frame.
    """

    def __init__(self) -> None:
        self._buf = bytearray()
        self.rejected: list[bytes] = []

    def feed(self, data: bytes) -> list[Frame]:
        self._buf.extend(data)
        frames: list[Frame] = []
        while True:
            if len(self._buf) >= 2 and self._buf[0] != 0x55:
                self._buf.pop(0)
                continue
            if len(self._buf) >= 3 and self._buf[1] != 0xAA:
                self._buf.pop(0)
                continue
            try:
                frame, consumed = decode(bytes(self._buf))
            except ValueError:
                if len(self._buf) >= 3:
                    end = min(self._buf[2] + 6, len(self._buf))
                    self.rejected.append(bytes(self._buf[:end]))
                del self._buf[:2]
                continue
            if frame is None:
                break
            del self._buf[:consumed]
            frames.append(frame)
        return frames
