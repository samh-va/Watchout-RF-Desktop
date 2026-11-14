package com.watchoutrf.desktop.data

import com.watchoutrf.desktop.data.dsp.FftProcessor
import com.watchoutrf.desktop.data.dsp.PeakDetector
import com.watchoutrf.desktop.data.dsp.SpectrumAverager
import com.watchoutrf.desktop.data.dsp.WindowType
import com.watchoutrf.desktop.data.export.WwbExporter
import com.watchoutrf.desktop.domain.model.SpectrumData
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FftProcessorTest {

    /** Unsigned-8 interleaved IQ for a complex tone at [cyclesPerFrame] (may be negative). */
    private fun tone(n: Int, cyclesPerFrame: Int, amplitude: Double = 0.5): ByteArray {
        val out = ByteArray(n * 2)
        for (i in 0 until n) {
            val phase = 2.0 * PI * cyclesPerFrame * i / n
            out[i * 2] = (127.5 + 128.0 * amplitude * cos(phase)).roundToInt().coerceIn(0, 255).toByte()
            out[i * 2 + 1] = (127.5 + 128.0 * amplitude * sin(phase)).roundToInt().coerceIn(0, 255).toByte()
        }
        return out
    }

    @Test
    fun `positive tone lands right of centre`() {
        val n = 1024
        val spectrum = FftProcessor.processIqToSpectrum(tone(n, 100), n, WindowType.HAMMING)
        assertEquals(n, spectrum.size)
        assertEquals(n / 2 + 100, spectrum.indices.maxBy { spectrum[it] })
    }

    @Test
    fun `negative tone lands left of centre`() {
        val n = 1024
        val spectrum = FftProcessor.processIqToSpectrum(tone(n, -200), n, WindowType.HANN)
        assertEquals(n / 2 - 200, spectrum.indices.maxBy { spectrum[it] })
    }

    @Test
    fun `tone level is independent of window and fft size`() {
        // amplitude 0.5 -> -6.02 dBFS, plus the -40 dB calibration offset
        val expected = -46.0f
        for (n in listOf(256, 1024, 4096)) {
            for (w in WindowType.entries) {
                val spectrum = FftProcessor.processIqToSpectrum(tone(n, n / 8), n, w)
                val peak = spectrum.max()
                assertTrue(abs(peak - expected) < 0.5f, "n=$n window=$w peak=$peak")
            }
        }
    }

    @Test
    fun `tone stands well clear of the quantisation floor`() {
        val n = 1024
        val spectrum = FftProcessor.processIqToSpectrum(tone(n, 100), n, WindowType.BLACKMAN)
        val peakBin = n / 2 + 100
        val farAway = spectrum.filterIndexed { i, _ -> abs(i - peakBin) > 20 }
        assertTrue(spectrum[peakBin] - farAway.max() > 40f)
    }

    @Test
    fun `dc offset is suppressed`() {
        val n = 1024
        val iq = ByteArray(n * 2) { 200.toByte() } // large constant offset, no signal
        val spectrum = FftProcessor.processIqToSpectrum(iq, n, WindowType.HAMMING)
        assertTrue(spectrum.max() < -150f, "max=${spectrum.max()}")
    }

    @Test
    fun `accumulating matches the dB path`() {
        val n = 512
        val iq = tone(n, 37)
        val accum = FloatArray(n)
        FftProcessor.accumulateIqPower(iq, n, WindowType.HAMMING, accum)
        FftProcessor.accumulateIqPower(iq, n, WindowType.HAMMING, accum)
        val averaged = FloatArray(n) { FftProcessor.powerToDbm(accum[it] / 2f) }
        val direct = FftProcessor.processIqToSpectrum(iq, n, WindowType.HAMMING)
        for (i in 0 until n) assertTrue(abs(averaged[i] - direct[i]) < 0.01f)
    }
}

class WwbExporterTest {

    private fun data(bins: Int, startHz: Long, endHz: Long) =
        SpectrumData(startHz, endHz, FloatArray(bins) { -90f - it % 7 })

