package com.guykn.smartchessboard2.ui

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.JsonParseException
import com.guykn.smartchessboard2.BroadcastEvent
import com.guykn.smartchessboard2.LichessGameEvent
import com.guykn.smartchessboard2.ServiceConnector
import com.guykn.smartchessboard2.network.lichess.WebManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import net.openid.appauth.AuthorizationException
import java.io.IOException
import javax.inject.Inject

@HiltViewModel
class LichessViewModel @Inject constructor(private val serviceConnector: ServiceConnector) :
    ViewModel() {

    companion object {
        private const val TAG = "MA_LichessViewModel"
    }

    val internetState: LiveData<WebManager.InternetState> =
        serviceConnector.copyLiveData { repository -> repository.internetState }

    val broadcastRound: LiveData<BroadcastEvent> =
        serviceConnector.copyLiveData { repository -> repository.broadcastRound }

    val activeGame: LiveData<LichessGameEvent> =
        serviceConnector.copyLiveData { repository -> repository.activeGame }

    fun createBroadcast() {
        viewModelScope.launch {
            try {
                val repository = serviceConnector.awaitConnected()
                repository.startBroadcast()
            } catch (e: Exception) {
                when (e) {
                    is IOException,
                    is AuthorizationException,
                    is JsonParseException -> {
                        Log.w(TAG, "Error creating broadcast: ${e.message}")
                    }
                    else -> throw e
                }
            }
        }
    }

    fun stopBroadcast() {
        viewModelScope.launch {
            val repository = serviceConnector.awaitConnected()
            repository.stopOnlineGame()
        }
    }

    fun startGame() {
        viewModelScope.launch {
            val repository = serviceConnector.awaitConnected()
            repository.startOnlineGame()
        }
    }

    fun stopGame() {
        viewModelScope.launch {
            val repository = serviceConnector.awaitConnected()
            repository.stopOnlineGame()
        }
    }
}
