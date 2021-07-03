package com.guykn.smartchessboard2.network.lichess

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonParseException
import com.guykn.smartchessboard2.network.lichess.LichessApi.UserInfo
import com.guykn.smartchessboard2.network.lines
import com.guykn.smartchessboard2.network.oauth2.*
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.scopes.ServiceScoped
import kotlinx.coroutines.flow.*
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

// TODO: 6/26/2021 Store lichess User id in addition to username, and use it.
// TODO: 6/30/2021 Incorporate the new lichess OAuth protocol

@ServiceScoped
class WebManager @Inject constructor(
    @ApplicationContext context: Context,
    private val savedAuthState: SavedAuthState,
    private val lichessApi: LichessApi,
    private val okHttpClient: OkHttpClient,
    private val gson: Gson
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

    private val _internetState = MutableStateFlow<InternetState>(InternetState.Connected)
    val internetState = _internetState as StateFlow<InternetState>

    private val authService = AuthorizationService(context)

    // TODO: 6/26/2021 save this value on disk, so that closing and opening the app will keep it.
    // when recieving a 429 http code from the server, we must wait 1 minute until resuming API usage. this variable tracks the time that we are allowed to use the API in.
    private var timeForValidRequests: Long = 0

    val isLoggedIn
        get() = userInfo !== null

    val userInfo
        get() = savedAuthState.userInfo

    /**
     * Utility function that
     */
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
        val authState = AuthState(authResponse, exception)
        exception?.let {
            throw it
        } ?: authResponse?.let { response ->
            val tokenResponse = authService.performCoroutineTokenRequest(
                response.createTokenExchangeRequest(),
                CLIENT_AUTH
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
    }

    @Throws(AuthorizationException::class, NotSignedInException::class)
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
        val authorization: String = formatAuthHeader(getFreshToken())
        val response = lichessApi.importGame(authorization, pgn)
        when(response.code()){
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

    val gameEvents: Flow<LichessApi.GameEvent> = flow<LichessApi.GameEvent> {
        networkCall {
            val authorization: String = formatAuthHeader(getFreshToken())
            val request = Request.Builder()
                .url("https://lichess.org/api/stream/event")
                .header("Authorization", authorization)
                .build()
            okHttpClient.newCall(request).await().use { response ->
                when (val code = response.code) {
                    200 -> {
                        _internetState.value = InternetState.Connected
                        response.body?.lines?.collect {
                            val event = gson.fromJson(it, LichessApi.GameEvent::class.java)
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

    // todo: make sure this closes the connecting after finding 1 game
    suspend fun awaitGameStart(): LichessApi.Game? {
        return try {
            gameStartStream.first()
        } catch (e: NoSuchElementException) {
            null
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
                                val gameStreamLine =
                                    gson.fromJson(line, LichessApi.GameStreamLine::class.java)
                                when (gameStreamLine.type) {
                                    "gameFull" -> {
                                        if (!gameStreamLine.isValidGame()) {
                                            throw LichessApi.InvalidGameException("User Tried to Connect to an invalid game. Only rapid and classic time controls are supported. ")
                                        }
                                        playerColor = gameStreamLine.playerColor(userInfo)
                                            ?: throw LichessApi.InvalidGameException("User tried to play in game a he does not own.")

                                        val state = gameStreamLine.state
                                            ?: throw LichessApi.InvalidMessageException("field 'state' must be non-null for type=='gameFull'")

                                        if (state.type != "gameState") {
                                            throw LichessApi.InvalidMessageException("field 'state' must be non-null for type=='gameFull'")
                                        }
                                        val gameState = state.toGameState(
                                            playerColor = playerColor ?: throw LichessApi.InvalidMessageException("User tried to playe in a game he does not own."),
                                            gameId = game.id
                                        )
                                        emit(gameState)
                                        if (gameState.isGameOver) {
                                            response.close()
                                        }
                                    }
                                    "gameState" -> {
                                        val gameState = gameStreamLine.toGameState(
                                            playerColor = playerColor ?: throw LichessApi.InvalidMessageException("'gameState' message sent before 'gameFull'"),
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


    // TODO: 5/12/2021 Make this function sign out using oAuth2 sign out protocol
    fun signOut() {
        savedAuthState.clear()
    }

    fun destroy() {
        authService.dispose()
    }

    @Throws(AuthorizationException::class, NotSignedInException::class)
    private suspend fun getFreshToken(): String {
        return savedAuthState.authState?.fetchTokensCoroutine(authService, CLIENT_AUTH)
            ?: throw NotSignedInException("Tried to get access token while authState was null.")
    }

    private fun checkTooManyRequests() {
        if (System.currentTimeMillis() < timeForValidRequests) {
            _internetState.value = InternetState.TooManyRequests(timeForValidRequests)
            throw TooManyRequestsException("Lichess API is overloaded, please try again later. ")
        }
    }

    private fun on429httpStatus(): Nothing {
        timeForValidRequests = System.currentTimeMillis() + TOO_MANY_REQUEST_DELAY
        _internetState.value = InternetState.TooManyRequests(timeForValidRequests)
        throw TooManyRequestsException("Lichess API is overloaded, please try again later. ")
    }
}