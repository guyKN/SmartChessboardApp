package com.guykn.smartchessboard2.newui

import android.app.AlertDialog
import android.app.ProgressDialog
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.activityViewModels
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.guykn.smartchessboard2.R
import com.guykn.smartchessboard2.bluetooth.ChessBoardModel.BluetoothState.*
import com.guykn.smartchessboard2.bluetooth.ChessBoardSettings
import com.guykn.smartchessboard2.bluetooth.companiondevice.CompanionDeviceConnector
import com.guykn.smartchessboard2.network.lichess.WebManager
import com.guykn.smartchessboard2.network.lichess.WebManager.InternetState.*
import com.guykn.smartchessboard2.network.lichess.WebManager.UiOAuthState
import com.guykn.smartchessboard2.network.oauth2.getLichessAuthIntent
import com.guykn.smartchessboard2.newui.viewmodels.MainViewModel
import com.guykn.smartchessboard2.openCustomChromeTab
import dagger.hilt.android.AndroidEntryPoint
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import javax.inject.Inject

@AndroidEntryPoint
class MainFragment : PreferenceFragmentCompat() {

    companion object {
        const val TAG = "MA_MainFragment"
        const val REQUEST_ENABLE_BLUETOOTH = 420
    }

    @Inject
    lateinit var companionDeviceConnector: CompanionDeviceConnector

    private val mainViewModel: MainViewModel by activityViewModels()

    private lateinit var signInInfo: Preference
    private lateinit var playAgainstAiButton: Preference
    private lateinit var playOnlineButton: Preference
    private lateinit var startBroadcastButton: Preference
    private lateinit var uploadGamesButton: Preference
    private lateinit var blinkLedsButton: Preference
    private lateinit var learningModeSwitch: SwitchPreferenceCompat

    private var loadingDialog: ProgressDialog? = null
    private var bluetoothMessageDialog: AlertDialog? = null

    private lateinit var authService: AuthorizationService

