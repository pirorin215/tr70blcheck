package com.pirorin215.tr70batterymonitor

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.pirorin215.tr70batterymonitor.data.DeviceBatteryInfo
import com.pirorin215.tr70batterymonitor.service.BleScanService
import com.pirorin215.tr70batterymonitor.service.BleScanServiceManager
import com.pirorin215.tr70batterymonitor.viewmodel.MainViewModel
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var viewModel: MainViewModel

    private lateinit var tvStatus: TextView
    private lateinit var tvDeviceInfo: TextView
    private lateinit var tvServices: TextView
    private lateinit var tvBattery: TextView
    private lateinit var tvDeviceList: TextView
    private lateinit var tvLogs: TextView

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            viewModel.onPermissionsGranted()
            startBleScanService()
            viewModel.startScan() // 自動スキャン開始
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
        tvBattery = findViewById(R.id.tv_battery)
        tvDeviceList = findViewById(R.id.tv_device_list)
        tvLogs = findViewById(R.id.tv_logs)
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
            viewModel.batteryLevel.collect { level ->
                tvBattery.text = if (level != null) "バッテリー: $level%" else "バッテリー: --"
            }
        }

        lifecycleScope.launch {
            viewModel.logs.collect { logs ->
                tvLogs.text = logs
            }
        }

        lifecycleScope.launch {
            BleScanServiceManager.deviceFoundFlow.collect { device ->
                viewModel.onDeviceFound(device)
            }
        }

        lifecycleScope.launch {
            viewModel.deviceList.collect { devices ->
                updateDeviceList(devices)
            }
        }
    }

    private fun updateDeviceList(devices: List<DeviceBatteryInfo>) {
        if (devices.isEmpty()) {
            tvDeviceList.text = "まだスキャンしていません"
        } else {
            val sb = StringBuilder()
            devices.forEach { device ->
                sb.append("${device.deviceName}: ${device.getBatteryDisplay()}\n")
            }
            tvDeviceList.text = sb.toString()
        }
    }

    private fun updateStatusUI(state: String) {
        tvStatus.text = "状態: $state"
        // ボタン制御は不要（自動監視）
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
            viewModel.startScan() // 自動スキャン開始
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
