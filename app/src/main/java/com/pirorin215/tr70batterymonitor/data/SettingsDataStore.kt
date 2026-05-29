package com.pirorin215.tr70batterymonitor.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * アプリ設定の永続化を管理するクラス
 * DataStoreを使用して設定を保存・読み込み
 */
class SettingsDataStore(private val context: Context) {

    companion object {
        private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "app_settings")

        // 設定キーの定義
        private val TR70_THRESHOLD = intPreferencesKey("tr70_threshold")
        private val BSC200S_THRESHOLD = intPreferencesKey("bsc200s_threshold")
        private val MONITORING_TIMER = intPreferencesKey("monitoring_timer")
        private val LOW_BATTERY_NOTIFICATION_ENABLED = booleanPreferencesKey("low_battery_notification_enabled")
        private val PARTIAL_DETECTION_NOTIFICATION_ENABLED = booleanPreferencesKey("partial_detection_notification_enabled")

        // デフォルト値
        const val DEFAULT_TR70_THRESHOLD = 20
        const val DEFAULT_BSC200S_THRESHOLD = 20
        const val DEFAULT_MONITORING_TIMER = 60
        const val DEFAULT_LOW_BATTERY_NOTIFICATION_ENABLED = true
        const val DEFAULT_PARTIAL_DETECTION_NOTIFICATION_ENABLED = true

        // 閾値・範囲
        const val MIN_THRESHOLD = 0
        const val MAX_THRESHOLD = 100
        const val MIN_MONITORING_TIMER = 10
        const val MAX_MONITORING_TIMER = 300
    }

    /**
     * TR70のローバッテリー閾値
     */
    val tr70Threshold: Flow<Int> = context.dataStore.data.map { preferences ->
        preferences[TR70_THRESHOLD] ?: DEFAULT_TR70_THRESHOLD
    }

    /**
     * BSC200Sのローバッテリー閾値
     */
    val bsc200sThreshold: Flow<Int> = context.dataStore.data.map { preferences ->
        preferences[BSC200S_THRESHOLD] ?: DEFAULT_BSC200S_THRESHOLD
    }

    /**
     * 片肺検知監視タイマー（秒）
     */
    val monitoringTimer: Flow<Int> = context.dataStore.data.map { preferences ->
        preferences[MONITORING_TIMER] ?: DEFAULT_MONITORING_TIMER
    }

    /**
     * ローバッテリー通知有効/無効
     */
    val lowBatteryNotificationEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[LOW_BATTERY_NOTIFICATION_ENABLED] ?: DEFAULT_LOW_BATTERY_NOTIFICATION_ENABLED
    }

    /**
     * 片肺検知通知有効/無効
     */
    val partialDetectionNotificationEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[PARTIAL_DETECTION_NOTIFICATION_ENABLED] ?: DEFAULT_PARTIAL_DETECTION_NOTIFICATION_ENABLED
    }

    /**
     * TR70閾値を設定
     */
    suspend fun setTr70Threshold(value: Int) {
        context.dataStore.edit { preferences ->
            preferences[TR70_THRESHOLD] = value.coerceIn(MIN_THRESHOLD, MAX_THRESHOLD)
        }
    }

    /**
     * BSC200S閾値を設定
     */
    suspend fun setBsc200sThreshold(value: Int) {
        context.dataStore.edit { preferences ->
            preferences[BSC200S_THRESHOLD] = value.coerceIn(MIN_THRESHOLD, MAX_THRESHOLD)
        }
    }

    /**
     * 監視タイマーを設定
     */
    suspend fun setMonitoringTimer(value: Int) {
        context.dataStore.edit { preferences ->
            preferences[MONITORING_TIMER] = value.coerceIn(MIN_MONITORING_TIMER, MAX_MONITORING_TIMER)
        }
    }

    /**
     * ローバッテリー通知の有効/無効を設定
     */
    suspend fun setLowBatteryNotificationEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[LOW_BATTERY_NOTIFICATION_ENABLED] = enabled
        }
    }

    /**
     * 片肺検知通知の有効/無効を設定
     */
    suspend fun setPartialDetectionNotificationEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PARTIAL_DETECTION_NOTIFICATION_ENABLED] = enabled
        }
    }

    /**
     * すべての設定をデフォルト値にリセット
     */
    suspend fun resetToDefaults() {
        context.dataStore.edit { preferences ->
            preferences[TR70_THRESHOLD] = DEFAULT_TR70_THRESHOLD
            preferences[BSC200S_THRESHOLD] = DEFAULT_BSC200S_THRESHOLD
            preferences[MONITORING_TIMER] = DEFAULT_MONITORING_TIMER
            preferences[LOW_BATTERY_NOTIFICATION_ENABLED] = DEFAULT_LOW_BATTERY_NOTIFICATION_ENABLED
            preferences[PARTIAL_DETECTION_NOTIFICATION_ENABLED] = DEFAULT_PARTIAL_DETECTION_NOTIFICATION_ENABLED
        }
    }
}