package com.watchoutrf.desktop.data.dsp

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin

enum class WindowType {
    RECTANGULAR, HAMMING, HANN, BLACKMAN
}

object FftProcessor {
    private const val CALIBRATION_OFFSET_DB = -40.0f

    /** Bins on each side of DC that are replaced by interpolation. */
    private const val DC_KILL_RADIUS = 5

    /**
     * Everything that only depends on the FFT size and window type.
     * Computed once and reused: the sweep runs thousands of FFTs per second
     * with the same parameters, so recomputing cosines per sample is wasteful.
     */
    private class Plan(n: Int, type: WindowType) {
        val window = FloatArray(n) { windowValue(it, n, type) }

        /** 1 / (N² · coherentGain²) — converts |X[k]|² to normalised power. */
        val powerScale: Float

        /** Twiddle factors e^(-2πik/N) for k in 0 until N/2, computed in double precision. */
        val twiddleRe = FloatArray(n / 2) { cos(-2.0 * PI * it / n).toFloat() }
        val twiddleIm = FloatArray(n / 2) { sin(-2.0 * PI * it / n).toFloat() }

        init {
            val coherentGain = (window.sum() / n).coerceAtLeast(1e-6f)
            powerScale = 1f / (n.toFloat() * n.toFloat() * coherentGain * coherentGain)
        }
    }

    private val plans = ConcurrentHashMap<Pair<Int, WindowType>, Plan>()

    private fun planFor(numBins: Int, type: WindowType): Plan =
        plans.getOrPut(numBins to type) { Plan(numBins, type) }

    /**
     * Converts raw RTL-SDR IQ bytes into a power spectrum (dBm).
     * @param iqBytes Raw samples: unsigned 8-bit, interleaved I/Q (I0 Q0 I1 Q1 ...)
     * @param numBins FFT size (must be power of two, e.g. 1024)
     * @param windowType Windowing function
     * @return FloatArray of length numBins with power in dBm, DC-centered
     */
    fun processIqToSpectrum(iqBytes: ByteArray, numBins: Int, windowType: WindowType): FloatArray {
        val power = FloatArray(numBins)
        accumulateIqPower(iqBytes, numBins, windowType, power)
        return FloatArray(numBins) { powerToDbm(power[it]) }
    }

    /**
     * Same pipeline as [processIqToSpectrum] but **adds** the linear (not dB) power of
     * each DC-centred bin into [accum]. Lets callers average several captures in the
     * linear domain without converting to dB and back for every bin.
     */
    fun accumulateIqPower(iqBytes: ByteArray, numBins: Int, windowType: WindowType, accum: FloatArray) {
        require(numBins >= 32 && (numBins and (numBins - 1)) == 0) { "numBins must be a power of two >= 32" }
        require(iqBytes.size >= numBins * 2) { "IQ buffer too small" }
        require(accum.size >= numBins) { "accumulator too small" }

        val plan = planFor(numBins, windowType)

        // 1. Unsigned 8-bit -> float, and measure the DC offset
        val re = FloatArray(numBins)
        val im = FloatArray(numBins)
        var sumI = 0f
        var sumQ = 0f
        for (i in 0 until numBins) {
            val iVal = ((iqBytes[i * 2].toInt() and 0xFF) - 127.5f) / 128.0f
            val qVal = ((iqBytes[i * 2 + 1].toInt() and 0xFF) - 127.5f) / 128.0f
            re[i] = iVal
            im[i] = qVal
            sumI += iVal
            sumQ += qVal
        }
        val meanI = sumI / numBins
        val meanQ = sumQ / numBins

        // 2. Remove DC offset and apply window
        val window = plan.window
        for (i in 0 until numBins) {
            re[i] = (re[i] - meanI) * window[i]
            im[i] = (im[i] - meanQ) * window[i]
        }

        // 3. Forward FFT
        radix2Fft(re, im, plan)

        // 4. Remove DC spike (interpolation around DC)
        val leftRef = numBins - DC_KILL_RADIUS - 1
        val rightRef = DC_KILL_RADIUS + 1
        val leftR = re[leftRef]
        val leftI = im[leftRef]
        val rightR = re[rightRef]
        val rightI = im[rightRef]
        val zoneLen = 2 * DC_KILL_RADIUS + 1

        for (k in 0 until zoneLen) {
            val t = (k + 1).toFloat() / (zoneLen + 1).toFloat()
            val idx = (numBins - DC_KILL_RADIUS + k) % numBins
            re[idx] = leftR * (1.0f - t) + rightR * t
            im[idx] = leftI * (1.0f - t) + rightI * t
        }

        // 5. Linear power with DC-centering (FFT shift)
        val scale = plan.powerScale
        val half = numBins / 2
        for (i in 0 until numBins) {
            val src = (i + half) % numBins
            accum[i] += (re[src] * re[src] + im[src] * im[src]) * scale
        }
    }

    /** Normalised linear power (as produced by [accumulateIqPower]) -> calibrated dBm. */
    fun powerToDbm(power: Float): Float =
        10.0f * log10(power + 1e-20f) + CALIBRATION_OFFSET_DB

    private fun windowValue(i: Int, n: Int, type: WindowType): Float {
        val x = i.toDouble() / (n - 1).toDouble()
        return when (type) {
            WindowType.HAMMING -> 0.54 - 0.46 * cos(2.0 * PI * x)
            WindowType.HANN -> 0.5 * (1.0 - cos(2.0 * PI * x))
            WindowType.BLACKMAN -> 0.42 - 0.50 * cos(2.0 * PI * x) + 0.08 * cos(4.0 * PI * x)
            WindowType.RECTANGULAR -> 1.0
        }.toFloat()
    }

    /**
     * In-place Radix-2 FFT (Cooley-Tukey) using the plan's precomputed twiddle table.
     * A table avoids the rounding drift of the "multiply the previous twiddle" recurrence.
     */
    private fun radix2Fft(real: FloatArray, imag: FloatArray, plan: Plan) {
        val n = real.size
        // Bit-reversal permutation
        var j = 0
        for (i in 0 until n - 1) {
            if (i < j) {
                val tempR = real[i]
                val tempI = imag[i]
                real[i] = real[j]
                imag[i] = imag[j]
                real[j] = tempR
                imag[j] = tempI
            }
            var k = n / 2
            while (k <= j) {
                j -= k
                k /= 2
            }
            j += k
        }

        val twRe = plan.twiddleRe
        val twIm = plan.twiddleIm

        var m = 2
        while (m <= n) {
            val halfM = m / 2
            val stride = n / m
            for (k in 0 until halfM) {
                val wR = twRe[k * stride]
                val wI = twIm[k * stride]
                var i = k
                while (i < n) {
                    val p = i + halfM
                    val tempR = wR * real[p] - wI * imag[p]
                    val tempI = wR * imag[p] + wI * real[p]
                    real[p] = real[i] - tempR
                    imag[p] = imag[i] - tempI
                    real[i] += tempR
                    imag[i] += tempI
                    i += m
                }
            }
            m *= 2
        }
    }
}
