import 'dart:typed_data';

import 'package:ibwalk_protocol/ibwalk_frame.dart';
import 'package:test/test.dart';

Uint8List hx(String s) => Uint8List.fromList(
    [for (var i = 0; i < s.length; i += 2) int.parse(s.substring(i, i + 2), radix: 16)]);

void main() {
  test('decodes hardcoded frame from binary (TestSendMsg rodata)', () {
    final (frame, consumed) = decodeFrame(hx('55aa02ff0103fafe'));
    expect(frame, isNotNull);
    expect(consumed, 8);
    expect(frame!.cmd, 0xFF);
    expect(frame.a1, 0x01);
    expect(frame.a2, 0x03);
    expect(frame.payload, isEmpty);
  });

  test('checksum formula matches reverse-engineered rule', () {
    expect(checksum(hx('02ff0103')), 0xFEFA);
  });

  test('encode roundtrip for CMD 0xF1 heartbeat frame', () {
    final raw = IbwalkFrame(cmd: 0xF1, a1: 0x05, a2: 0x01).encode();
    expect(raw, hx('55aa02f10501') + hx(_chk(0x02 + 0xF1 + 0x05 + 0x01)));
    final (g, n) = decodeFrame(raw);
    expect(g!.cmd, 0xF1);
    expect(g.a1, 0x05);
    expect(g.a2, 0x01);
    expect(n, raw.length);
  });

  test('encode with payload', () {
    final f = IbwalkFrame(cmd: 0x06, a1: 0, a2: 0x11, payload: hx('aabbcc'));
    final raw = f.encode();
    expect(raw[2], 5);
    expect(raw.length, 11);
    final (g, _) = decodeFrame(raw);
    expect(g!.payload, hx('aabbcc'));
  });

  test('stream reassembly across chunk boundaries with noise resync', () {
    final d = StreamDecoder();
    final f1 = IbwalkFrame(cmd: 0xF1, a1: 1, a2: 2).encode();
    final f2 = IbwalkFrame(cmd: 0x04, a1: 0, a2: 9, payload: hx('0102')).encode();
    expect(d.feed([0x00, 0x55, ...f1.sublist(0, 4)]), isEmpty);
    final out = d.feed([...f1.sublist(4), ...f2]);
    expect(out.map((f) => f.cmd), [0xF1, 0x04]);
  });

  test('checksum mismatch throws', () {
    expect(() => decodeFrame(hx('55aa02ff0103fadefe')), throwsA(isA<ChecksumMismatch>()));
  });

  test('stream decoder recovers after a bad-checksum frame instead of stalling', () {
    final d = StreamDecoder();
    final good = IbwalkFrame(cmd: 0xFF, a1: 1, a2: 3).encode();
    expect(d.feed(hx('55aa02ff0103dead')), isEmpty);
    expect(d.rejected.single, hx('55aa02ff0103dead'));
    final out = d.feed(good);
    expect(out.single.cmd, 0xFF);
    // rejection stays available for the caller to log; it is not a queue
    // that self-clears
    expect(d.rejected.length, 1);
  });

  test('bogus length byte resyncs once enough data arrives', () {
    final d = StreamDecoder();
    final good = IbwalkFrame(cmd: 0x06, a1: 1, a2: 5, payload: hx('1122')).encode();
    // L=0xFF claims a 261-byte frame; feed that many bytes of noise plus a
    // valid frame. The bad candidate must be rejected, not stall forever.
    final noisy = [0x55, 0xAA, 0xFF, ...List.filled(258, 0x00)];
    final out = d.feed([...noisy, ...good]);
    expect(out.single.cmd, 0x06);
    expect(d.rejected, isNotEmpty);
  });

  test('auto-detects XOR-0x55 wire encoding and reassembles split frames', () {
    final d = StreamDecoder();
    // Real scooter status frame shape (E46): wire header reads 00 FF.
    final plain = IbwalkFrame(
      cmd: 0x0D, a1: 1, a2: 0x14,
      payload: hx('303234333338303030303030e11a00000000c818f6010100000034fe63006900000000000000'),
    ).encode();
    final obf = Uint8List.fromList([for (final b in plain) b ^ 0x55]);
    expect(obf.sublist(0, 2), [0x00, 0xFF]);
    // MTU-20 split: first chunk latches the mode, rest follows deobfuscated.
    expect(d.feed(obf.sublist(0, 20)), isEmpty);
    expect(d.encodingMode, 0x55);
    expect(d.feed(obf.sublist(20, 40)), isEmpty);
    final out = d.feed(obf.sublist(40));
    expect(out.single.cmd, 0x0D);
    expect(out.single.a2, 0x14);
    expect(out.single.payload, plain.sublist(6, plain.length - 2));
  });

  test('plaintext stream stays untouched after mode latches to plain', () {
    final d = StreamDecoder();
    final f = IbwalkFrame(cmd: 0xF1, a1: 1, a2: 2).encode();
    final out = d.feed(f);
    expect(d.encodingMode, 0);
    expect(out.single.cmd, 0xF1);
    expect(d.feed(f).single.cmd, 0xF1);
  });
}

String _chk(int sum) {
  final c = (~sum) & 0xFFFF;
  return '${(c & 0xFF).toRadixString(16).padLeft(2, "0")}${(c >> 8).toRadixString(16).padLeft(2, "0")}';
}
