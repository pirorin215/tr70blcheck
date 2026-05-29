package com.pirorin215.tr70batterymonitor

import android.app.Application
import com.pirorin215.tr70batterymonitor.data.SettingsManager

/**
 * アプリケーション全体の設定初期化を管理するApplicationクラス
 */
class BatteryMonitorApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // 設定マネージャーを初期化
        SettingsManager.init(this)
    }
}