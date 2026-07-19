package com.watchoutrf.desktop.data.usb

data class UsbDeviceInfo(
    val vendorId: Int,
    val productId: Int,
    val manufacturerName: String?,
    val productName: String?,
    val deviceName: String,
    val serialNumber: String?
) {
    val displayName: String
        get() = productName ?: "Unknown Device"

    val frequencyRange: String
        get() = "24 \u2013 1766 MHz"
}
