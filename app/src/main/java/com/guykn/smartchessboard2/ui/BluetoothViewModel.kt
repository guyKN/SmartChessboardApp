package com.guykn.smartchessboard2.ui

import android.util.Log
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.guykn.smartchessboard2.bluetooth.ChessBoardSettings
import com.guykn.smartchessboard2.bluetooth.GameStartRequest
import com.guykn.smartchessboard2.ServiceConnector
import com.guykn.smartchessboard2.bluetooth.ChessBoardModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import java.io.IOException
import javax.inject.Inject

@HiltViewModel
class BluetoothViewModel @Inject constructor(private val serviceConnector: ServiceConnector) :
    ViewModel() {

    companion object{
        private const val TAG = "MA_BluetoothViewModel"
    }

    private val _bluetoothStateLiveData = MutableLiveData<ChessBoardModel.BluetoothState>()
    val bluetoothStateLiveData = _bluetoothStateLiveData

    fun startGame(request: GameStartRequest){
        viewModelScope.launch {
            try {
                val repository = serviceConnector.awaitConnected()
                repository.startOfflineGame(request)
            }catch (e: IOException){
                Log.w(TAG, "Error writing settings to chessboard: ${e.message}")
            }
        }
    }

    fun writeSettings(settings: ChessBoardSettings){
        viewModelScope.launch {
            try {
                val repository = serviceConnector.awaitConnected()
                repository.writeSettings(settings)
            }catch (e: IOException){
                Log.w(TAG, "Error writing settings to chessboard: ${e.message}")
            }
        }
    }

    fun uploadPgn(){
        viewModelScope.launch {
            try {
                val repository = serviceConnector.awaitConnected()
                repository.uploadPgn()
            }catch (e: IOException){
                Log.w(TAG, "Error uploading pgn: ${e.message}")
            }
        }
    }
}