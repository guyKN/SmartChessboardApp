package com.guykn.smartchessboard2.bluetooth

import android.bluetooth.BluetoothDevice
import android.util.Log
import com.guykn.smartchessboard2.ClientToServerMessage
import com.guykn.smartchessboard2.bluetooth.ChessBoardModel.*
import com.guykn.smartchessboard2.bluetooth.ChessBoardModel.BluetoothState.*
import dagger.hilt.android.scopes.ServiceScoped
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import java.io.IOException
import javax.inject.Inject

@ServiceScoped
class BluetoothManager @Inject constructor(
    private val coroutineScope: CoroutineScope,
    private val chessBoardModel: ChessBoardModel
) {

    companion object {
        const val TAG = "MA_BluetoothManager"
    }

    private var bluetoothConnection: BluetoothConnection? = null
    private var bluetoothConnectionJob: Job? = null

    fun setTargetDevice(bluetoothDevice: BluetoothDevice?) {
        Log.d(TAG, "Setting target device: ${bluetoothDevice?.name}")
        if (bluetoothDevice == null) {
            bluetoothConnection?.close()
        } else if (bluetoothConnection?.bluetoothDevice != bluetoothDevice || bluetoothConnection?.isBluetoothActive != true) {
            bluetoothConnectionJob?.cancel()
            bluetoothConnection?.close()
            bluetoothConnectionJob = coroutineScope.launch {
                connectLoop(bluetoothDevice)
            }
        }
    }


    private suspend fun connectLoop(bluetoothDevice: BluetoothDevice) =
        withContext(Dispatchers.Main.immediate) {
            // TODO: 6/30/2021 This might waste a lot of hardware resources, since it continuously scans for bluetooth device. Stop and wait for the user to choose to retry.
            while(true) {
                Log.d(TAG, "Connecting to device: ${bluetoothDevice.name}")
                try {
                    yield()
                    BluetoothConnection(bluetoothDevice).use { bluetoothConnector ->
                        this@BluetoothManager.bluetoothConnection = bluetoothConnector
                        chessBoardModel.setBluetoothState(CONNECTING)
                        bluetoothConnector.connect()
                        yield()
                        chessBoardModel.setBluetoothState(CONNECTED)
                        bluetoothConnector.serverMessageFlow.collect { message ->
                            chessBoardModel.update(message)
                        }
                    }
                } catch (e: IOException) {
                    Log.w(
                        TAG,
                        "Exception occurred while connecting or connected to bluetooth: ${e.message}"
                    )
                    delay(2500)
                } finally {
                    yield()
                    chessBoardModel.setBluetoothState(DISCONNECTED)
                }
            }
        }

    @Throws(IOException::class)
    suspend fun writeMessage(message: ClientToServerMessage) {
        bluetoothConnection?.write(message)
            ?: throw IOException("tried to write message while not connected via bluetooth. ")

    }


    fun destroy() {
        bluetoothConnection?.close()
    }
}