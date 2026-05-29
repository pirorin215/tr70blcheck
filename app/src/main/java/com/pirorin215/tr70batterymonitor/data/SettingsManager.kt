package com.pirorin215.tr70batterymonitor.data

import android.content.Context
import kotlinx.coroutines.flow.Flow

/**
 * 設定データストアのマネージャークラス
 * アプリ全体から設定にアクセスするためのシングルトンインターフェース
 */
object SettingsManager {

    private lateinit var dataStore: SettingsDataStore

    /**
     * 初期化（Applicationクラスから呼び出す）
     */
    fun init(context: Context) {
        dataStore = SettingsDataStore(context.applicationContext)
    }

    /**
     * 設定データストアを取得
     */
    fun getDataStore(): SettingsDataStore {
        if (!::dataStore.isInitialized) {
            throw IllegalStateException("SettingsManager must be initialized before use. Call SettingsManager.init(context) in Application.onCreate()")
        }
        return dataStore
    }

    // 以下、便利アクセスメソッド

    /**
     * TR70閾値を取得
     */
    fun getTr70Threshold(): Flow<Int> = dataStore.tr70Threshold

    /**
     * BSC200S閾値を取得
     */
    fun getBsc200sThreshold(): Flow<Int> = dataStore.bsc200sThreshold

    /**
     * 監視タイマーを取得
     */
    fun getMonitoringTimer(): Flow<Int> = dataStore.monitoringTimer

    /**
     * ローバッテリー通知有効/無効を取得
     */
    fun getLowBatteryNotificationEnabled(): Flow<Boolean> = dataStore.lowBatteryNotificationEnabled

    /**
     * 片肺検知通知有効/無効を取得
     */
    fun getPartialDetectionNotificationEnabled(): Flow<Boolean> = dataStore.partialDetectionNotificationEnabled

    /**
     * TR70閾値を設定
     */
    suspend fun setTr70Threshold(value: Int) = dataStore.setTr70Threshold(value)

    /**
     * BSC200S閾値を設定
     */
    suspend fun setBsc200sThreshold(value: Int) = dataStore.setBsc200sThreshold(value)

    /**
     * 監視タイマーを設定
     */
    suspend fun setMonitoringTimer(value: Int) = dataStore.setMonitoringTimer(value)

    /**
     * ローバッテリー通知有効/無効を設定
     */
    suspend fun setLowBatteryNotificationEnabled(enabled: Boolean) = dataStore.setLowBatteryNotificationEnabled(enabled)

    /**
     * 片肺検知通知有効/無効を設定
     */
    suspend fun setPartialDetectionNotificationEnabled(enabled: Boolean) = dataStore.setPartialDetectionNotificationEnabled(enabled)

    /**
     * すべての設定をデフォルト値にリセット
     */
    suspend fun resetToDefaults() = dataStore.resetToDefaults()
}