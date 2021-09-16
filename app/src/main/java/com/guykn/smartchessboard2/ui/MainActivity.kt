package com.guykn.smartchessboard2.ui

import android.app.AlertDialog
import android.app.Dialog
import android.app.ProgressDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.companion.CompanionDeviceManager
import android.content.Intent
import android.content.IntentSender
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.fragment.app.commit
import com.google.android.material.snackbar.Snackbar
import com.guykn.smartchessboard2.*
import com.guykn.smartchessboard2.bluetooth.ChessBoardModel
import com.guykn.smartchessboard2.bluetooth.GameStartRequest
import com.guykn.smartchessboard2.bluetooth.companiondevice.CompanionDeviceConnector
import com.guykn.smartchessboard2.network.lichess.WebManager
import com.guykn.smartchessboard2.network.oauth2.LICHESS_BASE_URL
import com.guykn.smartchessboard2.network.oauth2.getLichessAuthIntent
import dagger.hilt.android.AndroidEntryPoint
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import javax.inject.Inject


@AndroidEntryPoint
class MainActivity : AppCompatActivity(), CompanionDeviceConnector.IntentCallback,
    StartOfflineGameDialog.Callback, UiActionCallbacks {

    @Inject
    lateinit var companionDeviceConnector: CompanionDeviceConnector

    @Inject
    lateinit var customTabManager: CustomTabManager

    private lateinit var authService: AuthorizationService

    private val mainViewModel: MainViewModel by viewModels()


    private lateinit var coordinatorLayout: CoordinatorLayout

    companion object {
        const val TAG = "MainActivity"
        const val REQUEST_ENABLE_BLUETOOTH = 420
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
        setContentView(R.layout.activity_main)
        coordinatorLayout = findViewById(R.id.coordinator_layout)!!

        authService = AuthorizationService(this)

        companionDeviceConnector.refreshBluetoothDevice()
        setContentView(R.layout.activity_main)
        if (savedInstanceState == null) {
            supportFragmentManager.commit {
                setReorderingAllowed(true)
                add(R.id.container, MainOptionsFragment())
            }
        }

        startObserveForUi()
        startObserveForOpenTabs()
    }

    override fun onDestroy() {
        companionDeviceConnector.destroy()
        authService.dispose()
        super.onDestroy()
    }

    override fun onIntentFound(intentSender: IntentSender) {
        val intentSenderRequest: IntentSenderRequest =
            IntentSenderRequest.Builder(intentSender).build()
        bluetoothIntentLauncher.launch(intentSenderRequest)
    }

    override fun handleGameStartRequest(gameStartRequest: GameStartRequest) {
        mainViewModel.startOfflineGame(gameStartRequest)
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

    private var signInCallback: (() -> Unit)? = null

    private val lichessAuthLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        signInCallback = null
        continueSignIn(result)
    }

    private val lichessAuthLauncherWithStartOnlineGame = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        signInCallback = {
            startOnlineGameInternal()
        }
        continueSignIn(result)
    }

    private val lichessAuthLauncherWithStartBroadcast = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        signInCallback = {
            mainViewModel.startBroadcast()
        }
        continueSignIn(result)
    }

    private val lichessAuthLauncherWithViewSavedGames = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        signInCallback = {
            startOpenSavedGamesTab()
        }
        continueSignIn(result)
    }

    private fun continueSignIn(result: ActivityResult?) {
        result?.data?.let {
            val authorizationResponse = AuthorizationResponse.fromIntent(it)
            val authorizationException = AuthorizationException.fromIntent(it)
            mainViewModel.signIn(
                authorizationResponse,
                authorizationException
            )
        } ?: Log.w(TAG, "OAuth custom tab intent returned with no data. ")
    }

    override fun signIn() {
        val uiAuthState = mainViewModel.uiOAuthState.value
        if (uiAuthState is WebManager.UiOAuthState.NotAuthorized) {
            lichessAuthLauncher.launch(authService.getLichessAuthIntent())
        } else {
            Log.w(TAG, "Tried to sign in at a wrong time. uiAuthState=$uiAuthState")
        }
    }

    override fun signOut() {
        mainViewModel.signOut()
    }

    override fun startOfflineGame() {
        StartOfflineGameDialog()
            .show(supportFragmentManager, "start_offline_game")
    }

    override fun startOnlineGame() {
        if (!mainViewModel.isSignedIn()) {
            lichessAuthLauncherWithStartOnlineGame.launch(authService.getLichessAuthIntent())
        } else {
            startOnlineGameInternal()
        }

    }

    override fun startBroadcast() {
        if (!mainViewModel.isSignedIn()) {
            lichessAuthLauncherWithStartBroadcast.launch(authService.getLichessAuthIntent())
        } else {
            mainViewModel.startBroadcast()
            mainViewModel.broadcastRound.value?.value?.let { broadcastRound ->
                openCustomChromeTab(broadcastRound.url)
            }
        }
    }

    override fun viewSavedGames() {
        if (!mainViewModel.isSignedIn()) {
            lichessAuthLauncherWithViewSavedGames.launch(authService.getLichessAuthIntent())
        } else {
            startOpenSavedGamesTab()
        }
    }

    private fun startOnlineGameInternal() {
        if (mainViewModel.isOnlineGameOver.value == true) {
            // the online game has ended and no new online game has started, so we can open lichess right away.
            openCustomChromeTab(LICHESS_BASE_URL)
        } else {
            mainViewModel.startOnlineGame()
            mainViewModel.isAwaitingLaunchLichess.value = true
        }
    }

    private fun startOpenSavedGamesTab() {
        when (mainViewModel.numGamesToUpload.value) {
            // if there are no games to upload, simply open the custom chrome tab. Otherwise, first upload the games, then open the chrome tab.
            0, null -> {
                Log.d(TAG, "Only opening lichess. ")
                openSavedGamesTab()
            }
            else -> mainViewModel.uploadPgn()
        }
    }

    private fun openSavedGamesTab() {
        val authState = mainViewModel.uiOAuthState.value
        if (authState is WebManager.UiOAuthState.Authorized) {
            openCustomChromeTab(authState.userInfo.importedGamesUrl())
        } else {
            Log.w(
                TAG,
                "Tried to open Saved Games while not signed in. "
            )
        }
    }

    private fun openCustomChromeTab(url: String) {
        customTabManager.openChromeTab(this, url)
    }

    private fun startObserveForOpenTabs() {
        // If looking for an online game, and an active online game already exists, then open it.
        observeMultiple(
            this,
            mainViewModel.activeOnlineGame,
            mainViewModel.isAwaitingLaunchLichess,
            mainViewModel.isOnlineGameActive
        ) { activeOnlineGame, isAwaitingLaunchLichess, isOnlineGameActive ->
            if (isAwaitingLaunchLichess == true && activeOnlineGame?.value != null && isOnlineGameActive == true) {
                openCustomChromeTab(activeOnlineGame.value.url)
                mainViewModel.isAwaitingLaunchLichess.value = false
            }
        }

        // If done looking for an online game and none was found, open the lichess homepage.
        mainViewModel.launchLichessHomepageEvent.observe(this) { event ->
            if (event?.receive() == true && mainViewModel.isAwaitingLaunchLichess.value == true) {
                openCustomChromeTab(LICHESS_BASE_URL)
                mainViewModel.isAwaitingLaunchLichess.value = false
            }
        }

        mainViewModel.broadcastRound.observe(this) { broadcastEvent ->
            if (broadcastEvent?.receive() == true && broadcastEvent.value != null) {
                openCustomChromeTab(broadcastEvent.value.url)
            }
        }

        // if the user sucsessfully signed in, invoke the signInCallback
        mainViewModel.uiOAuthState.observe(this) {
            if (it is WebManager.UiOAuthState.Authorized) {
                signInCallback?.invoke()
                signInCallback = null
            }
        }
    }

    private fun startObserveForUi() {

        var bluetoothMessageDialog: Dialog? = null
        var loadingBroadcastDialog: Dialog? = null
        var uploadingPgnDialog: ProgressDialog? = null
        var signInProgressBar: ProgressDialog? = null


        mainViewModel.bluetoothState.observe(this) { bluetoothState ->
            bluetoothMessageDialog?.dismiss()
            bluetoothMessageDialog = null

            when (bluetoothState) {
                null -> {
                }
                ChessBoardModel.BluetoothState.BLUETOOTH_NOT_SUPPORTED -> {
                    bluetoothMessageDialog = AlertDialog.Builder(this)
                        .setTitle("Bluetooth Error")
                        .setMessage("Your Device does not support bluetooth. ")
                        .setCancelable(false)
                        .setPositiveButton("Close App") { _, _ ->
                            finish()
                        }
                        .show()
                }

                ChessBoardModel.BluetoothState.BLUETOOTH_NOT_ENABLED -> {
                    bluetoothMessageDialog = AlertDialog.Builder(this)
                        .setTitle("Bluetooth Error")
                        .setMessage("Bluetooth is not enabled. ")
                        .setCancelable(false)
                        .setPositiveButton("Enable Bluetooth") { _, _ ->
                            requestEnableBluetooth()
                        }
                        .show()
                    requestEnableBluetooth()
                }

                ChessBoardModel.BluetoothState.BLUETOOTH_TURNING_ON -> {
                    bluetoothMessageDialog =
                        ProgressDialog.show(
                            this,
                            "Bluetooth Loading...",
                            "Bluetooth is turning on",
                            true
                        )

                }
                ChessBoardModel.BluetoothState.SCANNING -> {
                    bluetoothMessageDialog =
                        ProgressDialog.show(
                            this,
                            "Bluetooth Loading...",
                            "Performing bluetooth scan",
                            true
                        )
                }
                ChessBoardModel.BluetoothState.PAIRING -> {
                    bluetoothMessageDialog = ProgressDialog.show(
                        this,
                        "Bluetooth Loading...",
                        "Pairing with chessboard",
                        true
                    )
                }
                ChessBoardModel.BluetoothState.CONNECTING -> {
                    bluetoothMessageDialog = ProgressDialog.show(
                        this,
                        "Bluetooth Loading...",
                        "Connecting to chessboard",
                        true
                    )
                }

                ChessBoardModel.BluetoothState.DISCONNECTED, ChessBoardModel.BluetoothState.CONNECTION_FAILED, ChessBoardModel.BluetoothState.SCAN_FAILED -> {
                    bluetoothMessageDialog = AlertDialog.Builder(this)
                        .setTitle("Bluetooth Error")
                        .setMessage("Failed to connect to Bluetooth")
                        .setCancelable(false)
                        .setPositiveButton("Try Again") { _, _ ->
                            companionDeviceConnector.refreshBluetoothDevice()
                        }
                        .show()
                }
                ChessBoardModel.BluetoothState.REQUESTING_USER_INPUT -> {
                } // requesting user input will open a separate window with an intent, so no UI change is necessary
                ChessBoardModel.BluetoothState.CONNECTED -> {
                }
            }
        }

        mainViewModel.successEvents.observe(this) { successEvent ->
            if (successEvent?.receive() != true) {
                return@observe
            }
            when (successEvent) {
                is EventBus.SuccessEvent.BlinkLedsSuccess -> {
                    Snackbar.make(
                        coordinatorLayout,
                        "Chessboard LEDs are now on",
                        resources.getInteger(R.integer.led_test_snackbar_duration)
                    )
                        .show()
                }
                is EventBus.SuccessEvent.ChangeSettingsSuccess -> {
                    Snackbar.make(
                        coordinatorLayout,
                        if (successEvent.settings.learningMode) "Learning Mode Enabled" else "Learning Mode Disabled",
                        Snackbar.LENGTH_SHORT
                    )
                        .show()
                }
                is EventBus.SuccessEvent.SignInSuccess -> {
                    Snackbar.make(
                        coordinatorLayout,
                        "Signed in as ${successEvent.userInfo.username}",
                        Snackbar.LENGTH_SHORT
                    )
                        .show()
                }
                is EventBus.SuccessEvent.SignOutSuccess -> {
                    Snackbar.make(
                        coordinatorLayout,
                        "Signed Out Successfully",
                        Snackbar.LENGTH_SHORT
                    )
                        .show()
                }
                is EventBus.SuccessEvent.StartOfflineGameSuccess -> {
                    Snackbar.make(
                        coordinatorLayout,
                        "Game Started",
                        Snackbar.LENGTH_SHORT
                    )
                        .show()
                }
                is EventBus.SuccessEvent.UploadGamesSuccess -> {
                    openSavedGamesTab()
                }
                is EventBus.SuccessEvent.UploadGamesPartialSuccess -> {
                    AlertDialog.Builder(this)
                        .setTitle("Not All Games Uploaded")
                        .setMessage("Because of Lichess' API limits, not all games on the chessboard have been uploaded. To see all games, wait a few minutes and try again.")
                        .setPositiveButton("OK") { _, _ -> }
                        .setOnDismissListener {
                            openSavedGamesTab()
                        }
                        .show()
                }
                is EventBus.SuccessEvent.ArchiveAllPgnSuccess -> {
                    Snackbar.make(
                        coordinatorLayout,
                        "Deleted ${successEvent.numFiles} games",
                        Snackbar.LENGTH_SHORT
                    )
                        .show()
                }
            }
        }

        mainViewModel.errorEvents.observe(this) { errorEvent ->
            if (errorEvent?.receive() != true) {
                return@observe
            }
            when (errorEvent) {
                is EventBus.ErrorEvent.BluetoothIOError -> {
                    // Do nothing. Bluetooth Errors are already handled by the Bluetooth State observer
                }
                is EventBus.ErrorEvent.InternetIOError -> {
                    Snackbar.make(
                        coordinatorLayout,
                        "Could Not Connect to Lichess. Please check your connection and try again. ",
                        Snackbar.LENGTH_SHORT
                    )
                        .show()
                }
                is EventBus.ErrorEvent.MiscError -> {
                    Snackbar.make(
                        coordinatorLayout,
                        "Error: ${errorEvent.description}",
                        Snackbar.LENGTH_SHORT
                    )
                        .show()
                }
                is EventBus.ErrorEvent.NoLongerAuthorizedError -> {
                    Snackbar.make(
                        coordinatorLayout,
                        "Unexpectedly signed out. ",
                        Snackbar.LENGTH_SHORT
                    )
                        .setAction("Sign In") { signIn() }
                        .show()
                }
                is EventBus.ErrorEvent.SignInError -> {
                    Snackbar.make(
                        coordinatorLayout,
                        "Could Not Sign In",
                        Snackbar.LENGTH_SHORT
                    )
                        .setAction("Try Again") { signIn() }
                        .show()
                }
                is EventBus.ErrorEvent.TooManyRequests -> {
                    val timeUntilServerAvailable =
                        errorEvent.timeForValidRequests - System.currentTimeMillis()
                    val timeUntilServerAvailableSeconds = timeUntilServerAvailable / 1000
                    Snackbar.make(
                        coordinatorLayout,
                        "Lichess Servers are overwhelmed. Please try again in $timeUntilServerAvailableSeconds seconds. ",
                        Snackbar.LENGTH_LONG
                    )
                        .show()
                }
                is EventBus.ErrorEvent.IllegalGameSelected -> {
                    AlertDialog.Builder(this)
                        .setTitle("Invalid Game")
                        .setMessage("You have chosen an invalid game mode. Only unlimited, classic, and rapid time controls are supported. No rules variants are supported. ")
                        .setPositiveButton("OK") { _, _ -> }
                        .show()

                }
                is EventBus.ErrorEvent.BroadcastCreatedWhileOnlineGameActive -> {
                    Snackbar.make(
                        coordinatorLayout,
                        "Can't create broadcast for an online game. ",
                        Snackbar.LENGTH_LONG
                    )
                        .show()
                }
            }
        }

        mainViewModel.isLoadingBroadcast.observe(this) { isLoading ->
            loadingBroadcastDialog?.dismiss()
            loadingBroadcastDialog = null
            if (isLoading) {
                loadingBroadcastDialog = ProgressDialog.show(
                    this,
                    null,
                    "Creating Broadcast",
                    true
                )
            }
        }

        mainViewModel.isAwaitingLaunchLichess.observe(this) { isLoading ->
            loadingBroadcastDialog?.dismiss()
            loadingBroadcastDialog = null
            if (isLoading == true) {
                loadingBroadcastDialog = ProgressDialog.show(
                    this,
                    null,
                    "Loading Online Games",
                    true
                )
            }
        }

        mainViewModel.pgnFilesUploadState.observe(this) { pgnFileUploadState ->
            when (pgnFileUploadState) {
                Repository.PgnFilesUploadState.NotUploading -> {
                    uploadingPgnDialog?.dismiss()
                    uploadingPgnDialog = null
                }
                Repository.PgnFilesUploadState.ExchangingBluetoothData,
                is Repository.PgnFilesUploadState.UploadingToLichess -> {
                    if (uploadingPgnDialog == null) {
                        uploadingPgnDialog = ProgressDialog.show(
                            this,
                            null,
                            "Uploading Saved Games",
                            true
                        )
                    }
                }
            }
        }

        mainViewModel.uiOAuthState.observe(this) { authState ->
            when (authState) {
                null -> {
                }
                is WebManager.UiOAuthState.NotAuthorized -> {
                    signInProgressBar?.dismiss()
                    signInProgressBar = null
                }
                is WebManager.UiOAuthState.AuthorizationLoading -> {
                    if (signInProgressBar == null) {
                        signInProgressBar =
                            ProgressDialog.show(this, "", "Signing In", true)
                    }
                }

                is WebManager.UiOAuthState.Authorized -> {
                    signInProgressBar?.dismiss()
                    signInProgressBar = null
                }
            }
        }

        // always observe the MediatorLiveData, so that it is kept up to date even when using getValue()
        mainViewModel.isOnlineGameActive.observe(this) {}
        mainViewModel.isOnlineGameOver.observe(this) {}
    }
}
