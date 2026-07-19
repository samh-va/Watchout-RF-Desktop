package com.watchoutrf.desktop.ui.screens.home

import com.watchoutrf.desktop.RtlSdr
import com.watchoutrf.desktop.data.usb.UsbDeviceInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class HomeState(
    val isDeviceConnected: Boolean = false,
    val isRequestingPermission: Boolean = false,
    val isPermissionDenied: Boolean = false,
    val errorMessage: String? = null,
    val statusText: String = "Desconectado",
    val deviceInfo: UsbDeviceInfo? = null,
    val nativeVersion: String? = null
)

class HomeViewModel {
    private val scope = CoroutineScope(Dispatchers.Default)
    private val _uiState = MutableStateFlow(HomeState())
    val uiState: StateFlow<HomeState> = _uiState.asStateFlow()

    private var monitorJob: Job? = null

    init {
        // En macOS (usando librtlsdr vía JNA), podemos ver la versión
        _uiState.value = _uiState.value.copy(nativeVersion = "Desktop-JNA")
    }

    fun startUsbMonitoring() {
        monitorJob?.cancel()
        monitorJob = scope.launch {
            while (isActive) {
                checkDevice()
                delay(2000) // Poll every 2 seconds
            }
        }
    }

    fun stopUsbMonitoring() {
        monitorJob?.cancel()
        monitorJob = null
    }

    fun retryConnection() {
        checkDevice()
    }

    private fun checkDevice() {
        val count = RtlSdr.getDeviceCount()
        if (count > 0) {
            val name = RtlSdr.getDeviceName(0) ?: "SDR Dongle"
            
            // Creamos un UsbDeviceInfo simulado para la UI, usando los datos reales de librtlsdr
            val info = UsbDeviceInfo(
                vendorId = 0x0bda, // Realtek typical
                productId = 0x2838,
                manufacturerName = "Realtek",
                productName = name,
                deviceName = "usb-sdr",
                serialNumber = null
            )
            
            _uiState.value = _uiState.value.copy(
                isDeviceConnected = true,
                statusText = "Conectado",
                deviceInfo = info,
                errorMessage = null
            )
        } else {
            _uiState.value = _uiState.value.copy(
                isDeviceConnected = false,
                statusText = if (count == -1) "Error de Librería" else "Desconectado",
                deviceInfo = null,
                errorMessage = if (count == -1) "No se pudo cargar librtlsdr." else null
            )
        }
    }

    fun getNativeVersion(): String? {
        return _uiState.value.nativeVersion
    }
}
