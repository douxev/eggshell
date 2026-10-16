import CoreBluetooth
import Foundation

/// One reading from a Bluetooth heart-rate sensor.
///
/// `rrIntervalsMs` are the gaps between consecutive heartbeats — the raw
/// material for heart-rate variability. Parsed, and deliberately never
/// persisted: a beat-by-beat series is a far more identifying signal than an
/// average, and it should not land in the vault until something needs it.
struct HeartRateSample: Equatable {
    let bpm: Int
    let energyExpendedKJ: Int?
    let rrIntervalsMs: [Int]
}

/// The Bluetooth SIG Heart Rate Measurement characteristic, `0x2A37`.
///
/// This is the whole reason a watch can be integrated without any vendor SDK:
/// the format is a public standard that chest straps, fitness rings and most
/// watches in "broadcast heart rate" mode all speak. The layout is a flags
/// byte followed by fields whose presence and width the flags decide:
///
/// ```text
///   byte 0   flags: bit 0 = uint16 value, bit 3 = energy present, bit 4 = RR present
///   then     heart rate      uint8 or uint16, little-endian
///   then     energy expended uint16, only if bit 3
///   then     RR intervals    uint16 each, only if bit 4, in units of 1/1024 s
/// ```
///
/// Every one of those decisions is a place to be quietly wrong — reading a
/// uint16 payload as uint8 gives a plausible-looking heart rate that happens
/// to be the low byte — which is why this is a pure function rather than a
/// few lines inside a delegate callback. Byte-for-byte the same rules as
/// Android's `HeartRateParser`.
enum HeartRateParser {
    static let serviceUUID = CBUUID(string: "180D")
    static let measurementUUID = CBUUID(string: "2A37")

    /// The range the vault accepts (`0017_sport.sql`: `avg_hr`/`max_hr BETWEEN
    /// 20 AND 300`). Kept identical on purpose — see `parse`.
    static let validRange = 20...300

    private static let flagUInt16 = 0x01
    private static let flagEnergy = 0x08
    private static let flagRR = 0x10

    /// Parse one notification. Nil when the packet is too short to hold what
    /// its own flags claim — a truncated packet is dropped rather than read
    /// past the end of.
    static func parse(_ data: Data) -> HeartRateSample? {
        let bytes = [UInt8](data)
        guard !bytes.isEmpty else { return nil }
        let flags = Int(bytes[0])
        var cursor = 1

        let bpm: Int
        if flags & flagUInt16 != 0 {
            guard bytes.count >= cursor + 2 else { return nil }
            bpm = u16(bytes, cursor)
            cursor += 2
        } else {
            guard bytes.count >= cursor + 1 else { return nil }
            bpm = Int(bytes[cursor])
            cursor += 1
        }

        var energy: Int?
        if flags & flagEnergy != 0 {
            guard bytes.count >= cursor + 2 else { return nil }
            energy = u16(bytes, cursor)
            cursor += 2
        }

        var rr: [Int] = []
        if flags & flagRR != 0 {
            while cursor + 1 < bytes.count {
                // 1/1024 s units, not milliseconds: the unit is part of the wire
                // format and nothing downstream should have to know it.
                rr.append((u16(bytes, cursor) * 1000) / 1024)
                cursor += 2
            }
        }

        // A sensor that has lost contact reports 0. That is not a heart rate,
        // and averaging it in would drag the session's number toward zero. The
        // same goes for anything outside what a human heart does — and that
        // bound is not cosmetic: the vault refuses a session whose average or
        // peak falls outside it, so one stray packet used to make the session
        // impossible to save.
        guard validRange.contains(bpm) else { return nil }
        return HeartRateSample(bpm: bpm, energyExpendedKJ: energy, rrIntervalsMs: rr)
    }

    /// Little-endian, which is what every BLE characteristic uses.
    private static func u16(_ bytes: [UInt8], _ at: Int) -> Int {
        Int(bytes[at]) | (Int(bytes[at + 1]) << 8)
    }
}

/// Running average and peak across a session. A mean of the samples: a sensor
/// notifies about once a second, so every sample carries the same weight.
struct HeartRateAccumulator {
    private var sum: Int64 = 0
    private var count: Int64 = 0
    private var peak = 0

    mutating func add(_ bpm: Int) {
        guard HeartRateParser.validRange.contains(bpm) else { return }
        sum += Int64(bpm)
        count += 1
        // `Swift.max`: inside this type, a bare `max` is the property below,
        // and the call does not compile.
        peak = Swift.max(peak, bpm)
    }

