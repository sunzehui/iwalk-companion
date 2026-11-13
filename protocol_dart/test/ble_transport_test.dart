import 'package:ibwalk_protocol/ble_transport.dart';
import 'package:test/test.dart';

Map<String, Set<String>> services(List<String> uuids, {Map<String, Set<String>> chars = const {}}) {
  return {
    for (final u in uuids) u.toLowerCase(): chars[u.toLowerCase()] ?? <String>{},
  };
}

void main() {
  const g1svc = '6e400001-b5a3-f393-e0a9-e50e24dcca9e';
  const g2svc = '0000ae00-0000-1000-8000-00805f9b34fb';
  const ffe0 = '0000ffe0-0000-1000-8000-00805f9b34fb';
  const fff0 = '0000fff0-0000-1000-8000-00805f9b34fb';
  const fff1 = '0000fff1-0000-1000-8000-00805f9b34fb';
  const fff2 = '0000fff2-0000-1000-8000-00805f9b34fb';

  test('captured device: 6e400001 -> G1 (Nordic UART)', () {
    final pair = selectTransport(services([g1svc]),
        deviceName: 'MiniRobot:IWK1779887');
    expect(pair!.group, TransportGroup.g1NordicUart);
    expect(pair.writeUuid, '6e400002-b5a3-f393-e0a9-e50e24dcca9e');
    expect(pair.notifyUuid, '6e400003-b5a3-f393-e0a9-e50e24dcca9e');
  });

  test('ffe0 non-M6 -> G3, M6 -> G1', () {
    expect(selectTransport(services([ffe0]), deviceName: 'Mini')!.group,
        TransportGroup.g3Ffe0);
    expect(selectTransport(services([ffe0]), deviceName: 'M6-abc')!.group,
        TransportGroup.g1NordicUart);
  });

  test('ffe0 shadows fff1/fff2 chars: never upgrades to G5', () {
    final pair = selectTransport(
        services([ffe0], chars: {ffe0: {fff1, fff2}}),
        deviceName: 'Mini');
    expect(pair!.group, TransportGroup.g3Ffe0);
  });

  test('ae00 -> G2', () {
    expect(selectTransport(services([g2svc]), deviceName: 'X1')!.group,
        TransportGroup.g2Ae00);
  });

  test('fff0 without fff1/fff2 -> G4', () {
    expect(selectTransport(services([fff0]), deviceName: 'Mini')!.group,
        TransportGroup.g4Fff0);
  });

  test('fff0 with fff1 or fff2 -> G5', () {
    expect(
        selectTransport(services([fff0], chars: {fff0: {fff1}}))!.group,
        TransportGroup.g5Fff0Alt);
    expect(
        selectTransport(services([fff0], chars: {fff0: {fff2}}))!.group,
        TransportGroup.g5Fff0Alt);
  });

  test('unknown service set -> null', () {
    expect(selectTransport(services(['00001800-0000-1000-8000-00805f9b34fb'])),
        isNull);
  });

  test('uuid normalisation is case-insensitive', () {
    expect(
        selectTransport(services([g1svc.toUpperCase()]))!.group,
        TransportGroup.g1NordicUart);
  });

  test('name whitelist matches vendor list, incl. leading-space entries', () {
    expect(isSupportedDeviceName('MiniRobot:IWK1779887'), isTrue);
    expect(isSupportedDeviceName(' MiniPro-1'), isTrue);
    expect(isSupportedDeviceName('EO STREET 3'), isTrue);
    expect(isSupportedDeviceName('UnknownScooter'), isFalse);
    expect(isSupportedDeviceName(''), isFalse);
    expect(deviceNamePrefixes.length, 33);
  });

  test('serial extraction', () {
    expect(deviceSerialFromName('MiniRobot:IWK1779887'), '1779887');
    expect(deviceSerialFromName('MiniRobot'), isNull);
    expect(deviceSerialFromName(null), isNull);
  });

  test('link budget constants', () {
    expect(LinkBudget.maxPayloadPerPacket, 20);
    expect(LinkBudget.reassemblyIdleTimeout.inMilliseconds,
        greaterThan(LinkBudget.maxInterval.inMilliseconds));
  });
}
