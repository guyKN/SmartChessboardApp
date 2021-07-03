package com.guykn.smartchessboard2.network.oauth2

import android.net.Uri
import net.openid.appauth.*

internal val serviceConfig = AuthorizationServiceConfiguration(
    Uri.parse("https://lichess.org/oauth"),  // authorization endpoint
    Uri.parse("https://lichess.org/api/token") // token endpoint
)

internal const val LICHESS_BASE_URL = "https://lichess.org"

internal const val CLIENT_ID = "gGB0nNuFu2vHyE30"

internal val REDIRECT_URI = Uri.parse("com.guykn.chessboard3://oauth2/callback")!!

internal val TARGET_OAUTH_SCOPES = arrayOf("board:play", "study:read", "study:write")