package com.pirorin215.tr70batterymonitor

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.pirorin215.tr70batterymonitor.data.SettingsManager
import kotlinx.coroutines.launch

/**
 * 設定画面アクティビティ
 * バッテリー閾値と通知設定を管理
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var seekbarTr70Threshold: SeekBar
    private lateinit var tvTr70ThresholdValue: TextView
    private lateinit var seekbarBsc200sThreshold: SeekBar
    private lateinit var tvBsc200sThresholdValue: TextView
    private lateinit var switchLowBatteryNotification: Switch
    private lateinit var tvNotificationPermissionStatus: TextView
    private lateinit var btnOpenNotificationSettings: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        initViews()
        setupListeners()
        loadSettings()
        updateNotificationPermissionStatus()
    }

    private fun initViews() {
        seekbarTr70Threshold = findViewById(R.id.seekbar_tr70_threshold)
        tvTr70ThresholdValue = findViewById(R.id.tv_tr70_threshold_value)
        seekbarBsc200sThreshold = findViewById(R.id.seekbar_bsc200s_threshold)
        tvBsc200sThresholdValue = findViewById(R.id.tv_bsc200s_threshold_value)
        switchLowBatteryNotification = findViewById(R.id.switch_low_battery_notification)
        tvNotificationPermissionStatus = findViewById(R.id.tv_notification_permission_status)
        btnOpenNotificationSettings = findViewById(R.id.btn_open_notification_settings)
    }

    private fun setupListeners() {
        // TR70閾値SeekBar
        seekbarTr70Threshold.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                tvTr70ThresholdValue.text = "${progress}%"
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                saveTr70Threshold()
            }
        })

        // BSC200S閾値SeekBar
        seekbarBsc200sThreshold.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                tvBsc200sThresholdValue.text = "${progress}%"
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                saveBsc200sThreshold()
            }
        })

        // ローバッテリー通知Switch
        switchLowBatteryNotification.setOnCheckedChangeListener { _, _ ->
            saveLowBatteryNotificationEnabled()
        }

        // 通知設定を開くボタン
        btnOpenNotificationSettings.setOnClickListener {
            openNotificationSettings()
        }
    }

    private fun loadSettings() {
        lifecycleScope.launch {
            // TR70閾値
            SettingsManager.getTr70Threshold().collect { threshold ->
                seekbarTr70Threshold.progress = threshold
                tvTr70ThresholdValue.text = "${threshold}%"
            }
        }

        lifecycleScope.launch {
            // BSC200S閾値
            SettingsManager.getBsc200sThreshold().collect { threshold ->
                seekbarBsc200sThreshold.progress = threshold
                tvBsc200sThresholdValue.text = "${threshold}%"
            }
        }

        lifecycleScope.launch {
            // ローバッテリー通知
            SettingsManager.getLowBatteryNotificationEnabled().collect { enabled ->
                switchLowBatteryNotification.isChecked = enabled
            }
        }
    }

    private fun saveTr70Threshold() {
        lifecycleScope.launch {
            SettingsManager.setTr70Threshold(seekbarTr70Threshold.progress)
        }
    }

    private fun saveBsc200sThreshold() {
        lifecycleScope.launch {
            SettingsManager.setBsc200sThreshold(seekbarBsc200sThreshold.progress)
        }
    }

    private fun saveLowBatteryNotificationEnabled() {
        lifecycleScope.launch {
            SettingsManager.setLowBatteryNotificationEnabled(switchLowBatteryNotification.isChecked)
        }
    }

    /**
     * 通知権限のステータスを更新
     */
    private fun updateNotificationPermissionStatus() {
        val hasPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            true // Android 13未満は権限不要
        }

        if (hasPermission) {
            tvNotificationPermissionStatus.text = "✅ 通知権限が有効です"
            tvNotificationPermissionStatus.setTextColor(getColor(android.R.color.holo_green_light))
            btnOpenNotificationSettings.visibility = Button.GONE
        } else {
            tvNotificationPermissionStatus.text = "❌ 通知権限がありません\n通知を受けるには権限が必要です"
            tvNotificationPermissionStatus.setTextColor(getColor(android.R.color.holo_red_light))
            btnOpenNotificationSettings.visibility = Button.VISIBLE
        }
    }

    /**
     * 通知設定画面を開く
     */
    private fun openNotificationSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        }
    }

    override fun onResume() {
        super.onResume()
        // 設定画面から戻ったときに権限ステータスを更新
        updateNotificationPermissionStatus()
    }
}