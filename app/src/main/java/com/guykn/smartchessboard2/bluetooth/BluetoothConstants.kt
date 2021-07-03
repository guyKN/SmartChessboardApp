package com.guykn.smartchessboard2

import androidx.annotation.IntDef
import com.google.gson.Gson
import com.guykn.smartchessboard2.BluetoothConstants.ClientToServerActions
import com.guykn.smartchessboard2.network.lichess.LichessApi
import dagger.hilt.android.scopes.ServiceScoped
import java.util.*
import javax.inject.Inject

object BluetoothConstants {
    val CHESS_BOARD_UUID: UUID = UUID.fromString("6c08ff89-2218-449f-9590-66c704994db9")!!

    // the number of bytes in the message head, which describes the number of bytes in the entire message
    const val MESSAGE_HEAD_LENGTH: Int = 4

    @kotlin.annotation.Retention(AnnotationRetention.SOURCE)
    @IntDef(
        ClientToServerActions.WRITE_PREFERENCES,
        ClientToServerActions.START_NORMAL_GAME,
        ClientToServerActions.FORCE_BLUETOOTH_MOVES,
        ClientToServerActions.TEST_LEDS
    )
    annotation class ClientToServerAction

    object ClientToServerActions {
        const val WRITE_PREFERENCES = 0
        const val START_NORMAL_GAME = 1
        const val FORCE_BLUETOOTH_MOVES = 2
        const val TEST_LEDS = 3

    }

    @kotlin.annotation.Retention(AnnotationRetention.SOURCE)
    @IntDef(
        ServerToClientActions.STATE_CHANGED,
        ServerToClientActions.ON_ERROR,
    )
    annotation class ServerToClientAction

    object ServerToClientActions {
        const val STATE_CHANGED = 0
        const val ON_ERROR = 1
    }
}

data class ServerToClientMessage(
    @BluetoothConstants.ServerToClientAction val action: Int,
    val data: String
)

data class ClientToServerMessage(
    @BluetoothConstants.ClientToServerAction val action: Int,
    val data: String
)

data class ChessBoardSettings(val learningMode: Boolean)
data class GameStartRequest(
    val enableEngine: Boolean,
    val engineColor: String,
    val engineLevel: Int,
    val gameId: String? = null,
    val startFen: String? = null
)

data class ForceBluetoothMovesRequest(
    val gameId: String,
    val clientColor: String,
    val moves: String,
    val winner: String?
)

@ServiceScoped
class ClientToServerMessageProvider @Inject constructor(val gson: Gson) {
    fun writePreferences(settings: ChessBoardSettings):ClientToServerMessage {
        return ClientToServerMessage(
            ClientToServerActions.WRITE_PREFERENCES,
            gson.toJson(settings)
        )
    }

    fun startNormalGame(request: GameStartRequest): ClientToServerMessage{
        return ClientToServerMessage(
            ClientToServerActions.START_NORMAL_GAME,
            gson.toJson(request)
        )
    }

    fun forceBluetoothMoves(lichessGameState: LichessApi.LichessGameState): ClientToServerMessage{
        return ClientToServerMessage(
            ClientToServerActions.FORCE_BLUETOOTH_MOVES,
            gson.toJson(lichessGameState)
        )
    }

    fun testLeds(): ClientToServerMessage{
        return ClientToServerMessage(ClientToServerActions.TEST_LEDS, "")
    }
}