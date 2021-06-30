package com.guykn.smartchessboard2.network.oauth2

import android.content.Intent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.openid.appauth.*
import okhttp3.ResponseBody
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

sealed class NetworkException: IOException{
    constructor() : super()
    constructor(message: String) : super(message)
    constructor(cause: Throwable) : super(cause)
}

class NotSignedInException : NetworkException {
    constructor() : super()
    constructor(message: String) : super(message)
    constructor(cause: Throwable) : super(cause)
}

class GenericNetworkException : NetworkException {
    constructor() : super()
    constructor(message: String) : super(message)
    constructor(cause: Throwable) : super(cause)
}

class TooManyRequestsException : NetworkException {
    constructor() : super()
    constructor(message: String) : super(message)
    constructor(cause: Throwable) : super(cause)
}

fun AuthorizationService.getLichessAuthIntent(): Intent {
    val authRequest = AuthorizationRequest.Builder(
        serviceConfig,
        CLIENT_ID,
        ResponseTypeValues.CODE,
        REDIRECT_URI
    )
        .setScopes(TARGET_OAUTH_SCOPES.asIterable())
        .build()
    return getAuthorizationRequestIntent(authRequest)
}

@Throws(AuthorizationException::class)
suspend fun AuthorizationService.performCoroutineTokenRequest(
    request: TokenRequest,
    clientAuth: ClientAuthentication = NoClientAuthentication.INSTANCE
): TokenResponse =
    suspendCoroutine { continuation ->
        performTokenRequest(request, clientAuth) { response, exception ->
            exception?.let {
                continuation.resumeWithException(exception)
            } ?: response?.let {
                continuation.resume(response)
            } ?: continuation.resumeWithException(
                IllegalArgumentException(
                    "Exactly one of response and exception must be non-null in performTokenRequest callback"
                )
            )

        }
    }

@Throws(AuthorizationException::class)
suspend fun AuthState.fetchTokensCoroutine(
    authService: AuthorizationService,
    clientAuth: ClientAuthentication = NoClientAuthentication.INSTANCE
): String = suspendCoroutine { continuation ->
    if (!isAuthorized) {
        throw NotSignedInException("tried to fetch access tokens while not signed in.")
    }
    performActionWithFreshTokens(authService, clientAuth) { accessToken, _, exception ->
        exception?.let {
            continuation.resumeWithException(exception)
        } ?: accessToken?.let {
            continuation.resume(accessToken)
        } ?: continuation.resumeWithException(
            NotSignedInException("Tried to fetch access tokens while not signed in.")
        )
    }
}

fun formatAuthHeader(accessToken: String): String{
    return "Bearer $accessToken"
}

suspend fun ResponseBody.stringCoroutine() : String = withContext(Dispatchers.IO){
    string()
}