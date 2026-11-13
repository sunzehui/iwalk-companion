/// iWalk (Lebid/LBtech) BLE protocol reference codec.
///
/// Frame layout (confirmed statically from libcpp_empty_test.so v5.4.2):
///
///   [0]      0x55            header
///   [1]      0xAA            header
///   [2]      L               payload length + 2
///   [3]      CMD
///   [4]      A1
///   [5]      A2
///   [6..]    payload         (L - 2 bytes, may be empty)
///   [L+4]    chk_lo          checksum, little-endian
///   [L+5]    chk_hi          total frame length = L + 6
///
///   checksum = (~uint16(sum(bytes[2..L+3]))) & 0xFFFF
library;

import 'dart:typed_data';

const int kHeader0 = 0x55;
const int kHeader1 = 0xAA;

int checksum(Uint8List covered) {
  var s = 0;
  for (final b in covered) {
    s = (s + b) & 0xFFFF;
  }
  return (~s) & 0xFFFF;
}

class IbwalkFrame {
  final int cmd;
  final int a1;
  final int a2;
  final Uint8List payload;

  IbwalkFrame({
    required this.cmd,
    required this.a1,
    required this.a2,
    Uint8List? payload,
  }) : payload = payload ?? Uint8List(0);

  int get lengthField => payload.length + 2;

  Uint8List encode() {
    final body = Uint8List(4 + payload.length);
    body[0] = lengthField;
    body[1] = cmd;
    body[2] = a1;
    body[3] = a2;
    body.setRange(4, body.length, payload);
    final chk = checksum(body);
    final out = Uint8List(2 + body.length + 2);
    out[0] = kHeader0;
    out[1] = kHeader1;
    out.setRange(2, 2 + body.length, body);
    out[out.length - 2] = chk & 0xFF;
    out[out.length - 1] = (chk >> 8) & 0xFF;
    return out;
  }

  @override
  String toString() =>
      'IbwalkFrame(cmd: 0x${cmd.toRadixString(16)}, a1: 0x${a1.toRadixString(16)}, '
      'a2: 0x${a2.toRadixString(16)}, payload: $payload)';
}

class ChecksumMismatch implements Exception {
  final int expected;
  final int received;
  ChecksumMismatch(this.expected, this.received);
  @override
  String toString() =>
      'ChecksumMismatch(calc=0x${expected.toRadixString(16)}, recv=0x${received.toRadixString(16)})';
}

/// Returns (frame, consumed). frame == null means more data needed.
/// Throws [ArgumentError] on bad header alignment is not possible here:
/// callers should use [StreamDecoder] which resynchronises.
(IbwalkFrame?, int) decodeFrame(Uint8List buf) {
  if (buf.length < 3 || buf[0] != kHeader0 || buf[1] != kHeader1) {
    return (null, 0);
  }
  final l = buf[2];
  final total = l + 6;
  if (buf.length < total) return (null, 0);
  final covered = buf.sublist(2, l + 4);
  final recv = buf[l + 4] | (buf[l + 5] << 8);
  final calc = checksum(Uint8List.fromList(covered));
  if (calc != recv) throw ChecksumMismatch(calc, recv);
  return (
    IbwalkFrame(
      cmd: buf[3],
      a1: buf[4],
      a2: buf[5],
      payload: Uint8List.fromList(buf.sublist(6, l + 4)),
    ),
    total,
  );
}

/// Sliding-window reassembler mirroring Bluetooth::combiningFrame.
///
/// A checksum failure must not stall the buffer: the bad header is dropped
/// so resynchronisation can continue. The rejected bytes are retained in
/// [rejected] for evidence rather than being silently discarded.
///
/// Wire encoding is auto-detected per stream (E46): real scooters notify
/// whole frames XOR-ed with 0x55, so the wire header reads `00 FF`. Detection
/// uses the first two bytes of a chunk (`55 AA` → plain, `00 FF` → obfuscated)
/// and then sticks for the life of the decoder.
class StreamDecoder {
  Uint8List _buf = Uint8List(0);
  final List<Uint8List> rejected = [];
  int? _xor;

  /// null = undetermined, 0 = plain `55 AA`, 0x55 = obfuscated `00 FF`.
  int? get encodingMode => _xor;

  /// Bytes held back because they form an incomplete frame.
  Uint8List get pendingBytes => Uint8List.fromList(_buf);

  List<IbwalkFrame> feed(List<int> data) {
    if (_xor == null && data.length >= 2) {
      if (data[0] == kHeader0 ^ 0x55 && data[1] == kHeader1 ^ 0x55) {
        _xor = 0x55;
      } else if (data[0] == kHeader0 && data[1] == kHeader1) {
        _xor = 0;
      }
    }
    if (_xor != null && _xor != 0) {
      data = Uint8List.fromList([for (final b in data) b ^ _xor!]);
    }
    _buf = Uint8List.fromList([..._buf, ...data]);
    final frames = <IbwalkFrame>[];
    while (true) {
      if (_buf.isEmpty) break;
      if (_buf[0] != kHeader0) {
        _buf = _buf.sublist(1);
        continue;
      }
      if (_buf.length >= 2 && _buf[1] != kHeader1) {
        _buf = _buf.sublist(1);
        continue;
      }
      IbwalkFrame? frame;
      int consumed;
      try {
        final result = decodeFrame(_buf);
        frame = result.$1;
        consumed = result.$2;
      } on ChecksumMismatch {
        final l = _buf[2];
        final total = l + 6;
        final end = total <= _buf.length ? total : _buf.length;
        rejected.add(Uint8List.fromList(_buf.sublist(0, end)));
        _buf = _buf.sublist(2);
        continue;
      }
      if (frame == null) break;
      _buf = _buf.sublist(consumed);
      frames.add(frame);
    }
    return frames;
  }
}
