package com.pirorin215.tr70batterymonitor.data

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

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
    data class Error(val message: String) : BleEvent()
    object Ready : BleEvent()
}

@SuppressLint("MissingPermission")
class BleRepository(private val context: Context) {

    companion object {
        const val TAG = "BleRepository"

        // Standard UUIDs
        const val BATTERY_SERVICE_UUID = "0000180F-0000-1000-8000-00805F9B34FB"
        const val BATTERY_LEVEL_UUID = "00002A19-0000-1000-8000-00805F9B34FB"
        const val CCCD_UUID = "00002902-0000-1000-8000-00805F9B34FB"

        // Garmin custom UUIDs (to be discovered)
        const val GARMIN_SERVICE_UUID_PREFIX = "0000"
        const val GARMIN_SERVICE_UUID_SUFFIX = "-0000-1000-8000-00805F9B34FB"
    }

    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var bluetoothGatt: BluetoothGatt? = null

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

    // Log storage
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs = _logs.asStateFlow()

    private fun addLog(message: String) {
        Log.d(TAG, message)
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        val newLogs = _logs.value.toMutableList()
        newLogs.add("[$timestamp] $message")
        if (newLogs.size > 100) newLogs.removeAt(0) // Keep last 100 logs
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
                        repositoryScope.launch {
                            delay(500)
                            gatt.discoverServices()
                        }
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

                // Check if this is battery level
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

            // Check if this is battery level notification
            if (uuid.equals(BATTERY_LEVEL_UUID, ignoreCase = true) && value.isNotEmpty()) {
                val level = value[0].toInt() and 0xFF
                _batteryLevel.value = level
                addLog("Battery level changed: $level%")
                repositoryScope.launch {
                    _events.emit(BleEvent.BatteryLevelChanged(level))
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
    }

    private fun analyzeServices(gatt: BluetoothGatt) {
        val services = gatt.services
        val serviceInfo = mutableListOf<String>()
        val charInfo = mutableMapOf<String, List<String>>()

        addLog("Found ${services.size} services:")

        services.forEach { service ->
            val serviceUuid = service.uuid.toString()
            val serviceInfoStr = "Service: $serviceUuid"
            serviceInfo.add(serviceInfoStr)
            addLog("  - $serviceInfoStr")

            // Check for known services
            when {
                serviceUuid.equals(BATTERY_SERVICE_UUID, ignoreCase = true) -> {
                    addLog("    -> Standard Battery Service!")
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
                serviceUuid.contains("6406") -> {
                    addLog("    -> Possible Garmin custom service!")
                }
                else -> {
                    addLog("    -> Custom/Unknown service")
                }
            }

            val characteristics = mutableListOf<String>()
            service.characteristics.forEach { characteristic ->
                val charUuid = characteristic.uuid.toString()
                val props = getPropertiesString(characteristic)
                val charInfoStr = "    Characteristic: $charUuid [$props]"
                characteristics.add(charInfoStr)
                addLog("    - $charInfoStr")

                // Check for battery level characteristic
                if (charUuid.equals(BATTERY_LEVEL_UUID, ignoreCase = true)) {
                    addLog("      -> Battery Level characteristic found!")
                    // Read battery level
                    readBatteryLevel(characteristic)
                    // Enable notifications
                    enableBatteryNotification(characteristic)
                }
            }
            charInfo[serviceUuid] = characteristics
        }

        _discoveredServices.value = serviceInfo
        _characteristicsInfo.value = charInfo

        repositoryScope.launch {
            _events.emit(BleEvent.ServicesDiscovered(serviceInfo))
        }
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

        try {
            bluetoothGatt = device.connectGatt(context, false, gattCallback)
        } catch (e: SecurityException) {
            addLog("Security exception: ${e.message}")
            _connectionState.value = ConnectionState.Error("Permission denied")
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
        _connectionState.value = ConnectionState.Disconnected
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }
}
