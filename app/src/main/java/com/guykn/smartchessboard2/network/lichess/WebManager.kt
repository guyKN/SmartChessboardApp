package com.guykn.smartchessboard2.network.lichess

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonParseException
import com.guykn.smartchessboard2.network.lichess.LichessApi.UserInfo
import com.guykn.smartchessboard2.network.lines
import com.guykn.smartchessboard2.network.oauth2.*
import com.guykn.smartchessboard2.ui.util.Event
import com.guykn.smartchessboard2.ui.util.EventWithValue
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.scopes.ServiceScoped
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.openid.appauth.AuthState
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import okhttp3.OkHttpClient
import okhttp3.Request
import retrofit2.Response
import ru.gildor.coroutines.okhttp.await
import java.io.IOException
import javax.inject.Inject

typealias BoolEvent = EventWithValue<Boolean>

@ServiceScoped
class WebManager @Inject constructor(
    @ApplicationContext context: Context,
    private val savedAuthState: SavedAuthState,
    private val lichessApi: LichessApi,
    private val okHttpClient: OkHttpClient,
    private val gson: Gson,
    private val coroutineScope: CoroutineScope
) {
    companion object {
        private const val TAG = "MA_WebManager"
        private const val TOO_MANY_REQUEST_DELAY = 60000
    }

    sealed class InternetState {
        object Connected : InternetState()
        object NotConnected : InternetState()
        class TooManyRequests(val endTime: Long) : InternetState()
    }

    sealed class UiOAuthState : Event() {
        class NotAuthorized : UiOAuthState()
        class AuthorizationLoading : UiOAuthState()
        class Authorized(val userInfo: LichessApi.UserInfo) : UiOAuthState()
    }

    private val _internetState = MutableStateFlow<InternetState>(InternetState.Connected)
    val internetState = _internetState as StateFlow<InternetState>

    private val _uiAuthState = MutableStateFlow<UiOAuthState>(savedAuthState.currentUiAuthState())
    val uiAuthState = _uiAuthState as StateFlow<UiOAuthState>

    private val _isLoadingOnlineGame: MutableStateFlow<BoolEvent> =
        MutableStateFlow(BoolEvent(false))

    val isLoadingOnlineGame: StateFlow<BoolEvent> = _isLoadingOnlineGame

    private val importGameLock = Mutex()

    init {
        coroutineScope.launch {
            isLoadingOnlineGame.collect {
                Log.d(TAG, "isLoadingOnlineGame collected: $it")
            }
        }
    }


    private val authService = AuthorizationService(context)

    // when recieving a 429 http code from the server, we must wait 1 minute until resuming API usage. this variable tracks the time that we are allowed to use the API in.
    private var timeForValidRequests: Long = 0

    private inline fun <T> networkCall(block: () -> T): T {
        try {
            checkTooManyRequests()
            val ret = block()
            _internetState.value = InternetState.Connected
            return ret
        } catch (e: Exception) {
            when (e) {
                is AuthorizationException -> {
                    if (e.type == AuthorizationException.TYPE_GENERAL_ERROR) {
                        _internetState.value = InternetState.NotConnected
                    }
                }
                // NotSignedInException and TooManyRequestsException don't mean that the internet isn't working, so we don't have to set isInternetWorking to False
                is NotSignedInException, is TooManyRequestsException -> {
                }
                is IOException -> {
                    _internetState.value = InternetState.NotConnected
                }
            }
            throw e
        }
    }

    @Throws(
        AuthorizationException::class,
        NetworkException::class,
        JsonParseException::class,
        IOException::class
    )
    suspend fun signIn(
        authResponse: AuthorizationResponse?,
        exception: AuthorizationException?
    ): UserInfo = networkCall {
        _uiAuthState.value = UiOAuthState.AuthorizationLoading()
        try {
            val authState = AuthState(authResponse, exception)
            exception?.let {
                throw it
            } ?: authResponse?.let { response ->
                val tokenResponse = authService.performCoroutineTokenRequest(
                    response.createTokenExchangeRequest()
                )
                authState.update(tokenResponse, null)
                savedAuthState.authState = authState
                val accessToken = getFreshToken()
                val userInfoResponse: Response<UserInfo> =
                    lichessApi.getUserInfo(formatAuthHeader(accessToken))

                when (userInfoResponse.code()) {
                    200 -> {
                        userInfoResponse.body()?.let {
                            savedAuthState.userInfo = it
                            _uiAuthState.value = UiOAuthState.Authorized(it)
                            return it
                        }
                            ?: throw GenericNetworkException("Error creating broadcast tournament: got http code 200, but no response body. ")
                    }
                    401 -> {
                        savedAuthState.clear()
                        throw NotSignedInException("Error getting user info: http code 401 unauthorized error.")
                    }
                    429 -> on429httpStatus()
                    else -> {
                        savedAuthState.clear()
                        throw GenericNetworkException("Error creating broadcast round: Received http error code: ${userInfoResponse.code()}. ")
                    }
                }
            }
            ?: throw IllegalStateException("Either response or exception must be non-null for fetch tokens")
        } catch (e: Throwable) {
            _uiAuthState.value = UiOAuthState.NotAuthorized()
            throw e
        }
    }

    @Throws(
        AuthorizationException::class,
        NotSignedInException::class,
        IOException::class,
        GenericNetworkException::class,
        TooManyRequestsException::class,
        JsonParseException::class
    )
    suspend fun createBroadcastTournament(
        name: String = "Test",
        description: String = "Test"
    ): LichessApi.BroadcastTournament = networkCall {
        val authorization: String = formatAuthHeader(getFreshToken())
        val response: Response<LichessApi.BroadcastTournamentWrapper> =
            lichessApi.createBroadcastTournament(authorization, name, description)

        when (response.code()) {
            200 -> {
                return response.body()?.tour
                    ?: throw GenericNetworkException("Error creating broadcast tournament: got http code 200, but no response body. ")
            }
            401 -> {
                Log.w(TAG, "Authorization is invalid or has expired, signing out.")
                signOut()
                throw NotSignedInException("Error creating broadcast round: Received http code 401 unauthorized. ")
            }
            429 -> on429httpStatus()
            else -> throw GenericNetworkException("Error creating broadcast round: Received http error code: ${response.code()}. ")
        }
    }

    @Throws(
        AuthorizationException::class,
        NotSignedInException::class,
        IOException::class,
        GenericNetworkException::class,
        TooManyRequestsException::class
    )
    suspend fun createBroadcastRound(
        broadcastTournament: LichessApi.BroadcastTournament,
        name: String = "Test"
    ): LichessApi.BroadcastRound = networkCall {
        val authorization: String = formatAuthHeader(getFreshToken())
        val response: Response<LichessApi.BroadcastRound> =
            lichessApi.createBroadcastRound(authorization, broadcastTournament.id, name)
        when (response.code()) {
            200 -> {
                return response.body()
                    ?: throw GenericNetworkException("Error creating broadcast round: Response code was 200, but body was null. ")
            }
            401 -> {
                Log.w(TAG, "Authorization is invalid or has expired, signing out.")
                signOut()
                throw NotSignedInException("Error creating broadcast round: Received http code 401 unauthorized. ")
            }
            429 -> on429httpStatus()
            else -> throw GenericNetworkException("Error creating broadcast round: Received http error code: ${response.code()}. ")
        }
    }


    suspend fun pushToBroadcast(
        broadcastRound: LichessApi.BroadcastRound,
        pgn: String
    ) = networkCall {
        val authorization: String = formatAuthHeader(getFreshToken())
        val response = lichessApi.updateBroadcast(authorization, broadcastRound.id, pgn)
        Log.d(TAG, "response.code: ${response.code()}")
        when (response.code()) {
            200 -> {
                response.body()?.let {
                    if (!it.ok) {
                        throw GenericNetworkException("Server returned code of ok=false when pushing to broadcast. ")
                    }
                }
                    ?: throw GenericNetworkException("Response code was 200, but body was null when pushing to broadcast. ")
            }
            401 -> {
                Log.w(TAG, "Authorization is invalid or has expired, signing out.")
                signOut()
                throw NotSignedInException("Error pushing pgn to broadcast: Received http code 401 unauthorized. ")
            }
            429 -> on429httpStatus()
            else -> throw GenericNetworkException("Received http error code: ${response.code()} when pushing to broadcast. ")
        }
    }

    suspend fun pushGameMove(game: LichessApi.Game, move: String) = networkCall {
        val authorization: String = formatAuthHeader(getFreshToken())
        val response = lichessApi.pushBoardMove(authorization, game.id, move)
        when (response.code()) {
            200 -> {
                response.body()?.let {
                    if (!it.ok) {
                        throw GenericNetworkException("Server returned code of ok=false when pushing move to game. ")
                    }
                }
                    ?: throw GenericNetworkException("Response code was 200, but body was null when pushing move to game. ")
            }
            401 -> {
                Log.w(TAG, "Authorization is invalid or has expired, signing out. ")
                signOut()
                throw NotSignedInException("Error pushing move to game: Received http code 401 unauthorized. ")
            }
            429 -> on429httpStatus()
            else -> throw GenericNetworkException("Received http error code: ${response.code()} when pushing move to game. ")
        }
    }

    suspend fun importGame(pgn: String): LichessApi.ImportedGame = networkCall {
        importGameLock.withLock<LichessApi.ImportedGame> {
            val authorization: String = formatAuthHeader(getFreshToken())
            val response = lichessApi.importGame(authorization, pgn)
            when (response.code()) {
                200 -> {
                    response.body()?.let {
                        return it
                    }
                        ?: throw GenericNetworkException("Response code was 200, but body was null when importing game. ")
                }
                401 -> {
                    Log.w(TAG, "Authorization is invalid or has expired, signing out. ")
                    signOut()
                    throw NotSignedInException("Error pushing move to game: Received http code 401 unauthorized. ")
                }
                429 -> on429httpStatus()
                else -> throw GenericNetworkException("Received http error code: ${response.code()} when importing game. ")
            }
        }
    }

    val gameEvents: Flow<LichessApi.GameEvent> = flow<LichessApi.GameEvent> {
        networkCall {
            val authorization: String = formatAuthHeader(getFreshToken())
            val request = Request.Builder()
                .url("https://lichess.org/api/stream/event")
                .header("Authorization", authorization)
                .build()

            val response = try {
                _isLoadingOnlineGame.value = BoolEvent(true)
                okHttpClient.newCall(request).await()
            } finally {
                coroutineScope.launch {
                    // Because it takes a few milliseconds for lichess to send the information
                    // about current games through the stream, we don't know for sure if the user if
                    // the user is in the middle of a game or not immidiatly when the connection is created.
                    // Since it will certainly take less than 1 second for current games to be sent,
                    // we wait 1 second before setting isLoadingOnlineGame to false so that nothing is missed.
                    delay(1000)
                    _isLoadingOnlineGame.value = BoolEvent(false)
                }
            }


            response.use {
                when (val code = response.code) {
                    200 -> {
                        Log.d(TAG, "starting to read lines")
                        _internetState.value = InternetState.Connected
                        response.body?.lines?.collect { line ->
                            Log.d(TAG, "read line: $line")
                            val event = gson.fromJson(line, LichessApi.GameEvent::class.java)
                            Log.d(TAG, "parsed json: $event")
                            emit(event)
                        }
                            ?: throw GenericNetworkException("Received http code 200, but no response body. ")
                    }
                    401 -> {
                        Log.w(TAG, "Authorization is invalid or has expired, signing out.")
                        signOut()
                        throw NotSignedInException("Error streaming game events: Received http code 401 unauthorized. ")
                    }
                    429 -> on429httpStatus()
                    else -> {
                        throw GenericNetworkException("Lichess returned http error code: $code.")
                    }
                }
            }
        }
    }

    val gameStartStream: Flow<LichessApi.Game> = gameEvents.transform { event ->
        if (event.type == "gameStart" && event.game != null) {
            emit(event.game)
        }
    }

    suspend fun awaitGameStart(): LichessApi.Game? = withContext(Dispatchers.IO) {
        networkCall {
            val authorization: String = formatAuthHeader(getFreshToken())
            val request = Request.Builder()
                .url("https://lichess.org/api/stream/event")
                .header("Authorization", authorization)
                .build()

            val response = try {
                _isLoadingOnlineGame.value = BoolEvent(true)
                okHttpClient.newCall(request).await()
            } finally {
                coroutineScope.launch {
                    // Because it takes a few milliseconds for lichess to send the information
                    // about current games through the stream, we don't know for sure if the user if
                    // the user is in the middle of a game or not immidiatly when the connection is created.
                    // Since it will certainly take less than 1 second for current games to be sent,
                    // we wait 1 second before setting isLoadingOnlineGame to false so that nothing is missed.
                    delay(1000)
                    _isLoadingOnlineGame.value = BoolEvent(false)
                }
            }

            response.use {
                when (val code = response.code) {
                    200 -> {
                        Log.d(TAG, "starting to read lines")
                        _internetState.value = InternetState.Connected
                        val body = response.body ?: throw GenericNetworkException("Received http code 200, but no response body. ")

                        return@withContext body.lines.map<String, LichessApi.GameEvent?> { line ->
                            Log.d(TAG, "read line: $line")
                            val event = gson.fromJson(line, LichessApi.GameEvent::class.java)
                            Log.d(TAG, "parsed json: $event")
                            event

                        }.transform { event ->
                            if (event?.type == "gameStart" && event.game != null) {
                                emit(event.game)
                            }
                        }.firstOrNull()
                    }
                    401 -> {
                        Log.w(TAG, "Authorization is invalid or has expired, signing out.")
                        signOut()
                        throw NotSignedInException("Error streaming game events: Received http code 401 unauthorized. ")
                    }
                    429 -> on429httpStatus()
                    else -> {
                        throw GenericNetworkException("Lichess returned http error code: $code.")
                    }
                }
            }
        }
    }

    fun gameStream(game: LichessApi.Game): Flow<LichessApi.LichessGameState> = flow {
        networkCall {
            Log.d(TAG, "Streaming Game")
            val userInfo = savedAuthState.userInfo
                ?: throw NotSignedInException("Tried to stream game state while not signed in")
            val authorization: String = formatAuthHeader(getFreshToken())
            val request = Request.Builder()
                .url("https://lichess.org/api/board/game/stream/${game.id}")
                .header("Authorization", authorization)
                .build()
            okHttpClient.newCall(request).await().use { response ->
                when (response.code) {
                    200 -> {
                        _internetState.value = InternetState.Connected
                        var playerColor: String? = null
                        response.body?.lines?.collect { line ->
                            try {
                                Log.d(TAG, "Line from lichess game stream: \n$line")
                                val gameStreamLine =
                                    gson.fromJson(line, LichessApi.GameStreamLine::class.java)
                                when (gameStreamLine.type) {
                                    "gameFull" -> {
                                        if (!gameStreamLine.isValidGame()) {
                                            throw LichessApi.InvalidGameException("User Tried to Connect to an invalid game. Only rapid and classic time controls are supported. ")
                                        }
                                        playerColor = gameStreamLine.playerColor(userInfo)
                                            ?: throw LichessApi.InvalidGameException("User tried to play in game a he does not own.")

                                        val stateLine = gameStreamLine.state
                                            ?: throw LichessApi.InvalidMessageException("field 'state' must be non-null for type=='gameFull'")

                                        if (stateLine.type != "gameState") {
                                            throw LichessApi.InvalidMessageException("field 'state' must be non-null for type=='gameFull'")
                                        }
                                        val gameState = stateLine.toGameState(
                                            playerColor = playerColor
                                                ?: throw LichessApi.InvalidMessageException("User tried to player in a game he does not own."),
                                            gameId = game.id
                                        )
                                        emit(gameState)
                                        if (gameState.isGameOver) {
                                            response.close()
                                        }
                                    }
                                    "gameState" -> {
                                        val gameState = gameStreamLine.toGameState(
                                            playerColor = playerColor
                                                ?: throw LichessApi.InvalidMessageException("'gameState' message sent before 'gameFull'"),
                                            gameId = game.id
                                        )
                                        emit(gameState)
                                        if (gameState.isGameOver) {
                                            response.close()
                                        }
                                    }
                                }
                            } catch (e: JsonParseException) {
                                Log.w(TAG, "Failed to parse line: ${e.message} \n$line")
                                throw e
                            } catch (e: LichessApi.InvalidMessageException) {
                                Log.w(TAG, "Invalid Line: ${e.message} \n$line")
                                throw e
                            }
                        }
                            ?: throw GenericNetworkException("Received http code 200, but no response body. ")

                    }
                    400 ->{
                        throw LichessApi.InvalidGameException("Recieved http code 400 when streaming game. Most likely because user tried to play an illegal game mode or time control. ")
                    }
                    401 -> {
                        Log.w(TAG, "Authorization is invalid or has expired, signing out.")
                        signOut()
                        throw NotSignedInException("Error streaming game state: Received http code 401 unauthorized. ")
                    }
                    429 -> on429httpStatus()
                    else -> throw GenericNetworkException("Lichess returned code: ${response.code} when trying to stream game state. ")
                }
            }
        }
    }

    fun signOut() {
        savedAuthState.clear()
        _uiAuthState.value = UiOAuthState.NotAuthorized()
    }

    fun destroy() {
        authService.dispose()
    }

    @Throws(AuthorizationException::class, NotSignedInException::class)
    private suspend fun getFreshToken(): String {
        return savedAuthState.authState?.fetchTokensCoroutine(authService)
            ?: throw NotSignedInException("Tried to get access token while authState was null.")
    }

    private fun checkTooManyRequests() {
        if (System.currentTimeMillis() < timeForValidRequests) {
            _internetState.value = InternetState.TooManyRequests(timeForValidRequests)
            throw TooManyRequestsException(timeForValidRequests)
        }
    }

    private fun on429httpStatus(): Nothing {
        timeForValidRequests = System.currentTimeMillis() + TOO_MANY_REQUEST_DELAY
        _internetState.value = InternetState.TooManyRequests(timeForValidRequests)
        throw TooManyRequestsException(timeForValidRequests)
    }
}