    @Test
    fun `uses a decimal point whatever the system locale`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("es-CO"))
            val lines = WwbExporter.buildCsvLines(data(100, 470_000_000L, 480_000_000L))
            assertEquals(100, lines.size)
            for (line in lines) {
                assertTrue(Regex("""^\d+\.\d{3},-?\d+\.\d$""").matches(line), "bad line: $line")
            }
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `frequencies are bin centres and stay inside the span`() {
        // 10 MHz / 100 bins = 100 kHz per bin -> centres at 470.050, 470.150, …
        val lines = WwbExporter.buildCsvLines(data(100, 470_000_000L, 480_000_000L))
        assertEquals("470.050", lines.first().substringBefore(','))
        assertEquals("479.950", lines.last().substringBefore(','))
    }

    @Test
    fun `fine resolution is decimated to 25 kHz keeping the peak`() {
        // 1 MHz / 400 bins = 2.5 kHz per bin -> 10 bins per exported point
        val mags = FloatArray(400) { -100f }
        mags[25] = -30f
        val lines = WwbExporter.buildCsvLines(SpectrumData(500_000_000L, 501_000_000L, mags))
        assertEquals(40, lines.size)
        assertEquals("500.063,-30.0", lines[2])
        assertEquals("500.013,-100.0", lines[0])
    }
}

class PeakDetectorTest {

    @Test
    fun `finds strongest peaks and respects minimum distance`() {
        val mags = FloatArray(200) { -100f }
        mags[50] = -40f
        mags[53] = -45f   // too close to bin 50
        mags[120] = -60f
        mags[180] = -95f  // below threshold
        val peaks = PeakDetector().detectPeaks(
            SpectrumData(0L, 200_000L, mags),
            minPeakDbm = -80f,
            minDistanceBins = 10,
            maxPeaks = 4,
        )
        assertEquals(listOf(-40f, -60f), peaks.map { it.amplitudeDbm })
        assertEquals(listOf(50_000.0, 120_000.0), peaks.map { it.frequencyHz })
    }
}

class SpectrumAveragerTest {

    @Test
    fun `averages and holds maxima independently`() {
        val averager = SpectrumAverager()
        assertContentEquals(floatArrayOf(-80f, -80f), averager.average(floatArrayOf(-80f, -80f), 0.5f))
        assertContentEquals(floatArrayOf(-70f, -85f), averager.average(floatArrayOf(-60f, -90f), 0.5f))

        assertContentEquals(floatArrayOf(-70f, -85f), averager.updateMaxHold(floatArrayOf(-70f, -85f)))
        assertContentEquals(floatArrayOf(-50f, -85f), averager.updateMaxHold(floatArrayOf(-50f, -99f)))

        averager.resetMaxHold()
        assertContentEquals(floatArrayOf(-99f, -99f), averager.updateMaxHold(floatArrayOf(-99f, -99f)))
        // the running average survived the max-hold reset
        assertContentEquals(floatArrayOf(-70f, -85f), averager.average(floatArrayOf(-70f, -85f), 0.5f))
    }

    @Test
    fun `restarts when the resolution changes`() {
        val averager = SpectrumAverager()
        averager.average(floatArrayOf(-80f, -80f), 0.9f)
        assertContentEquals(floatArrayOf(-10f, -20f, -30f), averager.average(floatArrayOf(-10f, -20f, -30f), 0.9f))
    }
}

class SweepTraceTest {
    private val e = com.watchoutrf.desktop.data.dsp.SweepTrace.EMPTY

    @Test
    fun `fills gaps by interpolation then smooths`() {
        val out = com.watchoutrf.desktop.data.dsp.SweepTrace.finish(floatArrayOf(-90f, e, e, -60f, -60f))
        // filled: -90, -80, -70, -60, -60 -> 3-bin mean with replicated edges
        assertContentEquals(floatArrayOf(-86.666664f, -80f, -70f, -63.333332f, -60f), out)
    }

    @Test
    fun `preview keeps unswept bins and blends swept ones like the averager`() {
        val raw = floatArrayOf(-50f, -50f, e, e)
        val previous = floatArrayOf(-90f, -90f, -90f, -90f)
        val out = com.watchoutrf.desktop.data.dsp.SweepTrace.preview(raw, 2, previous, 0.5f, -120f)
        assertContentEquals(floatArrayOf(-70f, -70f, -90f, -90f), out)

        val first = com.watchoutrf.desktop.data.dsp.SweepTrace.preview(raw, 2, null, 0.5f, -120f)
        assertContentEquals(floatArrayOf(-50f, -50f, -120f, -120f), first)
        assertContentEquals(floatArrayOf(-50f, -50f, e, e), raw) // input untouched
    }
}

