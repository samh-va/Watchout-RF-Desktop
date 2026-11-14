package com.watchoutrf.desktop.ui.screens.spectrum

import com.watchoutrf.desktop.RtlSdr
import com.watchoutrf.desktop.data.dsp.PeakDetector
import com.watchoutrf.desktop.data.dsp.SpectrumAverager
import com.watchoutrf.desktop.data.dsp.SweepTrace
import com.watchoutrf.desktop.data.dsp.SyntheticSignalGenerator
import com.watchoutrf.desktop.data.sdr.DesktopSdrSource
import com.watchoutrf.desktop.data.export.WwbExporter
import com.watchoutrf.desktop.domain.model.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.ceil
import kotlin.math.max

class SpectrumViewModel {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(SpectrumState())
    val state: StateFlow<SpectrumState> = _state.asStateFlow()

    private val sdrSource = DesktopSdrSource()
    private val syntheticGenerator = SyntheticSignalGenerator()
    private val averager = SpectrumAverager()
    private val peakDetector = PeakDetector()

    /**
     * Serialises every access to the dongle (open / close / tune / read / gain).
     * librtlsdr device handles are not safe to use from several threads at once.
     */
    private val hwMutex = Mutex()

    private var scanJob: Job? = null
    private var frameCount = 0
    private var fpsTimestamp = System.currentTimeMillis()

    init {
        // Opening the dongle takes a moment (USB + tuner init): keep it off the UI thread.
        scope.launch(Dispatchers.IO) { connectIfAvailable() }
    }

    /**
     * Opens dongle 0 if one is attached and we are not already using it.
     * Safe to call repeatedly — this is what makes hot-plugging work: a dongle
     * connected after the app was launched is picked up by the scan loop.
     *
     * @return true when hardware is open after the call.
     */
    private suspend fun connectIfAvailable(): Boolean = hwMutex.withLock {
        if (sdrSource.isOpen) return@withLock true
        if (RtlSdr.getDeviceCount() <= 0) return@withLock false

        val deviceName = RtlSdr.getDeviceName(0) ?: "SDR Dongle"
        val error = sdrSource.open(0)
        if (error == null) {
            val config = _state.value.scanConfig
            sdrSource.setGain(config.gainMode, config.manualGainDb)
            sdrSource.startStream()
            _state.update {
                it.copy(isDemoMode = false, connectedDeviceName = deviceName, hwError = null)
            }
            true
        } else {
            _state.update {
                it.copy(
                    isDemoMode = true,
                    connectedDeviceName = null,
                    hwError = "Error initializing $deviceName: $error",
                )
            }
            false
        }
    }

