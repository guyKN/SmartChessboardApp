package com.guykn.smartchessboard2

import android.bluetooth.BluetoothDevice
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonParseException
import com.guykn.smartchessboard2.ErrorEventBus.ErrorEvent
import com.guykn.smartchessboard2.bluetooth.*
import com.guykn.smartchessboard2.bluetooth.ChessBoardModel.BluetoothState.CONNECTED
import com.guykn.smartchessboard2.network.lichess.LichessApi
import com.guykn.smartchessboard2.network.lichess.LichessApi.BroadcastRound
import com.guykn.smartchessboard2.network.lichess.WebManager
import com.guykn.smartchessboard2.network.oauth2.GenericNetworkException
import com.guykn.smartchessboard2.network.oauth2.NotSignedInException
import com.guykn.smartchessboard2.network.oauth2.TooManyRequestsException
import com.guykn.smartchessboard2.ui.util.EventWithValue
import dagger.hilt.android.scopes.ServiceScoped
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationResponse
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

// TODO: 7/4/2021 Make sure error handling is done properly with kotlin flows (maybe use the flow.catch operator)
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
    private val gson: Gson,
    val errorEventBus: ErrorEventBus,
    val chessBoardModel: ChessBoardModel
) : BluetoothManager.PgnFilesCallback {

    companion object {
        private const val TAG = "MA_Repository"
    }

    private var isBroadcastActive = MutableStateFlow<Boolean>(false)

    private val _broadcastRound = MutableStateFlow<BroadcastEvent>(BroadcastEvent(null))
    val broadcastRound = _broadcastRound as StateFlow<BroadcastEvent>

    private val isOnlineGameActive = AtomicBoolean(false)

    private val _activeGame = MutableStateFlow<LichessGameEvent>(LichessGameEvent(null))
    val activeGame = _activeGame as StateFlow<LichessGameEvent>

    private val _lichessGameState: MutableStateFlow<LichessApi.LichessGameState?> =
        MutableStateFlow(null)
    val lichessGameState = _lichessGameState as StateFlow<LichessApi.LichessGameState?>

    val internetState: StateFlow<WebManager.InternetState> = webManager.internetState

    private var lichessGameJob: Job? = null

    val uiOAuthState: StateFlow<WebManager.UiOAuthState> = webManager.uiAuthState

    sealed class PgnFileUploadState {
        object NotUploading : PgnFileUploadState()
        object ExchangingBluetoothData : PgnFileUploadState()
        data class UploadingToLichess(val numFilesUploaded: Int, val numFilesTotal: Int) :
            PgnFileUploadState()
    }

    private val isRequestingPgnFiles = AtomicBoolean(false)

    private val _pgnFilesUploadState: MutableStateFlow<PgnFileUploadState> =
        MutableStateFlow(PgnFileUploadState.NotUploading)
    val pgnFileUploadState: StateFlow<PgnFileUploadState> = _pgnFilesUploadState

    private val _isLoadingBroadcast: MutableStateFlow<Boolean> = MutableStateFlow(false)
    val isLoadingBroadcast: StateFlow<Boolean> = _isLoadingBroadcast


    init {
        // Whether a move is made on the physical chessboard, update the lichess broadcast.
        coroutineScope.launch {
            var prevJob: Job? = null
            var prevGameId: String? = null
            chessBoardModel.boardState.combinePairs(isBroadcastActive)
                .collect { (boardState, isActive) ->
                    if (!isActive) {
                        return@collect
                    }
                    val pgn = boardState?.pgn ?: return@collect
                    prevJob?.cancel()
                    prevJob = launch {
                        val currentGameId = chessBoardModel.gameInfo.value?.gameId
                        val broadcastRound: BroadcastRound = if (prevGameId != currentGameId) {
                            createBroadcastRound()
                        } else {
                            _broadcastRound.value.value ?: createBroadcastRound()
                        }
                        prevGameId = currentGameId
                        // try to push to the broadcast until you succeed, retrying the request on errors.
                        while (true) {
                            try {
                                Log.d(TAG, "pushing pgn to broadcast")
                                webManager.pushToBroadcast(broadcastRound, pgn)
                                break
                            } catch (e: Exception) {
                                when (e) {
                                    is NotSignedInException -> {
                                        errorEventBus.errorEvents.value = ErrorEvent.NoLongerAuthorizedError()
                                    }
                                    is GenericNetworkException,
                                    is AuthorizationException,
                                    is JsonParseException -> {
                                        Log.w(
                                            TAG,
                                            "Error pushing pgn to broadcast: ${e.message}"
                                        )
//                                        errorEventBus.errorEvents.value =
//                                            ErrorEvent.OtherErrorPushingToBroadcast()

                                        break
                                    }
                                    is TooManyRequestsException -> {
//                                        errorEventBus.errorEvents.value =
//                                            ErrorEvent.TooManyRequests()
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

    fun signOut() {
        webManager.signOut()
    }


    fun startBroadcast() {
        isBroadcastActive.value = true
    }

    fun stopBroadcast() {
        _broadcastRound.value = BroadcastEvent(null)
        isBroadcastActive.value = false
    }

    private suspend fun getFreshBroadcastTournament(): LichessApi.BroadcastTournament {
        return savedBroadcastTournament.broadcastTournament
            ?: webManager.createBroadcastTournament().also {
                savedBroadcastTournament.broadcastTournament = it
            }
    }

    private suspend fun createBroadcastRound(): BroadcastRound {
        try {
            _isLoadingBroadcast.value = true
            val broadcastTournament = getFreshBroadcastTournament()
            val broadcastRound = webManager.createBroadcastRound(broadcastTournament)
            _broadcastRound.value = BroadcastEvent(broadcastRound)
            return broadcastRound
        }finally {
            _isLoadingBroadcast.value = false
        }
    }

    suspend fun writeSettings(settings: ChessBoardSettings) {
        bluetoothManager.writeMessage(clientToServerMessageProvider.writePreferences(settings))
    }

    suspend fun startOfflineGame(gameStartRequest: GameStartRequest) {
        bluetoothManager.writeMessage(clientToServerMessageProvider.startNormalGame(gameStartRequest))
    }

    fun startOnlineGame() {
        if (isOnlineGameActive.get()) {
            Log.w(TAG, "tried to stream game while game was already active.")
            return
        }
        stopBroadcast()
        lichessGameJob = coroutineScope.launch {
            isOnlineGameActive.set(true)
            var shouldStop = false
            try {
                while (!shouldStop) {
                    yield()
                    try {
                        webManager.awaitGameStart()?.let { game ->
                            _activeGame.value = LichessGameEvent(game)
                            webManager.gameStream(game).collect { gameState ->
                                yield()
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
                isOnlineGameActive.set(false)
            }
        }
    }

    fun stopOnlineGame() {
        lichessGameJob?.cancel()
    }

    fun setTargetBluetoothDevice(bluetoothDevice: BluetoothDevice?) {
        bluetoothManager.setTargetDevice(bluetoothDevice)
    }

    suspend fun uploadPgn() {
        isRequestingPgnFiles.set(true)
        _pgnFilesUploadState.value = PgnFileUploadState.ExchangingBluetoothData
        try {
            bluetoothManager.writeMessage(clientToServerMessageProvider.requestPgnFiles())
        } catch (e: IOException) {
            isRequestingPgnFiles.set(false)
            _pgnFilesUploadState.value = PgnFileUploadState.NotUploading
            throw e
        }
    }

    suspend fun blinkLeds(){
        bluetoothManager.writeMessage(clientToServerMessageProvider.testLeds())
    }

    override fun onPgnFilesSent(messageData: String) {
        if (!isRequestingPgnFiles.get()) {
            Log.w(TAG, "Received pgn files from bluetooth while not requesting pgn files.")
            _pgnFilesUploadState.value = PgnFileUploadState.NotUploading
            return
        }
        isRequestingPgnFiles.set(false)

        coroutineScope.launch {
            try {
                val pgnFiles = gson.fromJson(messageData, PgnFilesResponse::class.java)
                pgnFiles.files.forEachIndexed { index, file ->
                    _pgnFilesUploadState.value =
                        PgnFileUploadState.UploadingToLichess(index, pgnFiles.files.size)
                    webManager.importGame(file.pgn)
                    bluetoothManager.writeMessage(
                        clientToServerMessageProvider.requestArchivePgnFile(
                            file.name
                        )
                    )
                }
            } catch (e: JsonParseException) {
                Log.w(TAG, "Error parsing pgn files: \n${e.message}\n${messageData}")
            } catch (e: IOException) {
                Log.w(TAG, "error uploading pgn files: ${e.message}")
            } catch (e: AuthorizationException) {
                Log.w(TAG, "error uploading pgn files: ${e.message}")
            } finally {
                _pgnFilesUploadState.value = PgnFileUploadState.NotUploading
            }
        }
    }

    fun destroy() {
        webManager.destroy()
        bluetoothManager.destroy()
    }
}