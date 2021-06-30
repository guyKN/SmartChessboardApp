package com.guykn.smartchessboard2.ui

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.JsonParseException
import com.guykn.smartchessboard2.Repository
import com.guykn.smartchessboard2.ServiceConnector
import com.guykn.smartchessboard2.network.lichess.LichessApi
import com.guykn.smartchessboard2.network.oauth2.GenericNetworkException
import com.guykn.smartchessboard2.network.oauth2.NetworkException
import com.guykn.smartchessboard2.network.oauth2.NotSignedInException
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

    // todo: save uiAuthState in the repository or webmanager instead of in ViewModel, so that it updates when errors occur.
    sealed class UiOAuthState : Event() {
        class NotYetLoaded : UiOAuthState()
        class NotAuthorized : UiOAuthState()
        class AuthorizationLoading : UiOAuthState()
        class Authorized(val userInfo: LichessApi.UserInfo) : UiOAuthState()
    }


    private val _oAuthStateLiveData = MutableLiveData<UiOAuthState>(UiOAuthState.NotYetLoaded())
    val oAuthStateLiveData: LiveData<UiOAuthState> = _oAuthStateLiveData

    private val _errorLiveData = MutableLiveData<Event>()
    val errorLiveData: LiveData<Event> = _errorLiveData


    init {
        serviceConnector.callWhenConnected { repository ->
            updateUiState(repository)
        }
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
            _oAuthStateLiveData.value = UiOAuthState.AuthorizationLoading()
            viewModelScope.launch {
                try {
                    repository.signIn(response, exception)
                    updateUiState(repository)
                } catch (e: Exception) {
                    when (e) {
                        is AuthorizationException,
                        is NetworkException,
                        is JsonParseException,
                        is IOException -> {
                            e.printStackTrace()
                            updateUiState(repository)
                            onAuthorizationError()
                        }
                        else-> throw e
                    }
                }
            }
        }
    }

    fun signOut() {
        serviceConnector.callWhenConnected { repository ->
            repository.signOut()
            updateUiState(repository)
        }
    }

    fun onAuthorizationError() {
        _errorLiveData.value = Event()
    }

    private fun updateUiState(repository: Repository) {
        repository.lichessUserInfo?.let {
            _oAuthStateLiveData.value = UiOAuthState.Authorized(it)
        } ?: kotlin.run {
            _oAuthStateLiveData.value = UiOAuthState.NotAuthorized()
        }
    }
}