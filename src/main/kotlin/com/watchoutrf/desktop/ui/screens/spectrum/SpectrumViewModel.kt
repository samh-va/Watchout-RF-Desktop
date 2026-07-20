package com.watchoutrf.desktop.ui.screens.spectrum

import com.watchoutrf.desktop.RtlSdr
import com.watchoutrf.desktop.data.dsp.PeakDetector
import com.watchoutrf.desktop.data.dsp.SpectrumAverager
import com.watchoutrf.desktop.data.dsp.SyntheticSignalGenerator
import com.watchoutrf.desktop.data.sdr.DesktopSdrSource
import com.watchoutrf.desktop.domain.model.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.max

class SpectrumViewModel {
    private val scope = CoroutineScope(Dispatchers.Default)
    private val _state = MutableStateFlow(SpectrumState())
    val state: StateFlow<SpectrumState> = _state.asStateFlow()

    private val sdrSource = DesktopSdrSource()
    private val syntheticGenerator = SyntheticSignalGenerator()
    private val averager = SpectrumAverager()
    private val peakDetector = PeakDetector()

    private var scanJob: Job? = null
    private var frameCount = 0
    private var fpsTimestamp = System.currentTimeMillis()

    init {
        // En macOS Desktop, si abrimos esta pantalla, intentamos conectar al dispositivo 0
        if (RtlSdr.getDeviceCount() > 0) {
            val deviceName = RtlSdr.getDeviceName(0) ?: "SDR Dongle"
            val error = sdrSource.open(0)
            if (error == null) {
                sdrSource.setGain(
                    _state.value.scanConfig.gainMode,
                    _state.value.scanConfig.manualGainDb,
                )
                sdrSource.startStream()
                
                _state.value = _state.value.copy(
                    isDemoMode = false,
                    connectedDeviceName = deviceName,
                    hwError = null
                )
            } else {
                _state.value = _state.value.copy(
                    isDemoMode = true,
                    hwError = "Error initializing $deviceName: $error"
                )
            }
        } else {
            _state.value = _state.value.copy(isDemoMode = true)
        }
    }

