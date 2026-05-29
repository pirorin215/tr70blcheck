package com.pirorin215.tr70batterymonitor.viewmodel

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pirorin215.tr70batterymonitor.NotificationHelper
import com.pirorin215.tr70batterymonitor.data.BleEvent
import com.pirorin215.tr70batterymonitor.data.BleRepository
import com.pirorin215.tr70batterymonitor.data.ConnectionState
import com.pirorin215.tr70batterymonitor.data.DeviceBatteryInfo
import com.pirorin215.tr70batterymonitor.data.DeviceStatus
import com.pirorin215.tr70batterymonitor.data.SettingsDataStore
import com.pirorin215.tr70batterymonitor.data.getAllSettings
import com.pirorin215.tr70batterymonitor.service.BleScanServiceManager
import com.pirorin215.tr70batterymonitor.service.ScannedDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

@SuppressLint("MissingPermission")
class MainViewModel : ViewModel() {

    companion object {
        const val TAG = "MainViewModel"
        private const val DEVICE_TIMEOUT_MS = 20000L

        private val TARGET_DEVICES = listOf("TR70", "BSC200S", "Varia", "Garmin", "Radar", "RTL510", "RTR510")
        private const val SCAN_CHECK_INTERVAL_MS = 60000L
        private const val DEVICE_DISCONNECT_TIMEOUT_MS = 120000L
    }

    private lateinit var repository: BleRepository
    private lateinit var bluetoothAdapter: BluetoothAdapter

    private val deviceBatteryMap = ConcurrentHashMap<String, DeviceBatteryInfo>()
    private val deviceTimeoutJobs = ConcurrentHashMap<String, Job>()
    private var scanCheckJob: Job? = null

    private val _logs = MutableStateFlow("ログ:\n")
    val logs: StateFlow<String> = _logs.asStateFlow()

    private val _deviceList = MutableStateFlow<List<DeviceBatteryInfo>>(emptyList())
    val deviceList: StateFlow<List<DeviceBatteryInfo>> = _deviceList.asStateFlow()

    private var allLogs = mutableListOf<String>()

    fun init(context: Context) {
        repository = BleRepository(context)
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        viewModelScope.launch {
            repository.logs.collect { logs ->
                allLogs = logs.toMutableList()
                _logs.value = "ログ:\n" + allLogs.takeLast(50).joinToString("\n")
            }
        }

        viewModelScope.launch {
            repository.events.collect { event -> handleBleEvent(event) }
        }

        viewModelScope.launch {
            BleScanServiceManager.deviceFoundFlow.collect { scannedDevice ->
                onScannedDeviceFound(scannedDevice)
            }
        }
        
        startScanCheck()
        checkBondedDevices()
    }

    private fun handleBleEvent(event: BleEvent) {
        when (event) {
            is BleEvent.Connected -> {
                val name = event.device.name ?: "Unknown"
                appendLog("接続成功 ($name)")
                cancelDeviceTimeout(event.device.address)
                updateDeviceInfo(event.device.address) { it.copy(status = DeviceStatus.CONNECTED) }
            }
            is BleEvent.BatteryLevelChanged -> {
                appendLog("バッテリー更新 (${event.address}): ${event.level}%")
                cancelDeviceTimeout(event.address)

                val existingInfo = deviceBatteryMap[event.address]
                val deviceName = existingInfo?.deviceName ?: "Unknown"

                updateDeviceInfo(event.address) {
                    it.copy(
                        batteryLevel = event.level,
                        status = DeviceStatus.CONNECTED,
                        lastUpdate = System.currentTimeMillis()
                    )
                }

                // ローバッテリー通知チェック
                checkLowBatteryNotification(event.address, deviceName, event.level)
            }
            is BleEvent.Disconnected -> {
                appendLog("切断検知 (${event.address})")
                cancelDeviceTimeout(event.address)
                // 切断時にRSSIをクリア
                updateDeviceInfo(event.address) {
                    it.copy(status = DeviceStatus.DISCONNECTED, rssi = null)
                }
            }
            is BleEvent.RssiUpdated -> {
                updateDeviceInfo(event.address) { it.copy(rssi = event.rssi) }
            }
            is BleEvent.Ready -> {
                appendLog("デバイス準備完了 (${event.address})")
                startDeviceTimeout(event.address)
            }
            is BleEvent.Error -> {
                appendLog("エラー (${event.address ?: "unknown"}): ${event.message}")
                event.address?.let { cancelDeviceTimeout(it) }
            }
            is BleEvent.ServicesDiscovered -> {
                appendLog("Service発見完了 (${event.address})")
            }
            is BleEvent.CharacteristicRead -> {}
        }
    }

