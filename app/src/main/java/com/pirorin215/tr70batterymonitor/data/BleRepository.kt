package com.pirorin215.tr70batterymonitor.data

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

sealed class ConnectionState {
    object Disconnected : ConnectionState()
    object Connecting : ConnectionState()
    data class Connected(val device: BluetoothDevice) : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}

sealed class BleEvent {
    data class ServicesDiscovered(val services: List<String>) : BleEvent()
    data class CharacteristicRead(val uuid: String, val value: String) : BleEvent()
    data class BatteryLevelChanged(val level: Int) : BleEvent()
    data class RssiUpdated(val rssi: Int) : BleEvent()
    data class Error(val message: String) : BleEvent()
    object Ready : BleEvent()
}

@SuppressLint("MissingPermission")
class BleRepository(private val context: Context) {

    companion object {
        const val TAG = "BleRepository"

        // Standard UUIDs (TR70/Garmin用)
        const val BATTERY_SERVICE_UUID = "0000180F-0000-1000-8000-00805F9B34FB"
        const val BATTERY_LEVEL_UUID = "00002A19-0000-1000-8000-00805F9B34FB"
        const val CCCD_UUID = "00002902-0000-1000-8000-00805F9B34FB"

        // BSC200S (iGPSPORT) NUS UUIDs
        const val NUS_SERVICE_UUID = "6e400001-b5a3-f393-e0a9-e50e24dcca9e"
        const val NUS_RX_UUID = "6e400002-b5a3-f393-e0a9-e50e24dcca9e"  // Write
        const val NUS_TX_UUID = "6e400003-b5a3-f393-e0a9-e50e24dcca9e"  // Notify
    }

    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var bluetoothGatt: BluetoothGatt? = null
    private var currentDevice: BluetoothDevice? = null
    private var isBsc200s = false
    private var mtuNegotiated = false

    // iGPSPORT characteristics
    private val txCharacteristics = mutableMapOf<String, BluetoothGattCharacteristic>()
    private var nusRxCharacteristic: BluetoothGattCharacteristic? = null

    // State flows
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState = _connectionState.asStateFlow()

    private val _events = MutableSharedFlow<BleEvent>()
    val events = _events.asSharedFlow()

    // Discovered information
    private val _discoveredServices = MutableStateFlow<List<String>>(emptyList())
    val discoveredServices = _discoveredServices.asStateFlow()

    private val _characteristicsInfo = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val characteristicsInfo = _characteristicsInfo.asStateFlow()

    // Battery level
    private val _batteryLevel = MutableStateFlow<Int?>(null)
    val batteryLevel = _batteryLevel.asStateFlow()

    // RSSI
    private val _rssi = MutableStateFlow<Int?>(null)
    val rssi = _rssi.asStateFlow()

    // Log storage
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs = _logs.asStateFlow()

