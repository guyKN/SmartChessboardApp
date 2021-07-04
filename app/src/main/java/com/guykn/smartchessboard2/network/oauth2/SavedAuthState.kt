package com.guykn.smartchessboard2.network.oauth2

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonParseException
import com.guykn.smartchessboard2.network.lichess.LichessApi.UserInfo
import com.guykn.smartchessboard2.network.lichess.WebManager
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.scopes.ServiceScoped
import net.openid.appauth.AuthState
import org.json.JSONException
import javax.inject.Inject

@ServiceScoped
class SavedAuthState @Inject constructor(
    @ApplicationContext context: Context,
    private val gson: Gson
) {
    // TODO: 7/4/2021 Make UserInfo also include the user's id
    companion object {
        private const val TAG = "MA_SavedAuthState"

        private const val KEY_AUTH = "auth"
        private const val KEY_STATE_JSON = "stateJson"
        private const val KEY_USER_INFO = "user_info"
    }

    private val sharedPreferences: SharedPreferences =
        context.getSharedPreferences(KEY_AUTH, Context.MODE_PRIVATE)

    var authState: AuthState? = loadAuthState()
        set(value) {
            field = value
            sharedPreferences.edit()
                .putString(KEY_STATE_JSON, value?.jsonSerializeString())
                .apply()
            if (!isAuthorized) {
                userInfo = null
            }
        }

    var userInfo: UserInfo? = loadUserInfo()
        get() {
            return field.takeIf { authState?.isAuthorized == true }
        }
        set(value) {
            field = value
            sharedPreferences.edit()
                .putString(KEY_USER_INFO, value?.let { gson.toJson(it) })
                .apply()
        }

    fun currentUiAuthState(): WebManager.UiOAuthState {
        return userInfo?.let {
            WebManager.UiOAuthState.Authorized(it)
        } ?: WebManager.UiOAuthState.NotAuthorized()
    }


    fun clear() {
        userInfo = null
        authState = null
    }

    private val isAuthorized
        get() = userInfo != null

    private fun loadAuthState(): AuthState? {
        return sharedPreferences.getString(KEY_STATE_JSON, null)?.let {
            try {
                AuthState.jsonDeserialize(it)
            } catch (e: JSONException) {
                Log.w(TAG, "failed to parse json: \n$it")
                null
            }
        }
    }

    private fun loadUserInfo(): UserInfo? {
        return sharedPreferences.getString(KEY_USER_INFO, null)?.let {
            try {
                gson.fromJson(it, UserInfo::class.java)
            } catch (e: JsonParseException) {
                Log.w(TAG, "error parsing userInfo json: \n$it")
                null
            }
        }
    }
}
