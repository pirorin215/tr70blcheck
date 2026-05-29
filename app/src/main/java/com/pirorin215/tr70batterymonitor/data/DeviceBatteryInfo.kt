package com.pirorin215.tr70batterymonitor.data

data class DeviceBatteryInfo(
    val deviceAddress: String,
    val deviceName: String,
    val batteryLevel: Int?,
    val lastUpdate: Long = System.currentTimeMillis()
) {
    companion object {
        const val UNKNOWN_BATTERY = -1
    }

    fun getBatteryDisplay(): String {
        return if (batteryLevel != null && batteryLevel >= 0) {
            "$batteryLevel%"
        } else {
            "--"
        }
    }
}
