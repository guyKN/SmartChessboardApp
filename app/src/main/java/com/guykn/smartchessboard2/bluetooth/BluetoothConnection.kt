package com.guykn.smartchessboard2.bluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import com.guykn.smartchessboard2.bluetooth.BluetoothConstants.MESSAGE_HEAD_LENGTH
import com.guykn.smartchessboard2.bluetooth.BluetoothConstants.ServerToClientAction
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

class BluetoothConnection(
    val bluetoothDevice: BluetoothDevice,
    private val coroutineDispatcher: CoroutineDispatcher = Dispatchers.IO
) : Closeable {

    companion object {
        private const val TAG = "MA_BluetoothConnector"
    }


    private val bluetoothSocket: BluetoothSocket =
        bluetoothDevice.createRfcommSocketToServiceRecord(BluetoothConstants.CHESS_BOARD_UUID)

    private val inputStream: InputStream = bluetoothSocket.inputStream
    private val outputStream: OutputStream = bluetoothSocket.outputStream

    // only one thread may write to bluetooth at a time, so they must aquire this lock before using the output stream
    private val bluetoothWriteMutex: Mutex = Mutex()


    private val _isConnecting = AtomicBoolean(false)

    val isConnected: Boolean get() = bluetoothSocket.isConnected
    val isConnecting: Boolean get() = _isConnecting.get() && !isConnected
    val isBluetoothActive: Boolean get() = isConnected || isConnecting

    @Suppress("BlockingMethodInNonBlockingContext")
    suspend fun connect() = withContext(coroutineDispatcher) {
        _isConnecting.set(true)
        val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()!!
        bluetoothAdapter.cancelDiscovery()
        bluetoothSocket.connect()
        _isConnecting.set(false)
    }

    @Suppress("BlockingMethodInNonBlockingContext")
    val serverMessageFlow: Flow<ServerToClientMessage> = flow {
        checkConnected()
        val messageHeadBuffer: ByteBuffer =
            ByteBuffer.allocate(MESSAGE_HEAD_LENGTH).apply {
                order(ByteOrder.BIG_ENDIAN)
            }

        var bodyBuffer: ByteArray = ByteArray(1024)

        while (true) {

            yield()

            @ServerToClientAction val actionCode = inputStream.read()

            val numBytesReadInHead: Int = inputStream.read(
                messageHeadBuffer.array(),
                0,
                MESSAGE_HEAD_LENGTH
            )
            if (numBytesReadInHead != MESSAGE_HEAD_LENGTH) {
                throw IOException("Too few bytes read in Head. Out of sync with server")
            }

            val bodyLength: Int = messageHeadBuffer.getInt(0)
            if (bodyLength > bodyBuffer.size) {
                // todo: check for errors, to avoid accidentally allocating too much memory
                bodyBuffer = ByteArray(bodyLength)
            }

            val message: String = if (bodyLength == 0) {
                ""
            } else {
                val bytesReadInBody = inputStream.read(bodyBuffer, 0, bodyLength)
                if (bytesReadInBody != bodyLength) {
                    throw IOException("Too few bytes read in body. Probably out of sync with server")
                }
                String(bodyBuffer, 0, bodyLength, StandardCharsets.UTF_8)
            }
            emit(ServerToClientMessage(actionCode, message))
        }
    }.flowOn(coroutineDispatcher)

    suspend fun write(message: ClientToServerMessage) = withContext(coroutineDispatcher) {
        checkConnected()
        val body: ByteArray = message.data.toByteArray(StandardCharsets.UTF_8)
        val head: ByteArray =
            ByteBuffer.allocate(MESSAGE_HEAD_LENGTH)
                .order(ByteOrder.BIG_ENDIAN)
                .putInt(body.size)
                .array() // writes the number of bytes in the array into the header

        val dataToSend = ByteArray(body.size + MESSAGE_HEAD_LENGTH + 1)
        dataToSend[0] = message.action.toByte()
        System.arraycopy(head, 0, dataToSend, 1, head.size)
        System.arraycopy(body, 0, dataToSend, 1 + head.size, body.size)

        bluetoothWriteMutex.withLock {
            outputStream.write(dataToSend)
        }
    }

    private fun checkConnected() {
        if (!isConnected){
            throw IOException("Must be connected before doing bluetooth IO.")
        }
    }


    override fun close() {
        try {
            bluetoothSocket.close()
        } catch (e: IOException) {
            Log.d(TAG, "Failed to close bluetoothSocket: ${e.message}")
        }
    }
}