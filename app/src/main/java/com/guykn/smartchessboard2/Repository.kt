package com.guykn.smartchessboard2

import android.bluetooth.BluetoothDevice
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonParseException
import com.guykn.smartchessboard2.bluetooth.BluetoothManager
import com.guykn.smartchessboard2.bluetooth.ChessBoardModel
import com.guykn.smartchessboard2.bluetooth.ChessBoardModel.BluetoothState.CONNECTED
import com.guykn.smartchessboard2.network.lichess.LichessApi
import com.guykn.smartchessboard2.network.lichess.LichessApi.BroadcastRound
import com.guykn.smartchessboard2.network.lichess.WebManager
import com.guykn.smartchessboard2.network.oauth2.NotSignedInException
import com.guykn.smartchessboard2.network.oauth2.TooManyRequestsException
import com.guykn.smartchessboard2.ui.util.EventWithValue
import dagger.hilt.android.scopes.ServiceScoped
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationResponse
import java.io.IOException
import javax.inject.Inject

// todo: replace destroy callbacks with lifecycle-aware components
// TODO: 6/30/2021 Make sure everything is closed properly when the service is destroyed

typealias BroadcastEvent = EventWithValue<BroadcastRound?>
typealias LichessGameEvent = EventWithValue<LichessApi.Game?>

