package com.pirorin215.tr70batterymonitor.service

import android.bluetooth.BluetoothDevice
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

object BleScanServiceManager {
    private val _deviceFoundChannel = Channel<BluetoothDevice>(capacity = Channel.CONFLATED)
    val deviceFoundFlow: Flow<BluetoothDevice> = _deviceFoundChannel.receiveAsFlow()

    private val _restartScanChannel = Channel<Unit>(capacity = Channel.CONFLATED)
    val restartScanFlow: Flow<Unit> = _restartScanChannel.receiveAsFlow()

    suspend fun emitDeviceFound(device: BluetoothDevice) {
        _deviceFoundChannel.trySend(device)
    }

    suspend fun emitRestartScan() {
        _restartScanChannel.trySend(Unit)
    }
}
