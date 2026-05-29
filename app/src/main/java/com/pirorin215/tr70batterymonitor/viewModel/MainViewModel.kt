package com.pirorin215.tr70batterymonitor.viewmodel

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pirorin215.tr70batterymonitor.data.BleRepository
import com.pirorin215.tr70batterymonitor.data.ConnectionState
import com.pirorin215.tr70batterymonitor.data.DeviceBatteryInfo
import com.pirorin215.tr70batterymonitor.service.BleScanServiceManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

class MainViewModel : ViewModel() {

    companion object {
        private const val TAG = "MainViewModel"

        // ターゲットデバイス名
        private val TARGET_DEVICES = listOf("TR70", "BSC200S")
    }

    private lateinit var repository: BleRepository
    private lateinit var bluetoothAdapter: android.bluetooth.BluetoothAdapter
    private val deviceBatteryMap = ConcurrentHashMap<String, DeviceBatteryInfo>()
    private val pendingDevices = mutableListOf<BluetoothDevice>()
    private var isProcessing = false

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

    private val _deviceList = MutableStateFlow<List<DeviceBatteryInfo>>(emptyList())
    val deviceList: StateFlow<List<DeviceBatteryInfo>> = _deviceList.asStateFlow()

    private var allLogs = mutableListOf<String>()

    fun init(context: Context) {
        repository = BleRepository(context)

        // BluetoothAdapterを取得
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        // Collect connection state
        viewModelScope.launch {
            repository.connectionState.collect { state ->
                when (state) {
                    is ConnectionState.Disconnected -> {
                        _connectionState.value = "Disconnected"
                        appendLog("切断されました")
                        isProcessing = false
                        processNextDevice()
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
                        isProcessing = false
                        processNextDevice()
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

                    // 現在接続中のデバイスのバッテリーを更新
                    val currentDeviceAddress = getCurrentDeviceAddress()
                    if (currentDeviceAddress != null) {
                        val deviceInfo = deviceBatteryMap[currentDeviceAddress]
                        if (deviceInfo != null) {
                            deviceBatteryMap[currentDeviceAddress] = deviceInfo.copy(
                                batteryLevel = level,
                                lastUpdate = System.currentTimeMillis()
                            )
                            _deviceList.value = deviceBatteryMap.values.toList()

                            // バッテリー取得完了したら切断して次のデバイスへ
                            appendLog("バッテリー取得完了。切断して次のデバイスへ...")
                            repository.disconnect()
                        }
                    }
                }
            }
        }

        // Collect logs
        viewModelScope.launch {
            repository.logs.collect { logs ->
                allLogs = logs.toMutableList()
                _logs.value = "ログ:\n" + allLogs.takeLast(50).joinToString("\n")
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

        // Listen for devices found by the background scanning service
        viewModelScope.launch {
            BleScanServiceManager.deviceFoundFlow.collect { device ->
                onDeviceFound(device)
            }
        }
    }

    private fun getCurrentDeviceAddress(): String? {
        val state = _connectionState.value
        if (state == "Connected") {
            return _deviceInfo.value.let { info ->
                val regex = Regex("\\(([0-9A-F:]+)\\)")
                regex.find(info)?.groupValues?.get(1)
            }
        }
        return null
    }

    fun onPermissionsGranted() {
        appendLog("権限が付与されました")
    }

    fun startScan() {
        appendLog("スキャン開始...")
        _connectionState.value = "Scanning"
        _deviceList.value = emptyList()
        deviceBatteryMap.clear()
        pendingDevices.clear()
        isProcessing = false

        // まずBondedDevice（ペアリング済みデバイス）を確認
        checkBondedDevices()

        // スキャンも開始
        viewModelScope.launch {
            BleScanServiceManager.emitRestartScan()
        }
    }

    private fun checkBondedDevices() {
        if (!this::bluetoothAdapter.isInitialized) {
            appendLog("BluetoothAdapterが初期化されていません")
            return
        }

        val bondedDevices = bluetoothAdapter.bondedDevices
        appendLog("ペアリング済みデバイス数: ${bondedDevices.size}")

        bondedDevices.forEach { device ->
            val deviceName = device.name ?: "(no name)"
            appendLog("  ペアリング済み: $deviceName (${device.address})")

            // ターゲットデバイスかチェック
            val isTargetDevice = TARGET_DEVICES.any { deviceName.contains(it, ignoreCase = true) }
            if (isTargetDevice) {
                appendLog("  → ターゲットデバイス発見: $deviceName")
                onDeviceFound(device)
            }
        }
    }

    fun onDeviceFound(device: BluetoothDevice) {
        val deviceName = device.name ?: "(no name)"

        // ターゲットデバイスかチェック
        val isTargetDevice = TARGET_DEVICES.any { deviceName.contains(it, ignoreCase = true) }
        if (!isTargetDevice) {
            return
        }

        appendLog("ターゲットデバイス発見: $deviceName")

        // バッテリー取得済みの場合はスキップ
        val existingInfo = deviceBatteryMap[device.address]
        if (existingInfo != null && existingInfo.batteryLevel != null) {
            appendLog("  → バッテリー取得済み: $deviceName (${existingInfo.batteryLevel}%)")
            return
        }

        // 初期情報を登録
        deviceBatteryMap[device.address] = DeviceBatteryInfo(
            deviceAddress = device.address,
            deviceName = deviceName,
            batteryLevel = null
        )
        _deviceList.value = deviceBatteryMap.values.toList()

        // ペンディングリストに追加
        pendingDevices.add(device)
        appendLog("  → ペンディングリストに追加: $deviceName (${pendingDevices.size}台)")

        // 処理中でなければ次のデバイスを処理
        if (!isProcessing) {
            processNextDevice()
        }
    }

    private fun processNextDevice() {
        if (isProcessing || pendingDevices.isEmpty()) {
            if (pendingDevices.isEmpty() && deviceBatteryMap.isNotEmpty()) {
                appendLog("すべてのデバイス処理完了")
                _connectionState.value = "All Devices Processed"
            }
            return
        }

        isProcessing = true
        val nextDevice = pendingDevices.removeAt(0)
        appendLog("次のデバイスを処理: ${nextDevice.name} (残り: ${pendingDevices.size}台)")

        // デバイスに接続
        repository.connect(nextDevice)
    }

    fun connectToDevice() {
        // このメソッドは使用しない（自動処理）
        appendLog("自動処理中です。お待ちください...")
    }

    fun disconnect() {
        appendLog("全デバイス切断リクエスト...")
        repository.disconnect()
        deviceBatteryMap.clear()
        pendingDevices.clear()
        _deviceList.value = emptyList()
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
