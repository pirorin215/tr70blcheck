package com.pirorin215.tr70batterymonitor.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * 設定関連の拡張関数とユーティリティ
 */

/**
 * すべての設定をまとめて取得するFlow
 */
fun SettingsManager.getAllSettings(): Flow<AppSettings> =
    combine(
        getTr70Threshold(),
        getBsc200sThreshold(),
        getMonitoringTimer(),
        getLowBatteryNotificationEnabled(),
        getPartialDetectionNotificationEnabled()
    ) { tr70, bsc200s, timer, lowBatNotify, partialNotify ->
        AppSettings(
            tr70Threshold = tr70,
            bsc200sThreshold = bsc200s,
            monitoringTimer = timer,
            lowBatteryNotificationEnabled = lowBatNotify,
            partialDetectionNotificationEnabled = partialNotify
        )
    }

/**
 * アプリ設定をまとめたデータクラス
 */
data class AppSettings(
    val tr70Threshold: Int = SettingsDataStore.DEFAULT_TR70_THRESHOLD,
    val bsc200sThreshold: Int = SettingsDataStore.DEFAULT_BSC200S_THRESHOLD,
    val monitoringTimer: Int = SettingsDataStore.DEFAULT_MONITORING_TIMER,
    val lowBatteryNotificationEnabled: Boolean = SettingsDataStore.DEFAULT_LOW_BATTERY_NOTIFICATION_ENABLED,
    val partialDetectionNotificationEnabled: Boolean = SettingsDataStore.DEFAULT_PARTIAL_DETECTION_NOTIFICATION_ENABLED
) {
    /**
     * デバイス名から閾値を取得
     */
    fun getThresholdForDevice(deviceName: String): Int? {
        return when {
            deviceName.contains("TR70", ignoreCase = true) -> tr70Threshold
            deviceName.contains("BSC200S", ignoreCase = true) -> bsc200sThreshold
            else -> null
        }
    }

    /**
     * 有効な通知設定を持っているか
     */
    fun hasAnyNotificationEnabled(): Boolean {
        return lowBatteryNotificationEnabled || partialDetectionNotificationEnabled
    }
}