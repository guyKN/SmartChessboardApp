package com.guykn.smartchessboard2

import android.bluetooth.BluetoothDevice
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonParseException
import com.guykn.smartchessboard2.EventBus.ErrorEvent
import com.guykn.smartchessboard2.EventBus.SuccessEvent
import com.guykn.smartchessboard2.bluetooth.*
import com.guykn.smartchessboard2.bluetooth.ChessBoardModel.BluetoothState.CONNECTED
import com.guykn.smartchessboard2.network.lichess.BoolEvent
import com.guykn.smartchessboard2.network.lichess.LichessApi
import com.guykn.smartchessboard2.network.lichess.LichessApi.BroadcastRound
import com.guykn.smartchessboard2.network.lichess.WebManager
import com.guykn.smartchessboard2.network.oauth2.GenericNetworkException
import com.guykn.smartchessboard2.network.oauth2.NetworkException
import com.guykn.smartchessboard2.network.oauth2.NotSignedInException
import com.guykn.smartchessboard2.network.oauth2.TooManyRequestsException
import com.guykn.smartchessboard2.ui.util.EventWithValue
import dagger.hilt.android.scopes.ServiceScoped
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterIsInstance
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationResponse
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

// todo: replace destroy callbacks with lifecycle-aware components
// TODO: 6/30/2021 Make sure everything is closed properly when the service is destroyed
// todo: make sure that every exception that is thrown has a place to be caught. 


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
    val eventBus: EventBus,
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
    val activeGame: StateFlow<LichessGameEvent> = _activeGame

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

    val isLoadingOnlineGame: StateFlow<BoolEvent> = webManager.isLoadingOnlineGame


    init {
        // Whether a move is made on the physical chessboard, update the lichess broadcast.
        coroutineScope.launch outerLaunch@{
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
                        val broadcastRound: BroadcastRound? = if (prevGameId != currentGameId) {
                            createBroadcastRoundAndRetry()
                        } else {
                            _broadcastRound.value.value ?: createBroadcastRoundAndRetry()
                        }
                        if (broadcastRound == null) {
                            return@launch
                        }
                        prevGameId = currentGameId
                        // try to push to the broadcast until you succeed, retrying the request on errors.
                        while (true) {
                            yield()
                            // todo: replace this big chunk of code with a different method
                            try {
                                Log.d(TAG, "pushing pgn to broadcast")
                                webManager.pushToBroadcast(broadcastRound, pgn)
                                break
                            } catch (e: Exception) {
                                when (e) {
                                    is NotSignedInException,
                                    is AuthorizationException -> {
                                        eventBus.errorEvents.value =
                                            ErrorEvent.NoLongerAuthorizedError()
                                        stopBroadcast()
                                        break
                                    }
                                    is GenericNetworkException,
                                    is JsonParseException -> {
                                        Log.w(
                                            TAG,
                                            "Error pushing pgn to broadcast: ${e.message}"
                                        )
                                        eventBus.errorEvents.value =
                                            ErrorEvent.MiscError("Couldn't update broadcast. ")
                                        stopBroadcast()
                                        break
                                    }
                                    is TooManyRequestsException -> {
                                        eventBus.errorEvents.value =
                                            ErrorEvent.TooManyRequests(e.timeForValidRequests)
                                        stopBroadcast()
                                        break
                                    }
                                    is IOException -> {
                                        // in case of IOException, retry the request in a few seconds
                                        Log.w(
                                            TAG,
                                            "Error pushing pgn to broadcast: ${e.message}"
                                        )
                                        eventBus.errorEvents.value = ErrorEvent.InternetIOError()
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
                            // extract this bing chunk of code into a method
                            yield()
                            try {
                                webManager.pushGameMove(game, move)
                                lastSentBoardState = boardState
                                break
                            } catch (e: Exception) {
                                when (e) {
                                    is NotSignedInException, is AuthorizationException -> {
                                        Log.w(
                                            TAG,
                                            "Error with authorization to lichess making game move: ${e.message} "
                                        )
                                        eventBus.errorEvents.value =
                                            ErrorEvent.NoLongerAuthorizedError()
                                        stopOnlineGame()
                                        break
                                    }
                                    is GenericNetworkException, is JsonParseException -> {
                                        // in case of an exception that can't be recovered from, stop retrying the request
                                        Log.w(TAG, "Error making game move: ${e.message}")
                                        eventBus.errorEvents.value =
                                            ErrorEvent.MiscError("Couldn't make online game move. ")
                                        stopOnlineGame()
                                        break
                                    }
                                    is TooManyRequestsException -> {
                                        Log.w(
                                            TAG,
                                            "Too many request sent to lichess, please wait and try again later."
                                        )
                                        eventBus.errorEvents.value =
                                            ErrorEvent.TooManyRequests(e.timeForValidRequests)
                                        stopOnlineGame()
                                        break
                                    }
                                    is IOException -> {
                                        Log.w(TAG, "Failed push to lichess game: ${e.message}")
                                        eventBus.errorEvents.value = ErrorEvent.InternetIOError()
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
                            clientToServerMessageProvider.forceBluetoothMoves(gameState)
                        )
                    } catch (e: IOException) {
                        Log.w(TAG, "Failed to write game state via bluetooth: ${e.message}")
                        eventBus.errorEvents.value = ErrorEvent.BluetoothIOError()
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
                                eventBus.errorEvents.value = ErrorEvent.BluetoothIOError()
                            }
                        }
                    }
                }
            }
        }

        // When the physical chessboard starts a different game while an online game is active, cancel the online game.
        coroutineScope.launch {
            chessBoardModel.gameInfo.collect { physicalBoardGameType ->
                val lichessGameId = activeGame.value.value?.id
                if (lichessGameId != null && lichessGameId != physicalBoardGameType?.gameId) {
                    Log.d(TAG, "canceling online game because another game started ")
                    stopOnlineGame()
                }
            }
        }
    }


    suspend fun signIn(
        response: AuthorizationResponse?,
        exception: AuthorizationException?
    ): LichessApi.UserInfo? {
        try {
            return webManager.signIn(response, exception).also {
                eventBus.successEvents.value = SuccessEvent.SignInSuccess(it)
            }
        } catch (e: Exception) {
            when (e) {
                is AuthorizationException,
                is NetworkException,
                is IOException,
                is JsonParseException -> {
                    eventBus.errorEvents.value = ErrorEvent.SignInError()
                    Log.w(TAG, "Error signing in: ${e.message}")
                }
                else -> throw e
            }
            return null
        }
    }

    fun signOut() {
        stopOnlineGame()
        stopBroadcast()
        webManager.signOut()
        eventBus.successEvents.value = SuccessEvent.SignOutSuccess()
    }


    fun startBroadcast() {
        if(chessBoardModel.gameInfo.value?.isOnlineGame() == true){
            eventBus.errorEvents.value = ErrorEvent.BroadcastCreatedWhileOnlineGameActive()
            return
        }
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
        val broadcastTournament = getFreshBroadcastTournament()
        val broadcastRound = webManager.createBroadcastRound(broadcastTournament)
        _broadcastRound.value = BroadcastEvent(broadcastRound)
        return broadcastRound
    }

    // calls createBroadcastRound() until no IOException occurs when creating it. In case of other exceptions, reports them and returns null.
    private suspend fun createBroadcastRoundAndRetry(): BroadcastRound? {
        _isLoadingBroadcast.value = true
        try {
            while (true) {
                yield()
                try {
                    Log.d(TAG, "creating broadcast round")
                    return createBroadcastRound()
                } catch (e: Exception) {
                    when (e) {
                        is NotSignedInException, is AuthorizationException -> {
                            Log.w(
                                TAG,
                                "Error with authorization to lichess creating broadcast round: ${e.message} "
                            )
                            eventBus.errorEvents.value =
                                ErrorEvent.NoLongerAuthorizedError()
                            return null
                        }
                        is GenericNetworkException, is JsonParseException -> {
                            // in case of an exception that can't be recovered from, stop retrying the request
                            Log.w(TAG, "Error creating broadcast round: ${e.message}")
                            eventBus.errorEvents.value =
                                ErrorEvent.MiscError("Couldn't create broadcast. ")
                            return null
                        }
                        is TooManyRequestsException -> {
                            Log.w(
                                TAG,
                                "Too many request sent to lichess, please wait and try again later."
                            )
                            eventBus.errorEvents.value =
                                ErrorEvent.TooManyRequests(e.timeForValidRequests)
                            return null
                        }
                        is IOException -> {
                            Log.w(TAG, "Failed creating broadcast: ${e.message}")
                            eventBus.errorEvents.value = ErrorEvent.InternetIOError()
                            delay(2500)
                            continue
                        }
                        else -> throw e
                    }
                }
            }
        } finally {
            _isLoadingBroadcast.value = false

        }
    }

    suspend fun writeSettings(settings: ChessBoardSettings) {
        try {
            bluetoothManager.writeMessage(clientToServerMessageProvider.writePreferences(settings))
            eventBus.successEvents.value = SuccessEvent.ChangeSettingsSuccess(settings)
        } catch (e: IOException) {
            Log.w(TAG, "Error writing settings: ${e.message}")
            eventBus.errorEvents.value = ErrorEvent.BluetoothIOError()
        }
    }

    suspend fun startTwoPlayerGame() {
        startOfflineGame(
            GameStartRequest(
                enableEngine = false,
                // engineColor and engineLevel don't matter, but they must be sent as part of a gameStartRequest, so fill them in with arbitrary values.
                engineColor = "black",
                engineLevel = 5
            )
        )
    }

    suspend fun startOfflineGame(gameStartRequest: GameStartRequest) {
        try {
            bluetoothManager.writeMessage(
                clientToServerMessageProvider.startNormalGame(gameStartRequest)
            )
            eventBus.successEvents.value = SuccessEvent.StartOfflineGameSuccess()
        } catch (e: IOException) {
            Log.w(TAG, "Error writing starting offline Game: ${e.message}")
            eventBus.errorEvents.value = ErrorEvent.BluetoothIOError()
        }
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
                            Log.d(TAG, "activeGame changed to $game")
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
                                eventBus.errorEvents.value = ErrorEvent.IllegalGameSelected()
                                shouldStop = true
                            }
                            is NotSignedInException,
                            is AuthorizationException -> {
                                Log.w(
                                    TAG,
                                    "Error ocoured with authorization to lichess: ${e.message}"
                                )
                                eventBus.errorEvents.value = ErrorEvent.NoLongerAuthorizedError()
                                shouldStop = true
                            }
                            is LichessApi.InvalidMessageException,
                            is JsonParseException,
                            is GenericNetworkException -> {
                                Log.w(TAG, "Invalid data received from lichess: ${e.message}")
                                eventBus.errorEvents.value =
                                    ErrorEvent.MiscError("Error playing online game")
                                shouldStop = true
                            }
                            is IOException -> {
                                Log.w(
                                    TAG,
                                    "Error occurred trying to stream lichess game: ${e.message}"
                                )
                                eventBus.errorEvents.value = ErrorEvent.InternetIOError()
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
        isOnlineGameActive.set(false)
        _activeGame.value = LichessGameEvent(null)
    }

    suspend fun blinkLeds() {
        try {
            bluetoothManager.writeMessage(clientToServerMessageProvider.testLeds())
            eventBus.successEvents.value = SuccessEvent.BlinkLedsSuccess()
        } catch (e: IOException) {
            eventBus.errorEvents.value = ErrorEvent.BluetoothIOError()
        }
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
            eventBus.errorEvents.value = ErrorEvent.BluetoothIOError()
        }
    }

    // utility class for separating IOExceptions that ocour from bluetooth and from internet IO within the same block.
    class BluetoothIOException(cause: java.lang.Exception) : java.lang.Exception(cause)

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
                    yield()
                    _pgnFilesUploadState.value =
                        PgnFileUploadState.UploadingToLichess(index, pgnFiles.files.size)
                    webManager.importGame(file.pgn)
                    try {
                        bluetoothManager.writeMessage(
                            clientToServerMessageProvider.requestArchivePgnFile(
                                file.name
                            )
                        )
                    } catch (e: IOException) {
                        throw BluetoothIOException(e)
                    }
                }
                eventBus.successEvents.value = SuccessEvent.UploadGamesSuccess()
            } catch (e: Exception) {
                when (e) {
                    is NotSignedInException,
                    is AuthorizationException -> {
                        Log.w(TAG, "Error uploading pgn files: ${e.message}")
                        eventBus.errorEvents.value = ErrorEvent.NoLongerAuthorizedError()
                    }
                    is GenericNetworkException,
                    is JsonParseException -> {
                        Log.w(TAG, "Error uploading pgn files: ${e.message}")
                        eventBus.errorEvents.value =
                            ErrorEvent.MiscError("Couldn't upload games.")
                    }
                    is TooManyRequestsException -> {
                        Log.w(TAG, "Error uploading pgn files: ${e.message}")
                        eventBus.errorEvents.value =
                            ErrorEvent.TooManyRequests(e.timeForValidRequests)
                    }
                    is IOException -> {
                        Log.w(TAG, "Error uploading pgn files: ${e.message}")
                        eventBus.errorEvents.value = ErrorEvent.InternetIOError()
                    }
                    else -> throw e
                }
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