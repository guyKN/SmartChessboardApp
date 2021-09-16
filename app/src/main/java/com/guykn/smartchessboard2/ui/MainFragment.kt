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
import com.guykn.smartchessboard2.CustomTabManager
import com.guykn.smartchessboard2.EventBus.ErrorEvent
import com.guykn.smartchessboard2.EventBus.SuccessEvent
import com.guykn.smartchessboard2.R
import com.guykn.smartchessboard2.Repository.PgnFilesUploadState.*
import com.guykn.smartchessboard2.bluetooth.ChessBoardModel.BluetoothState.*
import com.guykn.smartchessboard2.bluetooth.ChessBoardSettings
import com.guykn.smartchessboard2.bluetooth.companiondevice.CompanionDeviceConnector
import com.guykn.smartchessboard2.network.lichess.WebManager.InternetState.*
import com.guykn.smartchessboard2.network.lichess.WebManager.UiOAuthState
import com.guykn.smartchessboard2.network.oauth2.LICHESS_BASE_URL
import com.guykn.smartchessboard2.network.oauth2.getLichessAuthIntent
import com.guykn.smartchessboard2.observeMultiple
import dagger.hilt.android.AndroidEntryPoint
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import javax.inject.Inject

// todo: encapsulate more data into repository and viewmodel, instead of reaching into fields of complex data classes in UI layer

