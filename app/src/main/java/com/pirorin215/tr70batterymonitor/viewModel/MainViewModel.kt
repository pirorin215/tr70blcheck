package com.pirorin215.tr70batterymonitor.viewModel

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pirorin215.tr70batterymonitor.data.BleEvent
import com.pirorin215.tr70batterymonitor.data.BleRepository
import com.pirorin215.tr70batterymonitor.data.ConnectionState
import com.pirorin215.tr70batterymonitor.data.DeviceBatteryInfo
import com.pirorin215.tr70batterymonitor.data.DeviceStatus
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
                updateDeviceInfo(event.address) {
                    it.copy(
                        batteryLevel = event.level,
                        status = DeviceStatus.CONNECTED,
                        lastUpdate = System.currentTimeMillis()
                    )
                }
            }
            is BleEvent.Disconnected -> {
                appendLog("切断検知 (${event.address})")
                cancelDeviceTimeout(event.address)
                updateDeviceInfo(event.address) { it.copy(status = DeviceStatus.DISCONNECTED) }
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

    override fun onCleared() {
        super.onCleared()
        scanCheckJob?.cancel()
        deviceTimeoutJobs.values.forEach { it.cancel() }
    }

    fun onPermissionsGranted() {}
}