    private fun updateDeviceInfo(address: String, update: (DeviceBatteryInfo) -> DeviceBatteryInfo) {
        val info = deviceBatteryMap[address] ?: return
        deviceBatteryMap[address] = update(info)
        _deviceList.value = deviceBatteryMap.values.toList()
    }

    private fun onScannedDeviceFound(scannedDevice: ScannedDevice) {
        val device = scannedDevice.device
        val address = device.address
        val deviceName = device.name ?: "(no name)"

        if (!TARGET_DEVICES.any { deviceName.contains(it, ignoreCase = true) }) return

        val existing = deviceBatteryMap[address]
        if (existing == null) {
            appendLog("ターゲットデバイス初発見: $deviceName ($address)")
            deviceBatteryMap[address] = DeviceBatteryInfo(
                deviceAddress = address,
                deviceName = deviceName,
                batteryLevel = null,
                rssi = scannedDevice.rssi,
                status = DeviceStatus.FOUND,
                lastDetectedTime = System.currentTimeMillis()
            )
            _deviceList.value = deviceBatteryMap.values.toList()
            repository.connect(device)
            updateDeviceInfo(address) { it.copy(status = DeviceStatus.CONNECTING) }
        } else {
            deviceBatteryMap[address] = existing.copy(
                rssi = scannedDevice.rssi,
                lastDetectedTime = System.currentTimeMillis()
            )
            _deviceList.value = deviceBatteryMap.values.toList()

            if (existing.status == DeviceStatus.DISCONNECTED || existing.status == DeviceStatus.ERROR) {
                repository.connect(device)
                updateDeviceInfo(address) { it.copy(status = DeviceStatus.CONNECTING) }
            }
        }
    }

    private fun startDeviceTimeout(address: String) {
        cancelDeviceTimeout(address)
        deviceTimeoutJobs[address] = viewModelScope.launch {
            delay(DEVICE_TIMEOUT_MS)
            val info = deviceBatteryMap[address]
            if (info != null && info.batteryLevel == null) {
                appendLog("タイムアウト: ${info.deviceName}")
                updateDeviceInfo(address) { it.copy(status = DeviceStatus.ERROR) }
                repository.disconnect(address) 
            }
        }
    }

    private fun cancelDeviceTimeout(address: String) {
        deviceTimeoutJobs.remove(address)?.cancel()
    }

    private fun checkBondedDevices() {
        if (!this::bluetoothAdapter.isInitialized) return
        bluetoothAdapter.bondedDevices.forEach { device ->
            val name = device.name ?: ""
            if (TARGET_DEVICES.any { name.contains(it, ignoreCase = true) }) {
                onScannedDeviceFound(ScannedDevice(device, 0))
            }
        }
    }

    fun startScan() {
        appendLog("手動スキャン開始...")
        deviceBatteryMap.forEach { (address, info) ->
            if (info.status == DeviceStatus.DISCONNECTED || info.status == DeviceStatus.ERROR) {
                repository.connect(bluetoothAdapter.getRemoteDevice(address))
            }
        }
        viewModelScope.launch {
            BleScanServiceManager.emitRestartScan()
        }
    }

    private fun startScanCheck() {
        scanCheckJob?.cancel()
        scanCheckJob = viewModelScope.launch {
            while (true) {
                delay(SCAN_CHECK_INTERVAL_MS)
                checkDetectedTimeouts()
            }
        }
    }

    private fun checkDetectedTimeouts() {
        val now = System.currentTimeMillis()
        deviceBatteryMap.forEach { (address, info) ->
            if (now - info.lastDetectedTime > DEVICE_DISCONNECT_TIMEOUT_MS) {
                if (info.status != DeviceStatus.CONNECTED) {
                    updateDeviceInfo(address) { it.copy(status = DeviceStatus.DISCONNECTED) }
                }
            }
        }
    }

    private fun appendLog(message: String) {
        Log.d(TAG, message)
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        allLogs.add("[$timestamp] $message")
        if (allLogs.size > 100) allLogs.removeAt(0)
        _logs.value = "ログ:\n" + allLogs.takeLast(50).joinToString("\n")
    }

