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
import android.view.Menu
import android.view.MenuItem
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
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
import com.guykn.smartchessboard2.ui.MainViewModel.ActionBarState.NORMAL_ACTION_BAR
import com.guykn.smartchessboard2.ui.MainViewModel.ActionBarState.SETTINGS_ACTION_BAR
import dagger.hilt.android.AndroidEntryPoint
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity(), CompanionDeviceConnector.IntentCallback,
    StartOfflineGameDialog.Callback, UiActionCallbacks {

    private val mainViewModel: MainViewModel by viewModels()

    @Inject
    lateinit var companionDeviceConnector: CompanionDeviceConnector

    @Inject
    lateinit var customTabManager: CustomTabManager
    private lateinit var authService: AuthorizationService

    private lateinit var coordinatorLayout: CoordinatorLayout
    private lateinit var signInButton: LinearLayout
    private lateinit var signInMainText: TextView
    private lateinit var signInSecondaryText: TextView
//    private lateinit var toolbarIcon: ImageView

    companion object {
        const val TAG = "MA_MainActivity"
        const val REQUEST_ENABLE_BLUETOOTH = 420

        const val isUiTest = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        coordinatorLayout = findViewById(R.id.coordinator_layout)!!
        signInMainText = findViewById(R.id.sign_in_main)!!
        signInSecondaryText = findViewById(R.id.sign_in_secondary)!!
        signInButton = findViewById(R.id.sign_in_button)!!
//        toolbarIcon = findViewById(R.id.toolbar_icon)!!


        val toolbar: Toolbar = findViewById(R.id.toolbar)!!
        setSupportActionBar(toolbar)

        authService = AuthorizationService(this)

        companionDeviceConnector.refreshBluetoothDevice()
        if (savedInstanceState == null) {
            supportFragmentManager.commit {
                setReorderingAllowed(true)
                add(R.id.container, MainOptionsFragment())
            }
        }

        customTabManager.mayLaunchUrl(LICHESS_BASE_URL)

        signInButton.setOnClickListener {
            when (mainViewModel.uiOAuthState.value) {
                null -> {
                }
                is WebManager.UiOAuthState.NotAuthorized -> {
                    signIn()
                }
                is WebManager.UiOAuthState.AuthorizationLoading -> {
                    Log.w(TAG, "Sign in button pressed while in the middle of signing in. ")
                }
                is WebManager.UiOAuthState.Authorized -> {
                    showSignOutDialog()
                }
            }
        }
        mainViewModel.observeAll(this)
        startObserveForUi()
        startObserveForOpenTabs()
        startObserveForActionBar()
    }

    override fun onDestroy() {
        companionDeviceConnector.destroy()
        authService.dispose()
        super.onDestroy()
    }

    override fun onPrepareOptionsMenu(menu: Menu?): Boolean {
        return when (val actionBarState = mainViewModel.actionBarState.value) {
            NORMAL_ACTION_BAR -> {
//                toolbarIcon.visibility = View.VISIBLE
                menuInflater.inflate(R.menu.action_bar_menu, menu)
                true
            }
            SETTINGS_ACTION_BAR -> {
//                toolbarIcon.visibility = View.GONE
                true
            }
            else -> error("Invalid value of actionBarState = $actionBarState")
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_settings -> {
            supportFragmentManager.commit {
                setReorderingAllowed(true)
                setCustomAnimations(
                    R.anim.slide_in,
                    R.anim.slide_out,
                    R.anim.slide_in,
                    R.anim.slide_out
                )
                addToBackStack(null)
                replace(R.id.container, ExtraOptionsFragment())
            }
            true
        }
        android.R.id.home -> {
            supportFragmentManager.popBackStack()
            true
        }
        else -> {
            super.onOptionsItemSelected(item)
        }
    }

//////////////////////////////////////////////////////////////////////////////////////////////

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
            mainViewModel.broadcastGame.value?.value?.let { broadcastGame ->
                openCustomChromeTab(broadcastGame.url)
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

    private fun showSignOutDialog() {
        AlertDialog.Builder(this)
            .setTitle("Are You Sure You Want to Sign Out?")
            .setCancelable(false)
            .setPositiveButton("Yes") { _, _ ->
                signOut()
            }
            .setNegativeButton("No") { _, _ -> }
            .show()
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

        mainViewModel.broadcastGame.observe(this) { broadcastEvent ->
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

    private fun startObserveForActionBar() {
        mainViewModel.actionBarState.observe(this) { actionBarState ->
            invalidateOptionsMenu()
            when (actionBarState!!) {
                NORMAL_ACTION_BAR -> {
                    supportActionBar!!.apply {
                        setTitle(R.string.app_name)
                        setDisplayHomeAsUpEnabled(false)
                    }
                }
                SETTINGS_ACTION_BAR -> {
                    supportActionBar!!.apply {
                        setTitle(R.string.settings)
                        setDisplayHomeAsUpEnabled(true)
                    }
                }
            }
        }
    }

    private fun startObserveForUi() {

        var bluetoothMessageDialog: Dialog? = null
        var loadingBroadcastDialog: Dialog? = null
        var loadingLichessDialog: ProgressDialog? = null
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
                        .setTitle(R.string.bluetooth_error_title)
                        .setMessage(R.string.bluetooth_not_supported_description)
                        .setCancelable(false)
                        .setPositiveButton(getString(R.string.bluetooth_not_supported_button)) { _, _ ->
                            if (!isUiTest) {
                                finish()
                            }
                        }
                        .show()
                }

                ChessBoardModel.BluetoothState.BLUETOOTH_NOT_ENABLED -> {
                    bluetoothMessageDialog = AlertDialog.Builder(this)
                        .setTitle(R.string.bluetooth_error_title)
                        .setMessage(getString(R.string.bluetooth_not_enabled_message))
                        .setCancelable(false)
                        .setPositiveButton(getString(R.string.enable_bluetooth)) { _, _ ->
                            requestEnableBluetooth()
                        }
                        .show()
                    requestEnableBluetooth()
                }

                ChessBoardModel.BluetoothState.BLUETOOTH_TURNING_ON -> {
                    bluetoothMessageDialog =
                        ProgressDialog.show(
                            this,
                            getString(R.string.bluetooth_loading_title),
                            getString(R.string.loading_bluetooth_turning_on),
                            true
                        )

                }
                ChessBoardModel.BluetoothState.SCANNING -> {
                    bluetoothMessageDialog =
                        ProgressDialog.show(
                            this,
                            getString(R.string.bluetooth_loading_title),
                            getString(R.string.loading_bluetooth_scan),
                            true
                        )
                }
                ChessBoardModel.BluetoothState.PAIRING -> {
                    bluetoothMessageDialog = ProgressDialog.show(
                        this,
                        getString(R.string.bluetooth_loading_title),
                        getString(R.string.loading_bluetooth_pairing),
                        true
                    )
                }
                ChessBoardModel.BluetoothState.CONNECTING -> {
                    bluetoothMessageDialog = ProgressDialog.show(
                        this,
                        getString(R.string.bluetooth_loading_title),
                        getString(R.string.loading_bluetooth_connecting),
                        true
                    )
                }

                ChessBoardModel.BluetoothState.DISCONNECTED, ChessBoardModel.BluetoothState.CONNECTION_FAILED, ChessBoardModel.BluetoothState.SCAN_FAILED -> {
                    bluetoothMessageDialog = AlertDialog.Builder(this)
                        .setTitle(R.string.bluetooth_error_title)
                        .setMessage(R.string.bluetooth_connection_failed_message)
                        .setCancelable(false)
                        .setPositiveButton(R.string.bluetooth_retry) { _, _ ->
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
                        R.string.success_test_chessboard,
                        resources.getInteger(R.integer.led_test_snackbar_duration)
                    )
                        .show()
                }
                is EventBus.SuccessEvent.ChangeSettingsSuccess -> {
                    Snackbar.make(
                        coordinatorLayout,
                        if (successEvent.settings.learningMode) {
                            R.string.success_learning_mode_enabled
                        } else {
                            R.string.success_learning_mode_disabled
                        },
                        Snackbar.LENGTH_SHORT
                    )
                        .show()
                }
                is EventBus.SuccessEvent.SignInSuccess -> {
                    Snackbar.make(
                        coordinatorLayout,
                        getString(R.string.success_sign_in, successEvent.userInfo.username),
                        Snackbar.LENGTH_SHORT
                    )
                        .show()
                }
                is EventBus.SuccessEvent.SignOutSuccess -> {
                    Snackbar.make(
                        coordinatorLayout,
                        getString(R.string.success_sign_out),
                        Snackbar.LENGTH_SHORT
                    )
                        .show()
                }
                is EventBus.SuccessEvent.StartOfflineGameSuccess -> {
                    Snackbar.make(
                        coordinatorLayout,
                        getString(R.string.success_start_game),
                        Snackbar.LENGTH_SHORT
                    )
                        .show()
                }
                is EventBus.SuccessEvent.UploadGamesSuccess -> {
                    openSavedGamesTab()
                }
                is EventBus.SuccessEvent.UploadGamesPartialSuccess -> {
                    AlertDialog.Builder(this)
                        .setTitle(getString(R.string.not_all_games_uploaded_title))
                        .setMessage(getString(R.string.not_all_games_uploaded_description))
                        .setPositiveButton(getString(R.string.not_all_games_uploaded_action)) { _, _ -> }
                        .setOnDismissListener {
                            openSavedGamesTab()
                        }
                        .show()
                }
                is EventBus.SuccessEvent.ArchiveAllPgnSuccess -> {
                    Snackbar.make(
                        coordinatorLayout,
                        resources.getQuantityString(R.plurals.success_archive_games, successEvent.numFiles, successEvent.numFiles),
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
                        getString(R.string.error_internet_io),
                        Snackbar.LENGTH_SHORT
                    )
                        .show()
                }
                // todo: give errors better descriptions
                is EventBus.ErrorEvent.MiscError -> {
                    Snackbar.make(
                        coordinatorLayout,
                        getString(R.string.error_generic, errorEvent.description),
                        Snackbar.LENGTH_SHORT
                    )
                        .show()
                }
                is EventBus.ErrorEvent.NoLongerAuthorizedError -> {
                    Snackbar.make(
                        coordinatorLayout,
                        getString(R.string.error_unexpected_sign_out),
                        Snackbar.LENGTH_SHORT
                    )
                        .setAction(getString(R.string.error_unexpected_sign_out_action)) { signIn() }
                        .show()
                }
                is EventBus.ErrorEvent.SignInError -> {
                    Snackbar.make(
                        coordinatorLayout,
                        getString(R.string.error_sign_in),
                        Snackbar.LENGTH_SHORT
                    )
                        .setAction(getString(R.string.error_sign_in_action)) { signIn() }
                        .show()
                }
                is EventBus.ErrorEvent.TooManyRequests -> {
                    val timeUntilServerAvailable =
                        (errorEvent.timeForValidRequests - System.currentTimeMillis())
                    val timeUntilServerAvailableSeconds = (timeUntilServerAvailable / 1000).toInt()
                    if(timeUntilServerAvailableSeconds <= 0){
                        // if by now too 429 too many requests is not a problem, no need to show it in UI.
                        return@observe
                    }
                    Snackbar.make(
                        coordinatorLayout,
                        resources.getString(R.string.error_429, timeUntilServerAvailableSeconds),
                        Snackbar.LENGTH_LONG
                    )
                        .show()
                }
                is EventBus.ErrorEvent.IllegalGameSelected -> {
                    AlertDialog.Builder(this)
                        .setTitle(getString(R.string.invalid_game_title))
                        .setMessage(getString(R.string.invalid_game_description))
                        .setPositiveButton(getString(R.string.invalid_game_action)) { _, _ -> }
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
                    getString(R.string.loading_generic),
                    getString(R.string.loading_creating_broadcast),
                    true
                )
            }
        }

        mainViewModel.isAwaitingLaunchLichess.observe(this) { isAwaitingLaunchLichess ->
            Log.d(TAG, "mainViewModel.isAwaitingLaunchLichess changed. isAwaitingLaunchLichess=$isAwaitingLaunchLichess")
            loadingLichessDialog?.dismiss()
            loadingLichessDialog = null
            if (isAwaitingLaunchLichess == true) {
                Log.d(TAG, "Showing progress bar.")
                loadingLichessDialog = ProgressDialog.show(
                    this,
                    getString(R.string.loading_generic),
                    getString(R.string.loading_start_online_game),
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
                            getString(R.string.loading_generic),
                            getString(R.string.loading_uploading_games),
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
                            ProgressDialog.show(
                                this,
                                getString(R.string.loading_generic),
                                getString(R.string.loading_signing_in),
                                true
                            )
                    }
                }

                is WebManager.UiOAuthState.Authorized -> {
                    signInProgressBar?.dismiss()
                    signInProgressBar = null
                }
            }
        }

        mainViewModel.uiOAuthState.observe(this) { authState ->
            when (authState) {
                null -> {
                }
                is WebManager.UiOAuthState.NotAuthorized, is WebManager.UiOAuthState.AuthorizationLoading -> {
                    signInMainText.text = getString(R.string.not_signed_in_button_primary)
                    signInSecondaryText.text = getString(R.string.not_signed_in_button_secondary)

                }
                is WebManager.UiOAuthState.Authorized -> {
                    signInMainText.text = authState.userInfo.username
                    signInSecondaryText.text = getString(R.string.signed_in_button_secondary)
                }
            }
        }

    }
}