    fun startScanning() {
        if (scanJob?.isActive == true) return

        _state.update { it.copy(isScanning = true, hwError = null) }
        frameCount = 0
        fpsTimestamp = System.currentTimeMillis()

        scanJob = scope.launch(Dispatchers.IO) {
            val waterfallBuffer = mutableListOf<FloatArray>()
            waterfallBuffer.addAll(_state.value.waterfallHistory)
            var peakFrameCounter = 0
            var lastRange: FrequencyRange? = null
            var lastNumBins = -1
            var lastHotplugCheck = 0L

            while (isActive && _state.value.isScanning) {
                val sweepStartMs = System.currentTimeMillis()
                val config = _state.value.scanConfig
                val range  = config.frequencyRange
                val spanHz = range.spanHz

                // The range or resolution changed since the previous sweep: everything
                // accumulated so far (average, max hold, waterfall) describes another
                // frequency axis and must not be mixed with the new data.
                if (range != lastRange || config.numBins != lastNumBins) {
                    if (lastRange != null) {
                        averager.reset()
                        waterfallBuffer.clear()
                    }
                    peakFrameCounter = 0
                    lastRange = range
                    lastNumBins = config.numBins
                }

                // Hot-plug: while in demo mode, periodically look for a dongle.
                if (_state.value.isDemoMode && sweepStartMs - lastHotplugCheck >= HOTPLUG_POLL_MS) {
                    lastHotplugCheck = sweepStartMs
                    if (connectIfAvailable()) {
                        // Switching source: do not blend synthetic data with real data
                        averager.reset()
                        waterfallBuffer.clear()
                    }
                }

                // ── FIXED SWEEP: use 80% of real SDR bandwidth as hop step ──
                // RTL-SDR real BW is 2,048,000 Hz. We use 80% (1,638,400 Hz)
                // as the hop advance so consecutive hops always overlap and
                // no frequency falls into a blind gap between hops.
                val sdrBwHz    = 2_048_000L
                val usableBwHz = (sdrBwHz * 0.80).toLong()  // 1,638,400 Hz
                val numHops    = max(1, ceil(spanHz.toDouble() / usableBwHz.toDouble()).toInt())
                
                val masterSpectrum = FloatArray(config.numBins) { SweepTrace.EMPTY }
                // Average as it stood before this sweep, for the in-progress previews
                val previousAverage = averager.current()
                var lastPreviewMs = System.currentTimeMillis()
                val hzPerUiBin = spanHz.toDouble() / config.numBins
                
                var sweepFailed = false
                var sweepAborted = false

                for (hopIndex in 0 until numHops) {
                    val live = _state.value
                    if (!isActive || !live.isScanning ||
                        live.scanConfig.frequencyRange != range ||
                        live.scanConfig.numBins != config.numBins
                    ) {
                        sweepAborted = true
                        break
                    }

                    // Center is the midpoint of the usable block for this hop
                    val hopCenterHz = range.startHz + (hopIndex * usableBwHz) + (usableBwHz / 2)
                    val hopStartHz  = range.startHz + (hopIndex * usableBwHz)
                    val hopEndHz    = (hopStartHz + usableBwHz).coerceAtMost(range.endHz)
                    
                    val rawMagnitudes: FloatArray? = if (!_state.value.isDemoMode && sdrSource.isOpen) {
                        hwMutex.withLock {
                            if (!sdrSource.setFrequency(hopCenterHz)) {
                                null // tuner rejected the frequency or the dongle is gone
                            } else {
                                delay(10) // wait for PLL lock
                                sdrSource.flush()

                                val numReads = if (spanHz > 50_000_000L) 2 else 4
                                sdrSource.readSpectrum(1024, numReads)
                            }
                        }
                    } else {
                        // Demo mode
                        syntheticGenerator.generateSpectrum(
                            startFreqHz = hopStartHz,
                            endFreqHz   = hopEndHz,
                            numBins     = 1024,
                            noiseFloorDbm = (config.referenceLevel - config.dynamicRange).toDouble(),
                        ).magnitudes
                    }

                    if (rawMagnitudes == null) {
                        sweepFailed = true
                        break
                    }

                    // The SDR always captures ±sdrBwHz/2 around the center
                    val chunkStartHz  = hopCenterHz - (sdrBwHz / 2)
                    val chunkEndHz    = hopCenterHz + (sdrBwHz / 2)
                    val chunkHzPerBin = (chunkEndHz - chunkStartHz).toDouble() / rawMagnitudes.size

                    // FIXED: 2% edge trim (was 5% = 102 kHz loss per side)
                    // Only suppress the minimal roll-off region at the very edge
                    val edgeTrim = (rawMagnitudes.size * 0.02).toInt()
                    val dcCenter = rawMagnitudes.size / 2
                    val dcHalf   = (rawMagnitudes.size * 0.005).toInt().coerceAtLeast(3)
                    
                    for (i in edgeTrim until (rawMagnitudes.size - edgeTrim)) {
                        if (i in (dcCenter - dcHalf)..(dcCenter + dcHalf)) continue
                        
                        val binFreqHz = chunkStartHz + (i * chunkHzPerBin)
                        if (binFreqHz >= range.startHz && binFreqHz <= range.endHz) {
                            val uiBinIndex = ((binFreqHz - range.startHz) / hzPerUiBin).toInt()
                                .coerceIn(0, config.numBins - 1)
                            if (rawMagnitudes[i] > masterSpectrum[uiBinIndex]) {
                                masterSpectrum[uiBinIndex] = rawMagnitudes[i]
                            }
                        }
                    }

                    // Show this hop right away instead of waiting for the whole sweep.
                    // A full sweep over a wide range takes seconds on real hardware;
                    // the trace is redrawn as the sweep advances across the span.
                    val nowMs = System.currentTimeMillis()
                    if (hopIndex < numHops - 1 && nowMs - lastPreviewMs >= PREVIEW_INTERVAL_MS) {
                        lastPreviewMs = nowMs
                        val coveredBins = ((hopEndHz - range.startHz) / hzPerUiBin).toInt()
                        val preview = SweepTrace.preview(
                            raw             = masterSpectrum,
                            coveredBins     = coveredBins,
                            previousAverage = previousAverage,
                            alpha           = config.averagingFactor,
                            floorDbm        = config.referenceLevel - config.dynamicRange,
                        )
                        _state.update { cur ->
                            if (cur.scanConfig.frequencyRange != range || cur.scanConfig.numBins != config.numBins) {
                                cur
                            } else {
                                cur.copy(currentSpectrum = preview)
                            }
                        }
                    }
                } // end hop loop

                if (sweepFailed) {
                    // Try to auto-reconnect before falling to demo mode
                    val reconnected = tryReconnect()
                    if (!reconnected) {
                        _state.update {
                            it.copy(
                                hwError = "Dongle disconnected. Falling back to demo mode.",
                                isDemoMode = true,
                                connectedDeviceName = null,
                            )
                        }
                    }
                    // Whatever comes next is a different source (or a re-initialised tuner)
                    averager.reset()
                    waterfallBuffer.clear()
                    lastHotplugCheck = System.currentTimeMillis()
                    delay(FRAME_INTERVAL_MS)
                    continue
                }

                // A sweep that was interrupted (stop pressed, range or resolution changed)
                // is only partially filled. Publishing it would seed the average and the
                // max-hold trace with garbage that belongs to the previous settings.
                if (sweepAborted) continue
                
                // Fill the bins no hop wrote to (DC notches) and smooth the trace
                val smoothed = SweepTrace.finish(masterSpectrum)

                val averaged = averager.average(smoothed, config.averagingFactor)
                val maxHold = if (_state.value.scanConfig.maxHoldEnabled) averager.updateMaxHold(averaged) else null

                waterfallBuffer.add(0, averaged.copyOf())
                if (waterfallBuffer.size > MAX_WATERFALL_HISTORY) waterfallBuffer.removeAt(waterfallBuffer.lastIndex)

                peakFrameCounter++
                val detectedPeaks = if (peakFrameCounter >= 10) {
                    peakFrameCounter = 0
                    val frameForDetection = SpectrumData(
                        frequencyStartHz = range.startHz,
                        frequencyEndHz   = range.endHz,
                        magnitudes       = averaged,
                    )
                    peakDetector.detectPeaks(
                        frameForDetection,
                        minPeakDbm    = config.referenceLevel - config.dynamicRange + 10f,
                        minDistanceBins = config.numBins / 50,
                        maxPeaks      = 4,
                    )
                } else {
                    _state.value.autoDetectedPeaks
                }

                frameCount++
                val now = System.currentTimeMillis()
                val elapsed = now - fpsTimestamp
                val fps = if (elapsed >= 1000L) {
                    val v = (frameCount * 1000L / elapsed).toInt()
                    frameCount = 0
                    fpsTimestamp = now
                    v
                } else {
                    _state.value.framesPerSecond
                }

                val history = waterfallBuffer.toList()
                _state.update { cur ->
                    // The user may have changed range/resolution while this sweep was being
                    // post-processed; in that case the frame no longer matches the axes.
                    if (cur.scanConfig.frequencyRange != range || cur.scanConfig.numBins != config.numBins) {
                        cur
                    } else {
                        cur.copy(
                            currentSpectrum   = averaged,
                            maxHoldSpectrum   = if (cur.scanConfig.maxHoldEnabled) maxHold else null,
                            waterfallHistory  = history,
                            autoDetectedPeaks = detectedPeaks,
                            framesPerSecond   = fps,
                        )
                    }
                }

                if (config.scanMode == ScanMode.SINGLE) {
                    _state.update { it.copy(isScanning = false) }
                    break
                }

                // Pace the loop. Hardware sweeps take far longer than a frame, but demo
                // sweeps are instantaneous and would otherwise spin a CPU core and
                // re-render the UI hundreds of times per second.
                val sweepMs = System.currentTimeMillis() - sweepStartMs
                delay((FRAME_INTERVAL_MS - sweepMs).coerceAtLeast(MIN_SWEEP_GAP_MS))
            }
        }
    }

