package com.guykn.smartchessboard2.bluetooth.companiondevice

import android.bluetooth.BluetoothDevice
import com.guykn.smartchessboard2.ServiceConnector
import com.guykn.smartchessboard2.bluetooth.BluetoothManager
import com.guykn.smartchessboard2.bluetooth.ChessBoardModel
import dagger.hilt.android.scopes.ActivityRetainedScoped
import javax.inject.Inject
import javax.inject.Singleton

@ActivityRetainedScoped
class CompanionDeviceCallbacks @Inject constructor(private val serviceConnector: ServiceConnector) :
    CompanionDeviceConnector.DeviceCallback, CompanionDeviceConnector.BluetoothStateCallback {

    override fun setTargetDevice(bluetoothDevice: BluetoothDevice?) {
        serviceConnector.callWhenConnected { repository ->
            repository.setTargetBluetoothDevice(bluetoothDevice)
        }
    }

    override fun onBluetoothStateChanged(bluetoothState: ChessBoardModel.BluetoothState) {
        serviceConnector.callWhenConnected { repository ->
            repository.chessBoardModel.setBluetoothState(bluetoothState)
        }
    }
}