package com.guykn.smartchessboard2.newui.viewmodels

import android.provider.MediaStore
import androidx.lifecycle.*
import com.google.gson.JsonParseException
import com.guykn.smartchessboard2.*
import com.guykn.smartchessboard2.ErrorEventBus.ErrorEvent
import com.guykn.smartchessboard2.bluetooth.ChessBoardModel
import com.guykn.smartchessboard2.bluetooth.ChessBoardSettings
import com.guykn.smartchessboard2.bluetooth.GameStartRequest
import com.guykn.smartchessboard2.network.lichess.WebManager
import com.guykn.smartchessboard2.network.oauth2.NetworkException
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationResponse
import java.io.IOException
import javax.inject.Inject

// todo: move all error handlig logic to the repository

@HiltViewModel
class MainViewModel @Inject constructor(val serviceConnector: ServiceConnector) : ViewModel() {

    val errorEvents: LiveData<ErrorEvent?> =
        serviceConnector.copyLiveData { repository -> repository.errorEventBus.errorEvents }

    val bluetoothState: LiveData<ChessBoardModel.BluetoothState> =
        serviceConnector.copyLiveData { repository -> repository.chessBoardModel.bluetoothState }

    val isOfflineGameActive: LiveData<Boolean?> =
        serviceConnector.copyLiveData { repository -> repository.chessBoardModel.gameActive }

    val numGamesToUpload: LiveData<Int?> =
        serviceConnector.copyLiveData { repository -> repository.chessBoardModel.numGamesToUpload }

    val offlineGameInfo: LiveData<ChessBoardModel.GameInfo?> =
        serviceConnector.copyLiveData { repository -> repository.chessBoardModel.gameInfo }

    val offlineGameBoardState: LiveData<ChessBoardModel.BoardState?> =
        serviceConnector.copyLiveData { repository -> repository.chessBoardModel.boardState }

    val chessBoardSettings: LiveData<ChessBoardSettings?> =
        serviceConnector.copyLiveData { repository -> repository.chessBoardModel.settings }

    val broadcastRound: LiveData<BroadcastEvent?> =
        serviceConnector.copyLiveData { repository -> repository.broadcastRound }

    val activeOnlineGame: LiveData<LichessGameEvent> =
        serviceConnector.copyLiveData { repository -> repository.activeGame }

    val internetState: LiveData<WebManager.InternetState> =
        serviceConnector.copyLiveData { repository -> repository.internetState }

    val uiOAuthState: LiveData<WebManager.UiOAuthState> =
        serviceConnector.copyLiveData { repository -> repository.uiOAuthState }

    val pgnFileUploadState: LiveData<Repository.PgnFileUploadState> =
        serviceConnector.copyLiveData { repository -> repository.pgnFileUploadState }

    val isLoadingBroadcast: LiveData<Boolean> =
        serviceConnector.copyLiveData { repository -> repository.isLoadingBroadcast }

    ///////////////////////////////////////////////////////////////////////////////////////////////

    val isServiceConnected: LiveData<Boolean> = liveData {
        emit(false)
        serviceConnector.awaitConnected()
        emit(true)
    }

    private val _isWritingSettings = MutableLiveData<Boolean>(false)
    val isWritingSettings: LiveData<Boolean> = _isWritingSettings

    private val _isStartingOfflineGame = MutableLiveData<Boolean>(false)
    val isStartingOfflineGame: LiveData<Boolean> = _isStartingOfflineGame

    private val _isLoading: MediatorLiveData<Boolean> = MediatorLiveData()
    val isLoading: LiveData<Boolean> = _isLoading
    init {
        _isLoading.addSource(isServiceConnected){ updateIsLoading() }
        _isLoading.addSource(bluetoothState){ updateIsLoading() }
        _isLoading.addSource(uiOAuthState){ updateIsLoading() }
        _isLoading.addSource(pgnFileUploadState){ updateIsLoading() }
        _isLoading.addSource(isLoadingBroadcast){ updateIsLoading() }
        _isLoading.addSource(isWritingSettings){ updateIsLoading() }
        _isLoading.addSource(isStartingOfflineGame){ updateIsLoading() }
    }

