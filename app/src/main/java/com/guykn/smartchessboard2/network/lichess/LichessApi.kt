package com.guykn.smartchessboard2.network.lichess

import com.guykn.smartchessboard2.network.oauth2.LICHESS_BASE_URL
import retrofit2.Response
import retrofit2.http.*

interface LichessApi {

    class InvalidGameException(message: String) : Exception(message)

    /**
     * Thrown when the Lichess API Gives a response that is missing a field or violates it's contract.
     */
    class InvalidMessageException(message: String) : Exception(message)

    data class UserInfo(val id: String, val username: String){
        fun importedGamesUrl(): String = "https://lichess.org/@/$id/import#games"
    }

    @GET("/api/account")
    suspend fun getUserInfo(@Header("Authorization") authentication: String): Response<UserInfo>

    data class BroadcastTournamentWrapper(val tour: BroadcastTournament)
    data class BroadcastTournament(val id: String, val url: String)

    @FormUrlEncoded
    @POST("/broadcast/new")
    suspend fun createBroadcastTournament(
        @Header("Authorization") authentication: String,
        @Field("name") name: String,
        @Field("description") description: String
    ): Response<BroadcastTournamentWrapper>

    data class BroadcastRound(val id: String, val url: String)

    @FormUrlEncoded
    @POST("/broadcast/{broadcastTournamentId}/new")
    suspend fun createBroadcastRound(
        @Header("Authorization") authentication: String,
        @Path("broadcastTournamentId") broadcastTournamentId: String,
        @Field("name") name: String
    ): Response<BroadcastRound>

    data class OkResponse(val ok: Boolean)

    @POST("/broadcast/round/{broadcastRoundId}/push")
    suspend fun updateBroadcast(
        @Header("Authorization") authentication: String,
        @Path("broadcastRoundId") broadcastRoundId: String,
        @Body pgn: String
    ): Response<OkResponse>


    @POST("/api/board/game/{gameId}/move/{move}")
    suspend fun pushBoardMove(
        @Header("Authorization") authentication: String,
        @Path("gameId") broadcastRoundId: String,
        @Path("move") move: String
    ): Response<OkResponse>

    data class ImportedGame(val url: String)

    @FormUrlEncoded
    @POST("/api/import")
    suspend fun importGame(
        @Header("Authorization") authentication: String,
        @Field("pgn") pgn: String
    ): Response<ImportedGame>

    data class EmailResponse(val email: String)

    @GET("/api/account/email")
    suspend fun getEmail(
        @Header("Authorization") authentication: String,
    ): Response<EmailResponse>


    data class GameEvent(val type: String, val game: Game?)
    data class Game(val id: String) {
        val url get() = "$LICHESS_BASE_URL/$id"
    }

    data class GameStreamLine(
        val type: String,

        // for type == "gameFull"
        val variant: Variant?,
        val speed: String?,
        val white: Player?,
        val black: Player?,
        val initialFen: String?,
        val state: GameStreamLine?,

        // for type == "gameState"
        val moves: String?,
        val status: String?,
        val winner: String?
    ) {
        fun isValidGame(): Boolean {
            check(type == "gameFull") { "May only check if a game is valid for line with type=='gameFull'" }
            return variant?.key == "standard"
                    && (speed == "classical" || speed == "rapid" || speed == "correspondence" || speed == "unlimited")
                    && initialFen == "startpos"
        }

        fun playerColor(userInfo: UserInfo): String? {
            return when (userInfo.id) {
                white?.id -> "white"
                black?.id -> "black"
                else -> null
            }
        }

        fun toGameState(playerColor: String, gameId: String): LichessGameState {
            check(type == "gameState") { "May only convert to gameState when type=='gameState'" }
            return LichessGameState(
                gameId = gameId,
                clientColor = playerColor,
                moves = moves ?: throw InvalidMessageException("Moves must be non-null for when type=='gameState'"),
                winner = if (status == "draw") "draw" else winner
            )
        }
    }

    data class Variant(val key: String)
    data class Player(val id: String?)

    data class LichessGameState(
        val gameId: String,
        val clientColor: String,
        val moves: String,
        val winner: String?
    ) {
        val isGameOver
            get() = winner != null
    }

}