package com.watchoutrf.desktop

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference

interface RtlSdrLibrary : Library {
    companion object {
        init {
            val currentPath = System.getProperty("jna.library.path") ?: ""
            System.setProperty("jna.library.path", "/opt/homebrew/lib:/usr/local/lib:$currentPath")
        }
        val INSTANCE: RtlSdrLibrary = Native.load("rtlsdr", RtlSdrLibrary::class.java)
    }

    fun rtlsdr_get_device_count(): Int
    fun rtlsdr_get_device_name(index: Int): String?
    fun rtlsdr_open(dev: PointerByReference, index: Int): Int
    fun rtlsdr_close(dev: Pointer): Int
    
    fun rtlsdr_set_center_freq(dev: Pointer, freq: Int): Int
    fun rtlsdr_set_sample_rate(dev: Pointer, rate: Int): Int
    fun rtlsdr_set_tuner_gain_mode(dev: Pointer, manual: Int): Int
    fun rtlsdr_set_tuner_gain(dev: Pointer, gain: Int): Int
    fun rtlsdr_reset_buffer(dev: Pointer): Int
    fun rtlsdr_read_sync(dev: Pointer, buf: ByteArray, len: Int, n_read: IntByReference): Int
}

object RtlSdr {
    fun getDeviceCount(): Int {
        return try {
            RtlSdrLibrary.INSTANCE.rtlsdr_get_device_count()
        } catch (e: UnsatisfiedLinkError) {
            -1
        }
    }

    fun getDeviceName(index: Int): String? {
        return try {
            RtlSdrLibrary.INSTANCE.rtlsdr_get_device_name(index)
        } catch (e: UnsatisfiedLinkError) {
            null
        }
    }
}
