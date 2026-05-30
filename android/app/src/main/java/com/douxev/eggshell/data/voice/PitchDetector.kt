package com.douxev.eggshell.data.voice

/**
 * Monophonic pitch detector based on the YIN algorithm (de Cheveigné & Kawahara, 2002).
 *
 * Implemented inline rather than pulled in via TarsosDSP/JFreeChart because we
 * only need the single algorithm and don't want to ship ~700 KB of unrelated
 * DSP / charting code in the APK.
 *
 * Operates on a short window (≈ 0.05–0.1 s of audio at 44.1 kHz); the caller
 * is responsible for slicing the recording into windows and aggregating per-
 * window F0 estimates (see [estimateMedianHz]).
 *
 * Voiced-speech range is clamped to [70 Hz, 500 Hz]: trans HRT voice tracking
 * sits squarely in that band and rejecting extreme outliers gives a much
 * more stable per-clip headline number than reporting raw per-window F0.
 */
object PitchDetector {

    private const val DEFAULT_THRESHOLD = 0.15f
    private const val MIN_HZ = 70f
    private const val MAX_HZ = 500f
    private const val WINDOW_SIZE = 2048
    private const val HOP_SIZE = 1024
    private const val MIN_VOICED_WINDOWS = 6

    /**
     * Single-window YIN: returns the detected fundamental frequency in Hz, or
     * null if no clear pitch was found within the voiced-speech band.
     */
    fun detectHz(
        samples: FloatArray,
        sampleRate: Int,
        threshold: Float = DEFAULT_THRESHOLD,
    ): Float? {
        if (samples.size < 4) return null
        val tauMin = (sampleRate / MAX_HZ).toInt().coerceAtLeast(2)
        val tauMax = (sampleRate / MIN_HZ).toInt().coerceAtMost(samples.size / 2)
        if (tauMax <= tauMin) return null

        // Step 1: squared difference function d_t(τ) for τ in [1, tauMax].
        // We MUST compute it for every τ from 1, not just from tauMin, because
        // step 3 normalises by Σ d(j) for j=1..τ — skipping the low lags would
        // give an inflated cmnd in the τ ≈ tauMin region and bias detection
        // toward lower frequencies.
        val diff = FloatArray(tauMax + 1)
        for (tau in 1..tauMax) {
            var sum = 0f
            val limit = samples.size - tau
            var i = 0
            while (i < limit) {
                val d = samples[i] - samples[i + tau]
                sum += d * d
                i++
            }
            diff[tau] = sum
        }

        // Step 2: cumulative-mean-normalised difference d'_t(τ).
        // Definition: d'(τ) = d(τ) * τ / Σ_{j=1}^{τ} d(j), with d'(0) = 1.
        val cmnd = FloatArray(tauMax + 1)
        cmnd[0] = 1f
        var runningSum = 0f
        for (tau in 1..tauMax) {
            runningSum += diff[tau]
            cmnd[tau] = if (runningSum > 0f) diff[tau] * tau / runningSum else 1f
        }

        // Step 3: absolute threshold — first τ ≥ tauMin with d' < threshold.
        var tauEst = -1
        for (tau in tauMin..tauMax) {
            if (cmnd[tau] < threshold) {
                // Walk forward to the local minimum (YIN's "best local estimate").
                var t = tau
                while (t + 1 <= tauMax && cmnd[t + 1] < cmnd[t]) t++
                tauEst = t
                break
            }
        }
        if (tauEst < 0) return null

        // Step 4: parabolic interpolation around the dip for sub-sample resolution.
        val refinedTau = if (tauEst > tauMin && tauEst < tauMax) {
            val s0 = cmnd[tauEst - 1]
            val s1 = cmnd[tauEst]
            val s2 = cmnd[tauEst + 1]
            val denom = 2f * (2f * s1 - s0 - s2)
            if (denom != 0f) tauEst + (s2 - s0) / denom else tauEst.toFloat()
        } else {
            tauEst.toFloat()
        }
        if (refinedTau <= 0f) return null
        val f0 = sampleRate.toFloat() / refinedTau
        if (f0 !in MIN_HZ..MAX_HZ) return null
        return f0
    }

    /**
     * Walks the recording in overlapping windows and returns the median F0
     * across all voiced windows. Returns null when there aren't enough voiced
     * windows (silence / noise / non-vocal audio).
     *
     * The whole buffer is first passed through a one-pole high-pass filter at
     * 70 Hz: this kills AC hum, HVAC rumble, breath plosives and any DC bias
     * coming out of the AAC decoder. YIN is sensitive to low-frequency
     * energy because the cumulative-sum normalisation lets a long-period
     * rumble dominate the denominator and pull the detection toward an
     * octave-down estimate.
     */
    fun estimateMedianHz(samples: FloatArray, sampleRate: Int): Float? {
        if (samples.size < WINDOW_SIZE) return null
        val filtered = highPass(samples, sampleRate, cutoffHz = 70f)
        val window = FloatArray(WINDOW_SIZE)
        val voicedHz = ArrayList<Float>(filtered.size / HOP_SIZE)
        var offset = 0
        while (offset + WINDOW_SIZE <= filtered.size) {
            System.arraycopy(filtered, offset, window, 0, WINDOW_SIZE)
            // Skip near-silent frames so the median isn't dragged around by
            // background hiss that happens to alias to a pitch by accident.
            if (rms(window) >= 0.005f) {
                detectHz(window, sampleRate)?.let { voicedHz.add(it) }
            }
            offset += HOP_SIZE
        }
        if (voicedHz.size < MIN_VOICED_WINDOWS) return null
        voicedHz.sort()
        return voicedHz[voicedHz.size / 2]
    }

    /**
     * One-pole IIR high-pass filter.
     *
     * y[n] = α · (y[n-1] + x[n] - x[n-1])
     *
     * with α = RC / (RC + dt) and RC = 1 / (2π · fc). This is a 6 dB/octave
     * filter — gentle enough to leave voice fundamentals (70 Hz+) intact
     * while attenuating everything below by progressively more.
     */
    private fun highPass(samples: FloatArray, sampleRate: Int, cutoffHz: Float): FloatArray {
        val rc = 1f / (2f * Math.PI.toFloat() * cutoffHz)
        val dt = 1f / sampleRate
        val alpha = rc / (rc + dt)
        val out = FloatArray(samples.size)
        var prevX = samples[0]
        var prevY = 0f
        out[0] = 0f
        for (i in 1 until samples.size) {
            val y = alpha * (prevY + samples[i] - prevX)
            out[i] = y
            prevY = y
            prevX = samples[i]
        }
        return out
    }

    private fun rms(samples: FloatArray): Float {
        var sum = 0.0
        for (v in samples) sum += v * v
        return kotlin.math.sqrt(sum / samples.size).toFloat()
    }
}