    fun stopScanning() {
        _state.update { it.copy(isScanning = false) }
        scanJob?.cancel()
        scanJob = null
    }

    /**
     * Attempts to reconnect the RTL-SDR dongle up to [maxAttempts] times.
     * Returns true if reconnection was successful, false if all attempts failed.
     */
    private suspend fun tryReconnect(maxAttempts: Int = 3): Boolean {
        repeat(maxAttempts) { attempt ->
            _state.update {
                it.copy(hwError = "Dongle disconnected. Reconnecting (${attempt + 1}/$maxAttempts)...")
            }
            hwMutex.withLock { sdrSource.close() }
            delay(500)
            // connectIfAvailable() clears hwError and leaves demo mode on success
            if (connectIfAvailable()) return true
            delay(1000)
        }
        // Make sure a half-dead handle is not kept around
        hwMutex.withLock { sdrSource.close() }
        return false
    }

    // NOTE: every mutation below goes through _state.update { }. The scan loop publishes
    // frames from a background thread, so a read-copy-assign on the state flow's value can
    // silently overwrite a change made in between (a new marker, a toggled option…).

    fun updateFrequencyRange(range: FrequencyRange) {
        _state.update {
            it.copy(
                scanConfig = it.scanConfig.copy(frequencyRange = range),
                currentSpectrum = null,
                maxHoldSpectrum = null,
                waterfallHistory = emptyList(),
                autoDetectedPeaks = emptyList(),
                markers = emptyList(),
            )
        }
        averager.reset()
    }