    private fun addLog(message: String) {
        Log.d(TAG, message)
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        val newLogs = _logs.value.toMutableList()
        newLogs.add("[$timestamp] $message")
        if (newLogs.size > 100) newLogs.removeAt(0)
        _logs.value = newLogs
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val deviceAddress = gatt.device.address
            if (status == BluetoothGatt.GATT_SUCCESS) {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        addLog("Connected to $deviceAddress")
                        _connectionState.value = ConnectionState.Connected(gatt.device)

                        // RSSI読取り
                        gatt.readRemoteRssi()

                        // MTUネゴシエーション（24バイト以上のペイロード送信のため）
                        mtuNegotiated = false
                        addLog("Requesting MTU 247...")
                        gatt.requestMtu(247)
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        addLog("Disconnected from $deviceAddress")
                        _connectionState.value = ConnectionState.Disconnected
                        close()
                    }
                }
            } else {
                addLog("Connection error: status=$status")
                _connectionState.value = ConnectionState.Error("GATT Error $status")
                close()
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                addLog("MTU negotiated: $mtu bytes")
                mtuNegotiated = true
            } else {
                addLog("MTU negotiation failed: status=$status, continuing with default")
            }
            // MTUネゴシエーション完了後にサービス探索
            repositoryScope.launch {
                delay(300)
                gatt.discoverServices()
            }
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                addLog("RSSI: $rssi dBm")
                _rssi.value = rssi
                repositoryScope.launch {
                    _events.emit(BleEvent.RssiUpdated(rssi))
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                addLog("Services discovered successfully")
                analyzeServices(gatt)
            } else {
                addLog("Service discovery failed: status=$status")
                repositoryScope.launch {
                    _events.emit(BleEvent.Error("Service discovery failed"))
                }
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val uuid = characteristic.uuid.toString()
                val valueStr = value.joinToString(", ") { "0x%02X".format(it) }
                addLog("Read characteristic $uuid: $valueStr")

                // Check if this is battery level (standard)
                if (uuid.equals(BATTERY_LEVEL_UUID, ignoreCase = true) && value.isNotEmpty()) {
                    val level = value[0].toInt() and 0xFF
                    _batteryLevel.value = level
                    addLog("Battery level: $level%")
                    repositoryScope.launch {
                        _events.emit(BleEvent.BatteryLevelChanged(level))
                    }
                }

                repositoryScope.launch {
                    _events.emit(BleEvent.CharacteristicRead(uuid, valueStr))
                }
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            val uuid = characteristic.uuid.toString()
            val valueStr = value.joinToString(", ") { "0x%02X".format(it) }
            addLog("Characteristic changed $uuid: $valueStr")

            // Standard Battery Service notification (TR70)
            if (uuid.equals(BATTERY_LEVEL_UUID, ignoreCase = true) && value.isNotEmpty()) {
                val level = value[0].toInt() and 0xFF
                _batteryLevel.value = level
                addLog("Battery level changed: $level%")
                repositoryScope.launch {
                    _events.emit(BleEvent.BatteryLevelChanged(level))
                }
            }

            // BSC200S NUS notification
            if (uuid.contains("6e400003", ignoreCase = true)) {
                val suffix = uuid.substring(uuid.length - 5)
                addLog("iGPSPORT notification received from $suffix (${value.size} bytes)")
                
                if (uuid.equals(NUS_TX_UUID, ignoreCase = true)) {
                    parseBsc200sBatteryResponse(value)
                }
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                addLog("Descriptor written successfully")
                repositoryScope.launch {
                    _events.emit(BleEvent.Ready)
                }
            } else {
                addLog("Descriptor write failed: status=$status")
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            val uuid = characteristic.uuid.toString()
            if (status == BluetoothGatt.GATT_SUCCESS) {
                addLog("Write successful to $uuid")
            } else {
                addLog("Write failed to $uuid: status=$status")
            }
        }
    }

    private fun analyzeServices(gatt: BluetoothGatt) {
        val services = gatt.services
        val serviceInfo = mutableListOf<String>()
        val charInfo = mutableMapOf<String, List<String>>()

        addLog("Found ${services.size} services:")

        // デバイスタイプを判定
        val deviceName = gatt.device.name ?: ""
        isBsc200s = deviceName.contains("BSC200", ignoreCase = true) ||
                     deviceName.contains("iGPSPORT", ignoreCase = true)
        addLog("Device: $deviceName, isBsc200s=$isBsc200s")

        var hasStandardBattery = false
        txCharacteristics.clear()
        nusRxCharacteristic = null

        services.forEach { service ->
            val serviceUuid = service.uuid.toString()
            val serviceInfoStr = "Service: $serviceUuid"
            serviceInfo.add(serviceInfoStr)
            addLog("  - $serviceInfoStr")

            when {
                serviceUuid.equals(BATTERY_SERVICE_UUID, ignoreCase = true) -> {
                    addLog("    -> Standard Battery Service!")
                    hasStandardBattery = true
                }
                serviceUuid.startsWith("6e400001", ignoreCase = true) -> {
                    addLog("    -> iGPSPORT Custom Service!")
                }
                serviceUuid.startsWith("000018") -> {
                    val serviceType = when (serviceUuid.substring(4, 8)) {
                        "1800" -> "Generic Access"
                        "1801" -> "Generic Attribute"
                        "180A" -> "Device Information"
                        "180F" -> "Battery Service"
                        "1815" -> "Automation IO"
                        else -> "Unknown BLE Service"
                    }
                    addLog("    -> Standard BLE Service: $serviceType")
                }
                else -> {
                    addLog("    -> Custom/Unknown service")
                }
            }

            val characteristics = mutableListOf<String>()
            service.characteristics.forEach { characteristic ->
                val charUuid = characteristic.uuid.toString()
                val props = getPropertiesString(characteristic)
                val charInfoStr = "Characteristic: $charUuid [$props]"
                characteristics.add(charInfoStr)
                addLog("    - $charInfoStr")

                // Standard Battery Level
                if (charUuid.equals(BATTERY_LEVEL_UUID, ignoreCase = true)) {
                    addLog("      -> Battery Level characteristic found!")
                    readBatteryLevel(characteristic)
                    enableBatteryNotification(characteristic)
                }

                // iGPSPORT Write Channel (primarily cca9e)
                if (charUuid.equals(NUS_RX_UUID, ignoreCase = true)) {
                    addLog("      -> iGPSPORT RX (Write) characteristic found!")
                    nusRxCharacteristic = characteristic
                }

                // iGPSPORT Notify Channels
                if (charUuid.contains("6e400003", ignoreCase = true)) {
                    val suffix = charUuid.substring(charUuid.length - 5)
                    addLog("      -> iGPSPORT TX (Notify) channel found: $suffix")
                    txCharacteristics[charUuid] = characteristic
                }
            }
            charInfo[serviceUuid] = characteristics
        }

        _discoveredServices.value = serviceInfo
        _characteristicsInfo.value = charInfo

        repositoryScope.launch {
            _events.emit(BleEvent.ServicesDiscovered(serviceInfo))
        }

        // BSC200Sの場合、全Notifyを有効にしてから要求を送信
        if (isBsc200s && !hasStandardBattery) {
            repositoryScope.launch {
                delay(500)
                setupBsc200sBatteryRead()
            }
        }
    }

    private fun setupBsc200sBatteryRead() {
        val gatt = bluetoothGatt ?: run {
            addLog("GATT is null, cannot setup BSC200S")
            return
        }

        if (txCharacteristics.isEmpty()) {
            addLog("No iGPSPORT TX channels found")
            return
        }

        repositoryScope.launch {
            // 全てのTXチャネルの通知を順次有効化
            txCharacteristics.forEach { (uuid, char) ->
                addLog("Enabling notifications for $uuid...")
                gatt.setCharacteristicNotification(char, true)
                val cccd = char.getDescriptor(UUID.fromString(CCCD_UUID))
                if (cccd != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                    } else {
                        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        gatt.writeDescriptor(cccd)
                    }
                    delay(300) // 連続書き込みを避けるため待機
                }
            }

            addLog("All custom notifications requested. Waiting for stability...")
            delay(1000)
            sendBsc200sBatteryRequest()
        }
    }

    /**
     * BSC200Sにバッテリー情報リクエストを送信
     */
    private fun sendBsc200sBatteryRequest() {
        val gatt = bluetoothGatt ?: run {
            addLog("GATT is null, cannot send BSC200S request")
            return
        }

        val rxChar = nusRxCharacteristic ?: run {
            addLog("NUS RX characteristic not found")
            return
        }

        addLog("Sending BSC200S battery request...")

        // リクエストデータを構築（CRC計算付き）
        val request = buildBatteryRequest()

        val valueStr = request.joinToString(" ") { "0x%02X".format(it) }
        addLog("Request data: $valueStr")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(rxChar, request, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
        } else {
            rxChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            rxChar.value = request
            gatt.writeCharacteristic(rxChar)
        }
    }

    /**
     * BSC200Sバッテリー要求データを構築
     * ヘッダー(20バイト) + Protobufペイロード(4バイト) = 24バイト
     */
    private fun buildBatteryRequest(): ByteArray {
        // ペイロード: 08 0B 10 06 (Command 11, Sub 6)
        val payload = byteArrayOf(0x08, 0x0B, 0x10, 0x06)
        val payloadSize = payload.size
        val payloadCrc = crc8Maxim(payload)

        // ヘッダー (20バイト)
        val header = ByteArray(20)
        header[0] = 0x01
        header[1] = 0x0B       // service = FACTORY (11)
        header[2] = 0xFF.toByte()
        header[3] = 0xFF.toByte()
        header[4] = 0x06       // operation = BATTARY_GET (6)
        header[5] = 0xFF.toByte()
        header[6] = 0xFF.toByte()
        header[7] = (payloadSize shr 8).toByte()
        header[8] = payloadSize.toByte()
        header[9] = payloadCrc
        header[10] = 0x00       // Request flag (Responseは0x01)
        for (i in 11..18) {
            header[i] = 0xFF.toByte()
        }
        // header CRC (byte 19)
        header[19] = crc8Maxim(header.copyOfRange(0, 19))

        // ヘッダー + ペイロード
        return header + payload
    }

    /**
     * CRC-8/MAXIM計算
     * 多項式: 0x31, 初期値: 0, 入出力反転
     */
    private fun crc8Maxim(data: ByteArray): Byte {
        var crc = 0
        for (b in data) {
            crc = crc xor (b.toInt() and 0xFF)
            for (i in 0 until 8) {
                crc = if (crc and 0x01 != 0) {
                    (crc shr 1) xor 0x8C  // 0x8C = 反転された 0x31
                } else {
                    crc shr 1
                }
            }
        }
        return crc.toByte()
    }

    /**
     * BSC200SのNUSレスポンスからバッテリー残量を抽出
     */
    private fun parseBsc200sBatteryResponse(data: ByteArray) {
        if (data.size < 20) {
            addLog("Response too short: ${data.size} bytes")
            return
        }

        val hexStr = data.joinToString(" ") { "0x%02X".format(it) }
        addLog("Full response: $hexStr")

        // ペイロード部分を取得（ヘッダー以降）
        val payload = data.copyOfRange(20, data.size)
        val payloadHex = payload.joinToString(" ") { "0x%02X".format(it) }
        addLog("Payload: $payloadHex")

        // 08 0B 10 06 パターンを探す
        val pattern = byteArrayOf(0x08, 0x0B, 0x10, 0x06)
        val idx = findPattern(payload, pattern)

        if (idx >= 0) {
            addLog("Found command pattern at payload offset $idx")
            // パターンの後にある 10 [Level] を探す
            for (i in (idx + 4) until (payload.size - 1)) {
                if (payload[i] == 0x10.toByte()) {
                    val level = payload[i + 1].toInt() and 0xFF
                    if (level in 0..100) {
                        _batteryLevel.value = level
                        addLog("BSC200S Battery level: $level%")
                        repositoryScope.launch {
                            _events.emit(BleEvent.BatteryLevelChanged(level))
                        }
                        return
                    }
                }
            }
        } else {
            addLog("Command pattern 08 0B 10 06 not found in payload")
        }
        
        // フォールバック
        parseBatteryFallback(payload)
    }

    /**
     * ProtobufデータからTag 2 (0x10) のVarint値を読み取る
     */
    private fun findBatteryInProtobuf(data: ByteArray): Int? {
        for (i in data.indices) {
            if (data[i] == 0x10.toByte() && i + 1 < data.size) {
                val value = data[i + 1].toInt() and 0xFF
                if (value in 0..100) {
                    addLog("Found potential battery at offset $i: $value%")
                    return value
                }
            }
        }
        return null
    }

    /**
     * フォールバック: ペイロード全体から最後の 10 タグの次のバイトをバッテリーとして解釈
     */
    private fun parseBatteryFallback(payload: ByteArray) {
        addLog("Trying fallback battery parsing...")

        for (i in payload.size - 2 downTo 0) {
            if (payload[i] == 0x10.toByte()) {
                val value = payload[i + 1].toInt() and 0xFF
                if (value in 0..100) {
                    addLog("Fallback: found battery at offset $i: $value%")
                    _batteryLevel.value = value
                    repositoryScope.launch {
                        _events.emit(BleEvent.BatteryLevelChanged(value))
                    }
                    return
                }
            }
        }

        addLog("Fallback parsing also failed")
    }

    /**
     * バイト配列内からパターンを検索
     */
    private fun findPattern(data: ByteArray, pattern: ByteArray): Int {
        if (pattern.size > data.size) return -1
        for (i in 0..(data.size - pattern.size)) {
            var found = true
            for (j in pattern.indices) {
                if (data[i + j] != pattern[j]) {
                    found = false
                    break
                }
            }
            if (found) return i
        }
        return -1
    }

    private fun getPropertiesString(characteristic: BluetoothGattCharacteristic): String {
        val props = mutableListOf<String>()
        if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) {
            props.add("READ")
        }
        if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) {
            props.add("WRITE")
        }
        if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) {
            props.add("WRITE_NO_RESPONSE")
        }
        if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
            props.add("NOTIFY")
        }
        if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) {
            props.add("INDICATE")
        }
        return props.joinToString(" | ")
    }

    private fun readBatteryLevel(characteristic: BluetoothGattCharacteristic) {
        val gatt = bluetoothGatt ?: return
        addLog("Reading battery level...")
        gatt.readCharacteristic(characteristic)
    }

    private fun enableBatteryNotification(characteristic: BluetoothGattCharacteristic) {
        val gatt = bluetoothGatt ?: return
        addLog("Enabling battery notifications...")

        gatt.setCharacteristicNotification(characteristic, true)

        val descriptor = characteristic.getDescriptor(
            UUID.fromString(CCCD_UUID)
        )

        if (descriptor != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(
                    descriptor,
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                )
            } else {
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(descriptor)
            }
        } else {
            addLog("CCCD descriptor not found")
        }
    }

    fun connect(device: BluetoothDevice) {
        addLog("Connecting to ${device.name} (${device.address})")
        _connectionState.value = ConnectionState.Connecting
        _batteryLevel.value = null
        _rssi.value = null
        currentDevice = device
        isBsc200s = false
        mtuNegotiated = false
        txCharacteristics.clear()
        nusRxCharacteristic = null

        repositoryScope.launch {
            // 前回の切断から時間が短いと133エラーになりやすいため待機
            delay(1000)
            
            try {
                // TRANSPORT_LEを明示的に指定して接続安定性を向上
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
                } else {
                    bluetoothGatt = device.connectGatt(context, false, gattCallback)
                }
            } catch (e: SecurityException) {
                addLog("Security exception: ${e.message}")
                _connectionState.value = ConnectionState.Error("Permission denied")
            }
        }
    }

    fun disconnect() {
        addLog("Disconnecting...")
        bluetoothGatt?.disconnect()
    }

    fun close() {
        addLog("Closing GATT connection")
        bluetoothGatt?.close()
        bluetoothGatt = null
        currentDevice = null
        _connectionState.value = ConnectionState.Disconnected
    }

    /**
     * 接続中デバイスのRSSIを読み取る
     */
    fun readRssi() {
        bluetoothGatt?.readRemoteRssi()
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }
}
