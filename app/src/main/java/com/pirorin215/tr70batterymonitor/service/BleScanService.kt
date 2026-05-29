package com.pirorin215.tr70batterymonitor.service

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.pirorin215.tr70batterymonitor.MainActivity
import com.pirorin215.tr70batterymonitor.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class BleScanService : Service() {

    companion object {
        const val TAG = "BleScanService"
        const val CHANNEL_ID = "BleScanServiceChannel"
        const val NOTIFICATION_ID = 1
        private const val SCAN_TIMEOUT_MS = 30000L // 30秒のスキャンタイトアウト
    }

    private lateinit var bluetoothManager: BluetoothManager
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var scanJob: Job? = null

    // スキャン設定 - まず全デバイスをスキャン
    private val scanSettings = ScanSettings.Builder()
        .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
        .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
        .setMatchMode(ScanSettings.MATCH_MODE_STICKY)
        .setReportDelay(0L)
        .build()

    // フィルターなし - 全デバイスをスキャン
    private val scanFilters: List<ScanFilter> = emptyList()

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "BleScanService onCreate")
        bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        createNotificationChannel()

        // スキャン再開イベントを監視
        CoroutineScope(Dispatchers.IO).launch {
            BleScanServiceManager.restartScanFlow.collect {
                Log.d(TAG, "Received restart scan signal")
                startBleScan()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "BleScanService onStartCommand")

        // 権限チェック
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val hasBluetoothConnect = checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            val hasBluetoothScan = checkSelfPermission(android.Manifest.permission.BLUETOOTH_SCAN) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED

            if (!hasBluetoothConnect && !hasBluetoothScan) {
                Log.e(TAG, "Bluetooth permissions not granted")
                stopSelf()
                return START_NOT_STICKY
            }
        }

        startForeground(NOTIFICATION_ID, buildNotification().build())
        startBleScan()

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "BleScanService onDestroy")
        stopBleScan()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    @SuppressLint("MissingPermission")
    private fun startBleScan() {
        if (bluetoothAdapter == null || !bluetoothAdapter!!.isEnabled) {
            Log.e(TAG, "Bluetooth not available or not enabled")
            return
        }

        stopBleScan()

        Log.d(TAG, "Starting BLE scan...")
        try {
            bluetoothAdapter?.bluetoothLeScanner?.startScan(
                scanFilters, // フィルターなし
                scanSettings,
                bleScanCallback
            )

            // スキャンタイトアウトを設定
            scanJob = CoroutineScope(Dispatchers.IO).launch {
                delay(SCAN_TIMEOUT_MS)
                Log.d(TAG, "Scan timeout reached. Stopping scan.")
                stopBleScan()
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception: ${e.message}")
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Illegal argument: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopBleScan() {
        Log.d(TAG, "Stopping BLE scan")
        try {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(bleScanCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping scan: ${e.message}")
        }
    }

    private val bleScanCallback = @SuppressLint("MissingPermission") object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            super.onScanResult(callbackType, result)
            val deviceName = result.device.name ?: "(no name)"
            Log.d(TAG, "Found device: $deviceName (${result.device.address}), RSSI: ${result.rssi}")

            // TR70、BSC200Sなどのバッテリーデバイスを探す
            // 該当デバイス名を含むデバイスを対象
            if (deviceName.contains("BSC200S", ignoreCase = true) ||
                deviceName.contains("Varia", ignoreCase = true) ||
                deviceName.contains("Garmin", ignoreCase = true) ||
                deviceName.contains("Radar", ignoreCase = true) ||
                deviceName.contains("TR70", ignoreCase = true) ||
                deviceName.contains("RTL510", ignoreCase = true) ||
                deviceName.contains("RTR510", ignoreCase = true)) {

                Log.d(TAG, "Target device found: $deviceName")
                // スキャンを継続して複数デバイスを見つける
                CoroutineScope(Dispatchers.IO).launch {
                    BleScanServiceManager.emitDeviceFound(result.device)
                }
            }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            super.onBatchScanResults(results)
            Log.d(TAG, "Batch scan results: ${results.size} devices")
        }

        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            Log.e(TAG, "Scan failed: error code $errorCode")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "BLE Scan Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): NotificationCompat.Builder {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("TR70 Battery Monitor")
            .setContentText("BLEスキャン中...")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
    }
}
