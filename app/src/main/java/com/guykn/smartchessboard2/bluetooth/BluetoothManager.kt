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
import kotlin.system.measureTimeMillis

// todo: try automatically reconnect if bluetooth disconnects

@ServiceScoped
class BluetoothManager @Inject constructor(
    private val coroutineScope: CoroutineScope,
    private val chessBoardModel: ChessBoardModel,
    private val pgnFilesCallback: dagger.Lazy<PgnFilesCallback>
) {

    // used to mark that a specific IOException ocoured while connected to bluetooth, rather than while connecting.
    class IOExceptionWhileConnected(source: IOException) : IOException(source)

    companion object {
        const val TAG = "MA_BluetoothManager"
        const val NUM_CONNECTION_ATTEMPTS = -1 // -1 means to retry to connect infinitely many times
        const val CONNECTION_FAIL_DELAY_INCREMENT: Long = 5 * 1000
        const val MIN_DURATION_FOR_SUCCESS = 30 * 1000
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


    private suspend fun connectLoop(bluetoothDevice: BluetoothDevice): Unit =
        withContext(Dispatchers.Main.immediate) {
            Log.d(TAG, "Connecting to device: ${bluetoothDevice.name}")
            var numConnectErrors = 0
            // if NUM_CONNECTION_ATTEMPTS is -1, then, we retry the connection infinitely many times.
            while (NUM_CONNECTION_ATTEMPTS == -1 || (numConnectErrors < NUM_CONNECTION_ATTEMPTS)) {
                if (isBluetoothEnabled()) {
                    yield()
                    val connectedDuration = connectAndRead(bluetoothDevice)
                    if (connectedDuration > MIN_DURATION_FOR_SUCCESS) {
                        numConnectErrors = 0
                    }
                    yield()
                    if (numConnectErrors != 0) {
                        // for the first connection error, we try to reconnect right away, so no need to show a connection error.
                        chessBoardModel.setBluetoothState(
                            if (isBluetoothEnabled()) {
                                DISCONNECTED
                            } else {
                                BLUETOOTH_NOT_ENABLED
                            }
                        )
                    }
                }
                delay(CONNECTION_FAIL_DELAY_INCREMENT * numConnectErrors)
                numConnectErrors++
            }
        }

    // Connects the chessboard by bluetooth, and reads all messages from the chessboard.
    // returns the duration for how long the chessboard was connected (not connecting).
    private suspend fun connectAndRead(bluetoothDevice: BluetoothDevice): Long {
        try {
            Log.d(TAG, "Running connectAndRead for device: ${bluetoothDevice.name}")
            BluetoothConnection(bluetoothDevice).use { bluetoothConnection ->
                this@BluetoothManager.bluetoothConnection = bluetoothConnection
                chessBoardModel.setBluetoothState(CONNECTING)
                bluetoothConnection.connect()
                yield()
                chessBoardModel.setBluetoothState(CONNECTED)
                return measureTimeMillis {
                    try {
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
                    } catch (e: IOException) {
                        Log.w(TAG, "IOException while connected to bluetooth: ${e.message}")
                    }
                }
            }
        } catch (e: IOException) {
            Log.w(TAG, "Exception trying to connect to bluetooth: ${e.message}")
            return 0
        }
    }

    private fun isBluetoothEnabled(): Boolean {
        return BluetoothAdapter.getDefaultAdapter()?.state == BluetoothAdapter.STATE_ON
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