    /// Nil until at least one sample has arrived — never 0, which is a reading.
    var average: Int? { count == 0 ? nil : Int(sum / count) }
    var max: Int? { peak > 0 ? peak : nil }
    var samples: Int64 { count }

    mutating func reset() {
        sum = 0
        count = 0
        peak = 0
    }
}

/// Reads a live heart rate from a Bluetooth sensor over the standard profile —
/// the iOS half of Android's `HeartRateMonitor`, on Core Bluetooth.
///
/// The Heart Rate Service (`0x180D`) needs nothing but the platform's own
/// Bluetooth stack: no vendor SDK, no account, no network. What this
/// deliberately does not do is sync the workout history a watch recorded on
/// its own — there is no standard for that — which is what `WatchImporter`
/// covers with an exported file.
///
/// Nothing is persisted here. Samples are held for the duration of a session
/// and only the average and the peak are written to the session.
@MainActor
final class HeartRateMonitor: NSObject, ObservableObject {

    enum State: Equatable {
        case idle
        case scanning
        case connecting(name: String?)
        /// Connected but no reading yet — a strap warming up, or not being worn.
        case waiting(name: String?)
        case live(name: String?, bpm: Int)
        /// The link dropped mid-session and the monitor is waiting for the same
        /// sensor to come back. The average and peak so far are kept.
        case reconnecting(name: String?)
        case failed(Reason)

        /// Whether a session with the sensor is under way (so the card offers
        /// « Déconnecter » rather than « Connecter »).
        var isActive: Bool {
            switch self {
            case .scanning, .connecting, .waiting, .live, .reconnecting: return true
            case .idle, .failed: return false
            }
        }
    }

    /// Typed, because each one has a different answer and a single "failed"
    /// would send the user to check the wrong thing.
    enum Reason: Equatable {
        case bluetoothOff, noPermission, notFound, disconnected, unsupported
    }

    @Published private(set) var state: State = .idle

    private var central: CBCentralManager?
    private var peripheral: CBPeripheral?
    private var accumulator = HeartRateAccumulator()
    private var wantsScan = false
    private var scanTimeout: Task<Void, Never>?
    /// Set once notifications are on: from then, a drop means "reconnect".
    private var sessionEstablished = false

    /// The session summary so far. Nil until a reading has arrived.
    var averageBpm: Int? { accumulator.average }
    var maxBpm: Int? { accumulator.max }

    /// Look for a sensor and connect to the first one found.
    ///
    /// Filtered on the Heart Rate Service so the scan sees only devices that
    /// advertise it — narrower than scanning everything and filtering
    /// afterwards, which would hand this app a list of every BLE device around
    /// the user. The system asks for Bluetooth permission on the first call.
    func start() {
        wantsScan = true
        sessionEstablished = false
        if central == nil {
            central = CBCentralManager(delegate: self, queue: .main)
            // The manager reports its state asynchronously; the scan starts
            // from centralManagerDidUpdateState.
            state = .scanning
            return
        }
        beginScanIfReady()
    }

    /// Stop everything and drop the connection. Safe to call at any point.
    func stop() {
        wantsScan = false
        sessionEstablished = false
        scanTimeout?.cancel()
        scanTimeout = nil
        state = .idle
        central?.stopScan()
        if let peripheral, let central {
            central.cancelPeripheralConnection(peripheral)
        }
        peripheral = nil
    }

    /// Stop, then report why. In that order: `stop()` resets the state to
    /// idle, and calling it after setting the failure used to erase the very
    /// message the user needed (« Cet appareil ne gère pas ce type de capteur »).
    private func fail(_ reason: Reason) {
        stop()
        state = .failed(reason)
    }

    /// Take `peripheral` as the session's sensor and connect to it.
    private func attach(_ peripheral: CBPeripheral, via central: CBCentralManager) {
        scanTimeout?.cancel()
        central.stopScan()
        self.peripheral = peripheral
        peripheral.delegate = self
        state = .connecting(name: peripheral.name)
        central.connect(peripheral, options: nil)
    }

    /// Forget the running totals — a new session must start from nothing.
    func resetSummary() {
        stop()
        accumulator.reset()
    }

