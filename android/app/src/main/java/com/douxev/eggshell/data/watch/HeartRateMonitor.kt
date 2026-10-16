package com.douxev.eggshell.data.watch

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Reads a live heart rate from a Bluetooth sensor, over the standard profile.
 *
 * **This is what watch integration looks like without Wear OS.** The Bluetooth
 * SIG Heart Rate Service (`0x180D`) is a public standard that chest straps,
 * fitness rings and most watches in "broadcast heart rate" mode all implement.
 * Talking to it needs nothing but `android.bluetooth`, which is in the platform:
 * no Google Play Services, no vendor SDK, no account, no network, no cloud. The
 * app connects to the device in the user's hand and reads a number off it.
 *
 * What this deliberately does **not** do is sync the workout history a watch
 * recorded on its own. There is no standard for that — every vendor uses a
 * private GATT protocol, which is why Gadgetbridge needs a hand-written driver
 * per device. Those sessions come in through [WatchImporter] as an exported
 * file instead. The two together cover both halves: a session eggshell records
 * with the watch as a sensor, and a session the watch recorded by itself.
 *
 * Nothing is persisted here. Samples are held in memory for the duration of a
 * session and only the average and the peak are written, because a beat-by-beat
 * series is a far more identifying signal than a summary and nothing needs it.
 */