    fun updateGainMode(mode: GainMode) {
        _state.update { it.copy(scanConfig = it.scanConfig.copy(gainMode = mode)) }
        if (!_state.value.isDemoMode && sdrSource.isOpen) {
            scope.launch(Dispatchers.IO) {
                hwMutex.withLock {
                    sdrSource.setGain(mode, _state.value.scanConfig.manualGainDb)
                }
            }
        }
    }

    fun updateScanMode(mode: ScanMode) {
        _state.update { it.copy(scanConfig = it.scanConfig.copy(scanMode = mode)) }
    }

    fun updateNumBins(numBins: Int) {
        _state.update {
            it.copy(
                scanConfig = it.scanConfig.copy(numBins = numBins),
                currentSpectrum = null,
                maxHoldSpectrum = null,
                // Rows of another width cannot be shown on the same waterfall
                waterfallHistory = emptyList(),
                autoDetectedPeaks = emptyList(),
            )
        }
        averager.reset()
    }

    fun updateMarkerColor(markerId: Int, color: MarkerColor) {
        _state.update { cur ->
            cur.copy(markers = cur.markers.map { if (it.id == markerId) it.copy(color = color) else it })
        }
    }

    fun toggleMaxHold() {
        var enabled = false
        _state.update { cur ->
            enabled = !cur.scanConfig.maxHoldEnabled
            cur.copy(
                scanConfig = cur.scanConfig.copy(maxHoldEnabled = enabled),
                maxHoldSpectrum = if (enabled) cur.maxHoldSpectrum else null,
            )
        }
        // Start from a clean trace next time it is enabled instead of resurrecting
        // maxima recorded before it was switched off.
        if (!enabled) averager.resetMaxHold()
    }

    fun resetMaxHold() {
        averager.resetMaxHold()
        _state.update { it.copy(maxHoldSpectrum = null) }
    }

    fun updateActiveMarker(normalizedX: Float?) {
        _state.update { it.copy(activeMarkerX = normalizedX) }
    }