    private fun updateIsLoading(){
        _isLoading.value = (isServiceConnected.value != true) ||
                (bluetoothState.value == ChessBoardModel.BluetoothState.BLUETOOTH_TURNING_ON) ||
                (bluetoothState.value == ChessBoardModel.BluetoothState.SCANNING) ||
                (bluetoothState.value == ChessBoardModel.BluetoothState.PAIRING) ||
                (bluetoothState.value == ChessBoardModel.BluetoothState.CONNECTING) ||
                (uiOAuthState.value is WebManager.UiOAuthState.AuthorizationLoading) ||
                (pgnFileUploadState.value is Repository.PgnFileUploadState.UploadingToLichess)||
                (pgnFileUploadState.value is Repository.PgnFileUploadState.ExchangingBluetoothData)||
                (isLoadingBroadcast.value == true) ||
                (isWritingSettings.value == true)||
                (isStartingOfflineGame.value == true)
    }

    fun postErrorEvent(error: ErrorEvent) {
        viewModelScope.launch {
            val repository = serviceConnector.awaitConnected()
            repository.errorEventBus.errorEvents.value = error
        }
    }

    fun signIn(
        response: AuthorizationResponse?,
        exception: AuthorizationException?
    ) {
        viewModelScope.launch {
            val repository = serviceConnector.awaitConnected()
            try {
                repository.signIn(response, exception)
            } catch (e: Exception) {
                when (e) {
                    is AuthorizationException,
                    is NetworkException,
                    is IOException,
                    is JsonParseException -> {
                        postErrorEvent(ErrorEvent.SignInError())
                        e.printStackTrace()
                    }
                }
            }
        }
    }

    fun signOut(){
        viewModelScope.launch {
            val repository = serviceConnector.awaitConnected()
            repository.signOut()
        }

    }

    fun writeSettings(settings: ChessBoardSettings) {
        viewModelScope.launch {
            val repository = serviceConnector.awaitConnected()
            try {
                _isWritingSettings.value = true
                repository.writeSettings(settings)
            } catch (e: IOException) {
                postErrorEvent(ErrorEvent.WriteSettingsError())
                e.printStackTrace()
            }finally {
                _isWritingSettings.value = false
            }
        }
    }

    fun startOfflineGame(gameStartRequest: GameStartRequest) {
        viewModelScope.launch {
            val repository = serviceConnector.awaitConnected()
            try {
                _isStartingOfflineGame.value = true
                repository.startOfflineGame(gameStartRequest)
            } catch (e: IOException) {
                postErrorEvent(ErrorEvent.StartOfflineGameError())
                e.printStackTrace()
            }finally {
                _isStartingOfflineGame.value = false
            }
        }
    }

    fun startBroadcast() {
        viewModelScope.launch {
            val repository = serviceConnector.awaitConnected()
            repository.startBroadcast()
        }
    }

    fun stopBroadcast() {
        viewModelScope.launch {
            val repository = serviceConnector.awaitConnected()
            repository.stopBroadcast()
        }
    }

    fun startOnlineGame() {
        viewModelScope.launch {
            val repository = serviceConnector.awaitConnected()
            repository.startOnlineGame()
        }
    }

    fun stopOnlineGame() {
        viewModelScope.launch {
            val repository = serviceConnector.awaitConnected()
            repository.stopOnlineGame()
        }
    }

    fun uploadPgn() {
        viewModelScope.launch {
            val repository = serviceConnector.awaitConnected()
            try {
                repository.uploadPgn()
            }catch (e:IOException){
                postErrorEvent(ErrorEvent.UploadPgnError())
            }
        }
    }

    fun blinkLeds() {
        viewModelScope.launch {
            val repository = serviceConnector.awaitConnected()
            try {
                repository.blinkLeds()
            }catch (e:IOException){
                postErrorEvent(ErrorEvent.BlinkLedsError())
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        serviceConnector.destroy()
    }
}