package com.guykn.smartchessboard2.bluetooth

import android.util.Log
import com.guykn.smartchessboard2.BluetoothConstants.ServerToClientActions.ON_MOVE
import com.guykn.smartchessboard2.BluetoothConstants.ServerToClientActions.RET_READ_PGN
import com.guykn.smartchessboard2.ServerToClientMessage
import dagger.hilt.android.scopes.ServiceScoped
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@ServiceScoped
class ChessBoardModel @Inject constructor() {

    companion object{
        const val TAG = "MA_ChessBoardModel"
    }

    enum class BluetoothState {
        BLUETOOTH_NOT_SUPPORTED,
        BLUETOOTH_NOT_ENABLED,
        BLUETOOTH_TURNING_ON,
        DISCONNECTED,
        SCAN_FAILED,
        CONNECTION_FAILED,
        SCANNING,
        REQUESTING_USER_INPUT,
        PAIRING,
        CONNECTING,
        CONNECTED
    }

    private val _bluetoothState = MutableStateFlow(BluetoothState.DISCONNECTED)
    val bluetoothState: StateFlow<BluetoothState> = _bluetoothState

    fun setBluetoothState(bluetoothState: ChessBoardModel.BluetoothState) {
        Log.d(BluetoothManager.TAG, "Bluetooth State changed to: $bluetoothState")
        _bluetoothState.value = bluetoothState
    }

    private val _boardPgn = MutableStateFlow<String?>(null)
    val boardPgn = _boardPgn as StateFlow<String?>

    private val _mostRecentMove = MutableStateFlow<String?>(null)
    val mostRecentMove = _mostRecentMove as StateFlow<String?>

    fun update(message: ServerToClientMessage){
        when(message.action){
            RET_READ_PGN -> {
                Log.d(TAG, "board pgn: \n${message.data}")
                _boardPgn.value = message.data
            }
            ON_MOVE -> {
                _mostRecentMove.value = message.data
            }

        }
    }

}