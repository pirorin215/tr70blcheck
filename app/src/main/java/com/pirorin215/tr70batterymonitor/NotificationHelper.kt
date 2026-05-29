package com.pirorin215.tr70batterymonitor

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.pirorin215.tr70batterymonitor.data.SettingsDataStore

/**
 * 通知関連のユーティリティクラス
 * 通知チャンネルの作成と通知発行を管理
 */
object NotificationHelper {

    // 通知チャンネルID
    const val CHANNEL_ID_LOW_BATTERY = "low_battery"
    const val CHANNEL_ID_SCAN_STATUS = "scan_status"

    // 通知ID
    private const val NOTIFICATION_ID_LOW_BATTERY = 1001
    private const val NOTIFICATION_ID_SCAN_STATUS = 1002

    /**
     * 通知チャンネルを作成する
     * Android 8.0（API 26）以上でのみ必要
     */
    fun createNotificationChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // ローバッテリー通知チャンネル
            val lowBatteryChannel = NotificationChannel(
                CHANNEL_ID_LOW_BATTERY,
                "低バッテリー警告",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "デバイスのバッテリー残量が閾値を下回った際に通知します"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 400, 200, 400)
            }

            // スキャン状態通知チャンネル
            val scanStatusChannel = NotificationChannel(
                CHANNEL_ID_SCAN_STATUS,
                "スキャン状態",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "BLEスキャンの状態に関する通知"
                setShowBadge(false)
            }

            // チャンネルの登録
            notificationManager.createNotificationChannel(lowBatteryChannel)
            notificationManager.createNotificationChannel(scanStatusChannel)
        }
    }

    /**
     * ローバッテリー通知を発行する
     *
     * @param context コンテキスト
     * @param deviceName デバイス名
     * @param batteryLevel バッテリーレベル（%）
     * @param threshold 閾値（%）
     */
    fun showLowBatteryNotification(
        context: Context,
        deviceName: String,
        batteryLevel: Int,
        threshold: Int
    ) {
        // 通知タップ時のインテント
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // 通知ビルダー
        val notification = NotificationCompat.Builder(context, CHANNEL_ID_LOW_BATTERY)
            .setSmallIcon(R.drawable.ic_notification_battery)
            .setContentTitle("⚠️ 低バッテリー警告")
            .setContentText("$deviceName のバッテリー残量が ${batteryLevel}% です（閾値: ${threshold}%）")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("$deviceName のバッテリー残量が ${batteryLevel}% です。\n閾値: ${threshold}%")
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setVibrate(longArrayOf(0, 400, 200, 400))
            .build()

        // 通知発行
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID_LOW_BATTERY, notification)
        } catch (e: SecurityException) {
            // 通知権限がない場合は何もしない
            // Android 13+でPOST_NOTIFICATIONS権限が必要
        }
    }

    /**
     * スキャン状態通知を発行する
     *
     * @param context コンテキスト
     * @param message 通知メッセージ
     */
    fun showScanStatusNotification(context: Context, message: String) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID_SCAN_STATUS)
            .setSmallIcon(R.drawable.ic_notification_scan)
            .setContentTitle("BLEスキャン")
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID_SCAN_STATUS, notification)
        } catch (e: SecurityException) {
            // 通知権限がない場合は何もしない
        }
    }
}