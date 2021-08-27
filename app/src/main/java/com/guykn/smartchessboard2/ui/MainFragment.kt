package com.guykn.smartchessboard2.ui

import android.app.AlertDialog
import android.app.Dialog
import android.app.ProgressDialog
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.activityViewModels
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.google.android.material.snackbar.Snackbar
import com.guykn.smartchessboard2.EventBus.ErrorEvent
import com.guykn.smartchessboard2.EventBus.SuccessEvent
import com.guykn.smartchessboard2.R
import com.guykn.smartchessboard2.Repository.PgnFileUploadState.*
import com.guykn.smartchessboard2.bluetooth.ChessBoardModel.BluetoothState.*
import com.guykn.smartchessboard2.bluetooth.ChessBoardSettings
import com.guykn.smartchessboard2.bluetooth.companiondevice.CompanionDeviceConnector
import com.guykn.smartchessboard2.network.lichess.WebManager.InternetState.*
import com.guykn.smartchessboard2.network.lichess.WebManager.UiOAuthState
import com.guykn.smartchessboard2.network.oauth2.getLichessAuthIntent
import com.guykn.smartchessboard2.openCustomChromeTab
import dagger.hilt.android.AndroidEntryPoint
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

@SuppressWarnings("DEPRECATION")
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
    private lateinit var twoPlayerGameButton: Preference
    private lateinit var playOnlineButton: Preference
    private lateinit var startBroadcastButton: Preference
    private lateinit var uploadGamesButton: Preference
    private lateinit var blinkLedsButton: Preference
    private lateinit var learningModeSwitch: SwitchPreferenceCompat

    private var bluetoothMessageDialog: Dialog? = null
    private var loadingBroadcastDialog: Dialog? = null
    private var uploadingPgnDialog: ProgressDialog? = null

    private lateinit var authService: AuthorizationService


    private val isAwaitingLaunchLichessHomePage = AtomicBoolean(false)

    private var signInCallback: (()->Unit)? = null

    private val lichessAuthLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        signInCallback = null
        continueLichessLogin(result)
    }

    private val lichessAuthLauncherWithStartOnlineGame = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        signInCallback = {
            startAndOpenOnlineGame()
        }
        continueLichessLogin(result)
    }

    private val lichessAuthLauncherWithStartBroadcast = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        signInCallback = {
            mainViewModel.startBroadcast()
        }
        continueLichessLogin(result)
    }

    private val lichessAuthLauncherWithViewSavedGames = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        signInCallback = {
           startViewSavedGames()
        }
        continueLichessLogin(result)
    }


    private fun continueLichessLogin(result: ActivityResult?) {
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
        twoPlayerGameButton = findPreference("two_player_game")!!
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
                    startLichessSignIn()
                }
                is UiOAuthState.Authorized -> {
                    mainViewModel.signOut()
                }
            }
            true
        }

        playAgainstAiButton.setOnPreferenceClickListener {
            StartOfflineGameDialog()
                .show(parentFragmentManager, "start_offline_game")
            true
        }

        twoPlayerGameButton.setOnPreferenceClickListener{
            mainViewModel.startTwoPlayerGame()
            true
        }

        playOnlineButton.setOnPreferenceClickListener {
            if (!mainViewModel.isSignedIn()) {
                lichessAuthLauncherWithStartOnlineGame.launch(authService.getLichessAuthIntent())
            } else {
                startAndOpenOnlineGame()
            }
            true
        }

        startBroadcastButton.setOnPreferenceClickListener {
            if (!mainViewModel.isSignedIn()) {
                lichessAuthLauncherWithStartBroadcast.launch(authService.getLichessAuthIntent())
            } else {
                mainViewModel.startBroadcast()
                mainViewModel.broadcastRound.value?.value?.let { broadcastRound ->
                    openCustomChromeTab(requireContext(), broadcastRound.url)
                }
            }
            true
        }

        uploadGamesButton.setOnPreferenceClickListener {
            if (!mainViewModel.isSignedIn()) {
                lichessAuthLauncherWithViewSavedGames.launch(authService.getLichessAuthIntent())
            } else {
                startViewSavedGames()
            }
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

    private fun startAndOpenOnlineGame() {
        mainViewModel.startOnlineGame()
        mainViewModel.activeOnlineGame.value?.value?.let { lichessGame ->
            openCustomChromeTab(requireContext(), lichessGame.url)
            isAwaitingLaunchLichessHomePage.set(false)
        } ?: isAwaitingLaunchLichessHomePage.set(true)
    }


    private fun startLichessSignIn() {
        lichessAuthLauncher.launch(authService.getLichessAuthIntent())
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        mainViewModel.activeOnlineGame.observe(viewLifecycleOwner) { lichessGameEvent ->
            if (lichessGameEvent?.receive() == true && lichessGameEvent.value != null) {
                openCustomChromeTab(requireContext(), lichessGameEvent.value.url)
                isAwaitingLaunchLichessHomePage.set(false)
            }
        }

        mainViewModel.broadcastRound.observe(viewLifecycleOwner) { broadcastEvent ->
            if (broadcastEvent?.receive() == true && broadcastEvent.value != null) {
                openCustomChromeTab(requireContext(), broadcastEvent.value.url)
            }
        }

        mainViewModel.isLoadingOnlineGame.observe(viewLifecycleOwner) { isLoadingEvent ->
            if (isLoadingEvent?.receive() == true && isAwaitingLaunchLichessHomePage.get() && !isLoadingEvent.value) {
                isAwaitingLaunchLichessHomePage.set(false)
                openCustomChromeTab(requireContext(), "https://lichess.org/")
            }
        }

        // if the user sucsessfully signed in, invoke the signInCallback
        mainViewModel.uiOAuthState.observe(viewLifecycleOwner){
            if (it is UiOAuthState.Authorized){
                signInCallback?.invoke()
                signInCallback = null
            }
        }

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

        mainViewModel.activeOnlineGame.observe(viewLifecycleOwner){
            if (it?.value == null){
                playOnlineButton.title = "Play Online"
                playOnlineButton.summary = ""
            }else{
                playOnlineButton.title = "Online Game In Progress"
                playOnlineButton.summary = "Click here to view"
            }
        }

        mainViewModel.numGamesToUpload.observe(viewLifecycleOwner){ numGamesToUpload ->
            when(numGamesToUpload){
                0,null->{
                    uploadGamesButton.summary = ""
                }
                else->{
                    uploadGamesButton.summary = "$numGamesToUpload games need to be uploaded"
                }
            }
        }

        mainViewModel.chessBoardSettings.observe(viewLifecycleOwner) { chessBoardSettings ->
            learningModeSwitch.isChecked = chessBoardSettings?.learningMode ?: return@observe
        }

        mainViewModel.bluetoothState.observe(viewLifecycleOwner) { bluetoothState ->
            bluetoothMessageDialog?.dismiss()
            bluetoothMessageDialog = null

            when (bluetoothState) {
                null -> {
                }
                BLUETOOTH_NOT_SUPPORTED -> {
                    bluetoothMessageDialog = AlertDialog.Builder(requireContext())
                        .setTitle("Bluetooth Error")
                        .setMessage("Your Device does not support bluetooth. ")
                        .setCancelable(false)
                        .setPositiveButton("Close App") { _, _ ->
                            activity?.finish()
                        }
                        .show()
                }

                BLUETOOTH_NOT_ENABLED -> {
                    bluetoothMessageDialog = AlertDialog.Builder(requireContext())
                        .setTitle("Bluetooth Error")
                        .setMessage("Bluetooth is not enabled. ")
                        .setCancelable(false)
                        .setPositiveButton("Enable Bluetooth") { _, _ ->
                            requestEnableBluetooth()
                        }
                        .show()
                    requestEnableBluetooth()
                }

                BLUETOOTH_TURNING_ON -> {
                    bluetoothMessageDialog =
                        ProgressDialog.show(
                            requireContext(),
                            "Bluetooth Loading...",
                            "Bluetooth is turning on",
                            true
                        )

                }
                SCANNING -> {
                    bluetoothMessageDialog =
                        ProgressDialog.show(
                            requireContext(),
                            "Bluetooth Loading...",
                            "Performing bluetooth scan",
                            true
                        )
                }
                PAIRING -> {
                    bluetoothMessageDialog = ProgressDialog.show(
                        requireContext(),
                        "Bluetooth Loading...",
                        "Pairing with chessboard",
                        true
                    )
                }
                CONNECTING -> {
                    bluetoothMessageDialog = ProgressDialog.show(
                        requireContext(),
                        "Bluetooth Loading...",
                        "Connecting to chessboard",
                        true
                    )
                }

                DISCONNECTED, CONNECTION_FAILED, SCAN_FAILED -> {
                    bluetoothMessageDialog = AlertDialog.Builder(requireContext())
                        .setTitle("Bluetooth Error")
                        .setMessage("Failed to connect to with Bluetooth")
                        .setCancelable(false)
                        .setPositiveButton("Try Again") { _, _ ->
                            companionDeviceConnector.refreshBluetoothDevice()
                        }
                        .show()
                }
                REQUESTING_USER_INPUT -> {
                } // requesting user input will open a separate window with an intent, so no UI change is necessary
                CONNECTED -> {
                }
            }
        }

        mainViewModel.successEvents.observe(viewLifecycleOwner) { successEvent ->
            if (successEvent?.receive() != true) {
                return@observe
            }
            when (successEvent) {
                is SuccessEvent.BlinkLedsSuccess -> {
                    Snackbar.make(
                        view,
                        "Chessboard LEDs are now on",
                        resources.getInteger(R.integer.led_test_snackbar_duration)
                    )
                        .show()
                }
                is SuccessEvent.ChangeSettingsSuccess -> {
                    Snackbar.make(
                        view,
                        if (successEvent.settings.learningMode) "Learning Mode Enabled" else "Learning Mode Disabled",
                        Snackbar.LENGTH_SHORT
                    )
                        .show()
                }
                is SuccessEvent.SignInSuccess -> {
                    Snackbar.make(
                        view,
                        "Signed in as ${successEvent.userInfo.username}",
                        Snackbar.LENGTH_SHORT
                    )
                        .show()
                }
                is SuccessEvent.SignOutSuccess -> {
                    Snackbar.make(
                        view,
                        "Signed Out Successfully",
                        Snackbar.LENGTH_SHORT
                    )
                        .show()
                }
                is SuccessEvent.StartOfflineGameSuccess -> {
                    Snackbar.make(
                        view,
                        "Game Started",
                        Snackbar.LENGTH_SHORT
                    )
                        .show()
                }
                is SuccessEvent.UploadGamesSuccess -> openLichessSavedGames()
            }
        }

        mainViewModel.errorEvents.observe(viewLifecycleOwner) { errorEvent ->
            if (errorEvent?.receive() != true) {
                return@observe
            }
            when (errorEvent) {
                is ErrorEvent.BluetoothIOError -> {
                    // Do nothing. Bluetooth Errors are already handled by the Bluetooth State observer
                }
                is ErrorEvent.InternetIOError -> {
                    Snackbar.make(
                        view,
                        "Could Not Connect to Lichess. Please check your connection and try again. ",
                        Snackbar.LENGTH_SHORT
                    )
                        .show()
                }
                is ErrorEvent.MiscError -> {
                    Snackbar.make(
                        view,
                        "Error: ${errorEvent.description}",
                        Snackbar.LENGTH_SHORT
                    )
                        .show()
                }
                is ErrorEvent.NoLongerAuthorizedError -> {
                    Snackbar.make(
                        view,
                        "Unexpectedly signed out. ",
                        Snackbar.LENGTH_SHORT
                    )
                        .setAction("Sign In") { startLichessSignIn() }
                        .show()
                }
                is ErrorEvent.SignInError -> {
                    Snackbar.make(
                        view,
                        "Could Not Sign In",
                        Snackbar.LENGTH_SHORT
                    )
                        .setAction("Try Again") { startLichessSignIn() }
                        .show()
                }
                is ErrorEvent.TooManyRequests -> {
                    val timeUntilServerAvailable =
                        errorEvent.timeForValidRequests - System.currentTimeMillis()
                    val timeUntilServerAvailableSeconds = timeUntilServerAvailable / 1000
                    Snackbar.make(
                        view,
                        "Lichess Servers are overwhelmed. Please try again in $timeUntilServerAvailableSeconds seconds. ",
                        Snackbar.LENGTH_LONG
                    )
                        .show()
                }
                is ErrorEvent.IllegalGameSelected -> {
                    AlertDialog.Builder(requireContext())
                        .setTitle("Invalid Game")
                        .setMessage("You have chosen an invalid game mode. Only unlimited, classic, and rapid time controls are supported. No rules variants are supported. ")
                        .setPositiveButton("OK") { _, _ -> }
                        .show()

                }
                is ErrorEvent.BroadcastCreatedWhileOnlineGameActive -> {
                    Snackbar.make(
                        view,
                        "Can't create broadcast for an online game. ",
                        Snackbar.LENGTH_SHORT
                    )
                        .show()
                }
            }
        }

        mainViewModel.isLoadingBroadcast.observe(viewLifecycleOwner) { isLoading ->
            loadingBroadcastDialog?.dismiss()
            loadingBroadcastDialog = null
            if (isLoading) {
                loadingBroadcastDialog = ProgressDialog.show(
                    requireContext(),
                    null,
                    "Creating Broadcast",
                    true
                )
            }
        }

        mainViewModel.isLoadingOnlineGame.observe(viewLifecycleOwner) { isLoading ->
            loadingBroadcastDialog?.dismiss()
            loadingBroadcastDialog = null
            if (isLoading?.value == true) {
                loadingBroadcastDialog = ProgressDialog.show(
                    requireContext(),
                    null,
                    "Starting Online Game",
                    true
                )
            }
        }

        mainViewModel.pgnFileUploadState.observe(viewLifecycleOwner) { pgnFileUploadState ->
            when (pgnFileUploadState) {
                NotUploading -> {
                    uploadingPgnDialog?.dismiss()
                    uploadingPgnDialog = null
                }
                ExchangingBluetoothData,
                is UploadingToLichess -> {
                    if (uploadingPgnDialog == null) {
                        uploadingPgnDialog = ProgressDialog.show(
                            requireContext(),
                            "Loading...",
                            "Uploading Saved Games",
                            true
                        )
                    }
                }
            }
        }


    }


    private fun startViewSavedGames() {
        when (mainViewModel.numGamesToUpload.value) {
            // if there are no games to upload, simply open the custom chrome tab. Otherwise, first upload the games, then open the chrome tab.
            0, null -> {
                Log.d(TAG, "Only opening lichess. ")
                openLichessSavedGames()
            }
            else -> mainViewModel.uploadPgn()
        }
    }

    private fun openLichessSavedGames() {
        val authState = mainViewModel.uiOAuthState.value
        if (authState is UiOAuthState.Authorized) {
            openCustomChromeTab(requireContext(), authState.userInfo.importedGamesUrl())
        } else {
            Log.w(
                TAG,
                "Received Success Event of UploadGamesSuccess while not signed in. "
            )
        }
    }

    private fun requestEnableBluetooth() {
        Log.d(TAG, "requestEnableBluetooth() called")
        if (CompanionDeviceConnector.shouldRequestEnableBluetooth()) {
            val intent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            startActivityForResult(intent, REQUEST_ENABLE_BLUETOOTH)
        } else {
            Log.w(TAG, "Tried to request enabling bluetooth while bluetooth was already enabled. ")
        }
    }


}