@Singleton
class HeartRateMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    sealed interface State {
        /** Nothing running. */
        data object Idle : State
        data object Scanning : State
        data class Connecting(val name: String?) : State
        data class Live(val name: String?, val bpm: Int) : State
        /** Connected but no reading yet — a strap warming up, or not being worn. */
        data class Waiting(val name: String?) : State
        /**
         * The link dropped mid-session — out of range for a moment, a strap
         * pulled off — and the monitor is waiting for the same sensor to come
         * back. The session's average and peak so far are kept.
         */
        data class Reconnecting(val name: String?) : State
        data class Failed(val reason: Reason) : State
    }

    /**
     * Typed, because each one has a different answer and a single "failed"
     * would send the user to check the wrong thing.
     */
    enum class Reason { BLUETOOTH_OFF, NO_PERMISSION, NOT_FOUND, DISCONNECTED, UNSUPPORTED }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val accumulator = HeartRateAccumulator()

    /** The session summary so far. Null until a reading has arrived. */
    val averageBpm: Int? get() = accumulator.average
    val maxBpm: Int? get() = accumulator.max

    // Written from Bluetooth binder threads as well as the main one.
    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var scanning = false

    /** Set once notifications are on: from then, a drop means "reconnect". */
    @Volatile private var sessionEstablished = false

    /** Reconnections that failed at once (GATT 133 and friends) in a row. */
    @Volatile private var quickFailures = 0

    private val main = Handler(Looper.getMainLooper())
    private val scanTimeout = Runnable {
        if (scanning) {
            stopScan()
            _state.value = State.Failed(Reason.NOT_FOUND)
        }
    }

    private val adapter: BluetoothAdapter?
        get() = context.getSystemService(BluetoothManager::class.java)?.adapter

    /** Whether this device can do BLE at all. */
    val isSupported: Boolean
        get() = context.packageManager.hasSystemFeature(
            android.content.pm.PackageManager.FEATURE_BLUETOOTH_LE
        )

    /**
     * The permissions that must be granted before [start] can work.
     *
     * Android 12 split Bluetooth into SCAN and CONNECT; before that, scanning
     * required a location permission, because a BLE scan can be used to infer
     * where someone is. `neverForLocation` is declared in the manifest so the
     * newer split does not drag location back in.
     */
    val requiredPermissions: List<String>
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(
                android.Manifest.permission.BLUETOOTH_SCAN,
                android.Manifest.permission.BLUETOOTH_CONNECT,
            )
        } else {
            listOf(android.Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            // Results already queued when the scan was stopped still arrive.
            // Without this guard a second one opened a second GATT connection
            // and leaked the first — one of the handful of slots the phone has.
            if (!scanning) return
            // First sensor advertising the service wins. A chooser would be
            // better with several in range, and is worth adding once anyone
            // actually has two — picking the nearest is not obviously right
            // either, since the strongest signal is often someone else's.
            stopScan()
            connect(result.device)
        }

        override fun onScanFailed(errorCode: Int) {
            stopScan()
            _state.value = State.Failed(Reason.NOT_FOUND)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    quickFailures = 0
                    runCatching { g.discoverServices() }
                }
                BluetoothProfile.STATE_DISCONNECTED -> onDropped(g)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val characteristic = g
                .getService(UUID.fromString(HeartRateParser.SERVICE_UUID))
                ?.getCharacteristic(UUID.fromString(HeartRateParser.MEASUREMENT_UUID))
            if (characteristic == null) {
                close()
                _state.value = State.Failed(Reason.UNSUPPORTED)
                return
            }
            runCatching {
                g.setCharacteristicNotification(characteristic, true)
                // Enabling notification locally is not enough: the descriptor
                // write is what tells the sensor to start sending. Without it
                // the connection succeeds and no reading ever arrives.
                val ccc = characteristic.getDescriptor(
                    UUID.fromString(HeartRateParser.CCC_DESCRIPTOR_UUID)
                )
                if (ccc != null) writeCcc(g, ccc)
            }
            sessionEstablished = true
            _state.value = State.Waiting(deviceName(g.device))
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) = handleSample(g, value)

        @Deprecated("Pre-API-33 signature; the platform calls one or the other.")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            handleSample(g, characteristic.value ?: return)
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleSample(g: BluetoothGatt, value: ByteArray) {
        val sample = HeartRateParser.parse(value) ?: return
        accumulator.add(sample.bpm)
        _state.value = State.Live(deviceName(g.device), sample.bpm)
    }

    /**
     * The link went down. After [stop] that is the expected end; before the
     * session was established it is a failed connection; in the middle of a
     * session it is a strap out of range for a moment, and the monitor waits
     * for the same sensor to come back rather than ending the session — the
     * previous build gave up on the first dropout and the rest of the workout
     * went unrecorded.
     */
    @SuppressLint("MissingPermission")
    private fun onDropped(g: BluetoothGatt) {
        if (_state.value is State.Idle) {
            close()
            return
        }
        val device = g.device
        close()
        val adapterOn = adapter?.isEnabled == true
        when {
            !adapterOn -> _state.value = State.Failed(Reason.BLUETOOTH_OFF)
            !sessionEstablished -> _state.value = State.Failed(Reason.DISCONNECTED)
            // A reconnection that keeps failing straight away (the sensor was
            // switched off, the bond was dropped) must not loop for ever.
            quickFailures >= MAX_QUICK_FAILURES -> _state.value = State.Failed(Reason.DISCONNECTED)
            else -> {
                quickFailures++
                _state.value = State.Reconnecting(deviceName(device))
                // autoConnect: the stack waits, without a timeout and at low
                // duty cycle, for the device to advertise again.
                gatt = runCatching {
                    device.connectGatt(context, true, gattCallback, BluetoothDevice.TRANSPORT_LE)
                }.getOrNull()
                if (gatt == null) _state.value = State.Failed(Reason.DISCONNECTED)
            }
        }
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private fun writeCcc(g: BluetoothGatt, ccc: BluetoothGattDescriptor) {
        val enable = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(ccc, enable)
        } else {
            ccc.value = enable
            g.writeDescriptor(ccc)
        }
    }

    @SuppressLint("MissingPermission")
    private fun deviceName(device: BluetoothDevice): String? =
        runCatching { device.name }.getOrNull()

    /**
     * Look for a sensor and connect to the first one found.
     *
     * A sensor the phone is already connected to — a strap another app opened,
     * a watch paired for heart-rate broadcast — does not advertise, so a scan
     * cannot see it; those are tried first, from the system's own list of
     * connected devices, when their cached services include the Heart Rate
     * Service.
     *
     * Then a scan, filtered on the Heart Rate Service so it sees only devices
     * that advertise it — faster and narrower than scanning everything and
     * filtering afterwards, which would hand this app a list of every BLE
     * device around the user — and bounded: after [SCAN_TIMEOUT_MS] with
     * nothing found it stops and says so, where the previous build kept the
     * radio scanning at full duty for as long as the screen stayed open.
     */
    @SuppressLint("MissingPermission")
    fun start() {
        if (!isSupported) {
            _state.value = State.Failed(Reason.UNSUPPORTED)
            return
        }
        val adapter = adapter
        if (adapter == null || !adapter.isEnabled) {
            _state.value = State.Failed(Reason.BLUETOOTH_OFF)
            return
        }
        // A fresh start after a failure or a stop: forget the old link.
        stopScan()
        close()
        sessionEstablished = false
        quickFailures = 0

        connectedSensor()?.let {
            connect(it)
            return
        }

        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            _state.value = State.Failed(Reason.BLUETOOTH_OFF)
            return
        }
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(UUID.fromString(HeartRateParser.SERVICE_UUID)))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        val started = runCatching {
            scanner.startScan(listOf(filter), settings, scanCallback)
            true
        }.getOrElse {
            // A SecurityException here is a missing runtime permission, which
            // is a different thing for the user to fix than "no sensor found".
            _state.value = State.Failed(Reason.NO_PERMISSION)
            false
        }
        if (started) {
            scanning = true
            _state.value = State.Scanning
            main.removeCallbacks(scanTimeout)
            main.postDelayed(scanTimeout, SCAN_TIMEOUT_MS)
        }
    }

    /**
     * A device already connected at the GATT level whose cached service list
     * includes the Heart Rate Service, if the system knows of one.
     */
    @SuppressLint("MissingPermission")
    private fun connectedSensor(): BluetoothDevice? = runCatching {
        val hrs = ParcelUuid(UUID.fromString(HeartRateParser.SERVICE_UUID))
        context.getSystemService(BluetoothManager::class.java)
            ?.getConnectedDevices(BluetoothProfile.GATT)
            ?.firstOrNull { device -> device.uuids?.contains(hrs) == true }
    }.getOrNull()

    @SuppressLint("MissingPermission")
    private fun connect(device: BluetoothDevice) {
        _state.value = State.Connecting(deviceName(device))
        gatt = runCatching {
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        }.getOrElse {
            _state.value = State.Failed(Reason.NO_PERMISSION)
            null
        }
    }

    /** Stop everything and drop the connection. Safe to call at any point. */
    fun stop() {
        _state.value = State.Idle
        stopScan()
        close()
    }

    /** Forget the running totals — a new session must start from nothing. */
    fun resetSummary() {
        stop()
        accumulator.reset()
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        main.removeCallbacks(scanTimeout)
        if (!scanning) return
        scanning = false
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
    }

    @SuppressLint("MissingPermission")
    private fun close() {
        val g = gatt ?: return
        gatt = null
        runCatching { g.disconnect() }
        runCatching { g.close() }
    }

    private companion object {
        /** Long enough for a strap that advertises every few seconds. */
        const val SCAN_TIMEOUT_MS = 20_000L
        const val MAX_QUICK_FAILURES = 3
    }
}
