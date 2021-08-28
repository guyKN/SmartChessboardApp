package com.guykn.smartchessboard2.ui

import androidx.lifecycle.*
import com.guykn.smartchessboard2.*
import com.guykn.smartchessboard2.EventBus.ErrorEvent
import com.guykn.smartchessboard2.EventBus.SuccessEvent
import com.guykn.smartchessboard2.bluetooth.ChessBoardModel
import com.guykn.smartchessboard2.bluetooth.ChessBoardSettings
import com.guykn.smartchessboard2.bluetooth.GameStartRequest
import com.guykn.smartchessboard2.network.lichess.BoolEvent
import com.guykn.smartchessboard2.network.lichess.LichessApi
import com.guykn.smartchessboard2.network.lichess.WebManager
import com.guykn.smartchessboard2.network.lichess.WebManager.UiOAuthState
import com.guykn.smartchessboard2.ui.util.Event
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationResponse
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(val serviceConnector: ServiceConnector) : ViewModel() {

    companion object{
        // When searching for a game on lichess, there's no way to know when no game was found. We wait this many milliseconds until assuming there is no game and opening the home page of liches.
        private const val DELAY_OPEN_LICHESS_HOMEPAGE: Long = 2000
    }

    val errorEvents: LiveData<ErrorEvent?> =
        serviceConnector.copyLiveData { repository -> repository.eventBus.errorEvents }

    val successEvents: LiveData<SuccessEvent?> =
        serviceConnector.copyLiveData { repository -> repository.eventBus.successEvents }

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

    val onlineGameState: LiveData<LichessApi.LichessGameState?> =
        serviceConnector.copyLiveData { repository -> repository.onlineGameState }


    val internetState: LiveData<WebManager.InternetState> =
        serviceConnector.copyLiveData { repository -> repository.internetState }

    val uiOAuthState: LiveData<UiOAuthState> =
        serviceConnector.copyLiveData { repository -> repository.uiOAuthState }

    val pgnFileUploadState: LiveData<Repository.PgnFileUploadState> =
        serviceConnector.copyLiveData { repository -> repository.pgnFileUploadState }

    val isLoadingBroadcast: LiveData<Boolean> =
        serviceConnector.copyLiveData { repository -> repository.isLoadingBroadcast }

    val isLoadingOnlineGame: LiveData<BoolEvent> =
        serviceConnector.copyLiveData { repository -> repository.isLoadingOnlineGame }


    ///////////////////////////////////////////////////////////////////////////////////////////////

    val isServiceConnected: LiveData<Boolean> = liveData {
        emit(false)
        serviceConnector.awaitConnected()
        emit(true)
    }

    val isOnlineGameActive = combineLiveData(activeOnlineGame, onlineGameState){gameEvent, gameState ->
        gameEvent?.value != null && (gameState?.isGameOver == false)
    }

    val isOnlineGameOver = combineLiveData(activeOnlineGame, onlineGameState){gameEvent, gameState ->
        gameEvent?.value != null && (gameState?.isGameOver == true)
    }

    private val _isWriteSettingsLoading = MutableLiveData<Boolean>(false)
    val isWriteSettingsLoading: LiveData<Boolean> = _isWriteSettingsLoading

    private val _isOfflineGameLoading = MutableLiveData<Boolean>(false)
    val isOfflineGameLoading: LiveData<Boolean> = _isOfflineGameLoading

    private val _isBlinkLedLoading = MutableLiveData<Boolean>(false)
    val isBlinkLedLoading: LiveData<Boolean> = _isBlinkLedLoading

    private val _isLoading: MediatorLiveData<Boolean> = MediatorLiveData<Boolean>().apply {
        addSource(isServiceConnected) { updateIsLoading() }
        addSource(isLoadingOnlineGame) { updateIsLoading() }
        addSource(bluetoothState) { updateIsLoading() }
        addSource(uiOAuthState) { updateIsLoading() }
        addSource(pgnFileUploadState) { updateIsLoading() }
        addSource(isLoadingBroadcast) { updateIsLoading() }
        addSource(isWriteSettingsLoading) { updateIsLoading() }
        addSource(isOfflineGameLoading) { updateIsLoading() }
        addSource(isBlinkLedLoading) { updateIsLoading() }
    }
    val isLoading: LiveData<Boolean> = _isLoading

    private fun updateIsLoading() {
        _isLoading.value = (isServiceConnected.value != true) ||
                (bluetoothState.value == ChessBoardModel.BluetoothState.BLUETOOTH_TURNING_ON) ||
                (bluetoothState.value == ChessBoardModel.BluetoothState.SCANNING) ||
                (bluetoothState.value == ChessBoardModel.BluetoothState.PAIRING) ||
                (bluetoothState.value == ChessBoardModel.BluetoothState.CONNECTING) ||
                (uiOAuthState.value is UiOAuthState.AuthorizationLoading) ||
                (pgnFileUploadState.value is Repository.PgnFileUploadState.UploadingToLichess) ||
                (pgnFileUploadState.value is Repository.PgnFileUploadState.ExchangingBluetoothData) ||
                (isLoadingBroadcast.value == true) ||
                (isLoadingOnlineGame.value?.value == true) ||
                (isWriteSettingsLoading.value == true) ||
                (isOfflineGameLoading.value == true) ||
                (isBlinkLedLoading.value == true)
    }

    private val _isBluetoothLoading: MediatorLiveData<Boolean> = MediatorLiveData<Boolean>().apply {
        addSource(isWriteSettingsLoading) { updateIsBluetoothLoading() }
        addSource(isOfflineGameLoading) { updateIsBluetoothLoading() }
        addSource(isBlinkLedLoading) { updateIsBluetoothLoading() }
    }

    private fun updateIsBluetoothLoading() {
        _isBluetoothLoading.value = (isWriteSettingsLoading.value == true) ||
                    (isOfflineGameLoading.value == true) ||
                    (isBlinkLedLoading.value == true)
    }

    val isAwaitingLaunchLichess: MutableLiveData<Boolean> = MutableLiveData(false)

    private val _launchLichessHomepageEvent: MutableLiveData<Event> = MutableLiveData()
    val launchLichessHomepageEvent: LiveData<Event> = _launchLichessHomepageEvent

    fun postErrorEvent(error: ErrorEvent) {
        viewModelScope.launch {
            val repository = serviceConnector.awaitConnected()
            repository.eventBus.errorEvents.value = error
        }
    }

    fun signIn(
        response: AuthorizationResponse?,
        exception: AuthorizationException?
    ) {
        viewModelScope.launch {
            val repository = serviceConnector.awaitConnected()
            repository.signIn(response, exception)
        }
    }

    fun signOut() {
        viewModelScope.launch {
            val repository = serviceConnector.awaitConnected()
            repository.signOut()
        }
    }

    fun isSignedIn(): Boolean{
        return uiOAuthState.value is UiOAuthState.Authorized
    }

    fun writeSettings(settings: ChessBoardSettings) {
        viewModelScope.launch {
            val repository = serviceConnector.awaitConnected()
            _isWriteSettingsLoading.value = true
            repository.writeSettings(settings)
            _isWriteSettingsLoading.value = false
        }
    }

    fun startOfflineGame(gameStartRequest: GameStartRequest) {
        // todo: handle this method being called while _isOfflineGameLoading.value == true
        viewModelScope.launch {
            val repository = serviceConnector.awaitConnected()
            _isOfflineGameLoading.value = true
            repository.startOfflineGame(gameStartRequest)
            _isOfflineGameLoading.value = false
        }
    }

    fun startTwoPlayerGame(){
        viewModelScope.launch {
            val repository = serviceConnector.awaitConnected()
            _isOfflineGameLoading.value = true
            repository.startTwoPlayerGame()
            _isOfflineGameLoading.value = false
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

        viewModelScope.launch {
            delay(DELAY_OPEN_LICHESS_HOMEPAGE)
            _launchLichessHomepageEvent.value = Event()
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
            repository.uploadPgn()
        }
    }

    fun blinkLeds() {
        viewModelScope.launch {
            val repository = serviceConnector.awaitConnected()
            repository.blinkLeds()
        }
    }

    override fun onCleared() {
        super.onCleared()
        serviceConnector.destroy()
    }
}