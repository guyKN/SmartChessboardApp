package com.guykn.smartchessboard2

import android.bluetooth.BluetoothDevice
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonParseException
import com.guykn.smartchessboard2.ui.EventBus.ErrorEvent
import com.guykn.smartchessboard2.ui.EventBus.SuccessEvent
import com.guykn.smartchessboard2.bluetooth.*
import com.guykn.smartchessboard2.bluetooth.ChessBoardModel.BluetoothState.CONNECTED
import com.guykn.smartchessboard2.network.LichessRateLimitManager
import com.guykn.smartchessboard2.network.SavedBroadcastTournament
import com.guykn.smartchessboard2.network.lichess.BoolEvent
import com.guykn.smartchessboard2.network.lichess.LichessApi
import com.guykn.smartchessboard2.network.lichess.LichessApi.BroadcastRound
import com.guykn.smartchessboard2.network.lichess.LichessApi.BroadcastRoundResponse.BroadcastGame
import com.guykn.smartchessboard2.network.lichess.WebManager
import com.guykn.smartchessboard2.network.oauth2.GenericNetworkException
import com.guykn.smartchessboard2.network.oauth2.NetworkException
import com.guykn.smartchessboard2.network.oauth2.NotSignedInException
import com.guykn.smartchessboard2.network.oauth2.TooManyRequestsException
import com.guykn.smartchessboard2.ui.EventBus
import com.guykn.smartchessboard2.ui.util.EventWithValue
import dagger.hilt.android.scopes.ServiceScoped
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationResponse
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

// TODO: 6/30/2021 Make sure everything is closed properly when the service is destroyed
// todo: make sure that every exception that is thrown has a place to be caught. 
// TODO: 8/27/2021 extract all stateFlows outisde of the repository itself, since it sometimes causes akward moments where other classes need a refrence to the repository

typealias BroadcastEvent = EventWithValue<BroadcastRound?>
typealias LichessGameEvent = EventWithValue<LichessApi.Game?>
typealias BroadcastGameEvent = EventWithValue<BroadcastGame?>

