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
    data class Connected(val device: BluetoothDevice) : BleEvent()
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
        const val BATTERY_SERVICE_UUID = "0000180F-0000-1000-8000-00805F9B34FB"
        const val BATTERY_LEVEL_UUID = "00002A19-0000-1000-8000-00805F9B34FB"
        const val CCCD_UUID = "00002902-0000-1000-8000-00805F9B34FB"
        const val NUS_SERVICE_UUID = "6e400001-b5a3-f393-e0a9-e50e24dcca9e"
        const val NUS_RX_UUID = "6e400002-b5a3-f393-e0a9-e50e24dcca9e"
        const val NUS_TX_UUID = "6e400003-b5a3-f393-e0a9-e50e24dcca9e"
    }

    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gattMap = java.util.concurrent.ConcurrentHashMap<String, BluetoothGatt>()
    private val isBsc200sMap = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    private val txCharsMap = java.util.concurrent.ConcurrentHashMap<String, MutableMap<String, BluetoothGattCharacteristic>>()
    private val nusRxCharMap = java.util.concurrent.ConcurrentHashMap<String, BluetoothGattCharacteristic>()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState = _connectionState.asStateFlow()

    private val _events = MutableSharedFlow<BleEvent>()
    val events = _events.asSharedFlow()

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
                        repositoryScope.launch {
                            _events.emit(BleEvent.Connected(gatt.device))
                        }
                        gatt.readRemoteRssi()
                        addLog("Requesting MTU 247 for $deviceAddress...")
                        gatt.requestMtu(247)
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        addLog("Disconnected from $deviceAddress")
                        gattMap.remove(deviceAddress)
                        repositoryScope.launch {
                            _events.emit(BleEvent.Disconnected(deviceAddress))
                        }
                        gatt.close()
                    }
                }
            } else {
                addLog("Connection error for $deviceAddress: status=$status")
                gattMap.remove(deviceAddress)
                repositoryScope.launch {
                    _events.emit(BleEvent.Disconnected(deviceAddress))
                }
                gatt.close()
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            val address = gatt.device.address
            addLog("MTU for $address: $mtu bytes (status=$status)")
            repositoryScope.launch {
                delay(300)
                gatt.discoverServices()
            }
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                repositoryScope.launch {
                    _events.emit(BleEvent.RssiUpdated(gatt.device.address, rssi))
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val address = gatt.device.address
            if (status == BluetoothGatt.GATT_SUCCESS) {
                addLog("Services discovered for $address")
                analyzeServices(gatt)
            }
        }

        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            val address = gatt.device.address
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val uuid = characteristic.uuid.toString()
                if (uuid.equals(BATTERY_LEVEL_UUID, ignoreCase = true) && value.isNotEmpty()) {
                    val level = value[0].toInt() and 0xFF
                    repositoryScope.launch {
                        _events.emit(BleEvent.BatteryLevelChanged(address, level))
                    }
                }
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            val address = gatt.device.address
            val uuid = characteristic.uuid.toString()
            if (uuid.equals(BATTERY_LEVEL_UUID, ignoreCase = true) && value.isNotEmpty()) {
                val level = value[0].toInt() and 0xFF
                repositoryScope.launch {
                    _events.emit(BleEvent.BatteryLevelChanged(address, level))
                }
            } else if (uuid.equals(NUS_TX_UUID, ignoreCase = true)) {
                parseBsc200sBatteryResponse(address, value)
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                repositoryScope.launch {
                    _events.emit(BleEvent.Ready(gatt.device.address))
                }
            }
        }
    }

    private fun analyzeServices(gatt: BluetoothGatt) {
        val address = gatt.device.address
        val services = gatt.services
        val deviceName = gatt.device.name ?: ""
        val isBsc200s = deviceName.contains("BSC200", ignoreCase = true) || deviceName.contains("iGPSPORT", ignoreCase = true)
        isBsc200sMap[address] = isBsc200s

        var hasStandardBattery = false
        val txChars = mutableMapOf<String, BluetoothGattCharacteristic>()
        var nusRxChar: BluetoothGattCharacteristic? = null

        services.forEach { service ->
            service.characteristics.forEach { characteristic ->
                val charUuid = characteristic.uuid.toString()
                if (charUuid.equals(BATTERY_LEVEL_UUID, ignoreCase = true)) {
                    gatt.readCharacteristic(characteristic)
                    enableBatteryNotification(gatt, characteristic)
                    hasStandardBattery = true
                }
                if (charUuid.equals(NUS_RX_UUID, ignoreCase = true)) nusRxChar = characteristic
                if (charUuid.contains("6e400003", ignoreCase = true)) txChars[charUuid] = characteristic
            }
        }
        
        txCharsMap[address] = txChars
        nusRxChar?.let { nusRxCharMap[address] = it }

        repositoryScope.launch {
            _events.emit(BleEvent.ServicesDiscovered(address, services.map { it.uuid.toString() }))
            if (isBsc200s && !hasStandardBattery) {
                delay(500)
                setupBsc200sBatteryRead(address)
            }
        }
    }

    private fun setupBsc200sBatteryRead(address: String) {
        val gatt = gattMap[address] ?: return
        val txChars = txCharsMap[address] ?: return
        repositoryScope.launch {
            txChars.forEach { (_, char) ->
                gatt.setCharacteristicNotification(char, true)
                char.getDescriptor(UUID.fromString(CCCD_UUID))?.let { cccd ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                    } else {
                        @Suppress("DEPRECATION")
                        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        gatt.writeDescriptor(cccd)
                    }
                }
                delay(300)
            }
            delay(2000)
            sendBsc200sBatteryRequest(address)
        }
    }

    private fun sendBsc200sBatteryRequest(address: String) {
        val gatt = gattMap[address] ?: return
        val rxChar = nusRxCharMap[address] ?: return
        val payload = byteArrayOf(0x08, 0x0B, 0x10, 0x06)
        val payloadCrc = crc8Maxim(payload)
        val header = ByteArray(20).apply {
            this[0] = 0x01; this[1] = 0x0B; this[2] = 0xFF.toByte(); this[3] = 0xFF.toByte()
            this[4] = 0x06; this[5] = 0xFF.toByte(); this[6] = 0xFF.toByte()
            this[7] = 0; this[8] = payload.size.toByte(); this[9] = payloadCrc; this[10] = 0
            for (i in 11..18) this[i] = 0xFF.toByte()
            this[19] = crc8Maxim(this.copyOfRange(0, 19))
        }
        val request = header + payload
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

    private fun crc8Maxim(data: ByteArray): Byte {
        var crc = 0
        for (b in data) {
            crc = crc xor (b.toInt() and 0xFF)
            for (i in 0 until 8) {
                crc = if (crc and 0x01 != 0) (crc shr 1) xor 0x8C else crc shr 1
            }
        }
        return crc.toByte()
    }

    private fun parseBsc200sBatteryResponse(address: String, data: ByteArray) {
        if (data.size < 20) return
        val payload = data.copyOfRange(20, data.size)
        val pattern = byteArrayOf(0x08, 0x0B, 0x10, 0x06)
        var idx = -1
        for (i in 0..payload.size - pattern.size) {
            if (payload.copyOfRange(i, i + pattern.size).contentEquals(pattern)) { idx = i; break }
        }
        if (idx >= 0) {
            var levelIdx = -1
            for (i in (idx + 4) until payload.size - 1) {
                if (payload[i] == 0x10.toByte()) { levelIdx = i; break }
            }
            if (levelIdx >= 0 && payload.size > levelIdx + 1) {
                val level = payload[levelIdx + 1].toInt() and 0xFF
                repositoryScope.launch {
                    _events.emit(BleEvent.BatteryLevelChanged(address, level))
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        gattMap[address]?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER)
                    }
                }
            }
        }
    }

    private fun enableBatteryNotification(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        gatt.setCharacteristicNotification(characteristic, true)
        characteristic.getDescriptor(UUID.fromString(CCCD_UUID))?.let { cccd ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION")
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(cccd)
            }
        }
    }

    fun connect(device: BluetoothDevice) {
        val address = device.address
        if (gattMap.containsKey(address)) return
        repositoryScope.launch {
            delay(100)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                // autoConnect = true にすることで、OSレベルでアドバタイズを見つけ次第接続されるようにする
                gattMap[address] = device.connectGatt(context, true, gattCallback, BluetoothDevice.TRANSPORT_LE)
            } else {
                gattMap[address] = device.connectGatt(context, true, gattCallback)
            }
        }
    }

    fun disconnect(address: String) { gattMap[address]?.disconnect() }
    fun close() { gattMap.forEach { (_, g) -> g.close() }; gattMap.clear() }
    fun clearLogs() { _logs.value = emptyList() }
}