    fun addMarker(normalizedX: Float) {
        val config   = _state.value.scanConfig
        val range    = config.frequencyRange
        val freqHz   = range.startHz + ((range.endHz - range.startHz) * normalizedX.coerceIn(0f, 1f)).toLong()
        val spectrum = _state.value.currentSpectrum
        val ampDbm   = if (spectrum != null && spectrum.isNotEmpty()) {
            val bin = (normalizedX * (spectrum.size - 1)).toInt().coerceIn(0, spectrum.size - 1)
            spectrum[bin]
        } else {
            -100f
        }

        _state.update { cur ->
            // max+1 (not size+1): ids must stay unique after a marker was removed,
            // otherwise editing/removing one marker would also hit its twin.
            val markerIndex = (cur.markers.maxOfOrNull { it.id } ?: 0) + 1
            val color       = MarkerColor.entries[(markerIndex - 1) % MarkerColor.entries.size]
            cur.copy(
                markers = cur.markers + Marker(
                    id           = markerIndex,
                    frequencyHz  = freqHz.toDouble(),
                    amplitudeDbm = ampDbm,
                    label        = "M$markerIndex",
                    color        = color,
                ),
            )
        }
    }

    fun clearMarkers() {
        _state.update { it.copy(markers = emptyList(), activeMarkerX = null) }
    }

    fun updateMarkerLabel(markerId: Int, newLabel: String) {
        _state.update { cur ->
            cur.copy(markers = cur.markers.map { if (it.id == markerId) it.copy(label = newLabel) else it })
        }
    }

    fun removeMarker(markerId: Int) {
        _state.update { cur -> cur.copy(markers = cur.markers.filter { it.id != markerId }) }
    }

    fun addMarkerManual(freqHz: Long, label: String, color: MarkerColor? = null) {
        val config = _state.value.scanConfig
        val spectrum = _state.value.currentSpectrum
        
        // Estimate amplitude if it is within the current range
        val range = config.frequencyRange
        val ampDbm = if (spectrum != null && freqHz >= range.startHz && freqHz <= range.endHz) {
            val normalizedX = (freqHz - range.startHz).toFloat() / (range.endHz - range.startHz).toFloat()
            val bin = (normalizedX * (spectrum.size - 1)).toInt().coerceIn(0, spectrum.size - 1)
            spectrum[bin]
        } else {
            -100f
        }

        _state.update { cur ->
            val markerIndex = (cur.markers.maxOfOrNull { it.id } ?: 0) + 1
            val finalColor = color ?: MarkerColor.entries[(markerIndex - 1) % MarkerColor.entries.size]
            cur.copy(
                markers = cur.markers + Marker(
                    id = markerIndex,
                    frequencyHz = freqHz.toDouble(),
                    amplitudeDbm = ampDbm,
                    label = if (label.isNotBlank()) label else "M$markerIndex",
                    color = finalColor,
                )
            )
        }
    }

    fun dismissHwError() {
        _state.update { it.copy(hwError = null) }
    }

    /**
     * Exports the current spectrum (or Max Hold if active) to a WWB7-compatible CSV file.
     * Returns null on success, or an error message on failure.
     */
    fun exportWwb(outputFile: java.io.File): String? {
        val config   = _state.value.scanConfig
        val range    = config.frequencyRange
        val spectrum = _state.value.maxHoldSpectrum?.takeIf { config.maxHoldEnabled }
            ?: _state.value.currentSpectrum
            ?: return "No spectrum data available to export."

        val spectrumData = SpectrumData(
            frequencyStartHz = range.startHz,
            frequencyEndHz   = range.endHz,
            magnitudes       = spectrum,
        )
        return WwbExporter.export(spectrumData, outputFile)
    }

    /**
     * Stops the sweep and releases the dongle. Call once when the application exits:
     * closing the handle while a read is still in flight can crash inside librtlsdr,
     * so we wait (briefly) for the scan loop to finish first.
     */
    fun onCleared() {
        val job = scanJob
        stopScanning()
        runBlocking {
            withTimeoutOrNull(SHUTDOWN_TIMEOUT_MS) {
                job?.cancelAndJoin()
                hwMutex.withLock { sdrSource.close() }
            }
        }
        scope.cancel()
    }

    private companion object {
        const val FRAME_INTERVAL_MS     = 33L     // ~30 sweeps/s upper bound (demo mode)
        const val MIN_SWEEP_GAP_MS      = 5L
        const val PREVIEW_INTERVAL_MS   = 50L     // in-sweep redraws, at most ~20 per second
        const val HOTPLUG_POLL_MS       = 3_000L  // how often demo mode looks for a dongle
        const val MAX_WATERFALL_HISTORY = 100
        const val SHUTDOWN_TIMEOUT_MS   = 2_000L
    }
}
