package com.guykn.smartchessboard2.network.oauth2

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.guykn.smartchessboard2.network.lichess.LichessApi.UserInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.scopes.ServiceScoped
import net.openid.appauth.AuthState
import org.json.JSONException
import javax.inject.Inject

@ServiceScoped
class SavedAuthState @Inject constructor(@ApplicationContext context: Context) {
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
            if (!isAuthorized){
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
                .putString(KEY_USER_INFO, value?.username)
                .apply()
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

    private fun loadUserInfo() : UserInfo?{
        return sharedPreferences.getString(KEY_USER_INFO, null)?.let {
            UserInfo(it)
        }
    }
}
