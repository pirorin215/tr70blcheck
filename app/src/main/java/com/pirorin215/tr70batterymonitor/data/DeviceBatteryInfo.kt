package com.pirorin215.tr70batterymonitor.data

data class DeviceBatteryInfo(
    val deviceAddress: String,
    val deviceName: String,
    val batteryLevel: Int?,
    val rssi: Int? = null,
    val status: DeviceStatus = DeviceStatus.SCANNING,
    val lastUpdate: Long = System.currentTimeMillis(),
    val lastDetectedTime: Long = System.currentTimeMillis(),
    val notifiedLowBattery: Boolean = false
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

    fun getRssiDisplay(): String {
        // 切断時はRSSIを表示しない
        if (status == DeviceStatus.DISCONNECTED || status == DeviceStatus.ERROR) {
            return "--"
        }
        return if (rssi != null) {
            "${rssi}dBm"
        } else {
            "--"
        }
    }

    fun getStatusDisplay(): String {
        return when (status) {
            DeviceStatus.SCANNING -> "スキャン中"
            DeviceStatus.FOUND -> "発見"
            DeviceStatus.CONNECTING -> "接続中"
            DeviceStatus.CONNECTED -> "接続済み"
            DeviceStatus.READING -> "読取中"
            DeviceStatus.COMPLETED -> "取得済み"
            DeviceStatus.DISCONNECTED -> "切断"
            DeviceStatus.ERROR -> "エラー"
        }
    }

    /**
     * RSSIと接続状態を統合した表示
     * 接続中: "接続中 (-60dBm)"
     * 切断: "切断"
     */
    fun getUnifiedStatusDisplay(): String {
        return when (status) {
            DeviceStatus.DISCONNECTED, DeviceStatus.ERROR -> "切断"
            DeviceStatus.SCANNING -> "スキャン中"
            DeviceStatus.FOUND -> "発見"
            DeviceStatus.CONNECTING -> "接続中"
            DeviceStatus.READING -> "読取中"
            else -> {
                // 接続完了状態でRSSIがある場合は統合表示
                if (rssi != null) {
                    "接続中 (${rssi}dBm)"
                } else {
                    "接続中"
                }
            }
        }
    }
}

enum class DeviceStatus {
    SCANNING,
    FOUND,
    CONNECTING,
    CONNECTED,
    READING,
    COMPLETED,
    DISCONNECTED,
    ERROR
}
