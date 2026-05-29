package com.pirorin215.tr70batterymonitor.viewmodel

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pirorin215.tr70batterymonitor.data.BleRepository
import com.pirorin215.tr70batterymonitor.data.ConnectionState
import com.pirorin215.tr70batterymonitor.service.BleScanServiceManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainViewModel : ViewModel() {

    companion object {
        private const val TAG = "MainViewModel"
    }

    private lateinit var repository: BleRepository
    private var currentDevice: BluetoothDevice? = null

    // UI States
    private val _connectionState = MutableStateFlow("Disconnected")
    val connectionState: StateFlow<String> = _connectionState.asStateFlow()

    private val _deviceInfo = MutableStateFlow("デバイス情報: --")
    val deviceInfo: StateFlow<String> = _deviceInfo.asStateFlow()

    private val _servicesInfo = MutableStateFlow("Services: --")
    val servicesInfo: StateFlow<String> = _servicesInfo.asStateFlow()

    private val _batteryLevel = MutableStateFlow<Int?>(null)
    val batteryLevel: StateFlow<Int?> = _batteryLevel.asStateFlow()

    private val _logs = MutableStateFlow("ログ:\n")
    val logs: StateFlow<String> = _logs.asStateFlow()

    private var allLogs = mutableListOf<String>()

    fun init(context: Context) {
        repository = BleRepository(context)

        // Collect connection state
        viewModelScope.launch {
            repository.connectionState.collect { state ->
                when (state) {
                    is ConnectionState.Disconnected -> {
                        _connectionState.value = "Disconnected"
                        appendLog("切断されました")
                    }
                    is ConnectionState.Connecting -> {
                        _connectionState.value = "Connecting"
                        appendLog("接続中...")
                    }
                    is ConnectionState.Connected -> {
                        _connectionState.value = "Connected"
                        _deviceInfo.value = "デバイス: ${state.device.name} (${state.device.address})"
                        appendLog("接続成功: ${state.device.name}")
                    }
                    is ConnectionState.Error -> {
                        _connectionState.value = "Error"
                        appendLog("エラー: ${state.message}")
                    }
                }
            }
        }

        // Collect discovered services
        viewModelScope.launch {
            repository.discoveredServices.collect { services ->
                _servicesInfo.value = "Services:\n" + services.joinToString("\n") { "  - $it" }
            }
        }

        // Collect battery level
        viewModelScope.launch {
            repository.batteryLevel.collect { level ->
                if (level != null) {
                    _batteryLevel.value = level
                    appendLog("バッテリー: $level%")
                }
            }
        }

        // Collect logs
        viewModelScope.launch {
            repository.logs.collect { logs ->
                allLogs = logs.toMutableList()
                _logs.value = "ログ:\n" + logs.takeLast(50).joinToString("\n")
            }
        }

        // Collect BLE events
        viewModelScope.launch {
            repository.events.collect { event ->
                when (event) {
                    is com.pirorin215.tr70batterymonitor.data.BleEvent.ServicesDiscovered -> {
                        appendLog("Service発見完了")
                        event.services.forEach { appendLog("  $it") }
                    }
                    is com.pirorin215.tr70batterymonitor.data.BleEvent.BatteryLevelChanged -> {
                        appendLog("バッテリー更新: ${event.level}%")
                    }
                    is com.pirorin215.tr70batterymonitor.data.BleEvent.CharacteristicRead -> {
                        appendLog("Characteristic読取: ${event.uuid} = ${event.value}")
                    }
                    is com.pirorin215.tr70batterymonitor.data.BleEvent.Ready -> {
                        appendLog("デバイス準備完了")
                    }
                    is com.pirorin215.tr70batterymonitor.data.BleEvent.Error -> {
                        appendLog("エラー: ${event.message}")
                    }
                }
            }
        }
    }

    fun onPermissionsGranted() {
        appendLog("権限が付与されました")
    }

    fun startScan() {
        appendLog("スキャン開始...")
        _connectionState.value = "Scanning"
        viewModelScope.launch {
            BleScanServiceManager.emitRestartScan()
        }
    }

    fun onDeviceFound(device: BluetoothDevice) {
        appendLog("デバイス発見: ${device.name}")
        currentDevice = device
        _connectionState.value = "Device Found"
        _deviceInfo.value = "デバイス: ${device.name} (${device.address})"
    }

    fun connectToDevice() {
        val device = currentDevice
        if (device != null) {
            appendLog("接続を開始します...")
            repository.connect(device)
        } else {
            appendLog("接続対象デバイスがありません")
        }
    }

    fun disconnect() {
        appendLog("切断をリクエスト...")
        repository.disconnect()
        currentDevice = null
    }

    fun clearLogs() {
        repository.clearLogs()
        allLogs.clear()
        _logs.value = "ログ:\n"
    }

    private fun appendLog(message: String) {
        Log.d(TAG, message)
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        allLogs.add("[$timestamp] $message")
        _logs.value = "ログ:\n" + allLogs.takeLast(50).joinToString("\n")
    }

    override fun onCleared() {
        super.onCleared()
        repository.close()
    }
}
