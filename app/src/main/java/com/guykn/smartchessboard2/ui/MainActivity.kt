package com.guykn.smartchessboard2.ui

import android.bluetooth.BluetoothDevice
import android.companion.CompanionDeviceManager
import android.content.Intent
import android.content.IntentSender
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.commit
import com.guykn.smartchessboard2.R
import com.guykn.smartchessboard2.bluetooth.GameStartRequest
import com.guykn.smartchessboard2.bluetooth.companiondevice.CompanionDeviceConnector
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject


@AndroidEntryPoint
class MainActivity : AppCompatActivity(), CompanionDeviceConnector.IntentCallback, StartOfflineGameDialog.Callback {

    @Inject
    lateinit var companionDeviceConnector: CompanionDeviceConnector

    private val mainViewModel: MainViewModel by viewModels()

    companion object {
        const val TAG = "MainActivity"
    }

    private val bluetoothIntentLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        result?.data?.getParcelableExtra<BluetoothDevice>(CompanionDeviceManager.EXTRA_DEVICE)
            ?.let {
                companionDeviceConnector.onDeviceSelected(it)
            } ?: kotlin.run {
            Toast.makeText(
                this,
                "Please allow the app to associate with the chessboard.",
                Toast.LENGTH_LONG
            ).show()
            companionDeviceConnector.refreshBluetoothDevice()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        companionDeviceConnector.refreshBluetoothDevice()
        setContentView(R.layout.activity_main)
        if (savedInstanceState == null) {
            supportFragmentManager.commit {
                setReorderingAllowed(true)
                add(R.id.container, MainFragment())
            }
        }


    }

    override fun onDestroy() {
        companionDeviceConnector.destroy()
        super.onDestroy()
    }

    override fun onIntentFound(intentSender: IntentSender) {
        val intentSenderRequest: IntentSenderRequest =
            IntentSenderRequest.Builder(intentSender).build()
        bluetoothIntentLauncher.launch(intentSenderRequest)
    }

    override fun startOfflineGame(gameStartRequest: GameStartRequest) {
        mainViewModel.startOfflineGame(gameStartRequest)
    }
}