@SuppressWarnings("DEPRECATION")
@AndroidEntryPoint
abstract class MainFragment : PreferenceFragmentCompat() {

//    companion object {
//        const val TAG = "MA_MainFragment"
//    }
//
//    @Inject
//    lateinit var companionDeviceConnector: CompanionDeviceConnector
//
//    @Inject
//    lateinit var customTabManager: CustomTabManager
//
//    private val mainViewModel: MainViewModel by activityViewModels()
//
//    private lateinit var signInInfo: Preference
//    private lateinit var playAgainstAiButton: Preference
//    private lateinit var twoPlayerGameButton: Preference
//    private lateinit var playOnlineButton: Preference
//    private lateinit var startBroadcastButton: Preference
//    private lateinit var uploadGamesButton: Preference
//    private lateinit var blinkLedsButton: Preference
//    private lateinit var learningModeSwitch: SwitchPreferenceCompat
//    private lateinit var archiveAllPgnButton: Preference
//
//    private var bluetoothMessageDialog: Dialog? = null
//    private var loadingBroadcastDialog: Dialog? = null
//    private var uploadingPgnDialog: ProgressDialog? = null
//    private var signInProgressBar: ProgressDialog? = null
//
//
//    override fun onCreate(savedInstanceState: Bundle?) {
//        super.onCreate(savedInstanceState)
//        customTabManager.mayLaunchUrl(LICHESS_BASE_URL)
//    }
//
//
//    override fun onAttach(context: Context) {
//        super.onAttach(context)
//    }
//
//    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
//        setPreferencesFromResource(R.xml.root_preferences, rootKey)
//        signInInfo = findPreference("sign_in_info")!!
//        playAgainstAiButton = findPreference("play_against_ai")!!
//        twoPlayerGameButton = findPreference("two_player_game")!!
//        playOnlineButton = findPreference("play_online")!!
//        startBroadcastButton = findPreference("start_broadcast")!!
//        uploadGamesButton = findPreference("upload_games")!!
//        blinkLedsButton = findPreference("test_connection")!!
//        learningModeSwitch = findPreference("learning_mode")!!
//        archiveAllPgnButton = findPreference("archive_all_pgn")!!
//
//        signInInfo.setOnPreferenceClickListener {
//            // if the user is signed out then this button sign in. If the user is signed in, this button signs out.
//            when (mainViewModel.uiOAuthState.value) {
//                null -> {
//                }
//                is UiOAuthState.AuthorizationLoading -> {
//                }
//                is UiOAuthState.NotAuthorized -> {
//                    startLichessSignIn()
//                }
//                is UiOAuthState.Authorized -> {
//                    mainViewModel.signOut()
//                }
//            }
//            true
//        }
//
//        playAgainstAiButton.setOnPreferenceClickListener {
//            StartOfflineGameDialog()
//                .show(parentFragmentManager, "start_offline_game")
//            true
//        }
//
//        twoPlayerGameButton.setOnPreferenceClickListener {
//            mainViewModel.startTwoPlayerGame()
//            true
//        }
//
//        playOnlineButton.setOnPreferenceClickListener {
//            if (!mainViewModel.isSignedIn()) {
//                lichessAuthLauncherWithStartOnlineGame.launch(authService.getLichessAuthIntent())
//            } else {
//                startAndOpenOnlineGame()
//            }
//            true
//        }
//
//        startBroadcastButton.setOnPreferenceClickListener {
//            if (!mainViewModel.isSignedIn()) {
//                lichessAuthLauncherWithStartBroadcast.launch(authService.getLichessAuthIntent())
//            } else {
//                mainViewModel.startBroadcast()
//                mainViewModel.broadcastRound.value?.value?.let { broadcastRound ->
//                    openCustomChromeTab(broadcastRound.url)
//                }
//            }
//            true
//        }
//
//        uploadGamesButton.setOnPreferenceClickListener {
//            if (!mainViewModel.isSignedIn()) {
//                lichessAuthLauncherWithViewSavedGames.launch(authService.getLichessAuthIntent())
//            } else {
//                startViewSavedGames()
//            }
//            true
//        }
//
//        learningModeSwitch.setOnPreferenceChangeListener { _, newValue ->
//            val isChecked = newValue as Boolean
//            mainViewModel.writeSettings(
//                ChessBoardSettings(learningMode = isChecked)
//            )
//            true
//        }
//
//        blinkLedsButton.setOnPreferenceClickListener {
//            mainViewModel.blinkLeds()
//            true
//        }
//
//        archiveAllPgnButton.setOnPreferenceClickListener {
//            AlertDialog.Builder(requireContext())
//                .setTitle("Are you sure?")
//                .setMessage("All saved games that have not been uploaded to Lichess will be deleted. Games that were already uploaded to Lichess will stay there. ")
//                .setCancelable(true)
//                .setPositiveButton("OK") { _, _ ->
//                    mainViewModel.archiveAllPgn()
//                }
//                .setNegativeButton("Cancel"){_, _-> }
//                .show()
//            true
//        }
//    }
//
//    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
//        super.onViewCreated(view, savedInstanceState)
//
//
//        mainViewModel.uiOAuthState.observe(viewLifecycleOwner) { authState ->
//            when (authState) {
//                null -> {
//                }
//                is UiOAuthState.NotAuthorized -> {
//                    signInProgressBar?.dismiss()
//                    signInProgressBar = null
//
//                    signInInfo.title = "Sign in"
//                    signInInfo.summary = ""
//                }
//                is UiOAuthState.AuthorizationLoading -> {
//                    signInInfo.title = "Sign in"
//                    signInInfo.summary = ""
//
//                    if (signInProgressBar == null) {
//                        signInProgressBar =
//                            ProgressDialog.show(requireContext(), "", "Signing In", true)
//                    }
//                }
//
//                is UiOAuthState.Authorized -> {
//                    signInProgressBar?.dismiss()
//                    signInProgressBar = null
//
//                    val username = authState.userInfo.username
//                    signInInfo.title = "Signed in as $username"
//                    signInInfo.summary = "Click here to sign out"
//                }
//            }
//        }
//
//        mainViewModel.isOnlineGameActive.observe(viewLifecycleOwner) { isOnlineGameActive ->
//            if (isOnlineGameActive == true) {
//                playOnlineButton.title = "Online Game In Progress"
//                playOnlineButton.summary = "Click here to view"
//            } else {
//                playOnlineButton.title = "Play Online"
//                playOnlineButton.summary = ""
//            }
//        }
//
//        mainViewModel.numGamesToUpload.observe(viewLifecycleOwner) { numGamesToUpload ->
//            when (numGamesToUpload) {
//                0, null -> {
//                    uploadGamesButton.summary = ""
//
//                    archiveAllPgnButton.summary = "No games saved. "
//                    archiveAllPgnButton.isEnabled = false
//
//                }
//                else -> {
//                    uploadGamesButton.summary = "$numGamesToUpload games need to be uploaded"
//
//                    archiveAllPgnButton.summary = "$numGamesToUpload games currently saved"
//                    archiveAllPgnButton.isEnabled = true
//
//                }
//            }
//        }
//
//        mainViewModel.chessBoardSettings.observe(viewLifecycleOwner) { chessBoardSettings ->
//            learningModeSwitch.isChecked = chessBoardSettings?.learningMode ?: return@observe
//        }
//
//    }
//
//    private fun requestEnableBluetooth() {
//        Log.d(TAG, "requestEnableBluetooth() called")
//        if (CompanionDeviceConnector.shouldRequestEnableBluetooth()) {
//            val intent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
//            startActivityForResult(intent, REQUEST_ENABLE_BLUETOOTH)
//        } else {
//            Log.w(TAG, "Tried to request enabling bluetooth while bluetooth was already enabled. ")
//        }
//    }
}