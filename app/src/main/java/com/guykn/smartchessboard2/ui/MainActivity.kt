package com.guykn.smartchessboard2.ui

import android.bluetooth.BluetoothDevice
import android.companion.CompanionDeviceManager
import android.content.IntentSender
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.commit
import com.guykn.smartchessboard2.R
import com.guykn.smartchessboard2.bluetooth.companiondevice.CompanionDeviceConnector
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity(), CompanionDeviceConnector.IntentCallback {

    @Inject
    lateinit var companionDeviceConnector: CompanionDeviceConnector

    companion object {
        const val TAG = "MainActivity"
    }

    private val bluetoothIntentLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
        ::onBluetoothIntentFinished
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        companionDeviceConnector.refreshBluetoothDevice()
        setContentView(R.layout.activity_main)
        if (savedInstanceState == null) {
            supportFragmentManager.commit {
                setReorderingAllowed(true)
                add(R.id.container, OAuthFragment())
            }
        }
    }

    override fun onDestroy() {
        companionDeviceConnector.destroy()
        super.onDestroy()
    }

    private fun onBluetoothIntentFinished(result: ActivityResult?) {
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

    override fun onIntentFound(intentSender: IntentSender) {
        val intentSenderRequest: IntentSenderRequest =
            IntentSenderRequest.Builder(intentSender).build()
        bluetoothIntentLauncher.launch(intentSenderRequest)
    }
}
