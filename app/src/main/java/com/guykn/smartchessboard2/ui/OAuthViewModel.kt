package com.guykn.smartchessboard2.ui

import androidx.lifecycle.*
import com.google.gson.JsonParseException
import com.guykn.smartchessboard2.ServiceConnector
import com.guykn.smartchessboard2.network.lichess.WebManager
import com.guykn.smartchessboard2.ui.util.Event
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationResponse
import java.io.IOException
import javax.inject.Inject

@HiltViewModel
class OAuthViewModel @Inject constructor(private val serviceConnector: ServiceConnector) :
    ViewModel() {

    // TODO: 5/6/2021 check for memory leaks from the service getting a reference to this
    companion object {
        const val TAG = "MA_OAuthViewModel"
    }

    val oAuthStateLiveData: LiveData<WebManager.UiOAuthState> =
        serviceConnector.copyLiveData { repository -> repository.uiOAuthState }

    private val _errorLiveData = MutableLiveData<Event>()
    val errorLiveData: LiveData<Event> = _errorLiveData

    val isServiceConnected: LiveData<Boolean> = liveData {
        emit(false)
        serviceConnector.awaitConnected()
        emit(true)
    }

    override fun onCleared() {
        super.onCleared()
        serviceConnector.destroy()
    }


    fun onAuthorizationIntentFinished(
        response: AuthorizationResponse?,
        exception: AuthorizationException?
    ) {
        serviceConnector.callWhenConnected { repository ->
            viewModelScope.launch {
                try {
                    repository.signIn(response, exception)
                } catch (e: Exception) {
                    when (e) {
                        is AuthorizationException,
                        is JsonParseException,
                        is IOException -> {
                            e.printStackTrace()
                            onAuthorizationError()
                        }
                        else -> throw e
                    }
                }
            }
        }
    }

    fun signOut() {
        serviceConnector.callWhenConnected { repository ->
            repository.signOut()
        }
    }

    fun onAuthorizationError() {
        _errorLiveData.value = Event()
    }
}