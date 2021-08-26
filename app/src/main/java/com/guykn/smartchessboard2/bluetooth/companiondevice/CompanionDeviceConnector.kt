package com.guykn.smartchessboard2.bluetooth.companiondevice

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.*
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.annotation.RequiresApi
import com.guykn.smartchessboard2.bluetooth.BluetoothConstants.CHESS_BOARD_DEVICE_NAME
import com.guykn.smartchessboard2.bluetooth.BluetoothConstants.CHESS_BOARD_UUID
import com.guykn.smartchessboard2.bluetooth.ChessBoardModel
import dagger.hilt.android.qualifiers.ActivityContext
import dagger.hilt.android.scopes.ActivityScoped
import java.util.regex.Pattern
import javax.inject.Inject

@ActivityScoped
@RequiresApi(api = Build.VERSION_CODES.O)
class CompanionDeviceConnector @Inject constructor(
    @ActivityContext private val context: Context,
    private val deviceCallback: DeviceCallback,
    private val intentCallback: IntentCallback,
    private val bluetoothStateCallback: BluetoothStateCallback
) {

    interface IntentCallback {
        fun onIntentFound(intentSender: IntentSender)
    }

    interface DeviceCallback {
        fun setTargetDevice(bluetoothDevice: BluetoothDevice?)
    }

    interface BluetoothStateCallback {
        fun onBluetoothStateChanged(bluetoothState: ChessBoardModel.BluetoothState)
    }

    companion object {
        const val TAG = "MA_CompanionConn"

        fun shouldRequestEnableBluetooth(): Boolean {
            return when(BluetoothAdapter.getDefaultAdapter().state){
                BluetoothAdapter.STATE_ON, BluetoothAdapter.STATE_TURNING_ON->false
                else->true
            }
        }

        fun nameForBluetoothState(state: Int): String {
            return when (state) {
                BluetoothAdapter.STATE_OFF -> "OFF"
                BluetoothAdapter.STATE_TURNING_ON -> "TURNING_ON"
                BluetoothAdapter.STATE_ON -> "ON"
                BluetoothAdapter.STATE_TURNING_OFF -> "TURNING_OFF"
                else -> "?!?!? ($state)"
            }
        }

    }

    private val companionDeviceManager: CompanionDeviceManager = context.getSystemService(
        CompanionDeviceManager::class.java
    )

    private var isScanning = false
    private var isDestroyed = false
    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()

    private val companionDeviceCallback: CompanionDeviceManager.Callback =
        object : CompanionDeviceManager.Callback() {
            override fun onDeviceFound(chooserLauncher: IntentSender) {
                Log.d(TAG, "Bluetooth Scan sucsess")
                isScanning = false
                bluetoothStateCallback.onBluetoothStateChanged(ChessBoardModel.BluetoothState.REQUESTING_USER_INPUT)
                if (!isDestroyed) {
                    Log.i(TAG, "found device to connect with. ")
                    intentCallback.onIntentFound(chooserLauncher)
                } else {
                    Log.w(TAG, "found device to connect with while destroyed. ")
                }
            }

            override fun onFailure(error: CharSequence) {
                Log.d(TAG, "Bluetooth Scan failed")
                isScanning = false
                bluetoothStateCallback.onBluetoothStateChanged(ChessBoardModel.BluetoothState.SCAN_FAILED)
            }
        }

    private val bluetoothEventBroadcastReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            if (BluetoothAdapter.ACTION_STATE_CHANGED == action) {
                val connectionState =
                    intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_OFF)
                Log.d(TAG, "Current bluetooth state on bluetooth adapter: ${nameForBluetoothState(connectionState)}")
                refreshBluetoothDevice()

            } else if (BluetoothDevice.ACTION_BOND_STATE_CHANGED == action) {
                val bluetoothDevice =
                    intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                val bondState =
                    intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
                val prevBondState =
                    intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
                refreshBluetoothDevice()
            }
        }
    }

    init {
        val intentFilter = IntentFilter()
        intentFilter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        intentFilter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        context.registerReceiver(bluetoothEventBroadcastReceiver, intentFilter)
    }

    fun destroy() {
        isDestroyed = true
        context.unregisterReceiver(bluetoothEventBroadcastReceiver)
        bluetoothAdapter?.cancelDiscovery()
    }

    fun refreshBluetoothDevice() {
        Log.d(TAG, "refreshing bluetooth Device")
        if (bluetoothAdapter == null) {
            bluetoothStateCallback.onBluetoothStateChanged(ChessBoardModel.BluetoothState.BLUETOOTH_NOT_SUPPORTED)
            deviceCallback.setTargetDevice(null)
            return
        }
        when (val bluetoothState = bluetoothAdapter.state) {
            BluetoothAdapter.STATE_OFF, BluetoothAdapter.STATE_TURNING_OFF -> {
                bluetoothStateCallback.onBluetoothStateChanged(ChessBoardModel.BluetoothState.BLUETOOTH_NOT_ENABLED)
                deviceCallback.setTargetDevice(null)
                return
            }
            BluetoothAdapter.STATE_TURNING_ON -> {
                bluetoothStateCallback.onBluetoothStateChanged(ChessBoardModel.BluetoothState.BLUETOOTH_TURNING_ON)
                return
            }
            BluetoothAdapter.STATE_ON -> {
                // doesn't return, continue below
            }
            else -> throw IllegalArgumentException("bluetoothAdapter.getState() returned illegal value: $bluetoothState")
        }
        val bluetoothDevice = pairedChessboardDevice()
        if (bluetoothDevice == null) {
            Log.d(TAG, "pairedChessboardDevice is null scanning for companion. ")
            deviceCallback.setTargetDevice(null)
            scanForBluetoothCompanion()
            return
        }
        Log.d(TAG, "pairedChessboardDevice is ${bluetoothDevice.name}. ")
        when (val bondState = bluetoothDevice.bondState) {
            BluetoothDevice.BOND_NONE -> {
                removeAssociatedDevices()
                bluetoothStateCallback.onBluetoothStateChanged(ChessBoardModel.BluetoothState.SCAN_FAILED)
                deviceCallback.setTargetDevice(null)
                return
            }
            BluetoothDevice.BOND_BONDING -> {
                bluetoothStateCallback.onBluetoothStateChanged(ChessBoardModel.BluetoothState.PAIRING)
                deviceCallback.setTargetDevice(null)
                return
            }
            BluetoothDevice.BOND_BONDED -> {
                deviceCallback.setTargetDevice(bluetoothDevice)
                return
            }
            else -> throw IllegalArgumentException("bluetoothDevice.getBondState() returned an illegal value of $bondState")
        }
    }

    fun onDeviceSelected(bluetoothDevice: BluetoothDevice) {
        // remove all previously associated bluetoothDevices, so that only the new device remains
        val associatedDevices = companionDeviceManager.associations
        for (deviceMacAddress in associatedDevices) {
            if (bluetoothDevice.address != deviceMacAddress) {
                companionDeviceManager.disassociate(deviceMacAddress)
            }
        }
        if (bluetoothDevice.bondState != BluetoothDevice.BOND_NONE) {
            Log.w(TAG, "onDeviceSelected on an already bonding or bonded device. ")
            refreshBluetoothDevice()
            return
        }
        if (!bluetoothDevice.createBond()) {
            Log.w(TAG, "Failed to create bond with bluetooth device.")
            bluetoothStateCallback.onBluetoothStateChanged(ChessBoardModel.BluetoothState.SCAN_FAILED)
        }
    }


    private fun pairedChessboardDevice(): BluetoothDevice? {
        return bluetoothAdapter?.let {
            val associatedDevices = companionDeviceManager.associations
            if (associatedDevices.size == 0) {
                null
            } else {
                bluetoothAdapter.getRemoteDevice(associatedDevices[0])
            }
        }
    }

    private fun scanForBluetoothCompanion() {
        if (!isScanning) {
            removeAssociatedDevices()
            Log.d(TAG, "scanning for bluetooth devices. ")
            isScanning = true
            bluetoothStateCallback.onBluetoothStateChanged(ChessBoardModel.BluetoothState.SCANNING)
            val bluetoothDeviceFilter = BluetoothDeviceFilter.Builder()
// searching using uuid like below doesn't seem to work, maybe because raspberry pi is not set up properly or because of a bug in android.
//                .addServiceUuid(ParcelUuid(CHESS_BOARD_UUID), null)
                .setNamePattern(Pattern.compile(CHESS_BOARD_DEVICE_NAME))
                .build()
            val associationRequest = AssociationRequest.Builder()
                .addDeviceFilter(bluetoothDeviceFilter)
                .setSingleDevice(true)
                .build()
            companionDeviceManager.associate(associationRequest, companionDeviceCallback, null)
        } else {
            Log.w(TAG, "trying to scan for devices while already scanning for devices. ")
        }
    }

    private fun removeAssociatedDevices() {
        for (deviceMacAddress in companionDeviceManager.associations) {
            companionDeviceManager.disassociate(deviceMacAddress)
        }
    }
}