    fun startScanning() {
        if (scanJob?.isActive == true) return

        _state.value = _state.value.copy(isScanning = true, hwError = null)
        frameCount = 0
        fpsTimestamp = System.currentTimeMillis()

        scanJob = scope.launch(Dispatchers.IO) {
            val waterfallBuffer = mutableListOf<FloatArray>()
            waterfallBuffer.addAll(_state.value.waterfallHistory)
            var peakFrameCounter = 0

            while (isActive && _state.value.isScanning) {
                val config = _state.value.scanConfig
                val range  = config.frequencyRange
                val spanHz = range.spanHz
                
                val chunkHz = 2_000_000L
                val numHops = max(1, ceil(spanHz.toDouble() / chunkHz.toDouble()).toInt())
                
                val masterSpectrum = FloatArray(config.numBins) { -300f }
                val hzPerUiBin = spanHz.toDouble() / config.numBins
                
                var sweepFailed = false

                for (hopIndex in 0 until numHops) {
                    if (!isActive || !_state.value.isScanning) break
                    if (_state.value.scanConfig.frequencyRange != range) break
                    
                    val hopStartHz = range.startHz + (hopIndex * chunkHz)
                    var hopEndHz = hopStartHz + chunkHz
                    if (hopEndHz > range.endHz) hopEndHz = range.endHz
                    
                    val hopCenterHz = (hopStartHz + hopEndHz) / 2
                    
                    val rawMagnitudes: FloatArray? = if (!_state.value.isDemoMode && sdrSource.isOpen) {
                        sdrSource.setFrequency(hopCenterHz)
                        delay(10) // wait for PLL lock
                        sdrSource.flush()
                        
                        val numReads = if (spanHz > 50_000_000L) 2 else 4
                        sdrSource.readSpectrum(1024, numReads)
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
                    
                    val chunkStartHz = hopCenterHz - 1_024_000L
                    val chunkEndHz = hopCenterHz + 1_024_000L
                    val chunkHzPerBin = (chunkEndHz - chunkStartHz).toDouble() / rawMagnitudes.size
                    
                    val edgeTrim = (rawMagnitudes.size * 0.05).toInt()
                    val dcCenter = rawMagnitudes.size / 2
                    val dcHalf   = (rawMagnitudes.size * 0.005).toInt().coerceAtLeast(3)
                    
                    for (i in edgeTrim until (rawMagnitudes.size - edgeTrim)) {
                        if (i in (dcCenter - dcHalf)..(dcCenter + dcHalf)) continue
                        
                        val binFreqHz = chunkStartHz + (i * chunkHzPerBin)
                        if (binFreqHz >= hopStartHz && binFreqHz <= hopEndHz) {
                            if (binFreqHz >= range.startHz && binFreqHz <= range.endHz) {
                                val uiBinIndex = ((binFreqHz - range.startHz) / hzPerUiBin).toInt()
                                    .coerceIn(0, config.numBins - 1)
                                
                                if (rawMagnitudes[i] > masterSpectrum[uiBinIndex]) {
                                    masterSpectrum[uiBinIndex] = rawMagnitudes[i]
                                }
                            }
                        }
                    }
                } // end hop loop

                if (sweepFailed) {
                    _state.value = _state.value.copy(
                        hwError = "Read error from dongle. Falling back to demo.",
                        isDemoMode = true,
                    )
                    delay(33L)
                    continue
                }
                
                // Gap filling
                var gapStart = -1
                for (i in masterSpectrum.indices) {
                    if (masterSpectrum[i] <= -299.9f) {
                        if (gapStart < 0) gapStart = i
                    } else {
                        if (gapStart >= 0) {
                            val leftVal = if (gapStart > 0) masterSpectrum[gapStart - 1] else masterSpectrum[i]
                            val rightVal = masterSpectrum[i]
                            val gapLen = i - gapStart
                            for (g in 0 until gapLen) {
                                val t = (g + 1).toFloat() / (gapLen + 1).toFloat()
                                masterSpectrum[gapStart + g] = leftVal + (rightVal - leftVal) * t
                            }
                            gapStart = -1
                        }
                    }
                }
                if (gapStart >= 0) {
                    val leftVal = if (gapStart > 0) masterSpectrum[gapStart - 1] else -100f
                    for (g in gapStart until masterSpectrum.size) {
                        masterSpectrum[g] = leftVal
                    }
                }
                
                // Smoothing
                val smoothed = FloatArray(masterSpectrum.size)
                for (i in masterSpectrum.indices) {
                    val left  = if (i > 0) masterSpectrum[i - 1] else masterSpectrum[i]
                    val right = if (i < masterSpectrum.size - 1) masterSpectrum[i + 1] else masterSpectrum[i]
                    smoothed[i] = (left + masterSpectrum[i] + right) / 3f
                }

                val averaged = averager.average(smoothed, config.averagingFactor)
                val maxHold = if (config.maxHoldEnabled) averager.updateMaxHold(averaged) else _state.value.maxHoldSpectrum

                waterfallBuffer.add(0, averaged.copyOf())
                if (waterfallBuffer.size > 100) waterfallBuffer.removeAt(waterfallBuffer.lastIndex)

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

                _state.value = _state.value.copy(
                    currentSpectrum   = averaged,
                    maxHoldSpectrum   = maxHold,
                    waterfallHistory  = waterfallBuffer.toList(),
                    autoDetectedPeaks = detectedPeaks,
                    framesPerSecond   = fps,
                )

                if (config.scanMode == ScanMode.SINGLE) {
                    _state.value = _state.value.copy(isScanning = false)
                    break
                }

                delay(10)
            }
        }
    }

    fun stopScanning() {
        _state.value = _state.value.copy(isScanning = false)
        scanJob?.cancel()
        scanJob = null
    }

    fun updateFrequencyRange(range: FrequencyRange) {
        averager.reset()
        _state.value = _state.value.copy(
            scanConfig = _state.value.scanConfig.copy(frequencyRange = range),
            currentSpectrum = null,
            maxHoldSpectrum = null,
            waterfallHistory = emptyList(),
            autoDetectedPeaks = emptyList(),
            markers = emptyList(),
        )
    }

    fun updateGainMode(mode: GainMode) {
        _state.value = _state.value.copy(
            scanConfig = _state.value.scanConfig.copy(gainMode = mode),
        )
        if (!_state.value.isDemoMode && sdrSource.isOpen) {
            scope.launch(Dispatchers.IO) {
                sdrSource.setGain(mode, _state.value.scanConfig.manualGainDb)
            }
        }
    }

    fun updateScanMode(mode: ScanMode) {
        _state.value = _state.value.copy(
            scanConfig = _state.value.scanConfig.copy(scanMode = mode),
        )
    }

    fun updateNumBins(numBins: Int) {
        averager.reset()
        _state.value = _state.value.copy(
            scanConfig = _state.value.scanConfig.copy(numBins = numBins),
            currentSpectrum = null,
            maxHoldSpectrum = null,
        )
    }

    fun updateMarkerColor(markerId: Int, color: MarkerColor) {
        val existing = _state.value.markers.map {
            if (it.id == markerId) it.copy(color = color) else it
        }
        _state.value = _state.value.copy(markers = existing)
    }

    fun toggleMaxHold() {
        val config = _state.value.scanConfig
        val newEnabled = !config.maxHoldEnabled
        _state.value = _state.value.copy(
            scanConfig = config.copy(maxHoldEnabled = newEnabled),
            maxHoldSpectrum = if (!newEnabled) null else _state.value.maxHoldSpectrum,
        )
    }

    fun resetMaxHold() {
        averager.reset()
        _state.value = _state.value.copy(maxHoldSpectrum = null)
    }

    fun updateActiveMarker(normalizedX: Float?) {
        _state.value = _state.value.copy(activeMarkerX = normalizedX)
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

        val existing    = _state.value.markers
        val markerIndex = existing.size + 1
        val color       = MarkerColor.entries[(markerIndex - 1) % MarkerColor.entries.size]

        _state.value = _state.value.copy(
            markers = existing + Marker(
                id           = markerIndex,
                frequencyHz  = freqHz.toDouble(),
                amplitudeDbm = ampDbm,
                label        = "M$markerIndex",
                color        = color,
            ),
        )
    }

    fun clearMarkers() {
        _state.value = _state.value.copy(
            markers = emptyList(),
            activeMarkerX = null
        )
    }

    fun updateMarkerLabel(markerId: Int, newLabel: String) {
        val existing = _state.value.markers.map {
            if (it.id == markerId) it.copy(label = newLabel) else it
        }
        _state.value = _state.value.copy(markers = existing)
    }

    fun removeMarker(markerId: Int) {
        val existing = _state.value.markers.filter { it.id != markerId }
        _state.value = _state.value.copy(markers = existing)
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

        val existing = _state.value.markers
        val markerIndex = (existing.maxOfOrNull { it.id } ?: 0) + 1
        val finalColor = color ?: MarkerColor.entries[(markerIndex - 1) % MarkerColor.entries.size]

        _state.value = _state.value.copy(
            markers = existing + Marker(
                id = markerIndex,
                frequencyHz = freqHz.toDouble(),
                amplitudeDbm = ampDbm,
                label = if (label.isNotBlank()) label else "M$markerIndex",
                color = finalColor,
            )
        )
    }

    fun dismissHwError() {
        _state.value = _state.value.copy(hwError = null)
    }

    fun onCleared() {
        stopScanning()
        sdrSource.close()
    }
}
