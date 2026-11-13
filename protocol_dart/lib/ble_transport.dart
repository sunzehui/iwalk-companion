/// BLE GATT transport-group selection, ported from the vendor app
/// (`BluetoothHolder.searchGattServices`, v11.3.8) — see
/// analysis/08_ble_gatt_spec.md §1–§2.
///
/// Pure functions only: no plugin dependencies, so this can be unit tested
/// without a device. The vendor implementation contains an unreachable
/// branch (`UUID_SERVICE4 == ffe0`, shadowed by the first `if`) and a
/// bitwise-OR property check that is always true; neither is reproduced
/// here.
library;

/// The five documented UUID groups.
enum TransportGroup { g1NordicUart, g2Ae00, g3Ffe0, g4Fff0, g5Fff0Alt }

class TransportPair {
  final TransportGroup group;
  final String serviceUuid;

  /// APP -> scooter.
  final String writeUuid;

  /// Scooter -> APP (notification only).
  final String notifyUuid;

  const TransportPair({
    required this.group,
    required this.serviceUuid,
    required this.writeUuid,
    required this.notifyUuid,
  });

  @override
  String toString() => '${group.name} write=$writeUuid notify=$notifyUuid';
}

const String _base = '-0000-1000-8000-00805f9b34fb';
const String cccdUuid = '00002902-0000-1000-8000-00805f9b34fb';

const TransportPair g1 = TransportPair(
  group: TransportGroup.g1NordicUart,
  serviceUuid: '6e400001-b5a3-f393-e0a9-e50e24dcca9e',
  writeUuid: '6e400002-b5a3-f393-e0a9-e50e24dcca9e',
  notifyUuid: '6e400003-b5a3-f393-e0a9-e50e24dcca9e',
);

const TransportPair g2 = TransportPair(
  group: TransportGroup.g2Ae00,
  serviceUuid: '0000ae00$_base',
  writeUuid: '0000ae01$_base',
  notifyUuid: '0000ae02$_base',
);

const TransportPair g3 = TransportPair(
  group: TransportGroup.g3Ffe0,
  serviceUuid: '0000ffe0$_base',
  writeUuid: '0000fff3$_base',
  notifyUuid: '0000fff4$_base',
);

const TransportPair g4 = TransportPair(
  group: TransportGroup.g4Fff0,
  serviceUuid: '0000fff0$_base',
  writeUuid: '0000fff3$_base',
  notifyUuid: '0000fff7$_base',
);

const TransportPair g5 = TransportPair(
  group: TransportGroup.g5Fff0Alt,
  serviceUuid: '0000fff0$_base',
  writeUuid: '0000fff2$_base',
  notifyUuid: '0000fff1$_base',
);

const String fff0Service = '0000fff0$_base';
const String ffe0Service = '0000ffe0$_base';
const String fff1Char = '0000fff1$_base';
const String fff2Char = '0000fff2$_base';

/// Advertisement-name prefixes the vendor app accepts (33 entries, §3).
const List<String> deviceNamePrefixes = [
  'M0', 'H1', 'M1', 'Mini', 'Plus', 'X1', 'X3', 'M6', 'GoKart', 'A6', 'MI',
  'N3MTenbot', 'miniPLUS_', ' MiniPro', 'SFSO', 'V5Robot', 'EO STREET',
  'XRIDER', 'TECAR', 'MAX', 'i10', 'NEXRIDE', 'E-WHEELS', 'E12', 'E9PRO',
  'T10', 'KING', 'oP9G', 'Mentor', 'E9G', 'XT-', 'SPARK', 'EROBOT',
];

bool isSupportedDeviceName(String? name) {
  if (name == null || name.isEmpty) return false;
  return deviceNamePrefixes.any(name.startsWith);
}

/// Device naming is `MiniRobot:IWK<serial>` (real capture, §10.5).
String? deviceSerialFromName(String? name) {
  if (name == null) return null;
  final i = name.indexOf('IWK');
  if (i < 0 || i + 3 >= name.length) return null;
  final serial = name.substring(i + 3).trim();
  return serial.isEmpty ? null : serial;
}

/// Chooses the transport group from one service discovery result.
///
/// [services] maps lowercase service UUID -> set of lowercase char UUIDs.
/// [deviceName] feeds the M6 rule for the `ffe0` family only; every other
/// family forces small=1 in the vendor app.
TransportPair? selectTransport(
  Map<String, Set<String>> services, {
  String? deviceName,
}) {
  final small = (deviceName == null || !deviceName.startsWith('M6')) ? 1 : 0;

  // ffe0 is checked first and shadows every later branch (vendor dead-code
  // quirk documented in §1).
  if (services.containsKey(ffe0Service)) {
    return small == 1 ? g3 : g1;
  }
  if (services.containsKey(g1.serviceUuid)) return g1;
  if (services.containsKey(g2.serviceUuid)) return g2;
  if (services.containsKey(fff0Service)) {
    final chars = services[fff0Service] ?? const {};
    // Only the fff0 branch may upgrade to g5.
    return (chars.contains(fff1Char) || chars.contains(fff2Char)) ? g5 : g4;
  }
  return null;
}

/// Frame-size facts that constrain framing and reassembly (§4-1, §10.6).
class LinkBudget {
  /// ATT MTU stays at the default because the vendor app never calls
  /// requestMtu; useful payload per packet.
  static const int defaultMtu = 23;
  static const int maxPayloadPerPacket = defaultMtu - 3; // 20

  /// Preferred connection parameter upper bound from `2a04` on the captured
  /// device; a split frame's halves are at most one interval apart.
  static const Duration maxInterval = Duration(milliseconds: 75);

  /// Recommended idle timeout for frame reassembly: 3 intervals.
  static const Duration reassemblyIdleTimeout = Duration(milliseconds: 250);
}

/// Normalises a UUID string for map lookups (lowercase, 128-bit form kept).
String normaliseUuid(String uuid) => uuid.toLowerCase();
