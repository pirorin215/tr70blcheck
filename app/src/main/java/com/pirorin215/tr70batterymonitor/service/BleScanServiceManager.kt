package com.pirorin215.tr70batterymonitor.service

import android.bluetooth.BluetoothDevice
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

data class ScannedDevice(
    val device: BluetoothDevice,
    val rssi: Int
)

object BleScanServiceManager {
    private val _deviceFoundChannel = Channel<ScannedDevice>(capacity = Channel.CONFLATED)
    val deviceFoundFlow: Flow<ScannedDevice> = _deviceFoundChannel.receiveAsFlow()

    private val _restartScanChannel = Channel<Unit>(capacity = Channel.CONFLATED)
    val restartScanFlow: Flow<Unit> = _restartScanChannel.receiveAsFlow()

    suspend fun emitDeviceFound(device: BluetoothDevice, rssi: Int) {
        _deviceFoundChannel.trySend(ScannedDevice(device, rssi))
    }

    suspend fun emitRestartScan() {
        _restartScanChannel.trySend(Unit)
    }
}