    private val lichessAuthLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        result?.data?.let {
            val authorizationResponse = AuthorizationResponse.fromIntent(it)
            val authorizationException = AuthorizationException.fromIntent(it)
            mainViewModel.signIn(
                authorizationResponse,
                authorizationException
            )
        } ?: Log.w(TAG, "OAuth custom tab intent returned with no data. ")
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        authService = AuthorizationService(context)
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.root_preferences, rootKey)
        signInInfo = findPreference("sign_in_info")!!
        playAgainstAiButton = findPreference("play_against_ai")!!
        playOnlineButton = findPreference("play_online")!!
        startBroadcastButton = findPreference("start_broadcast")!!
        uploadGamesButton = findPreference("upload_games")!!
        blinkLedsButton = findPreference("test_connection")!!
        learningModeSwitch = findPreference("learning_mode")!!

        signInInfo.setOnPreferenceClickListener {
            // if the user is signed out then this button sign in. If the user is signed in, this button signs out.
            when (mainViewModel.uiOAuthState.value) {
                null -> {
                }
                is UiOAuthState.AuthorizationLoading -> {
                }
                is UiOAuthState.NotAuthorized -> {
                    lichessAuthLauncher.launch(authService.getLichessAuthIntent())
                }
                is UiOAuthState.Authorized -> {
                    mainViewModel.signOut()
                }
            }
            true
        }

        playAgainstAiButton.setOnPreferenceClickListener {
            StartOfflineGameDialog(requireContext(), mainViewModel::startOfflineGame)
                .show()
            true
        }

        // todo: for all the buttons that require sign in, redirect the user to the sign in page if they are not currently signed in

        playOnlineButton.setOnPreferenceClickListener {
            // todo: also open the lichess home page if no active game is found
            mainViewModel.startOnlineGame()
            mainViewModel.activeOnlineGame.value?.value?.let { lichessGame ->
                openCustomChromeTab(requireContext(), lichessGame.url)
            }
            true
        }

        startBroadcastButton.setOnPreferenceClickListener {
            mainViewModel.startBroadcast()
            mainViewModel.broadcastRound.value?.value?.let { broadcastRound ->
                openCustomChromeTab(requireContext(), broadcastRound.url)
            }
            true
        }

        uploadGamesButton.setOnPreferenceClickListener {
            mainViewModel.uploadPgn()
            true
        }

        learningModeSwitch.setOnPreferenceChangeListener { _, newValue ->
            val isChecked = newValue as Boolean
            mainViewModel.writeSettings(
                ChessBoardSettings(learningMode = isChecked)
            )
            true
        }

        blinkLedsButton.setOnPreferenceClickListener {
            mainViewModel.blinkLeds()
            true
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        mainViewModel.uiOAuthState.observe(viewLifecycleOwner) { authState ->
            when (authState) {
                null -> {
                }
                is UiOAuthState.NotAuthorized, is UiOAuthState.AuthorizationLoading -> {
                    signInInfo.title = "Sign in"
                    signInInfo.summary = ""
                }
                is UiOAuthState.Authorized -> {
                    val username = authState.userInfo.username
                    signInInfo.title = "Signed in as $username"
                    signInInfo.summary = "Click here to sign out"
                }
            }
        }

        mainViewModel.activeOnlineGame.observe(viewLifecycleOwner) { lichessGameEvent ->
            if (lichessGameEvent?.receive() == true && lichessGameEvent.value != null) {
                openCustomChromeTab(requireContext(), lichessGameEvent.value.url)
            }
        }

        mainViewModel.broadcastRound.observe(viewLifecycleOwner){ broadcastEvent ->
            if (broadcastEvent?.receive() == true && broadcastEvent.value != null){
                openCustomChromeTab(requireContext(), broadcastEvent.value.url)
            }
        }

        mainViewModel.chessBoardSettings.observe(viewLifecycleOwner){ chessBoardSettings ->
            learningModeSwitch.isChecked = chessBoardSettings?.learningMode ?: return@observe
        }

        mainViewModel.numGamesToUpload.observe(viewLifecycleOwner){ numGamesToUpload ->
            val actualNumGamesToUpload = numGamesToUpload ?: 0
            uploadGamesButton.summary = "$actualNumGamesToUpload games available to upload"
            uploadGamesButton.isEnabled = actualNumGamesToUpload != 0
        }

        mainViewModel.isLoading.observe(viewLifecycleOwner){ isLoading ->
            if (isLoading == true){
                loadingDialog?.dismiss()
                loadingDialog = ProgressDialog.show(requireContext(), "Loading", "", true)
            }else{
                loadingDialog?.dismiss()
                loadingDialog = null
            }
        }

        mainViewModel.bluetoothState.observe(viewLifecycleOwner){ bluetoothState ->
            bluetoothMessageDialog?.dismiss()
            bluetoothMessageDialog = null
            when(bluetoothState){
                null->{
                }
                BLUETOOTH_NOT_SUPPORTED ->{
                    bluetoothMessageDialog = AlertDialog.Builder(requireContext())
                        .setTitle("Bluetooth Error")
                        .setMessage("Your Device does not support bluetooth. ")
                        .setCancelable(false)
                        .setPositiveButton("Close App"){_,_->
                            activity?.finish()
                        }
                        .show()
                }

                BLUETOOTH_NOT_ENABLED ->{
                    bluetoothMessageDialog = AlertDialog.Builder(requireContext())
                        .setTitle("Bluetooth Error")
                        .setMessage("Bluetooth is not enabled. ")
                        .setCancelable(false)
                        .setPositiveButton("Enable Bluetooth"){_,_->
                            requestEnableBluetooth()
                        }
                        .show()
                    requestEnableBluetooth()
                }

                BLUETOOTH_TURNING_ON, SCANNING, PAIRING, CONNECTING ->{
                    // the loading icon will already be displayed here by isLoading from the view model
                    bluetoothMessageDialog?.dismiss()
                    bluetoothMessageDialog = null
                }

                DISCONNECTED, CONNECTION_FAILED ->{
                    bluetoothMessageDialog = AlertDialog.Builder(requireContext())
                        .setTitle("Bluetooth Error")
                        .setMessage("Bluetooth Disconnected")
                        .setCancelable(false)
                        .setPositiveButton("Try Again"){_,_->
                            companionDeviceConnector.refreshBluetoothDevice()
                        }
                        .show()
                }
                REQUESTING_USER_INPUT->{} // requesting user input will open a separate window with an intent, so no UI change is necessary
                CONNECTED ->{
                }
            }
        }

        mainViewModel.internetState.observe(viewLifecycleOwner){ internetState ->
            when(internetState){
                null->{}
                is Connected->{
                    
                }
                is NotConnected->{

                }
                is TooManyRequests->{

                }
            }
        }
    }

    private fun requestEnableBluetooth(){
        Log.d(TAG, "requestEnableBluetooth() called")
        if (CompanionDeviceConnector.shouldRequestEnableBluetooth()){
            val intent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            startActivityForResult(intent, REQUEST_ENABLE_BLUETOOTH)
        }else{
            Log.w(TAG, "Tried to request enabling bluetooth while bluetooth was already enabled. ")
        }
    }


}