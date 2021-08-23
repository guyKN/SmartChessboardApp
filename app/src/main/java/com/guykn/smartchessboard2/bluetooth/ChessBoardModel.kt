package com.guykn.smartchessboard2.bluetooth

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonParseException
import dagger.hilt.android.scopes.ServiceScoped
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@ServiceScoped
class ChessBoardModel @Inject constructor(val gson: Gson) {

    companion object {
        const val TAG = "MA_ChessBoardModel"
    }

    data class StateChangeMessage(
        val gameActive: Boolean?,
        val gamesToUpload: Int?,
        val game: GameInfo?,
        val boardState: BoardState?,
        val settings: ChessBoardSettings?
    )

    data class GameInfo(
        val gameId: String,
        val engineLevel: String,
        val white: String,
        val black: String
    )

    data class BoardState(
        val fen: String,
        val pgn: String,
        val lastMove: String?,
        val moveCount: Int,
        val shouldSendMove: Boolean
    )

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

    private val _gameActive = MutableStateFlow<Boolean?>(null)
    val gameActive = _gameActive as StateFlow<Boolean?>

    private val _numGamesToUpload = MutableStateFlow<Int?>(null)
    val numGamesToUpload = _numGamesToUpload as StateFlow<Int?>

    private val _gameInfo = MutableStateFlow<GameInfo?>(null)
    val gameInfo = _gameInfo as StateFlow<GameInfo?>

    private val _boardState = MutableStateFlow<BoardState?>(null)
    val boardState = _boardState as StateFlow<BoardState?>

    private val _settings = MutableStateFlow<ChessBoardSettings?>(null)
    val settings = _settings as StateFlow<ChessBoardSettings?>

    fun setBluetoothState(bluetoothState: BluetoothState) {
        Log.d(BluetoothManager.TAG, "Bluetooth State changed to: $bluetoothState")
        _bluetoothState.value = bluetoothState
    }


    fun update(messageData: String) {
        try {
            val stateChange: StateChangeMessage =
                gson.fromJson(messageData, StateChangeMessage::class.java)
            stateChange.gameActive?.let {
                _gameActive.value = it
            }
            stateChange.gamesToUpload?.let {
                _numGamesToUpload.value = it
            }
            stateChange.game?.let {
                _gameInfo.value = it
            }
            stateChange.boardState?.let {
                _boardState.value = it
            }
            stateChange.settings?.let {
                _settings.value = it
            }
        } catch (e: JsonParseException) {
            Log.w(TAG, "Failed to parse json:\n${e.message}\n$messageData")
        }

    }
}