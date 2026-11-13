package com.watchoutrf.desktop.data.sdr

import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import com.watchoutrf.desktop.RtlSdrLibrary
import com.watchoutrf.desktop.data.dsp.FftProcessor
import com.watchoutrf.desktop.data.dsp.WindowType
import com.watchoutrf.desktop.domain.model.FrequencyRange
import com.watchoutrf.desktop.domain.model.GainMode

class DesktopSdrSource {
    private val tag = "DesktopSdrSource"
    private var devPtr: Pointer? = null

    private var numBins = 1024
    private var windowType = WindowType.HAMMING
    var currentFrequencyHz: Long = 0L
        private set

    fun open(deviceIndex: Int = 0, sampleRate: Int = 2048000): String? {
        close()
        val lib = RtlSdrLibrary.instanceOrNull ?: return "LIBRTLSDR_NOT_INSTALLED"
        val ptrRef = PointerByReference()
        val res = lib.rtlsdr_open(ptrRef, deviceIndex)
        if (res < 0) return "FAIL_OPEN_LIBRTLSDR_$res"

        val dev = ptrRef.value ?: return "FAIL_OPEN_LIBRTLSDR_NULL"

        // Init typical parameters
        val rateRes = lib.rtlsdr_set_sample_rate(dev, sampleRate)
        if (rateRes < 0) {
            lib.rtlsdr_close(dev)
            return "FAIL_SAMPLE_RATE_$rateRes"
        }
        // Auto gain by default
        lib.rtlsdr_set_tuner_gain_mode(dev, 0)

        devPtr = dev
        return null
    }

    fun setFrequency(range: FrequencyRange): Boolean {
        return setFrequency(range.centerHz)
    }

    fun setFrequency(freqHz: Long): Boolean {
        val dev = devPtr ?: return false
        val res = RtlSdrLibrary.INSTANCE.rtlsdr_set_center_freq(dev, freqHz.toInt())
        if (res == 0) {
            currentFrequencyHz = freqHz
            return true
        }
        return false
    }

    fun setGain(mode: GainMode, manualGainDb: Int = 40): Boolean {
        val dev = devPtr ?: return false
        if (mode == GainMode.Auto) {
            RtlSdrLibrary.INSTANCE.rtlsdr_set_tuner_gain_mode(dev, 0)
        } else {
            RtlSdrLibrary.INSTANCE.rtlsdr_set_tuner_gain_mode(dev, 1)
            // librtlsdr gain is in tenths of a dB
            RtlSdrLibrary.INSTANCE.rtlsdr_set_tuner_gain(dev, manualGainDb * 10)
        }
        return true
    }

    fun startStream() {
        val dev = devPtr ?: return
        RtlSdrLibrary.INSTANCE.rtlsdr_reset_buffer(dev)
    }

    fun flush() {
        val dev = devPtr ?: return
        RtlSdrLibrary.INSTANCE.rtlsdr_reset_buffer(dev)
    }

    fun readSpectrum(bins: Int = numBins, numReads: Int = 4): FloatArray? {
        numBins = bins
        val dev = devPtr ?: return null

        val bytesPerBlock = bins * 2
        val linearAccum = FloatArray(bins)
        var validReads = 0
        
        val iqBuffer = ByteArray(bytesPerBlock)
        val nRead = IntByReference()

        // Dummy read to flush stale buffers from previous frequency hop
        RtlSdrLibrary.INSTANCE.rtlsdr_read_sync(dev, iqBuffer, bytesPerBlock, nRead)

        repeat(numReads) { i ->
            val res = RtlSdrLibrary.INSTANCE.rtlsdr_read_sync(dev, iqBuffer, bytesPerBlock, nRead)
            if (res < 0 || nRead.value != bytesPerBlock) {
                return@repeat
            }
            
            // Accumulate directly in linear power: avoids a dB -> linear round trip per bin
            FftProcessor.accumulateIqPower(iqBuffer, bins, windowType, linearAccum)
            validReads++
        }

        if (validReads == 0) return null

        val result = FloatArray(bins)
        for (j in 0 until bins) {
            val avgLinear = linearAccum[j] / validReads.toFloat()
            result[j] = FftProcessor.powerToDbm(avgLinear)
        }

        return result
    }

    fun close() {
        devPtr?.let {
            RtlSdrLibrary.INSTANCE.rtlsdr_close(it)
        }
        devPtr = null
    }

    val isOpen: Boolean get() = devPtr != null
}
