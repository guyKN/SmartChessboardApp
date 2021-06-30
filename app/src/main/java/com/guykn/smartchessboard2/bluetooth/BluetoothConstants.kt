package com.guykn.smartchessboard2

import androidx.annotation.IntDef
import com.google.gson.Gson
import com.guykn.smartchessboard2.BluetoothConstants.ClientToServerActions.BLUETOOTH_GAME_WRITE_MOVES
import com.guykn.smartchessboard2.BluetoothConstants.ClientToServerActions.START_BLUETOOTH_GAME
import com.guykn.smartchessboard2.network.lichess.LichessApi
import java.util.*

object BluetoothConstants {
    val CHESS_BOARD_UUID: UUID = UUID.fromString("6c08ff89-2218-449f-9590-66c704994db9")!!

    // the number of bytes in the message head, which describes the number of bytes in the entire message
    const val MESSAGE_HEAD_LENGTH: Int = 4

    @kotlin.annotation.Retention(AnnotationRetention.SOURCE)
    @IntDef(
        ClientToServerActions.REQUEST_READ_FEN,
        ClientToServerActions.REQUEST_READ_PGN,
        ClientToServerActions.REQUEST_READ_PREFERENCES,
        ClientToServerActions.WRITE_PREFERENCES,
        ClientToServerActions.REQUEST_PGN_FILE_NAMES,
        ClientToServerActions.REQUEST_READ_PGN_FILE,
        ClientToServerActions.REQUEST_ARCHIVE_PGN_FILE,
        ClientToServerActions.REQUEST_PGN_FILE_COUNT,
        ClientToServerActions.START_BLUETOOTH_GAME,
        ClientToServerActions.BLUETOOTH_GAME_WRITE_MOVES,
        ClientToServerActions.TEST_LEDS
    )
    annotation class ClientToServerAction

    object ClientToServerActions {
        const val REQUEST_READ_FEN = 0
        const val REQUEST_READ_PGN = 1
        const val REQUEST_READ_PREFERENCES = 2
        const val WRITE_PREFERENCES = 3
        const val REQUEST_PGN_FILE_NAMES = 4
        const val REQUEST_READ_PGN_FILE = 5
        const val REQUEST_ARCHIVE_PGN_FILE = 6
        const val REQUEST_PGN_FILE_COUNT = 7
        const val START_BLUETOOTH_GAME = 8
        const val BLUETOOTH_GAME_WRITE_MOVES = 9
        const val TEST_LEDS = 10
    }

    @kotlin.annotation.Retention(AnnotationRetention.SOURCE)
    @IntDef(
        ServerToClientActions.RET_READ_FEN,
        ServerToClientActions.RET_READ_PGN,
        ServerToClientActions.RET_READ_PREFERENCES,
        ServerToClientActions.RET_WRITE_PREFERENCES,
        ServerToClientActions.ON_MOVE,
        ServerToClientActions.ON_ERROR,
        ServerToClientActions.RET_PGN_FILE_NAMES,
        ServerToClientActions.RET_PGN_FILE,
        ServerToClientActions.RET_PGN_FILE_COUNT
    )
    annotation class ServerToClientAction

    object ServerToClientActions {
        const val RET_READ_FEN = 0
        const val RET_READ_PGN = 1
        const val RET_READ_PREFERENCES = 2
        const val RET_WRITE_PREFERENCES = 3
        const val ON_MOVE = 4
        const val ON_ERROR = 5
        const val RET_PGN_FILE_NAMES = 6
        const val RET_PGN_FILE = 7
        const val RET_PGN_FILE_COUNT = 8
    }
}

data class ServerToClientMessage(
    @BluetoothConstants.ServerToClientAction val action: Int,
    val data: String
)

data class GameStartMessage(val gameId: String, val clientColor: String)

data class ClientToServerMessage(
    @BluetoothConstants.ClientToServerAction val action: Int,
    val data: String
) {
    companion object {
        fun writeMoves(gameState: LichessApi.GameState): ClientToServerMessage {
            return ClientToServerMessage(BLUETOOTH_GAME_WRITE_MOVES, gameState.moves)
        }

        fun bluetoothGameStart(
            game: LichessApi.Game,
            playerColor: String,
            gson: Gson
        ): ClientToServerMessage {
            val gameStartMessage = GameStartMessage(game.id, playerColor)
            return ClientToServerMessage(START_BLUETOOTH_GAME, gson.toJson(gameStartMessage))
        }
    }
}
