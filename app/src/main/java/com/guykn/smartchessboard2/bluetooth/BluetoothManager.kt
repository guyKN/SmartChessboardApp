package com.guykn.smartchessboard2.bluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.util.Log
import com.guykn.smartchessboard2.bluetooth.BluetoothConstants.ServerToClientActions
import com.guykn.smartchessboard2.bluetooth.ChessBoardModel.*
import com.guykn.smartchessboard2.bluetooth.ChessBoardModel.BluetoothState.*
import dagger.hilt.android.scopes.ServiceScoped
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import java.io.IOException
import javax.inject.Inject

// todo: try automatically reconnect if bluetooth disconnects

@ServiceScoped
class BluetoothManager @Inject constructor(
    private val coroutineScope: CoroutineScope,
    private val chessBoardModel: ChessBoardModel,
    private val pgnFilesCallback: dagger.Lazy<PgnFilesCallback>
) {

    companion object {
        const val TAG = "MA_BluetoothManager"
        const val NUM_CONNECTION_ATTEMPTS = 3
    }


    interface PgnFilesCallback {
        fun onPgnFilesSent(messageData: String)
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
            Log.d(TAG, "Connecting to device: ${bluetoothDevice.name}")
            try {
                yield()
                BluetoothConnection(bluetoothDevice).use { bluetoothConnection ->
                    this@BluetoothManager.bluetoothConnection = bluetoothConnection
                    chessBoardModel.setBluetoothState(CONNECTING)
                    bluetoothConnection.connect()
                    yield()
                    chessBoardModel.setBluetoothState(CONNECTED)
                    bluetoothConnection.serverMessageFlow.collect { message ->
                        when (message.action) {
                            ServerToClientActions.STATE_CHANGED -> {
                                chessBoardModel.update(message.data)
                            }
                            ServerToClientActions.RET_PGN_FILES -> {
                                pgnFilesCallback.get().onPgnFilesSent(message.data)
                            }
                        }
                    }
                }
            } catch (e: IOException) {
                Log.w(
                    TAG,
                    "Exception occurred while connecting or connected to bluetooth: ${e.message}"
                )
            } finally {
                yield()
                chessBoardModel.setBluetoothState(
                    if (BluetoothAdapter.getDefaultAdapter()?.state == BluetoothAdapter.STATE_ON){
                        DISCONNECTED
                    }else {
                        BLUETOOTH_NOT_ENABLED
                    }
                )
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