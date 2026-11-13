import unittest

from ibwalk_frame import Frame, StreamDecoder, checksum, decode


class TestKnownFrames(unittest.TestCase):
    def test_hardcoded_frame_from_binary(self):
        # TestSendMsg constant at rodata 0xa6dca0
        raw = bytes.fromhex("55aa02ff0103fafe")
        frame, consumed = decode(raw)
        self.assertIsNotNone(frame)
        self.assertEqual(consumed, 8)
        self.assertEqual((frame.cmd, frame.a1, frame.a2, frame.payload), (0xFF, 0x01, 0x03, b""))

    def test_checksum_formula(self):
        # sum(02 ff 01 03) = 0x105 -> ~0x105 & 0xffff = 0xfefa -> lo=fa hi=fe
        self.assertEqual(checksum(bytes.fromhex("02ff0103")), 0xFEFA)

    def test_encode_roundtrip(self):
        f = Frame(cmd=0xF1, a1=0x05, a2=0x01)
        raw = f.encode()
        self.assertEqual(raw.hex(), "55aa02f10501" + "%02x%02x" % (
            (~((0x02 + 0xF1 + 0x05 + 0x01) & 0xFFFF)) & 0xFF,
            ((~((0x02 + 0xF1 + 0x05 + 0x01) & 0xFFFF)) >> 8) & 0xFF,
        ))
        g, n = decode(raw)
        self.assertEqual((g.cmd, g.a1, g.a2), (0xF1, 0x05, 0x01))
        self.assertEqual(n, len(raw))

    def test_encode_with_payload(self):
        f = Frame(cmd=0x06, a1=0x00, a2=0x00, payload=bytes([0xAA, 0xBB, 0xCC]))
        raw = f.encode()
        self.assertEqual(raw[2], 5)  # L = payload + 2
        self.assertEqual(len(raw), 11)  # L + 6
        g, _ = decode(raw)
        self.assertEqual(g.payload, bytes([0xAA, 0xBB, 0xCC]))

    def test_stream_reassembly(self):
        d = StreamDecoder()
        f1 = Frame(cmd=0xF1, a1=1, a2=2).encode()
        f2 = Frame(cmd=0x04, a1=0, a2=9, payload=b"\x01\x02").encode()
        noise = b"\x00\x55"
        out = d.feed(noise + f1[:4])
        self.assertEqual(out, [])
        out = d.feed(f1[4:] + f2)
        self.assertEqual([f.cmd for f in out], [0xF1, 0x04])

    def test_stream_recovers_after_bad_checksum(self):
        d = StreamDecoder()
        bad = bytes.fromhex("55aa02ff0103dead")
        self.assertEqual(d.feed(bad), [])
        self.assertEqual(d.rejected, [bad])
        good = Frame(cmd=0xFF, a1=1, a2=3).encode()
        self.assertEqual([f.cmd for f in d.feed(good)], [0xFF])

    def test_bogus_length_resyncs(self):
        d = StreamDecoder()
        good = Frame(cmd=0x06, a1=1, a2=5, payload=b"\x11\x22").encode()
        noisy = b"\x55\xaa\xff" + b"\x00" * 258
        out = d.feed(noisy + good)
        self.assertEqual([f.cmd for f in out], [0x06])
        self.assertTrue(d.rejected)


if __name__ == "__main__":
    unittest.main()