@ServiceScoped
class Repository @Inject constructor(
    private val coroutineScope: CoroutineScope,
    private val webManager: WebManager,
    private val bluetoothManager: BluetoothManager,
    private val savedBroadcastTournament: SavedBroadcastTournament,
    private val gson: Gson,
    val chessBoardModel: ChessBoardModel,
) {

    companion object {
        private const val TAG = "MA_Repository"
    }

    private val _broadcastRound = MutableStateFlow<BroadcastEvent>(BroadcastEvent(null))
    val broadcastRound = _broadcastRound as StateFlow<BroadcastEvent>

    private var isGameActive = false

    private val _activeGame = MutableStateFlow<LichessGameEvent>(LichessGameEvent(null))
    @Suppress("MemberVisibilityCanBePrivate")
    val activeGame = _activeGame as StateFlow<LichessGameEvent>

    private val _lichessGameState: MutableStateFlow<LichessApi.GameState?> = MutableStateFlow(null)
    @Suppress("MemberVisibilityCanBePrivate")
    val lichessGameState = _lichessGameState as StateFlow<LichessApi.GameState?>

    val internetState: StateFlow<WebManager.InternetState> = webManager.internetState

    val lichessGameJob: Job? = null

    val isLichessLoggedIn: Boolean
        get() = webManager.isLoggedIn

    val lichessUserInfo: LichessApi.UserInfo?
        get() = webManager.userInfo

    init {
        // Whether a move is made on the physical chessboard, update the lichess broadcast.
        coroutineScope.launch {
            var prevJob: Job? = null
            chessBoardModel.boardPgn.collect { pgn ->
                Log.d(TAG, "pgn: \n$pgn")
                prevJob?.cancel()
                prevJob = launch {
                    if (pgn != null) {
                        Log.d(TAG, "broadcastRound: ${broadcastRound.value.value}")
                        _broadcastRound.value.value?.let {
                            // try to push to the broadcast until you succeed, retrying the request on errors.
                            while (true) {
                                try {
                                    Log.d(TAG, "pushing pgn to broadcast")
                                    webManager.pushToBroadcast(it, pgn)
                                    break
                                } catch (e: Exception) {
                                    when (e) {
                                        is NotSignedInException,
                                        is AuthorizationException,
                                        is JsonParseException -> {
                                            Log.w(
                                                TAG,
                                                "Error pushing pgn to broadcast: ${e.message}"
                                            )
                                            break
                                        }
                                        is TooManyRequestsException->{
                                            Log.w(TAG, "Too many request sent to lichess, please wait and try again later.")
                                            break
                                        }
                                        is IOException -> {
                                            // in case of IOException, retry the request in a few seconds
                                            Log.w(
                                                TAG,
                                                "Error pushing pgn to broadcast: ${e.message}"
                                            )
                                            delay(2500)
                                        }
                                        else -> throw e
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Whether a move is made on the physical chessboard, update the online lichess game.
        coroutineScope.launch {
            var prevJob: Job? = null
            chessBoardModel.mostRecentMove.collect { move ->
                prevJob?.cancel()
                prevJob = launch {
                    if (move != null) {
                        _activeGame.value.value?.let { game ->
                            while (true) {
                                try {
                                    webManager.pushGameMove(game, move)
                                    break
                                } catch (e: Exception) {
                                    when (e) {
                                        is NotSignedInException,
                                        is AuthorizationException,
                                        is JsonParseException -> {
                                            // in case of an exception that can't be recovered from, stop retrying the request
                                            Log.w(TAG, "Error making game move: ${e.message}")
                                            break
                                        }
                                        is TooManyRequestsException->{
                                            Log.w(TAG, "Too many request sent to lichess, please wait and try again later.")
                                            break
                                        }
                                        is IOException -> {
                                            Log.w(TAG, "Failed push to lichess game: ${e.message}")
                                        }
                                        else -> throw e
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Whether a move is made online, update the physical chessboard
        coroutineScope.launch {
            var prevGame: LichessApi.Game? = null
            lichessGameState.collect { gameState ->
                launch {
                    if (gameState != null) {
                        try {
                            activeGame.value.value?.let {
                                // when a new game starts, we need to notify the chessboard that it is separate from the previous game
                                if (it != prevGame) {
                                    prevGame = it
                                    bluetoothManager.writeMessage(
                                        ClientToServerMessage.bluetoothGameStart(
                                            it,
                                            gameState.playerColor,
                                            gson
                                        )
                                    )
                                }
                            } ?: Log.w(TAG, "activeGame == null while gameState != null")
                            bluetoothManager.writeMessage(ClientToServerMessage.writeMoves(gameState))
                        } catch (e: IOException) {
                            Log.w(TAG, "Failed to write game state via bluetooth: ${e.message}")
                        }
                    }
                }
            }
        }

        // When the chessboard connects, send all relevant info to it
        coroutineScope.launch {
            chessBoardModel.bluetoothState.collect { bluetoothState ->
                if (bluetoothState == CONNECTED) {
                    lichessGameState.value?.let { gameState ->
                        activeGame.value.value?.let { activeGame ->
                            launch {
                                try {
                                    bluetoothManager.writeMessage(
                                        ClientToServerMessage.bluetoothGameStart(
                                            activeGame,
                                            gameState.playerColor,
                                            gson
                                        )
                                    )
                                    bluetoothManager.writeMessage(
                                        ClientToServerMessage.writeMoves(gameState)
                                    )
                                } catch (e: IOException) {
                                    Log.w(TAG, "Failed to write game state to chessboard. ")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    suspend fun signIn(
        response: AuthorizationResponse?,
        exception: AuthorizationException?
    ): LichessApi.UserInfo = webManager.signIn(response, exception)

    private suspend fun getFreshBroadcastTournament(): LichessApi.BroadcastTournament {
        return savedBroadcastTournament.broadcastTournament
            ?: webManager.createBroadcastTournament().also {
                savedBroadcastTournament.broadcastTournament = it
            }
    }

    suspend fun startBroadcast(): BroadcastRound {
        val broadcastTournament = getFreshBroadcastTournament()
        val broadcastRound = webManager.createBroadcastRound(broadcastTournament)
        _broadcastRound.value = BroadcastEvent(broadcastRound)
        chessBoardModel.boardPgn.value?.let { pgn ->
            webManager.pushToBroadcast(broadcastRound, pgn)
        }
        return broadcastRound
    }

    fun stopBroadcast() {
        _broadcastRound.value = BroadcastEvent(null)
    }

    fun startGame() {
        if (isGameActive) {
            Log.w(TAG, "tried to stream game while game was already active.")
            return
        }
        coroutineScope.launch {
            isGameActive = true
            var shouldStop = false
            try {
                while (!shouldStop) {
                    try {
                        webManager.awaitGameStart()?.let { game ->
                            _activeGame.value = LichessGameEvent(game)
                            webManager.gameStream(game).collect { gameState ->
                                _lichessGameState.value = gameState
                            }
                            // if the flow completes without throwing an exception, then the game has ended and we should stop observing.
                            shouldStop = true
                        } ?: kotlin.run {
                            // awaitGameStart() returned null. No game was found.
                            Log.w(TAG, "No game found")
                            // wait before trying to find a game again.
                            delay(2500)
                        }
                    } catch (e: Exception) {
                        when (e) {
                            is LichessApi.InvalidGameException -> {
                                Log.w(
                                    TAG,
                                    "Only standard games with rapid or classical time controls are allowed. "
                                )
                                shouldStop = true
                            }
                            is NotSignedInException,
                            is AuthorizationException -> {
                                Log.w(
                                    TAG,
                                    "Error ocoured with authorization to lichess: ${e.message}"
                                )
                                shouldStop = true
                            }
                            is LichessApi.InvalidMessageException,
                            is JsonParseException -> {
                                Log.w(TAG, "Invalid data received from lichess: ${e.message}")
                                shouldStop = true
                            }
                            is IOException -> {
                                Log.w(
                                    TAG,
                                    "Error occurred trying to stream lichess game: ${e.message}"
                                )
                                e.printStackTrace()

                                // wait a short while before trying to connect again.
                                delay(2500)
                            }
                            else -> throw e
                        }
                    }
                }
            } finally {
                Log.d(TAG, "Done streaming game.")
                _activeGame.value = LichessGameEvent(null)
                isGameActive = false
            }
        }
    }

    fun stopGame(){
        lichessGameJob?.cancel()
    }

    fun signOut() {
        webManager.signOut()
    }

    fun setTargetBluetoothDevice(bluetoothDevice: BluetoothDevice?) {
        bluetoothManager.setTargetDevice(bluetoothDevice)
    }

    fun destroy() {
        webManager.destroy()
        bluetoothManager.destroy()
    }
}