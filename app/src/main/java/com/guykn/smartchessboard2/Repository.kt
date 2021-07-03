package com.guykn.smartchessboard2

import android.bluetooth.BluetoothDevice
import android.util.Log
import com.google.gson.JsonParseException
import com.guykn.smartchessboard2.bluetooth.BluetoothManager
import com.guykn.smartchessboard2.bluetooth.ChessBoardModel
import com.guykn.smartchessboard2.bluetooth.ChessBoardModel.BluetoothState.CONNECTED
import com.guykn.smartchessboard2.network.lichess.LichessApi
import com.guykn.smartchessboard2.network.lichess.LichessApi.BroadcastRound
import com.guykn.smartchessboard2.network.lichess.WebManager
import com.guykn.smartchessboard2.network.oauth2.GenericNetworkException
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
import kotlinx.coroutines.flow.combine
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
    private val clientToServerMessageProvider: ClientToServerMessageProvider,
    val chessBoardModel: ChessBoardModel
) {

    companion object {
        private const val TAG = "MA_Repository"
    }

    private var isBroadcastActive = MutableStateFlow<Boolean>(false)

    private val _broadcastRound = MutableStateFlow<BroadcastEvent>(BroadcastEvent(null))
    val broadcastRound = _broadcastRound as StateFlow<BroadcastEvent>

    private var isOnlineGameActive = false

    private val _activeGame = MutableStateFlow<LichessGameEvent>(LichessGameEvent(null))

    @Suppress("MemberVisibilityCanBePrivate")
    val activeGame = _activeGame as StateFlow<LichessGameEvent>

    private val _lichessGameState: MutableStateFlow<LichessApi.LichessGameState?> =
        MutableStateFlow(null)

    @Suppress("MemberVisibilityCanBePrivate")
    val lichessGameState = _lichessGameState as StateFlow<LichessApi.LichessGameState?>

    val internetState: StateFlow<WebManager.InternetState> = webManager.internetState

    private var lichessGameJob: Job? = null

    val isLichessLoggedIn: Boolean
        get() = webManager.isLoggedIn

    val lichessUserInfo: LichessApi.UserInfo?
        get() = webManager.userInfo

    init {
        // TODO: 7/2/2021 Whenever a new game starts, create a new broadcast. 
        // Whether a move is made on the physical chessboard, update the lichess broadcast.
        coroutineScope.launch {
            var prevJob: Job? = null
            var prevGameId: String? = null
            chessBoardModel.boardState.combine(isBroadcastActive) { boardState, isActive ->
                Pair(boardState, isActive)
            }.collect { (boardState, isActive) ->
                if (!isActive) {
                    return@collect
                }
                val pgn = boardState?.pgn ?: return@collect
                prevJob?.cancel()
                prevJob = launch {
                    val isNewGame = prevGameId != chessBoardModel.gameInfo.value?.gameId

                    val broadcastRound: BroadcastRound = if (isNewGame) {
                        createBroadcastRound()
                    } else {
                        _broadcastRound.value.value ?: createBroadcastRound()
                    }

                    // try to push to the broadcast until you succeed, retrying the request on errors.
                    while (true) {
                        try {
                            Log.d(TAG, "pushing pgn to broadcast")
                            webManager.pushToBroadcast(broadcastRound, pgn)
                            break
                        } catch (e: Exception) {
                            when (e) {
                                is GenericNetworkException,
                                is NotSignedInException,
                                is AuthorizationException,
                                is JsonParseException -> {
                                    Log.w(
                                        TAG,
                                        "Error pushing pgn to broadcast: ${e.message}"
                                    )
                                    break
                                }
                                is TooManyRequestsException -> {
                                    Log.w(
                                        TAG,
                                        "Too many request sent to lichess, please wait and try again later."
                                    )
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

        // Whether a move is made on the physical chessboard, update the online lichess game.
        coroutineScope.launch {
            var prevJob: Job? = null

            // To ensure that the same move isn't sent twice,
            var lastSentBoardState: ChessBoardModel.BoardState? = null

            chessBoardModel.boardState.collect { boardState ->
                prevJob?.cancel()
                if (boardState?.shouldSendMove != true || boardState.lastMove == null) {
                    lastSentBoardState = null
                    return@collect
                }
                if (boardState == lastSentBoardState) {
                    // We already sent this boardState to the server, so we don't need to send it again
                    return@collect
                }
                val move = boardState.lastMove
                prevJob = launch {
                    _activeGame.value.value?.let { game ->
                        while (true) {
                            try {
                                webManager.pushGameMove(game, move)
                                lastSentBoardState = boardState
                                break
                            } catch (e: Exception) {
                                when (e) {
                                    is NotSignedInException,
                                    is AuthorizationException,
                                    is GenericNetworkException,
                                    is JsonParseException -> {
                                        // in case of an exception that can't be recovered from, stop retrying the request
                                        Log.w(TAG, "Error making game move: ${e.message}")
                                        break
                                    }
                                    is TooManyRequestsException -> {
                                        Log.w(
                                            TAG,
                                            "Too many request sent to lichess, please wait and try again later."
                                        )
                                        break
                                    }
                                    is IOException -> {
                                        Log.w(TAG, "Failed push to lichess game: ${e.message}")
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

        // Whether a move is made online, update the physical chessboard
        coroutineScope.launch {
            lichessGameState.collect { gameState ->
                if (gameState == null) return@collect
                launch {
                    try {
                        bluetoothManager.writeMessage(
                            clientToServerMessageProvider.forceBluetoothMoves(
                                gameState
                            )
                        )
                    } catch (e: IOException) {
                        Log.w(TAG, "Failed to write game state via bluetooth: ${e.message}")
                    }
                }
            }
        }

        // When the chessboard connects, send all relevant info to it
        coroutineScope.launch {
            chessBoardModel.bluetoothState.collect { bluetoothState ->
                if (bluetoothState == CONNECTED) {
                    lichessGameState.value?.let { gameState ->
                        launch {
                            try {
                                bluetoothManager.writeMessage(
                                    clientToServerMessageProvider.forceBluetoothMoves(gameState)
                                )
                            } catch (e: IOException) {
                                Log.w(TAG, "Failed to write game state to chessboard. ")
                            }
                        }
                    }
                }
            }
        }

        // When the physical chessboard starts a different game while an online game is active, cancel the online game.
        coroutineScope.launch {
            chessBoardModel.gameInfo.collect { gameType ->
                val id = activeGame.value.value?.id
                if (id != null && id != gameType?.gameId) {
                    Log.d(TAG, "canceling online game because another game started ")
                    stopOnlineGame()
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

    private suspend fun createBroadcastRound(): BroadcastRound {
        val broadcastTournament = getFreshBroadcastTournament()
        val broadcastRound = webManager.createBroadcastRound(broadcastTournament)
        _broadcastRound.value = BroadcastEvent(broadcastRound)
        return broadcastRound
    }

    suspend fun writeSettings(settings: ChessBoardSettings) {
        bluetoothManager.writeMessage(clientToServerMessageProvider.writePreferences(settings))
    }

    suspend fun startOfflineGame(gameStartRequest: GameStartRequest) {
        bluetoothManager.writeMessage(clientToServerMessageProvider.startNormalGame(gameStartRequest))
    }

    fun startBroadcast() {
        isBroadcastActive.value = true
        coroutineScope.launch {
            val broadcastRound = createBroadcastRound()

        }
    }

    fun stopBroadcast() {
        _broadcastRound.value = BroadcastEvent(null)
        isBroadcastActive.value = false
    }

    fun startOnlineGame() {
        if (isOnlineGameActive) {
            Log.w(TAG, "tried to stream game while game was already active.")
            return
        }
        stopBroadcast()
        lichessGameJob = coroutineScope.launch {
            isOnlineGameActive = true
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
                            is JsonParseException,
                            is GenericNetworkException -> {
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
                isOnlineGameActive = false
            }
        }
    }

    fun stopOnlineGame() {
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