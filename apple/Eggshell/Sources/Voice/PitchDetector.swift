import Foundation
import AVFoundation

// ===========================================================================
// Monophonic fundamental-frequency (F0) estimator. Mirrors the Android
// PitchDetector + AudioDecoder pair:
//   1. decode the recorded .m4a to mono Float PCM via AVAudioFile →
//      AVAudioPCMBuffer (pure AVFoundation, no extra deps),
//   2. high-pass filter to kill DC / hum / rumble,
//   3. run the YIN algorithm (de Cheveigné & Kawahara, 2002) over
//      overlapping windows and take the median voiced F0.
//
// Implemented inline because we only need the single algorithm; the voiced
// band is clamped to 70–400 Hz, the plausible range for trans-HRT voice
// tracking — rejecting outliers gives a much steadier per-clip headline
// number than reporting raw per-window estimates.
// ===========================================================================

enum PitchDetector {

    private static let minHz: Float = 70
    private static let maxHz: Float = 400
    private static let threshold: Float = 0.15
    private static let windowSize = 2048
    private static let hopSize = 1024
    private static let minVoicedWindows = 6

    /// Decodes the clip at `url` and returns the median voiced F0 in Hz, or
    /// nil when the audio can't be decoded or no clear pitch is found.
    static func estimatePitch(url: URL) -> Int32? {
        guard let decoded = decodeToMonoFloats(url: url) else { return nil }
        guard let hz = estimateMedianHz(samples: decoded.samples, sampleRate: decoded.sampleRate) else {
            return nil
        }
        return Int32(hz.rounded())
    }

    // MARK: - Decoding (AVFoundation)

    private struct Decoded {
        let samples: [Float]
        let sampleRate: Float
    }

    /// Reads the audio file and converts it to mono Float PCM in [-1, 1].
    private static func decodeToMonoFloats(url: URL) -> Decoded? {
        guard let file = try? AVAudioFile(forReading: url) else { return nil }
        let inFormat = file.processingFormat
        let sampleRate = Float(inFormat.sampleRate)
        if sampleRate <= 0 { return nil }

        // Target: same sample rate, mono, deinterleaved Float32. Converting up
        // front means the YIN windows always see a single clean channel.
        guard let monoFormat = AVAudioFormat(
            commonFormat: .pcmFormatFloat32,
            sampleRate: inFormat.sampleRate,
            channels: 1,
            interleaved: false
        ) else { return nil }

        guard let converter = AVAudioConverter(from: inFormat, to: monoFormat) else { return nil }

        let frameCount = AVAudioFrameCount(file.length)
        if frameCount == 0 { return nil }

        guard let inputBuffer = AVAudioPCMBuffer(pcmFormat: inFormat, frameCapacity: frameCount) else {
            return nil
        }
        do {
            try file.read(into: inputBuffer)
        } catch {
            return nil
        }
        if inputBuffer.frameLength == 0 { return nil }

        // Converted capacity matches input frame count since the sample rate is
        // unchanged (mono downmix only).
        guard let outputBuffer = AVAudioPCMBuffer(pcmFormat: monoFormat, frameCapacity: inputBuffer.frameLength) else {
            return nil
        }

        var fed = false
        let inputBlock: AVAudioConverterInputBlock = { _, outStatus in
            if fed {
                outStatus.pointee = .endOfStream
                return nil
            }
            fed = true
            outStatus.pointee = .haveData
            return inputBuffer
        }

        var conversionError: NSError?
        let status = converter.convert(to: outputBuffer, error: &conversionError, withInputFrom: inputBlock)
        if status == .error || conversionError != nil { return nil }

        let n = Int(outputBuffer.frameLength)
        guard n > 0, let channelData = outputBuffer.floatChannelData else { return nil }
        let ptr = channelData[0]
        var samples = [Float](repeating: 0, count: n)
        for i in 0..<n { samples[i] = ptr[i] }
        return Decoded(samples: samples, sampleRate: sampleRate)
    }

    // MARK: - Median over windows

