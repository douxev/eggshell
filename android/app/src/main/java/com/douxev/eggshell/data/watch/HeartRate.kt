package com.douxev.eggshell.data.watch

/**
 * One reading from a Bluetooth heart-rate sensor.
 *
 * [rrIntervalsMs] are the gaps between consecutive heartbeats. They are the raw
 * material for heart-rate variability, which is why they are kept rather than
 * discarded — but nothing consumes them yet, and they are deliberately not
 * persisted: a beat-by-beat series is a far more identifying signal than an
 * average, and it should not land in the vault until something actually needs it.
 */
data class HeartRateSample(
    val bpm: Int,
    val energyExpendedKJ: Int?,
    val rrIntervalsMs: List<Int>,
)

/**
 * The Bluetooth SIG Heart Rate Measurement characteristic, `0x2A37`.
 *
 * This is the whole reason a watch can be integrated without Wear OS and
 * without any vendor SDK: the format is a public standard that chest straps,
 * fitness rings and most watches in "broadcast heart rate" mode all speak. No
 * account, no cloud, no proprietary library — just a GATT notification.
 *
 * The layout is a flags byte followed by fields whose presence and width the
 * flags decide:
 *
 * ```text
 *   byte 0   flags
 *              bit 0  value format: 0 = uint8, 1 = uint16
 *              bits 1-2  sensor contact status
 *              bit 3  energy expended present
 *              bit 4  RR intervals present
 *   then     heart rate      uint8 or uint16, little-endian
 *   then     energy expended uint16, only if bit 3
 *   then     RR intervals    uint16 each, only if bit 4, in units of 1/1024 s
 * ```
 *
 * Every one of those decisions is a place to be quietly wrong. Reading a uint16
 * payload as uint8 gives a plausible-looking heart rate that happens to be the
 * low byte; getting the endianness backwards gives another. Neither throws, and
 * neither looks wrong on screen — which is why this is a pure function with
 * tests rather than a few lines inside a GATT callback.
 */
object HeartRateParser {

    /** The Bluetooth SIG assigned numbers this works with. */
    const val SERVICE_UUID = "0000180d-0000-1000-8000-00805f9b34fb"
    const val MEASUREMENT_UUID = "00002a37-0000-1000-8000-00805f9b34fb"
    const val CCC_DESCRIPTOR_UUID = "00002902-0000-1000-8000-00805f9b34fb"

    /**
     * The range the vault accepts (`0017_sport.sql`: `avg_hr`/`max_hr BETWEEN
     * 20 AND 300`). Kept identical on purpose — see [parse].
     */
    const val MIN_BPM = 20
    const val MAX_BPM = 300

    private const val FLAG_UINT16 = 0x01
    private const val FLAG_ENERGY = 0x08
    private const val FLAG_RR = 0x10

    /**
     * Parse one notification. Returns null when the packet is too short to hold
     * what its own flags claim — a truncated packet is dropped rather than read
     * past the end of.
     */
    fun parse(data: ByteArray): HeartRateSample? {
        if (data.isEmpty()) return null
        val flags = data[0].toInt() and 0xFF
        var cursor = 1

        val bpm: Int
        if (flags and FLAG_UINT16 != 0) {
            if (data.size < cursor + 2) return null
            bpm = u16(data, cursor)
            cursor += 2
        } else {
            if (data.size < cursor + 1) return null
            bpm = data[cursor].toInt() and 0xFF
            cursor += 1
        }

        var energy: Int? = null
        if (flags and FLAG_ENERGY != 0) {
            if (data.size < cursor + 2) return null
            energy = u16(data, cursor)
            cursor += 2
        }

        val rr = mutableListOf<Int>()
        if (flags and FLAG_RR != 0) {
            while (cursor + 1 < data.size) {
                // 1/1024 s units, not milliseconds. Converting here rather than
                // at the call site: the unit is part of the wire format and
                // nothing downstream should have to know it.
                rr += (u16(data, cursor) * 1000) / 1024
                cursor += 2
            }
        }

        // A sensor that has lost contact reports 0. That is not a heart rate,
        // and averaging it in would drag the session's number toward zero for
        // every second the strap was loose. The same goes for anything outside
        // what a human heart does — a uint16 of garbage, a strap settling —
        // and that bound is not only cosmetic: the vault refuses a session
        // whose average or peak falls outside it, so one stray packet used to
        // make the whole session impossible to save.
        if (bpm !in MIN_BPM..MAX_BPM) return null

        return HeartRateSample(bpm = bpm, energyExpendedKJ = energy, rrIntervalsMs = rr)
    }

    /** Little-endian, which is what every BLE characteristic uses. */
    private fun u16(data: ByteArray, at: Int): Int =
        (data[at].toInt() and 0xFF) or ((data[at + 1].toInt() and 0xFF) shl 8)
}

/**
 * Running average and peak across a session.
 *
 * A mean of the samples, not of "the readings that looked interesting": a
 * sensor notifies about once a second, so every sample carries the same weight
 * and a plain mean is the honest summary. Kept as a class rather than a list of
 * every reading because an hour-long session is 3600 numbers whose only use is
 * these two.
 */
class HeartRateAccumulator {
    private var sum = 0L
    private var count = 0L
    private var peak = 0

    /**
     * Synchronized: samples arrive on a Bluetooth binder thread while the
     * editor reads the summary on the main one.
     */
    @Synchronized
    fun add(bpm: Int) {
        if (bpm !in HeartRateParser.MIN_BPM..HeartRateParser.MAX_BPM) return
        sum += bpm
        count++
        if (bpm > peak) peak = bpm
    }

    /** Null until at least one sample has arrived — never 0, which is a reading. */
    val average: Int? @Synchronized get() = if (count == 0L) null else (sum / count).toInt()
    val max: Int? @Synchronized get() = peak.takeIf { it > 0 }
    val samples: Long @Synchronized get() = count

    /** Start over. A new session must not inherit the previous one's numbers. */
    @Synchronized
    fun reset() {
        sum = 0
        count = 0
        peak = 0
    }
}
