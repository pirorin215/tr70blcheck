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
    data class ServicesDiscovered(val address: String, val services: List<String>) : BleEvent()
    data class CharacteristicRead(val address: String, val uuid: String, val value: String) : BleEvent()
    data class BatteryLevelChanged(val address: String, val level: Int) : BleEvent()
    data class RssiUpdated(val address: String, val rssi: Int) : BleEvent()
    data class Disconnected(val address: String) : BleEvent()
    data class Error(val address: String?, val message: String) : BleEvent()
    data class Ready(val address: String) : BleEvent()
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
    private val gattMap = java.util.concurrent.ConcurrentHashMap<String, BluetoothGatt>()
    private var currentDevice: BluetoothDevice? = null
    
    // デバイスごとのフラグ管理
    private val isBsc200sMap = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    private val mtuNegotiatedMap = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    
    private var reconnectionJob: kotlinx.coroutines.Job? = null
    private var retryCount = 0
    private val maxRetryCount = 3

    // iGPSPORT characteristics (Map for each device)
    private val txCharsMap = java.util.concurrent.ConcurrentHashMap<String, MutableMap<String, BluetoothGattCharacteristic>>()
    private val nusRxCharMap = java.util.concurrent.ConcurrentHashMap<String, BluetoothGattCharacteristic>()

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
                        gattMap[deviceAddress] = gatt
                        _connectionState.value = ConnectionState.Connected(gatt.device)

                        // RSSI読取り
                        gatt.readRemoteRssi()

                        // MTUネゴシエーション
                        mtuNegotiatedMap[deviceAddress] = false
                        addLog("Requesting MTU 247 for $deviceAddress...")
                        gatt.requestMtu(247)
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        addLog("Disconnected from $deviceAddress")
                        gattMap.remove(deviceAddress)
                        _connectionState.value = ConnectionState.Disconnected

                        repositoryScope.launch {
                            _events.emit(BleEvent.Disconnected(deviceAddress))
                        }

                        startReconnection(deviceAddress)
                        gatt.close()
                    }
                }
            } else {
                addLog("Connection error for $deviceAddress: status=$status")
                gattMap.remove(deviceAddress)
                _connectionState.value = ConnectionState.Error("GATT Error $status")
                
                repositoryScope.launch {
                    _events.emit(BleEvent.Disconnected(deviceAddress))
                }
                gatt.close()
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            val address = gatt.device.address
            if (status == BluetoothGatt.GATT_SUCCESS) {
                addLog("MTU negotiated for $address: $mtu bytes")
                mtuNegotiatedMap[address] = true
            } else {
                addLog("MTU negotiation failed for $address: status=$status")
            }
            repositoryScope.launch {
                delay(300)
                gatt.discoverServices()
            }
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
            val address = gatt.device.address
            if (status == BluetoothGatt.GATT_SUCCESS) {
                addLog("RSSI for $address: $rssi dBm")
                _rssi.value = rssi
                repositoryScope.launch {
                    _events.emit(BleEvent.RssiUpdated(address, rssi))
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val address = gatt.device.address
            if (status == BluetoothGatt.GATT_SUCCESS) {
                addLog("Services discovered successfully for $address")
                analyzeServices(gatt)
            } else {
                addLog("Service discovery failed for $address: status=$status")
                repositoryScope.launch {
                    _events.emit(BleEvent.Error(address, "Service discovery failed"))
                }
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            val address = gatt.device.address
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val uuid = characteristic.uuid.toString()
                val valueStr = value.joinToString(", ") { "0x%02X".format(it) }
                addLog("Read characteristic for $address $uuid: $valueStr")

                if (uuid.equals(BATTERY_LEVEL_UUID, ignoreCase = true) && value.isNotEmpty()) {
                    val level = value[0].toInt() and 0xFF
                    _batteryLevel.value = level
                    addLog("Battery level for $address: $level%")
                    repositoryScope.launch {
                        _events.emit(BleEvent.BatteryLevelChanged(address, level))
                    }
                }

                repositoryScope.launch {
                    _events.emit(BleEvent.CharacteristicRead(address, uuid, valueStr))
                }
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            val address = gatt.device.address
            val uuid = characteristic.uuid.toString()
            val valueStr = value.joinToString(", ") { "0x%02X".format(it) }
            addLog("Characteristic changed for $address $uuid: $valueStr")

            if (uuid.equals(BATTERY_LEVEL_UUID, ignoreCase = true) && value.isNotEmpty()) {
                val level = value[0].toInt() and 0xFF
                _batteryLevel.value = level
                addLog("Battery level changed for $address: $level%")
                repositoryScope.launch {
                    _events.emit(BleEvent.BatteryLevelChanged(address, level))
                }
            }

            if (uuid.contains("6e400003", ignoreCase = true)) {
                if (uuid.equals(NUS_TX_UUID, ignoreCase = true)) {
                    parseBsc200sBatteryResponse(address, value)
                }
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            val address = gatt.device.address
            if (status == BluetoothGatt.GATT_SUCCESS) {
                addLog("Descriptor written successfully for $address")
                repositoryScope.launch {
                    _events.emit(BleEvent.Ready(address))
                }
            } else {
                addLog("Descriptor write failed for $address: status=$status")
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            val address = gatt.device.address
            val uuid = characteristic.uuid.toString()
            if (status == BluetoothGatt.GATT_SUCCESS) {
                addLog("Write successful to $address $uuid")
            } else {
                addLog("Write failed to $address $uuid: status=$status")
            }
        }
    }

    private fun analyzeServices(gatt: BluetoothGatt) {
        val address = gatt.device.address
        val services = gatt.services
        val serviceInfo = mutableListOf<String>()
        val charInfo = mutableMapOf<String, List<String>>()

        addLog("Found ${services.size} services for $address:")

        val deviceName = gatt.device.name ?: ""
        val isBsc200s = deviceName.contains("BSC200", ignoreCase = true) ||
                     deviceName.contains("iGPSPORT", ignoreCase = true)
        isBsc200sMap[address] = isBsc200s
        addLog("Device: $deviceName ($address), isBsc200s=$isBsc200s")

        var hasStandardBattery = false
        val txChars = mutableMapOf<String, BluetoothGattCharacteristic>()
        var nusRxChar: BluetoothGattCharacteristic? = null

        services.forEach { service ->
            val serviceUuid = service.uuid.toString()
            val characteristics = mutableListOf<String>()
            service.characteristics.forEach { characteristic ->
                val charUuid = characteristic.uuid.toString()
                characteristics.add("Characteristic: $charUuid")

                if (charUuid.equals(BATTERY_LEVEL_UUID, ignoreCase = true)) {
                    readBatteryLevel(gatt, characteristic)
                    enableBatteryNotification(gatt, characteristic)
                    hasStandardBattery = true
                }
                if (charUuid.equals(NUS_RX_UUID, ignoreCase = true)) {
                    nusRxChar = characteristic
                }
                if (charUuid.contains("6e400003", ignoreCase = true)) {
                    txChars[charUuid] = characteristic
                }
            }
            charInfo[serviceUuid] = characteristics
            serviceInfo.add("Service: $serviceUuid")
        }

        _discoveredServices.value = serviceInfo
        _characteristicsInfo.value = charInfo
        
        txCharsMap[address] = txChars
        nusRxChar?.let { nusRxCharMap[address] = it }

        repositoryScope.launch {
            _events.emit(BleEvent.ServicesDiscovered(address, serviceInfo))
        }

        if (isBsc200s && !hasStandardBattery) {
            repositoryScope.launch {
                delay(500)
                setupBsc200sBatteryRead(address)
            }
        }
    }

    private fun setupBsc200sBatteryRead(address: String) {
        val gatt = gattMap[address] ?: return
        val txChars = txCharsMap[address] ?: return

        repositoryScope.launch {
            txChars.forEach { (uuid, char) ->
                gatt.setCharacteristicNotification(char, true)
                val cccd = char.getDescriptor(UUID.fromString(CCCD_UUID))
                if (cccd != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                    } else {
                        @Suppress("DEPRECATION")
                        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        gatt.writeDescriptor(cccd)
                    }
                    delay(300)
                }
            }
            delay(2000)
            sendBsc200sBatteryRequest(address)
        }
    }

    private fun sendBsc200sBatteryRequest(address: String) {
        val gatt = gattMap[address] ?: return
        val rxChar = nusRxCharMap[address] ?: return
        val request = buildBatteryRequest()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(rxChar, request, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
        } else {
            @Suppress("DEPRECATION")
            rxChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            @Suppress("DEPRECATION")
            rxChar.value = request
            gatt.writeCharacteristic(rxChar)
        }
    }

    private fun buildBatteryRequest(): ByteArray {
        val payload = byteArrayOf(0x08, 0x0B, 0x10, 0x06)
        val payloadSize = payload.size
        val payloadCrc = crc8Maxim(payload)
        val header = ByteArray(20)
        header[0] = 0x01
        header[1] = 0x0B
        header[2] = 0xFF.toByte()
        header[3] = 0xFF.toByte()
        header[4] = 0x06
        header[5] = 0xFF.toByte()
        header[6] = 0xFF.toByte()
        header[7] = (payloadSize shr 8).toByte()
        header[8] = payloadSize.toByte()
        header[9] = payloadCrc
        header[10] = 0x00
        for (i in 11..18) { header[i] = 0xFF.toByte() }
        header[19] = crc8Maxim(header.copyOfRange(0, 19))
        return header + payload
    }

    private fun crc8Maxim(data: ByteArray): Byte {
        var crc = 0
        for (b in data) {
            crc = crc xor (b.toInt() and 0xFF)
            for (i in 0 until 8) {
                crc = if (crc and 0x01 != 0) {
                    (crc shr 1) xor 0x8C
                } else {
                    crc shr 1
                }
            }
        }
        return crc.toByte()
    }

    private fun parseBsc200sBatteryResponse(address: String, data: ByteArray) {
        if (data.size < 20) return
        val payload = data.copyOfRange(20, data.size)
        val pattern = byteArrayOf(0x08, 0x0B, 0x10, 0x06)
        val idx = findPattern(payload, pattern)
        if (idx >= 0) {
            // パターンの後にある 0x10 [Level] を探す (ペイロード内の位置が変動することがあるため)
            var levelIdx = -1
            for (i in (idx + 4) until payload.size - 1) {
                if (payload[i] == 0x10.toByte()) {
                    levelIdx = i
                    break
                }
            }

            if (levelIdx >= 0 && payload.size > levelIdx + 1) {
                val level = payload[levelIdx + 1].toInt() and 0xFF
                addLog("Battery level for $address parsed: $level%")
                _batteryLevel.value = level
                repositoryScope.launch {
                    _events.emit(BleEvent.BatteryLevelChanged(address, level))
                    gattMap[address]?.let { gatt ->
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            addLog("Requesting LOW_POWER for $address after successful data retrieval")
                            gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER)
                        }
                    }
                }
            }
        }
    }

    private fun findPattern(data: ByteArray, pattern: ByteArray): Int {
        for (i in 0..data.size - pattern.size) {
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

    private fun readBatteryLevel(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        gatt.readCharacteristic(characteristic)
    }

    private fun enableBatteryNotification(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        gatt.setCharacteristicNotification(characteristic, true)
        val descriptor = characteristic.getDescriptor(UUID.fromString(CCCD_UUID))
        if (descriptor != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(descriptor)
            }
        }
    }

    fun connect(device: BluetoothDevice) {
        val address = device.address
        _connectionState.value = ConnectionState.Connecting
        currentDevice = device
        repositoryScope.launch {
            delay(1000)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    gattMap[address] = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
                } else {
                    gattMap[address] = device.connectGatt(context, false, gattCallback)
                }
            } catch (e: SecurityException) {
                _connectionState.value = ConnectionState.Error("Permission denied")
            }
        }
    }

    fun disconnect() {
        gattMap.forEach { (_, gatt) -> gatt.disconnect() }
    }

    fun disconnect(address: String) {
        gattMap[address]?.disconnect()
    }

    fun close() {
        gattMap.forEach { (_, gatt) -> gatt.close() }
        gattMap.clear()
        isBsc200sMap.clear()
        mtuNegotiatedMap.clear()
        txCharsMap.clear()
        nusRxCharMap.clear()
    }

    private fun startReconnection(deviceAddress: String) { /* Skip for brevity */ }
    fun readRssi() { gattMap.values.forEach { it.readRemoteRssi() } }
    fun clearLogs() { _logs.value = emptyList() }
}
