package com.pirorin215.tr70batterymonitor.viewmodel

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

class MainViewModel : ViewModel() {

    companion object {
        private const val TAG = "MainViewModel"

        // ターゲットデバイス名
        private val TARGET_DEVICES = listOf("TR70", "BSC200S")

        // デバイスごとのタイムアウト（ミリ秒）
        private const val DEVICE_TIMEOUT_MS = 15000L
    }

    private lateinit var repository: BleRepository
    private lateinit var bluetoothAdapter: android.bluetooth.BluetoothAdapter
    private val deviceBatteryMap = ConcurrentHashMap<String, DeviceBatteryInfo>()
    private val pendingDevices = mutableListOf<BluetoothDevice>()
    private var isProcessing = false
    private var currentProcessingAddress: String? = null
    private var timeoutJob: Job? = null

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
                        cancelTimeout()
                        // バッテリー未取得ならエラーステータスに
                        val address = currentProcessingAddress
                        if (address != null) {
                            val info = deviceBatteryMap[address]
                            if (info != null && info.batteryLevel == null) {
                                updateDeviceStatus(address, DeviceStatus.ERROR)
                            }
                        }
                        isProcessing = false
                        currentProcessingAddress = null
                        processNextDevice()
                    }
                    is ConnectionState.Connecting -> {
                        _connectionState.value = "Connecting"
                        appendLog("接続中...")
                        updateDeviceStatus(currentProcessingAddress, DeviceStatus.CONNECTING)
                    }
                    is ConnectionState.Connected -> {
                        _connectionState.value = "Connected"
                        _deviceInfo.value = "デバイス: ${state.device.name} (${state.device.address})"
                        appendLog("接続成功: ${state.device.name}")
                        updateDeviceStatus(state.device.address, DeviceStatus.CONNECTED)

                        // タイムアウト開始
                        startDeviceTimeout(state.device.address)
                    }
                    is ConnectionState.Error -> {
                        _connectionState.value = "Error"
                        appendLog("エラー: ${state.message}")
                        cancelTimeout()
                        updateDeviceStatus(currentProcessingAddress, DeviceStatus.ERROR)
                        isProcessing = false
                        currentProcessingAddress = null
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
                    val address = currentProcessingAddress
                    if (address != null) {
                        cancelTimeout()
                        updateDeviceStatus(address, DeviceStatus.READING)
                        val deviceInfo = deviceBatteryMap[address]
                        if (deviceInfo != null) {
                            deviceBatteryMap[address] = deviceInfo.copy(
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
                    is BleEvent.ServicesDiscovered -> {
                        appendLog("Service発見完了")
                        event.services.forEach { appendLog("  $it") }
                    }
                    is BleEvent.BatteryLevelChanged -> {
                        appendLog("バッテリー更新: ${event.level}%")
                    }
                    is BleEvent.CharacteristicRead -> {
                        appendLog("Characteristic読取: ${event.uuid} = ${event.value}")
                    }
                    is BleEvent.RssiUpdated -> {
                        appendLog("RSSI更新: ${event.rssi} dBm")
                        val address = currentProcessingAddress
                        if (address != null) {
                            updateDeviceRssi(address, event.rssi)
                        }
                    }
                    is BleEvent.Ready -> {
                        appendLog("デバイス準備完了")
                    }
                    is BleEvent.Error -> {
                        appendLog("エラー: ${event.message}")
                    }
                }
            }
        }

        // Listen for scanned devices (with RSSI) from the background scanning service
        viewModelScope.launch {
            BleScanServiceManager.deviceFoundFlow.collect { scannedDevice ->
                onScannedDeviceFound(scannedDevice)
            }
        }
    }

    /**
     * デバイスごとのタイムアウト開始
     */
    private fun startDeviceTimeout(address: String) {
        cancelTimeout()
        timeoutJob = CoroutineScope(Dispatchers.Main).launch {
            delay(DEVICE_TIMEOUT_MS)
            appendLog("タイムアウト: ${deviceBatteryMap[address]?.deviceName ?: address}")
            updateDeviceStatus(address, DeviceStatus.ERROR)
            repository.disconnect()
        }
    }

    private fun cancelTimeout() {
        timeoutJob?.cancel()
        timeoutJob = null
    }

    private fun updateDeviceStatus(address: String?, status: DeviceStatus) {
        if (address == null) return
        val deviceInfo = deviceBatteryMap[address]
        if (deviceInfo != null) {
            deviceBatteryMap[address] = deviceInfo.copy(status = status)
            _deviceList.value = deviceBatteryMap.values.toList()
        }
    }

    private fun updateDeviceRssi(address: String, rssi: Int) {
        val deviceInfo = deviceBatteryMap[address]
        if (deviceInfo != null) {
            deviceBatteryMap[address] = deviceInfo.copy(rssi = rssi)
            _deviceList.value = deviceBatteryMap.values.toList()
        }
    }

    fun onPermissionsGranted() {
        appendLog("権限が付与されました")
    }

    fun startScan() {
        appendLog("スキャン開始...")

        // 既存のGATT接続を切断
        repository.disconnect()
        cancelTimeout()
        isProcessing = false
        currentProcessingAddress = null

        _connectionState.value = "Scanning"
        _deviceList.value = emptyList()
        deviceBatteryMap.clear()
        pendingDevices.clear()

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
                addDeviceToQueue(device, deviceName, rssi = null)
            }
        }
    }

    /**
     * BLEスキャンからのデバイス発見（RSSI付き）
     */
    private fun onScannedDeviceFound(scannedDevice: ScannedDevice) {
        val device = scannedDevice.device
        val rssi = scannedDevice.rssi
        val deviceName = device.name ?: "(no name)"

        // ターゲットデバイスかチェック
        val isTargetDevice = TARGET_DEVICES.any { deviceName.contains(it, ignoreCase = true) }
        if (!isTargetDevice) {
            return
        }

        appendLog("ターゲットデバイス発見(スキャン): $deviceName (RSSI: $rssi)")
        addDeviceToQueue(device, deviceName, rssi)
    }

    /**
     * デバイスを処理キューに追加
     */
    private fun addDeviceToQueue(device: BluetoothDevice, deviceName: String, rssi: Int?) {
        val address = device.address

        // 既存のエントリがある場合
        val existingInfo = deviceBatteryMap[address]
        if (existingInfo != null) {
            // RSSI更新（スキャンから再検出された場合など）
            if (rssi != null) {
                deviceBatteryMap[address] = existingInfo.copy(rssi = rssi)
                _deviceList.value = deviceBatteryMap.values.toList()
            }
            // バッテリー取得済みの場合はスキップ
            if (existingInfo.batteryLevel != null) {
                appendLog("  → バッテリー取得済み: $deviceName (${existingInfo.batteryLevel}%)")
                return
            }
            return
        }

        appendLog("ターゲットデバイス発見: $deviceName")

        // 初期情報を登録
        deviceBatteryMap[address] = DeviceBatteryInfo(
            deviceAddress = address,
            deviceName = deviceName,
            batteryLevel = null,
            rssi = rssi,
            status = DeviceStatus.FOUND
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

    /**
     * BluetoothDeviceのみ（ペアリング済みデバイス用、RSSIなし）
     */
    fun onDeviceFound(device: BluetoothDevice) {
        val deviceName = device.name ?: "(no name)"

        // ターゲットデバイスかチェック
        val isTargetDevice = TARGET_DEVICES.any { deviceName.contains(it, ignoreCase = true) }
        if (!isTargetDevice) {
            return
        }

        addDeviceToQueue(device, deviceName, rssi = null)
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
        currentProcessingAddress = nextDevice.address
        appendLog("次のデバイスを処理: ${nextDevice.name} (残り: ${pendingDevices.size}台)")

        // デバイスに接続
        repository.connect(nextDevice)
    }

    fun connectToDevice() {
        appendLog("自動処理中です。お待ちください...")
    }

    fun disconnect() {
        appendLog("全デバイス切断リクエスト...")
        cancelTimeout()
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
        cancelTimeout()
        repository.close()
    }
}