    /// Walks the buffer in overlapping windows and returns the median F0 of all
    /// voiced windows, or nil when too few windows are voiced.
    private static func estimateMedianHz(samples: [Float], sampleRate: Float) -> Float? {
        if samples.count < windowSize { return nil }
        let filtered = highPass(samples, sampleRate: sampleRate, cutoffHz: 70)
        var voiced: [Float] = []
        var window = [Float](repeating: 0, count: windowSize)
        var offset = 0
        while offset + windowSize <= filtered.count {
            for i in 0..<windowSize { window[i] = filtered[offset + i] }
            // Skip near-silent frames so background hiss doesn't drag the median.
            if rms(window) >= 0.005, let hz = detectHz(window, sampleRate: sampleRate) {
                voiced.append(hz)
            }
            offset += hopSize
        }
        if voiced.count < minVoicedWindows { return nil }
        voiced.sort()
        return voiced[voiced.count / 2]
    }

    // MARK: - Single-window YIN

    private static func detectHz(_ samples: [Float], sampleRate: Float) -> Float? {
        if samples.count < 4 { return nil }
        let tauMin = max(Int(sampleRate / maxHz), 2)
        let tauMax = min(Int(sampleRate / minHz), samples.count / 2)
        if tauMax <= tauMin { return nil }

        // Step 1: squared difference function d_t(τ) for τ in [1, tauMax].
        var diff = [Float](repeating: 0, count: tauMax + 1)
        for tau in 1...tauMax {
            var sum: Float = 0
            let limit = samples.count - tau
            var i = 0
            while i < limit {
                let d = samples[i] - samples[i + tau]
                sum += d * d
                i += 1
            }
            diff[tau] = sum
        }

        // Step 2: cumulative-mean-normalised difference d'_t(τ).
        var cmnd = [Float](repeating: 0, count: tauMax + 1)
        cmnd[0] = 1
        var runningSum: Float = 0
        for tau in 1...tauMax {
            runningSum += diff[tau]
            cmnd[tau] = runningSum > 0 ? diff[tau] * Float(tau) / runningSum : 1
        }

        // Step 3: absolute threshold — first τ ≥ tauMin with d' < threshold.
        var tauEst = -1
        var tau = tauMin
        while tau <= tauMax {
            if cmnd[tau] < threshold {
                var t = tau
                while t + 1 <= tauMax && cmnd[t + 1] < cmnd[t] { t += 1 }
                tauEst = t
                break
            }
            tau += 1
        }
        if tauEst < 0 { return nil }

        // Step 4: parabolic interpolation around the dip for sub-sample resolution.
        let refinedTau: Float
        if tauEst > tauMin && tauEst < tauMax {
            let s0 = cmnd[tauEst - 1]
            let s1 = cmnd[tauEst]
            let s2 = cmnd[tauEst + 1]
            let denom = 2 * (2 * s1 - s0 - s2)
            refinedTau = denom != 0 ? Float(tauEst) + (s2 - s0) / denom : Float(tauEst)
        } else {
            refinedTau = Float(tauEst)
        }
        if refinedTau <= 0 { return nil }
        let f0 = sampleRate / refinedTau
        if f0 < minHz || f0 > maxHz { return nil }
        return f0
    }

    // MARK: - DSP helpers

    /// One-pole IIR high-pass: y[n] = α · (y[n-1] + x[n] - x[n-1]).
    private static func highPass(_ samples: [Float], sampleRate: Float, cutoffHz: Float) -> [Float] {
        if samples.isEmpty { return samples }
        let rc = 1 / (2 * Float.pi * cutoffHz)
        let dt = 1 / sampleRate
        let alpha = rc / (rc + dt)
        var out = [Float](repeating: 0, count: samples.count)
        var prevX = samples[0]
        var prevY: Float = 0
        out[0] = 0
        for i in 1..<samples.count {
            let y = alpha * (prevY + samples[i] - prevX)
            out[i] = y
            prevY = y
            prevX = samples[i]
        }
        return out
    }

    private static func rms(_ samples: [Float]) -> Float {
        if samples.isEmpty { return 0 }
        var sum: Double = 0
        for v in samples { sum += Double(v) * Double(v) }
        return Float((sum / Double(samples.count)).squareRoot())
    }
}