    /**
     * ローバッテリー通知をチェックして発行する
     *
     * @param address デバイスアドレス
     * @param deviceName デバイス名
     * @param batteryLevel バッテリーレベル
     */
    private fun checkLowBatteryNotification(address: String, deviceName: String, batteryLevel: Int) {
        viewModelScope.launch {
            try {
                appendLog("通知チェック開始 ($deviceName): ${batteryLevel}%")

                // 設定値を取得（最初の値のみ取得）
                val settingsFlow = com.pirorin215.tr70batterymonitor.data.SettingsManager.getAllSettings()

                // Flowから最初の値を収集して処理を継続
                var appSettings: com.pirorin215.tr70batterymonitor.data.AppSettings? = null
                val job = launch {
                    settingsFlow.collect { appSettingsValue ->
                        appSettings = appSettingsValue
                        // 最初の値を取得したら収集を終了
                        return@collect
                    }
                }

                // 少し待ってから収集をキャンセル
                kotlinx.coroutines.delay(100)
                job.cancel()

                val actualSettings = appSettings
                if (actualSettings == null) {
                    appendLog("通知チェック: 設定取得失敗、デフォルト値を使用")
                    // デフォルト値で処理を継続
                    val threshold = SettingsDataStore.DEFAULT_TR70_THRESHOLD
                    val existingInfo = deviceBatteryMap[address]

                    val notificationEnabled = SettingsDataStore.DEFAULT_LOW_BATTERY_NOTIFICATION_ENABLED
                    val isLowBattery = batteryLevel <= threshold
                    val notNotifiedYet = existingInfo?.notifiedLowBattery != true

                    appendLog("通知条件チェック（デフォルト）: 有効=$notificationEnabled, 低バッテリー=$isLowBattery (${batteryLevel}<=${threshold}), 未通知=$notNotifiedYet")

                    val shouldNotify = notificationEnabled && isLowBattery && notNotifiedYet

                    if (batteryLevel > threshold && existingInfo?.notifiedLowBattery == true) {
                        updateDeviceInfo(address) { it.copy(notifiedLowBattery = false) }
                        appendLog("バッテリー回復 ($deviceName): $batteryLevel% -> フラグリセット")
                    }

                    if (shouldNotify) {
                        appendLog("通知発行条件を満たしました ($deviceName)")
                        try {
                            NotificationHelper.showLowBatteryNotification(
                                repository.getContext(),
                                deviceName,
                                batteryLevel,
                                threshold
                            )
                            updateDeviceInfo(address) { it.copy(notifiedLowBattery = true) }
                            appendLog("低バッテリー通知発行完了 ($deviceName): $batteryLevel% <= $threshold%")
                        } catch (notifyException: Exception) {
                            appendLog("通知発行エラー: ${notifyException.message}")
                            Log.e(TAG, "通知発行エラー", notifyException)
                        }
                    } else {
                        appendLog("通知発行なし: 条件を満たしていません")
                    }
                    return@launch
                }

                // デバイス名から閾値を取得
                val threshold = when {
                    deviceName.contains("TR70", ignoreCase = true) -> actualSettings.tr70Threshold
                    deviceName.contains("BSC200S", ignoreCase = true) -> actualSettings.bsc200sThreshold
                    else -> SettingsDataStore.DEFAULT_TR70_THRESHOLD // デフォルト値
                }

                appendLog("通知設定: 有効=${actualSettings.lowBatteryNotificationEnabled}, 閾値=${threshold}%")

                val existingInfo = deviceBatteryMap[address]
                appendLog("通知フラグ: ${existingInfo?.notifiedLowBattery}")

                // 通知条件チェック
                val notificationEnabled = actualSettings.lowBatteryNotificationEnabled
                val isLowBattery = batteryLevel <= threshold
                val notNotifiedYet = existingInfo?.notifiedLowBattery != true

                appendLog("通知条件チェック: 有効=$notificationEnabled, 低バッテリー=$isLowBattery (${batteryLevel}<=${threshold}), 未通知=$notNotifiedYet")

                val shouldNotify = notificationEnabled && isLowBattery && notNotifiedYet

                // バッテリーが回復した場合は通知済みフラグをリセット
                if (batteryLevel > threshold && existingInfo?.notifiedLowBattery == true) {
                    updateDeviceInfo(address) { it.copy(notifiedLowBattery = false) }
                    appendLog("バッテリー回復 ($deviceName): $batteryLevel% -> フラグリセット")
                }

                // 通知発行
                if (shouldNotify) {
                    appendLog("通知発行条件を満たしました ($deviceName)")
                    try {
                        NotificationHelper.showLowBatteryNotification(
                            repository.getContext(),
                            deviceName,
                            batteryLevel,
                            threshold
                        )
                        updateDeviceInfo(address) { it.copy(notifiedLowBattery = true) }
                        appendLog("低バッテリー通知発行完了 ($deviceName): $batteryLevel% <= $threshold%")
                    } catch (notifyException: Exception) {
                        appendLog("通知発行エラー: ${notifyException.message}")
                        Log.e(TAG, "通知発行エラー", notifyException)
                    }
                } else {
                    appendLog("通知発行なし: 条件を満たしていません")
                }
            } catch (e: Exception) {
                appendLog("通知チェックエラー: ${e.message}")
                Log.e(TAG, "通知チェックエラー: ${e.message}", e)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        scanCheckJob?.cancel()
        deviceTimeoutJobs.values.forEach { it.cancel() }
    }

    fun onPermissionsGranted() {}
}
