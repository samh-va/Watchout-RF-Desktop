package com.watchoutrf.desktop

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference

interface RtlSdrLibrary : Library {
    companion object {
        /**
         * The loaded librtlsdr binding, or null when the library is not installed.
         *
         * Loading is attempted exactly once. A failed load must not be retried through a
         * static initialiser: the JVM would turn every later access into NoClassDefFoundError,
         * which callers do not expect and which would kill the USB polling coroutine.
         */
        val instanceOrNull: RtlSdrLibrary? by lazy {
            try {
                val currentPath = System.getProperty("jna.library.path") ?: ""
                System.setProperty(
                    "jna.library.path",
                    listOf("/opt/homebrew/lib", "/usr/local/lib", currentPath)
                        .filter { it.isNotBlank() }
                        .joinToString(java.io.File.pathSeparator),
                )
                Native.load("rtlsdr", RtlSdrLibrary::class.java)
            } catch (t: Throwable) {
                System.err.println("librtlsdr could not be loaded: ${t.message}")
                null
            }
        }

        /** Non-null accessor for code paths that only run after a device was opened. */
        val INSTANCE: RtlSdrLibrary
            get() = instanceOrNull ?: throw UnsatisfiedLinkError("librtlsdr is not available")
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
    /** True when librtlsdr was found and loaded. */
    val isLibraryAvailable: Boolean get() = RtlSdrLibrary.instanceOrNull != null

    /** Number of attached RTL-SDR dongles, or -1 when librtlsdr is not installed. */
    fun getDeviceCount(): Int {
        val lib = RtlSdrLibrary.instanceOrNull ?: return -1
        return try {
            lib.rtlsdr_get_device_count()
        } catch (e: Throwable) {
            -1
        }
    }

    fun getDeviceName(index: Int): String? {
        val lib = RtlSdrLibrary.instanceOrNull ?: return null
        return try {
            lib.rtlsdr_get_device_name(index)
        } catch (e: Throwable) {
            null
        }
    }
}
