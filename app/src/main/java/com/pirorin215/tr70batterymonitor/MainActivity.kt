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
import com.pirorin215.tr70batterymonitor.service.ScannedDevice
import com.pirorin215.tr70batterymonitor.viewmodel.MainViewModel
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var viewModel: MainViewModel

    private lateinit var tvStatus: TextView
    private lateinit var tvDeviceInfo: TextView
    private lateinit var tvServices: TextView
    private lateinit var tvLogs: TextView
    private lateinit var deviceCardContainer: LinearLayout
    private lateinit var tvNoDevices: TextView
    private lateinit var btnRescan: Button

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            viewModel.onPermissionsGranted()
            startBleScanService()
            viewModel.startScan()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupViewModel()
        checkPermissions()
    }

    private fun initViews() {
        tvStatus = findViewById(R.id.tv_status)
        tvDeviceInfo = findViewById(R.id.tv_device_info)
        tvServices = findViewById(R.id.tv_services)
        tvLogs = findViewById(R.id.tv_logs)
        deviceCardContainer = findViewById(R.id.device_card_container)
        tvNoDevices = findViewById(R.id.tv_no_devices)
        btnRescan = findViewById(R.id.btn_rescan)

        btnRescan.setOnClickListener {
            viewModel.startScan()
        }
    }

    private fun setupViewModel() {
        viewModel = ViewModelProvider(this)[MainViewModel::class.java]
        viewModel.init(this)

        lifecycleScope.launch {
            viewModel.connectionState.collect { state ->
                updateStatusUI(state)
            }
        }

        lifecycleScope.launch {
            viewModel.deviceInfo.collect { info ->
                tvDeviceInfo.text = info
            }
        }

        lifecycleScope.launch {
            viewModel.servicesInfo.collect { info ->
                tvServices.text = info
            }
        }

        lifecycleScope.launch {
            viewModel.logs.collect { logs ->
                tvLogs.text = logs
            }
        }

        lifecycleScope.launch {
            BleScanServiceManager.deviceFoundFlow.collect { scannedDevice: ScannedDevice ->
                viewModel.onDeviceFound(scannedDevice.device)
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
            text = device.getStatusDisplay()
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

        // 2行目: バッテリー + RSSI
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
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val tvBatteryLabel = TextView(this).apply {
            text = "🔋 "
            textSize = 16f
        }

        val tvBatteryValue = TextView(this).apply {
            text = device.getBatteryDisplay()
            textSize = 20f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(getBatteryColor(device.batteryLevel))
        }

        batteryContainer.addView(tvBatteryLabel)
        batteryContainer.addView(tvBatteryValue)

        // RSSI
        val rssiContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val tvRssiLabel = TextView(this).apply {
            text = "📶 "
            textSize = 16f
        }

        val tvRssiValue = TextView(this).apply {
            text = device.getRssiDisplay()
            textSize = 16f
            setTextColor(Color.parseColor("#757575"))
        }

        rssiContainer.addView(tvRssiLabel)
        rssiContainer.addView(tvRssiValue)

        row2.addView(batteryContainer)
        row2.addView(rssiContainer)

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
}