/** Runs only when an RTL-SDR dongle is attached; otherwise it is a no-op. */
class DongleSmokeTest {
    @Test
    fun `hops return plausible spectra at the configured sample rate`() {
        if (com.watchoutrf.desktop.RtlSdr.getDeviceCount() <= 0) {
            println("DongleSmokeTest: no dongle attached, skipped")
            return
        }
        val source = com.watchoutrf.desktop.data.sdr.DesktopSdrSource()
        assertEquals(null, source.open(0))
        try {
            source.startStream()
            val step = (com.watchoutrf.desktop.data.sdr.DesktopSdrSource.SAMPLE_RATE_HZ * 0.8).toLong()
            val hops = 30
            val start = System.nanoTime()
            for (hop in 0 until hops) {
                assertTrue(source.setFrequency(470_000_000L + hop * step + step / 2))
                source.flush()
                val spectrum = source.readSpectrum(1024, 2)
                assertTrue(spectrum != null && spectrum.size == 1024, "hop $hop returned no data")
                assertTrue(spectrum.all { it.isFinite() && it > -200f && it < 20f }, "hop $hop out of range")
            }
            val msPerHop = (System.nanoTime() - start) / 1e6 / hops
            println("DongleSmokeTest: %.1f ms per hop".format(java.util.Locale.US, msPerHop))
            assertTrue(msPerHop < 60.0, "hops are unexpectedly slow: $msPerHop ms")
        } finally {
            source.close()
        }
    }
}

class ShowRepositoryTest {
    private fun show(name: String, notes: String = "", savedAt: Long = 1_000L) =
        com.watchoutrf.desktop.data.shows.ShowConfig(
            name = name,
            notes = notes,
            savedAtMillis = savedAt,
            deviceName = "Generic RTL2832U",
            frequencyRange = com.watchoutrf.desktop.domain.model.FrequencyRange.IEM_RANGE,
            numBins = 4096,
            scanMode = com.watchoutrf.desktop.domain.model.ScanMode.SINGLE,
            maxHoldEnabled = false,
            showAutoPeaks = false,
            gainMode = com.watchoutrf.desktop.domain.model.GainMode.Manual,
            manualGainDb = 28,
            markers = listOf(
                com.watchoutrf.desktop.data.shows.ShowMarker(566_350_000L, "Voz líder = #1", com.watchoutrf.desktop.domain.model.MarkerColor.MAGENTA),
                com.watchoutrf.desktop.data.shows.ShowMarker(518_000_000L, "IEM Batería", com.watchoutrf.desktop.domain.model.MarkerColor.GREEN, device = "PSM1000 ch 2"),
            ),
        )

    @Test
    fun `saves, lists, replaces, searches and deletes`() {
        val dir = java.nio.file.Files.createTempDirectory("watchoutrf-shows").toFile()
        try {
            val repo = com.watchoutrf.desktop.data.shows.ShowRepository(dir)
            assertEquals(emptyList(), repo.list())

            val bogota = show("Teatro Colón / Bogotá", notes = "Shure Axient, 8 canales", savedAt = 2_000L)
            assertEquals(null, repo.save(bogota))
            assertEquals(null, repo.save(show("Festival Estéreo", savedAt = 3_000L)))
            assertEquals(listOf(show("Festival Estéreo", savedAt = 3_000L), bogota), repo.list()) // newest first, lossless

            // Same name, different case: replaced rather than duplicated
            assertEquals(null, repo.save(bogota.copy(name = "teatro colón / bogotá", notes = "updated", savedAtMillis = 4_000L)))
            assertEquals(2, repo.list().size)
            assertEquals("updated", repo.list().first().notes)

            assertEquals(listOf("teatro colón / bogotá"), repo.list().filter { it.matches("colón updated") }.map { it.name })
            assertEquals(2, repo.list().count { it.matches("batería") }) // marker labels are searchable
            assertEquals(0, repo.list().count { it.matches("madrid") })
            assertEquals(2, repo.list().count { it.matches("psm1000") }) // marker devices are searchable
            assertEquals("PSM1000 ch 2", repo.list().first().markers[1].device)
            assertEquals("", repo.list().first().markers[0].device)

            assertTrue(repo.save(show("   ")) != null)
            assertTrue(repo.delete("FESTIVAL ESTÉREO"))
            assertEquals(1, repo.list().size)

            java.io.File(dir, "broken.show").writeText("not a show")
            assertEquals(1, repo.list().size)
        } finally {
            dir.deleteRecursively()
        }
    }
}
