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
        private const val SCAN_TIMEOUT_MS = 30000L
    }

    private lateinit var bluetoothManager: BluetoothManager
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var scanJob: Job? = null
    private var isScanningActive = false

    private val scanSettings = ScanSettings.Builder()
        .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
        .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
        .setMatchMode(ScanSettings.MATCH_MODE_STICKY)
        .setReportDelay(0L)
        .build()

    private val scanFilters: List<ScanFilter> = emptyList()

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "BleScanService onCreate")
        bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        createNotificationChannel()

        CoroutineScope(Dispatchers.IO).launch {
            BleScanServiceManager.restartScanFlow.collect {
                Log.d(TAG, "Received restart scan signal")
                startBleScan()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "BleScanService onStartCommand")
        startForeground(NOTIFICATION_ID, buildNotification().build())
        startBleScan()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "BleScanService onDestroy")
        stopBleScan()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("MissingPermission")
    private fun startBleScan() {
        if (bluetoothAdapter == null || !bluetoothAdapter!!.isEnabled) return
        isScanningActive = true
        runScanLoop()
    }

    private fun runScanLoop() {
        scanJob?.cancel()
        scanJob = CoroutineScope(Dispatchers.IO).launch {
            while (isScanningActive) {
                stopBleScanInternal()
                delay(500)
                startBleScanInternal()
                delay(SCAN_TIMEOUT_MS)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startBleScanInternal() {
        Log.d(TAG, "Starting BLE scan step...")
        try {
            bluetoothAdapter?.bluetoothLeScanner?.startScan(scanFilters, scanSettings, bleScanCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Start scan error: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopBleScanInternal() {
        Log.d(TAG, "Stopping BLE scan step...")
        try {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(bleScanCallback)
        } catch (e: Exception) {}
    }

    @SuppressLint("MissingPermission")
    private fun stopBleScan() {
        Log.d(TAG, "Stopping BLE scan completely")
        isScanningActive = false
        scanJob?.cancel()
        stopBleScanInternal()
    }

    private val bleScanCallback = @SuppressLint("MissingPermission") object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            super.onScanResult(callbackType, result)
            val deviceName = result.device.name ?: "(no name)"
            if (deviceName.contains("BSC200S", ignoreCase = true) ||
                deviceName.contains("Varia", ignoreCase = true) ||
                deviceName.contains("Garmin", ignoreCase = true) ||
                deviceName.contains("Radar", ignoreCase = true) ||
                deviceName.contains("TR70", ignoreCase = true) ||
                deviceName.contains("RTL510", ignoreCase = true) ||
                deviceName.contains("RTR510", ignoreCase = true)) {
                Log.d(TAG, "Target device found: $deviceName (${result.device.address}), RSSI: ${result.rssi}")
                CoroutineScope(Dispatchers.IO).launch {
                    BleScanServiceManager.emitDeviceFound(result.device, result.rssi)
                }
            }
        }
        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed: error code $errorCode")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "BLE Scan Service", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): NotificationCompat.Builder {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("TR70 Battery Monitor")
            .setContentText("BLEスキャン中...")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
    }
}