    private func beginScanIfReady() {
        guard let central else { return }
        // Bluetooth switched off (or the stack reset) under a live session:
        // Core Bluetooth sends no disconnect for that, and a peripheral left
        // set here would make every later scan ignore every sensor.
        if central.state == .poweredOff || central.state == .resetting, peripheral != nil {
            peripheral = nil
            sessionEstablished = false
            state = .failed(.bluetoothOff)
            return
        }
        guard wantsScan else { return }
        switch central.state {
        case .poweredOn:
            // A sensor already connected to the phone — a strap another app
            // opened — does not advertise, so a scan would never see it.
            if peripheral == nil,
               let connected = central.retrieveConnectedPeripherals(
                   withServices: [HeartRateParser.serviceUUID]).first {
                wantsScan = false
                attach(connected, via: central)
                return
            }
            state = .scanning
            central.scanForPeripherals(withServices: [HeartRateParser.serviceUUID], options: nil)
            scanTimeout?.cancel()
            scanTimeout = Task { [weak self] in
                try? await Task.sleep(nanoseconds: 20_000_000_000)
                guard !Task.isCancelled, let self else { return }
                if case .scanning = self.state {
                    self.central?.stopScan()
                    self.state = .failed(.notFound)
                }
            }
        case .poweredOff:
            state = .failed(.bluetoothOff)
        case .unauthorized:
            state = .failed(.noPermission)
        case .unsupported:
            state = .failed(.unsupported)
        default:
            // .unknown / .resetting: wait for the next state update.
            break
        }
    }
}

extension HeartRateMonitor: CBCentralManagerDelegate {

    nonisolated func centralManagerDidUpdateState(_ central: CBCentralManager) {
        Task { @MainActor in self.beginScanIfReady() }
    }

    nonisolated func centralManager(
        _ central: CBCentralManager, didDiscover peripheral: CBPeripheral,
        advertisementData: [String: Any], rssi RSSI: NSNumber
    ) {
        Task { @MainActor in
            // First sensor advertising the service wins. A chooser would be
            // better with several in range, and is worth adding once anyone
            // actually has two.
            guard self.peripheral == nil else { return }
            self.attach(peripheral, via: central)
        }
    }

    nonisolated func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        Task { @MainActor in
            peripheral.discoverServices([HeartRateParser.serviceUUID])
        }
    }

    nonisolated func centralManager(
        _ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?
    ) {
        Task { @MainActor in
            if self.sessionEstablished, self.peripheral != nil {
                // A reconnection attempt that failed outright: try again; the
                // request waits for the sensor to come back in range.
                central.connect(peripheral, options: nil)
                return
            }
            self.peripheral = nil
            self.state = .failed(.notFound)
        }
    }

    nonisolated func centralManager(
        _ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?
    ) {
        Task { @MainActor in
            // After stop() this is the expected end of a session.
            guard self.state != .idle else {
                self.peripheral = nil
                return
            }
            // Mid-session — out of range for a moment, a strap pulled off — wait
            // for the same sensor rather than end the session: a connection
            // request on iOS does not time out, it completes when the sensor is
            // back. The previous build gave up on the first dropout.
            if self.sessionEstablished, self.peripheral === peripheral {
                self.state = .reconnecting(name: peripheral.name)
                central.connect(peripheral, options: nil)
                return
            }
            self.peripheral = nil
            self.state = .failed(.disconnected)
        }
    }
}

extension HeartRateMonitor: CBPeripheralDelegate {

    nonisolated func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        Task { @MainActor in
            guard let service = peripheral.services?.first(where: { $0.uuid == HeartRateParser.serviceUUID }) else {
                self.fail(.unsupported)
                return
            }
            peripheral.discoverCharacteristics([HeartRateParser.measurementUUID], for: service)
        }
    }

    nonisolated func peripheral(
        _ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?
    ) {
        Task { @MainActor in
            guard let characteristic = service.characteristics?.first(where: {
                $0.uuid == HeartRateParser.measurementUUID
            }) else {
                self.fail(.unsupported)
                return
            }
            // Subscribing is what tells the sensor to start sending; Core
            // Bluetooth writes the client-configuration descriptor itself.
            peripheral.setNotifyValue(true, for: characteristic)
            self.sessionEstablished = true
            self.state = .waiting(name: peripheral.name)
        }
    }

    nonisolated func peripheral(
        _ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?
    ) {
        guard characteristic.uuid == HeartRateParser.measurementUUID,
              let data = characteristic.value,
              let sample = HeartRateParser.parse(data) else { return }
        Task { @MainActor in
            self.accumulator.add(sample.bpm)
            self.state = .live(name: peripheral.name, bpm: sample.bpm)
        }
    }
}
