package com.watchoutrf.desktop.data.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10

enum class WindowType {
    RECTANGULAR, HAMMING, HANN, BLACKMAN
}

object FftProcessor {
    private const val CALIBRATION_OFFSET_DB = -40.0f

    /**
     * Converts raw RTL-SDR IQ bytes into a power spectrum (dBm).
     * @param iqBytes Raw samples: unsigned 8-bit, interleaved I/Q (I0 Q0 I1 Q1 ...)
     * @param numBins FFT size (must be power of two, e.g. 1024)
     * @param windowType Windowing function
     * @return FloatArray of length numBins with power in dBm, DC-centered
     */
    fun processIqToSpectrum(iqBytes: ByteArray, numBins: Int, windowType: WindowType): FloatArray {
        require(numBins > 0 && (numBins and (numBins - 1)) == 0) { "numBins must be a power of two" }
        require(iqBytes.size >= numBins * 2) { "IQ buffer too small" }

        val norm = windowNorm(numBins, windowType).coerceAtLeast(1e-6f)

        // 1. Calculate DC offset
        var sumI = 0f
        var sumQ = 0f
        for (i in 0 until numBins) {
            val iVal = ((iqBytes[i * 2].toInt() and 0xFF) - 127.5f) / 128.0f
            val qVal = ((iqBytes[i * 2 + 1].toInt() and 0xFF) - 127.5f) / 128.0f
            sumI += iVal
            sumQ += qVal
        }
        val meanI = sumI / numBins
        val meanQ = sumQ / numBins

        // 2. Apply window and remove DC offset
        val inReal = FloatArray(numBins)
        val inImag = FloatArray(numBins)
        for (i in 0 until numBins) {
            val iVal = (((iqBytes[i * 2].toInt() and 0xFF) - 127.5f) / 128.0f) - meanI
            val qVal = (((iqBytes[i * 2 + 1].toInt() and 0xFF) - 127.5f) / 128.0f) - meanQ
            val w = applyWindow(i, numBins, windowType)
            inReal[i] = iVal * w
            inImag[i] = qVal * w
        }

        // 3. Forward FFT
        radix2Fft(inReal, inImag)

        // 4. Remove DC spike (interpolation around DC)
        val dcKillRadius = 5
        val leftRef = numBins - dcKillRadius - 1
        val rightRef = dcKillRadius + 1
        val leftR = inReal[leftRef]
        val leftI = inImag[leftRef]
        val rightR = inReal[rightRef]
        val rightI = inImag[rightRef]
        val zoneLen = 2 * dcKillRadius + 1

        for (k in 0 until zoneLen) {
            val t = (k + 1).toFloat() / (zoneLen + 1).toFloat()
            val idx = (numBins - dcKillRadius + k) % numBins
            inReal[idx] = leftR * (1.0f - t) + rightR * t
            inImag[idx] = leftI * (1.0f - t) + rightI * t
        }

        // 5. Magnitude -> dBm with DC-centering (FFT shift)
        val mag = FloatArray(numBins)
        val powerDivisor = numBins.toFloat() * numBins.toFloat() * norm * norm
        for (i in 0 until numBins) {
            val src = (i + numBins / 2) % numBins
            val re = inReal[src]
            val im = inImag[src]

            val power = (re * re + im * im) / powerDivisor
            mag[i] = 10.0f * log10(power + 1e-20f) + CALIBRATION_OFFSET_DB
        }

        return mag
    }

    private fun applyWindow(i: Int, n: Int, type: WindowType): Float {
        val x = i.toFloat() / (n - 1).toFloat()
        return when (type) {
            WindowType.HAMMING -> 0.54f - 0.46f * cos(2.0f * PI.toFloat() * x)
            WindowType.HANN -> 0.5f * (1.0f - cos(2.0f * PI.toFloat() * x))
            WindowType.BLACKMAN -> 0.42f - 0.50f * cos(2.0f * PI.toFloat() * x) + 0.08f * cos(4.0f * PI.toFloat() * x)
            WindowType.RECTANGULAR -> 1.0f
        }
    }

    private fun windowNorm(n: Int, type: WindowType): Float {
        var sum = 0.0f
        for (i in 0 until n) {
            sum += applyWindow(i, n, type)
        }
        return sum / n.toFloat()
    }

    /**
     * In-place Radix-2 FFT (Cooley-Tukey)
     */
    private fun radix2Fft(real: FloatArray, imag: FloatArray) {
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

        // Cooley-Tukey
        var m = 2
        while (m <= n) {
            val halfM = m / 2
            val theta = -2.0 * PI / m
            val wpR = cos(theta).toFloat()
            val wpI = kotlin.math.sin(theta).toFloat()
            var wR = 1.0f
            var wI = 0.0f

            for (k in 0 until halfM) {
                for (i in k until n step m) {
                    val p = i + halfM
                    val tempR = wR * real[p] - wI * imag[p]
                    val tempI = wR * imag[p] + wI * real[p]
                    real[p] = real[i] - tempR
                    imag[p] = imag[i] - tempI
                    real[i] += tempR
                    imag[i] += tempI
                }
                val nextWR = wR * wpR - wI * wpI
                val nextWI = wR * wpI + wI * wpR
                wR = nextWR
                wI = nextWI
            }
            m *= 2
        }
    }
}