@ServiceScoped
class Repository @Inject constructor(
    private val coroutineScope: CoroutineScope,
    private val webManager: WebManager,
    private val bluetoothManager: BluetoothManager,
    private val savedBroadcastTournament: SavedBroadcastTournament,
    private val clientToServerMessageProvider: ClientToServerMessageProvider,
    private val gson: Gson,
    private val lichessRateLimitManager: LichessRateLimitManager,
    val eventBus: EventBus,
    val chessBoardModel: ChessBoardModel,
    val customTabNavigationManager: CustomTabNavigationManager
) : BluetoothManager.PgnFilesCallback {

    companion object {
        private const val TAG = "MA_Repository"
    }

    private var isBroadcastActive = MutableStateFlow<Boolean>(false)

    private val _broadcastRound = MutableStateFlow<BroadcastEvent>(BroadcastEvent(null))

    private val _broadcastGame = MutableStateFlow<BroadcastGameEvent>(BroadcastGameEvent(null))
    val broadcastGame = _broadcastGame as StateFlow<BroadcastGameEvent>

    private val isOnlineGameActive = AtomicBoolean(false)

    private val _activeGame = MutableStateFlow<LichessGameEvent>(LichessGameEvent(null))
    val activeGame: StateFlow<LichessGameEvent> = _activeGame

    private val _onlineGameState: MutableStateFlow<LichessApi.LichessGameState?> =
        MutableStateFlow(null)
    val onlineGameState = _onlineGameState as StateFlow<LichessApi.LichessGameState?>

    val internetState: StateFlow<WebManager.InternetState> = webManager.internetState

    private var lichessGameJob: Job? = null
    private var broadcastJob: Job? = null
    private var broadcastUrlJob: Job? = null


    val uiOAuthState: StateFlow<WebManager.UiOAuthState> = webManager.uiAuthState

    sealed class PgnFilesUploadState {
        object NotUploading : PgnFilesUploadState()
        object ExchangingBluetoothData : PgnFilesUploadState()
        object UploadingToLichess : PgnFilesUploadState()
    }

    private val isRequestingPgnFiles: AtomicBoolean = AtomicBoolean(false)

    private val _pgnFilesUploadState: MutableStateFlow<PgnFilesUploadState> =
        MutableStateFlow(PgnFilesUploadState.NotUploading)
    val pgnFilesUploadState: StateFlow<PgnFilesUploadState> = _pgnFilesUploadState

    val isLoadingBroadcast: Flow<Boolean> =
        isBroadcastActive.combine(broadcastGame) { isBroadcastActive, broadcastGame ->
            isBroadcastActive && broadcastGame.value == null
        }

    val isLoadingOnlineGame: StateFlow<BoolEvent> = webManager.isLoadingOnlineGame

    // only one thread may import games at any time.
    private val importGameMutex = Mutex()

    init {
        // Whether a move is made on the physical chessboard, update the lichess broadcast.
        coroutineScope.launch outerLaunch@{
            var prevGameId: String? = null
            chessBoardModel.boardState.combinePairs(isBroadcastActive)
                .collect { (boardState, isBroadcastActive) ->
                    broadcastJob?.cancel()
                    broadcastJob = null
                    if (!isBroadcastActive) {
                        return@collect
                    }
                    val pgn = boardState?.pgn ?: return@collect
                    broadcastJob = launch {
                        val currentGameId = chessBoardModel.gameInfo.value?.gameId
                        val broadcastRound: BroadcastRound? = if (prevGameId != currentGameId) {
//                            if (chessBoardModel.gameActive.value != true) {
//                                Log.d(TAG, "gameActive not true. Returning. ")
//                                // if starting a broadcast for a new game, but that game is already over, don't upload it.
//                                return@launch
//                            }
                            createBroadcastRoundUntilSuccess()
                        } else {
                            _broadcastRound.value.value ?: createBroadcastRoundUntilSuccess()
                        }
                        if (broadcastRound == null) {
                            return@launch
                        }
                        prevGameId = currentGameId
                        // try to push to the broadcast until you succeed, retrying the request on errors.
                        while (true) {
                            Log.d(TAG, "inside of loop to push to broadcast. ")
                            yield()
                            // todo: replace this big chunk of code with a different method
                            try {
                                Log.d(TAG, "pushing pgn to broadcast: $pgn")
                                webManager.pushToBroadcast(broadcastRound, pgn)
                                updateBroadcastGameUrl()
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
            onlineGameState.collect { gameState ->
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
                    onlineGameState.value?.let { gameState ->
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
    }

    // when disconnecting from bluetooth, stop all bluetooth related tasks
    init {
        coroutineScope.launch {
            var prevState: ChessBoardModel.BluetoothState? = null
            chessBoardModel.bluetoothState.collect { currentState ->
                if (prevState == CONNECTED && currentState != CONNECTED) {
                    isRequestingPgnFiles.set(false)
                    _pgnFilesUploadState.value = PgnFilesUploadState.NotUploading
                }
                prevState = currentState
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
        savedBroadcastTournament.broadcastTournament = null
        savedBroadcastTournament.numRounds = 1
        eventBus.successEvents.value = SuccessEvent.SignOutSuccess()
    }

    fun startBroadcast() {
        coroutineScope.launch {
            // Only broadcast 2 player games.
            if (chessBoardModel.gameInfo.value?.isTwoPlayerGame() != true) {
                startTwoPlayerGame(fromUi = false)
            }
            isBroadcastActive.value = true
        }
    }

    fun stopBroadcast() {
        _broadcastRound.value = BroadcastEvent(null)
        _broadcastGame.value = BroadcastGameEvent(null)
        isBroadcastActive.value = false
        broadcastJob?.cancel()
    }

    private suspend fun getFreshBroadcastTournament(): LichessApi.BroadcastTournament {
        return savedBroadcastTournament.broadcastTournament
            ?: webManager.createBroadcastTournament().also {
                savedBroadcastTournament.broadcastTournament = it
            }
    }

    private suspend fun createBroadcastRound(): BroadcastRound {
        val broadcastTournament = getFreshBroadcastTournament()
        val roundNumber = savedBroadcastTournament.numRounds
        savedBroadcastTournament.numRounds++

        val broadcastRound = webManager.createBroadcastRound(
            broadcastTournament = broadcastTournament,
            name = "Game $roundNumber"
        )
        _broadcastRound.value = BroadcastEvent(broadcastRound)
        return broadcastRound
    }

    // calls createBroadcastRound() until no IOException occurs when creating it. In case of other exceptions, reports them and returns null.
    private suspend fun createBroadcastRoundUntilSuccess(): BroadcastRound? {
        _broadcastGame.value =
            BroadcastGameEvent(null) // when a new broadcast game is created, the broadcast round inside of it is invalidated.
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
    }

    private fun updateBroadcastGameUrl() {
        broadcastUrlJob?.cancel()
        broadcastUrlJob = coroutineScope.launch {
            try {
                if (_broadcastGame.value.value != null) {
                    // no need to update the broadcast game if it's already loaded.
                    return@launch
                }
                _broadcastRound.value.value?.let { round ->
                    _broadcastGame.value =
                        BroadcastGameEvent(webManager.getGameInBroadcastRound(round))
                } ?: Log.w(
                    TAG,
                    "updateBroadcastGame called while broadcastRound.value.value==null."
                )
                yield()
            } catch (e: Exception) {
                when (e) {
                    is NotSignedInException, is AuthorizationException -> {
                        Log.w(
                            TAG,
                            "Error with authorization to lichess when getting game URL in broadcast round: ${e.message} "
                        )
                        eventBus.errorEvents.value =
                            ErrorEvent.NoLongerAuthorizedError()
                    }
                    is GenericNetworkException, is JsonParseException -> {
                        // in case of an exception that can't be recovered from, stop retrying the request
                        Log.w(TAG, "Error getting game URL in broadcast round: ${e.message}")
                        eventBus.errorEvents.value =
                            ErrorEvent.MiscError("Couldn't create broadcast. ")
                    }
                    is TooManyRequestsException -> {
                        Log.w(
                            TAG,
                            "Too many request sent to lichess, please wait and try again later."
                        )
                        eventBus.errorEvents.value =
                            ErrorEvent.TooManyRequests(e.timeForValidRequests)
                    }
                    is IOException -> {
                        Log.w(TAG, "Failed to get game URL in broadcast round: ${e.message}")
                        eventBus.errorEvents.value = ErrorEvent.InternetIOError()
                    }
                    else -> throw e
                }
            }
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

    suspend fun startTwoPlayerGame(fromUi: Boolean = true) {
        startOfflineGame(
            GameStartRequest(
                enableEngine = false,
                // engineColor and engineLevel don't matter, but they must be sent as part of a gameStartRequest, so fill them in with arbitrary values.
                engineColor = "black",
                engineLevel = 5
            ),
            fromUi = fromUi
        )
    }


    // If fromUi is true, then this offline game was started by a button click from the UI, meaning we need to send a Success message,
    // and we need to stop the broadcast if it's ongoing.
    suspend fun startOfflineGame(gameStartRequest: GameStartRequest, fromUi: Boolean = true) {
        stopOnlineGame()
        if (fromUi) {
            stopBroadcast()
        }
        try {
            bluetoothManager.writeMessage(
                clientToServerMessageProvider.startNormalGame(gameStartRequest)
            )
            if (fromUi) {
                eventBus.successEvents.value = SuccessEvent.StartOfflineGameSuccess()
            }
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
        lichessGameJob = coroutineScope.launch {
            isOnlineGameActive.set(true)
            var shouldStop = false
            try {
                while (!shouldStop) {
                    yield()
                    stopBroadcast()
                    try {
                        webManager.awaitGameStart()?.let { game ->
                            _activeGame.value = LichessGameEvent(game)
                            Log.d(TAG, "activeGame changed to $game")
                            webManager.gameStream(game).collect { gameState ->
                                yield()
                                _onlineGameState.value = gameState
                            }
                            // if the flow completes without throwing an exception, then the game has ended.
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

    suspend fun archiveAllPgn() {
        try {
            val numPgnFiles = chessBoardModel.numGamesToUpload.value ?: kotlin.run {
                Log.w(TAG, "chessBoardModel.numGamesToUpload.value is null. ")
                return
            }
            bluetoothManager.writeMessage(clientToServerMessageProvider.requestArchiveAllPgn())
            eventBus.successEvents.value = SuccessEvent.ArchiveAllPgnSuccess(numPgnFiles)
        } catch (e: IOException) {
            eventBus.errorEvents.value = ErrorEvent.BluetoothIOError()
        }
    }


    fun setTargetBluetoothDevice(bluetoothDevice: BluetoothDevice?) {
        bluetoothManager.setTargetDevice(bluetoothDevice)
    }

    suspend fun uploadPgn() {
        Log.d(TAG, "uploading Pgn. ")
        if (isRequestingPgnFiles.getAndSet(true)) {
            Log.w(TAG, "called uploadPgn() while already requesting pgn files.")
            return
        }
        importGameMutex.withLock {
            if (!lichessRateLimitManager.mayUploadPgnFile()) {
                isRequestingPgnFiles.set(false)
                _pgnFilesUploadState.value = PgnFilesUploadState.NotUploading
                eventBus.successEvents.value = SuccessEvent.UploadGamesPartialSuccess()
                return
            }
            _pgnFilesUploadState.value = PgnFilesUploadState.ExchangingBluetoothData
            try {
                bluetoothManager.writeMessage(clientToServerMessageProvider.requestPgnFiles())
            } catch (e: IOException) {
                isRequestingPgnFiles.set(false)
                _pgnFilesUploadState.value = PgnFilesUploadState.NotUploading
                eventBus.errorEvents.value = ErrorEvent.BluetoothIOError()
            }
        }
    }

    // utility class for separating IOExceptions that occur from bluetooth and from internet IO within the same block.
    class BluetoothIOException(cause: java.lang.Exception) : java.lang.Exception(cause)

    override fun onPgnFileSent(messageData: String) {
        coroutineScope.launch {
            importGameMutex.withLock {
                if (!isRequestingPgnFiles.get()) {
                    Log.w(
                        TAG,
                        "Recieved pgn files while not requesting pgn files: $messageData"
                    )
                    return@launch
                }

                if (!lichessRateLimitManager.mayUploadPgnFile()) {
                    Log.w(
                        TAG,
                        "Too many pgn files requested to upload, which surpassed the Lichess limit. "
                    )
                    isRequestingPgnFiles.set(false)
                    _pgnFilesUploadState.value = PgnFilesUploadState.NotUploading
                    eventBus.successEvents.value = SuccessEvent.UploadGamesPartialSuccess()
                    return@launch
                }

                lichessRateLimitManager.onUploadFile()

                try {
                    val file = gson.fromJson(messageData, PgnFile::class.java)
                    _pgnFilesUploadState.value =
                        PgnFilesUploadState.UploadingToLichess
                    webManager.importGame(file.pgn)
                    try {
                        bluetoothManager.writeMessage(
                            clientToServerMessageProvider.requestArchivePgnFile(file.name)
                        )
                    } catch (e: IOException) {
                        throw BluetoothIOException(e)
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Exception occurred, stopped requesting pgn files")
                    isRequestingPgnFiles.set(false)
                    _pgnFilesUploadState.value = PgnFilesUploadState.NotUploading
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
                        is BluetoothIOException -> {
                            Log.w(TAG, "Error uploading pgn files: ${e.message}")
                            eventBus.errorEvents.value = ErrorEvent.BluetoothIOError()
                        }
                        is IOException -> {
                            Log.w(TAG, "Error uploading pgn files: ${e.message}")
                            eventBus.errorEvents.value = ErrorEvent.InternetIOError()
                        }
                        else -> throw e
                    }
                }
            }
        }
    }

    override fun onPgnFilesDone(messageData: String) {
        coroutineScope.launch {
            importGameMutex.withLock {
                if (isRequestingPgnFiles.getAndSet(false)) {
                    _pgnFilesUploadState.value = PgnFilesUploadState.NotUploading
                    eventBus.successEvents.value = SuccessEvent.UploadGamesSuccess()
                }
            }
        }
    }

    fun destroy() {
        webManager.destroy()
        bluetoothManager.destroy()

    }
}