package com.guykn.smartchessboard2.ui

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.guykn.smartchessboard2.ServiceConnector
import com.guykn.smartchessboard2.bluetooth.BluetoothManager
import com.guykn.smartchessboard2.bluetooth.ChessBoardModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class BluetoothViewModel @Inject constructor(private val serviceConnector: ServiceConnector) :
    ViewModel() {

    private val _bluetoothStateLiveData = MutableLiveData<ChessBoardModel.BluetoothState>()
    val bluetoothStateLiveData = _bluetoothStateLiveData
}