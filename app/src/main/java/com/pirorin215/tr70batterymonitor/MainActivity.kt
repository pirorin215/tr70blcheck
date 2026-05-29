package com.pirorin215.tr70batterymonitor

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.pirorin215.tr70batterymonitor.data.DeviceBatteryInfo
import com.pirorin215.tr70batterymonitor.data.DeviceStatus
import com.pirorin215.tr70batterymonitor.service.BleScanService
import com.pirorin215.tr70batterymonitor.service.BleScanServiceManager
import com.pirorin215.tr70batterymonitor.viewmodel.MainViewModel
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var viewModel: MainViewModel

    private lateinit var tvStatus: TextView
    private lateinit var tvLogs: TextView
    private lateinit var deviceCardContainer: LinearLayout
    private lateinit var tvNoDevices: TextView
    private lateinit var btnRescan: Button
    private lateinit var btnSettings: Button

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            startBleScanService()
            viewModel.startScan()
        } else {
            // 一部権限が拒否された場合のチェック
            checkNotificationPermissionAfterRequest()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 通知チャンネルの作成
        NotificationHelper.createNotificationChannels(this)

        initViews()
        setupViewModel()
        checkPermissions()

        // 通知権限のチェックと催促
        checkAndRequestNotificationPermission()
    }

    private fun initViews() {
        tvStatus = findViewById(R.id.tv_status)
        tvLogs = findViewById(R.id.tv_logs)
        deviceCardContainer = findViewById(R.id.device_card_container)
        tvNoDevices = findViewById(R.id.tv_no_devices)
        btnRescan = findViewById(R.id.btn_rescan)
        btnSettings = findViewById(R.id.btn_settings)

        btnRescan.setOnClickListener {
            viewModel.startScan()
        }

        btnSettings.setOnClickListener {
            val intent = Intent(this, SettingsActivity::class.java)
            startActivity(intent)
        }
    }

    private fun setupViewModel() {
        viewModel = ViewModelProvider(this)[MainViewModel::class.java]
        viewModel.init(this)

        lifecycleScope.launch {
            viewModel.logs.collect { logs ->
                tvLogs.text = logs
            }
        }

        lifecycleScope.launch {
            viewModel.deviceList.collect { devices ->
                updateDeviceCards(devices)
            }
        }
    }

    private fun updateDeviceCards(devices: List<DeviceBatteryInfo>) {
        deviceCardContainer.removeAllViews()

        if (devices.isEmpty()) {
            tvNoDevices.visibility = View.VISIBLE
            deviceCardContainer.visibility = View.GONE
            return
        }

        tvNoDevices.visibility = View.GONE
        deviceCardContainer.visibility = View.VISIBLE

        devices.forEach { device ->
            val cardView = createDeviceCard(device)
            deviceCardContainer.addView(cardView)
        }
    }

    private fun createDeviceCard(device: DeviceBatteryInfo): View {
        val cardLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(12), dpToPx(12), dpToPx(12), dpToPx(12))
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.setMargins(0, 0, 0, dpToPx(8))
            layoutParams = params
            setBackgroundResource(android.R.drawable.dialog_holo_light_frame)
        }

        // 1行目: デバイス名 + ステータスバッジ
        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            layoutParams = params
        }

        val tvName = TextView(this).apply {
            text = device.deviceName
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor("#212121"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val tvStatusBadge = TextView(this).apply {
            text = device.getUnifiedStatusDisplay()
            textSize = 12f
            setPadding(dpToPx(8), dpToPx(4), dpToPx(8), dpToPx(4))
            setTextColor(getStatusTextColor(device.status))
            setBackgroundColor(getStatusBackgroundColor(device.status))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        row1.addView(tvName)
        row1.addView(tvStatusBadge)

        // 2行目: バッテリーのみ（RSSIはステータスに統合）
        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.setMargins(0, dpToPx(8), 0, 0)
            layoutParams = params
        }

        // バッテリー
        val batteryContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        val tvBatteryLabel = TextView(this).apply {
            text = "🔋 "
            textSize = 16f
        }

        // プログレスバー（バッテリーレベル表示）
        val progressBar = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = LinearLayout.LayoutParams(0, dpToPx(16), 1f)
            max = 100
            progress = device.batteryLevel ?: 0

            // 色設定
            val batteryColor = when {
                device.batteryLevel == null -> Color.parseColor("#9E9E9E")
                device.batteryLevel > 50 -> Color.parseColor("#4CAF50")
                device.batteryLevel > 20 -> Color.parseColor("#FF9800")
                else -> Color.parseColor("#F44336")
            }
            progressTintList = android.content.res.ColorStateList.valueOf(batteryColor)
        }

        val tvBatteryValue = TextView(this).apply {
            text = device.getBatteryDisplay()
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(getBatteryColor(device.batteryLevel))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(dpToPx(8), 0, 0, 0)
            }
        }

        batteryContainer.addView(tvBatteryLabel)
        batteryContainer.addView(progressBar)
        batteryContainer.addView(tvBatteryValue)

        row2.addView(batteryContainer)

        // 3行目: アドレス
        val tvAddress = TextView(this).apply {
            text = device.deviceAddress
            textSize = 10f
            setTextColor(Color.parseColor("#9E9E9E"))
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.setMargins(0, dpToPx(4), 0, 0)
            layoutParams = params
        }

        cardLayout.addView(row1)
        cardLayout.addView(row2)
        cardLayout.addView(tvAddress)

        return cardLayout
    }

    private fun getStatusTextColor(status: DeviceStatus): Int {
        return when (status) {
            DeviceStatus.COMPLETED -> Color.parseColor("#2E7D32")
            DeviceStatus.CONNECTED,
            DeviceStatus.READING -> Color.parseColor("#1565C0")
            DeviceStatus.CONNECTING,
            DeviceStatus.FOUND -> Color.parseColor("#E65100")
            DeviceStatus.SCANNING -> Color.parseColor("#616161")
            DeviceStatus.DISCONNECTED -> Color.parseColor("#757575")
            DeviceStatus.ERROR -> Color.parseColor("#C62828")
        }
    }

    private fun getStatusBackgroundColor(status: DeviceStatus): Int {
        return when (status) {
            DeviceStatus.COMPLETED -> Color.parseColor("#E8F5E9")
            DeviceStatus.CONNECTED,
            DeviceStatus.READING -> Color.parseColor("#E3F2FD")
            DeviceStatus.CONNECTING,
            DeviceStatus.FOUND -> Color.parseColor("#FFF3E0")
            DeviceStatus.SCANNING -> Color.parseColor("#F5F5F5")
            DeviceStatus.DISCONNECTED -> Color.parseColor("#EEEEEE")
            DeviceStatus.ERROR -> Color.parseColor("#FFEBEE")
        }
    }

    private fun getBatteryColor(level: Int?): Int {
        if (level == null) return Color.parseColor("#9E9E9E")
        return when {
            level > 50 -> Color.parseColor("#4CAF50")
            level > 20 -> Color.parseColor("#FF9800")
            else -> Color.parseColor("#F44336")
        }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    private fun updateStatusUI(state: String) {
        tvStatus.text = "状態: $state"
    }

    private fun checkPermissions() {
        val permissions = mutableListOf<String>()

        // Bluetooth permissions for Android 12+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        } else {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        // Notification permission for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            requestPermissionLauncher.launch(missingPermissions.toTypedArray())
        } else {
            viewModel.onPermissionsGranted()
            startBleScanService()
            viewModel.startScan()
        }
    }

    private fun startBleScanService() {
        val serviceIntent = Intent(this, BleScanService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    /**
     * 通知権限が付与されているかチェック
     */
    private fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true // Android 13未満は権限不要
        }
    }

    /**
     * 権限リクエスト後に通知権限をチェックし、なければダイアログ表示
     */
    private fun checkNotificationPermissionAfterRequest() {
        if (!hasNotificationPermission()) {
            showNotificationPermissionDialog()
        } else {
            // 通知権限がある場合、BLEスキャン開始
            startBleScanService()
            viewModel.startScan()
        }
    }

    /**
     * 通知権限催促ダイアログを表示
     */
    private fun showNotificationPermissionDialog() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("通知権限が必要です")
            .setMessage("低バッテリー通知を受け取るために、通知権限を有効にしてください。\n\n「設定」から通知権限を有効にできます。")
            .setPositiveButton("設定に移動") { _, _ ->
                openNotificationSettings()
            }
            .setNegativeButton("キャンセル") { dialog, _ ->
                dialog.dismiss()
                // 通知権限なしでもBLEスキャン開始
                startBleScanService()
                viewModel.startScan()
            }
            .setCancelable(false)
            .show()
    }

    /**
     * 通知設定画面を開く
     */
    private fun openNotificationSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = android.net.Uri.parse("package:$packageName")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        }
    }

    /**
     * 通知権限をチェックし、必要に応じてリクエストまたはダイアログを表示
     */
    private fun checkAndRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED) {

                // ユーザーが権限を永続的に拒否したかチェック
                if (shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
                    // 権限の説明が必要な場合
                    showNotificationRationaleDialog()
                } else {
                    // 初回リクエスト
                    // ここでは何もしない（checkPermissionsで処理済み）
                }
            }
        }
    }

    /**
     * 通知権限の説明ダイアログを表示
     */
    private fun showNotificationRationaleDialog() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("通知権限について")
            .setMessage("低バッテリー通知を受け取るために、通知権限が必要です。\n\nデバイスのバッテリー残量が閾値を下回った際に通知でお知らせします。")
            .setPositiveButton("許可する") { _, _ ->
                // 権限リクエスト
                requestNotificationPermission()
            }
            .setNegativeButton("キャンセル") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    /**
     * 通知権限をリクエスト
     */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
        }
    }
}
