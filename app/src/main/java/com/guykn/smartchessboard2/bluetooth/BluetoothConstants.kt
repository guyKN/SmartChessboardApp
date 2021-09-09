package com.guykn.smartchessboard2.bluetooth

import androidx.annotation.IntDef
import com.google.gson.Gson
import com.guykn.smartchessboard2.bluetooth.BluetoothConstants.ClientToServerActions
import com.guykn.smartchessboard2.network.lichess.LichessApi
import dagger.hilt.android.scopes.ServiceScoped
import java.util.*
import javax.inject.Inject

object BluetoothConstants {
    val CHESS_BOARD_UUID: UUID = UUID.fromString("6c08ff89-2218-449f-9590-66c704994db9")!!
    const val CHESS_BOARD_DEVICE_NAME = "Chess Board"

    // the number of bytes in the message head, which describes the number of bytes in the entire message
    const val MESSAGE_HEAD_LENGTH: Int = 4

    @kotlin.annotation.Retention(AnnotationRetention.SOURCE)
    @IntDef(
        ClientToServerActions.WRITE_PREFERENCES,
        ClientToServerActions.START_NORMAL_GAME,
        ClientToServerActions.FORCE_BLUETOOTH_MOVES,
        ClientToServerActions.REQUEST_PGN_FILES,
        ClientToServerActions.REQUEST_ARCHIVE_PGN_FILE,
        ClientToServerActions.TEST_LEDS
    )
    annotation class ClientToServerAction

    object ClientToServerActions {
        const val WRITE_PREFERENCES = 0
        const val START_NORMAL_GAME = 1
        const val FORCE_BLUETOOTH_MOVES = 2
        const val REQUEST_PGN_FILES = 3
        const val REQUEST_ARCHIVE_PGN_FILE = 4
        const val TEST_LEDS = 5

    }

    @kotlin.annotation.Retention(AnnotationRetention.SOURCE)
    @IntDef(
        ServerToClientActions.STATE_CHANGED,
        ServerToClientActions.RET_PGN_FILES,
        ServerToClientActions.PGN_FILES_DONE,
        ServerToClientActions.ON_ERROR,
    )
    annotation class ServerToClientAction

    object ServerToClientActions {
        const val STATE_CHANGED = 0
        const val RET_PGN_FILES = 1
        const val PGN_FILES_DONE = 2
        const val ON_ERROR = 3
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

data class ArchivePgnFileRequest(val name: String?, val all: Boolean?)

data class PgnFile(
    val name: String,
    val pgn: String
)

@ServiceScoped
class ClientToServerMessageProvider @Inject constructor(val gson: Gson) {
    fun writePreferences(settings: ChessBoardSettings): ClientToServerMessage {
        return ClientToServerMessage(
            ClientToServerActions.WRITE_PREFERENCES,
            gson.toJson(settings)
        )
    }

    fun startNormalGame(request: GameStartRequest): ClientToServerMessage {
        return ClientToServerMessage(
            ClientToServerActions.START_NORMAL_GAME,
            gson.toJson(request)
        )
    }

    fun forceBluetoothMoves(lichessGameState: LichessApi.LichessGameState): ClientToServerMessage {
        return ClientToServerMessage(
            ClientToServerActions.FORCE_BLUETOOTH_MOVES,
            gson.toJson(lichessGameState)
        )
    }

    fun requestPgnFiles(): ClientToServerMessage{
        return ClientToServerMessage(ClientToServerActions.REQUEST_PGN_FILES, "")
    }

    fun requestArchivePgnFile(fileName: String): ClientToServerMessage{
        return ClientToServerMessage(
            ClientToServerActions.REQUEST_ARCHIVE_PGN_FILE,
            gson.toJson(ArchivePgnFileRequest(fileName, all = false))
        )
    }

    fun requestArchiveAllPgn(): ClientToServerMessage{
        return ClientToServerMessage(
            ClientToServerActions.REQUEST_ARCHIVE_PGN_FILE,
            gson.toJson(ArchivePgnFileRequest(all = true, name = null))
        )
    }

    fun testLeds(): ClientToServerMessage {
        return ClientToServerMessage(ClientToServerActions.TEST_LEDS, "")
    }
}