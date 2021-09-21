package com.guykn.smartchessboard2.ui

import android.app.AlertDialog
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.fragment.app.activityViewModels
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.guykn.smartchessboard2.R
import com.guykn.smartchessboard2.bluetooth.ChessBoardSettings
import com.guykn.smartchessboard2.network.lichess.WebManager
import com.guykn.smartchessboard2.ui.MainViewModel.ActionBarState.*

class ExtraOptionsFragment : PreferenceFragmentCompat() {

    private lateinit var signInButton: Preference
    private lateinit var learningModeSwitch: SwitchPreferenceCompat
    private lateinit var testLedsButton: Preference
    private lateinit var archiveAllPgnButton: Preference

    private val mainViewModel: MainViewModel by activityViewModels()

    private val uiActionCallbacks: UiActionCallbacks
        get() {
            return requireContext() as? UiActionCallbacks
                ?: error("All activities must implement uiActionCallbacks")
        }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.root_preferences, rootKey)
        signInButton = findPreference("lichess_login")!!
        learningModeSwitch = findPreference("learning_mode")!!
        testLedsButton = findPreference("test_connection")!!
        archiveAllPgnButton = findPreference("archive_all_pgn")!!

        learningModeSwitch.setOnPreferenceChangeListener { _, newValue ->
            val isChecked = newValue as Boolean
            mainViewModel.writeSettings(
                ChessBoardSettings(learningMode = isChecked)
            )
            true
        }

        testLedsButton.setOnPreferenceClickListener {
            mainViewModel.blinkLeds()
            true
        }

        archiveAllPgnButton.setOnPreferenceClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.confirm_archive_all_pgn_title))
                .setMessage(getString(R.string.confirm_archive_all_pgn_description))
                .setCancelable(true)
                .setPositiveButton(getString(R.string.confirm_archive_all_pgn_yes)) { _, _ ->
                    mainViewModel.archiveAllPgn()
                }
                .setNegativeButton(getString(R.string.confirm_archive_all_pgn_no)) { _, _ -> }
                .show()
            true
        }

        signInButton.setOnPreferenceClickListener {
            when (mainViewModel.uiOAuthState.value) {
                null -> {
                }
                is WebManager.UiOAuthState.NotAuthorized -> {
                    uiActionCallbacks.signIn()
                }
                is WebManager.UiOAuthState.AuthorizationLoading -> {
                    Log.w(MainActivity.TAG, "Sign in button pressed while in the middle of signing in. ")
                }
                is WebManager.UiOAuthState.Authorized -> {
                    uiActionCallbacks.signOut()
                }
            }
            true
        }

        if (!resources.getBoolean(R.bool.signInButtonInOptionsMenu)){
            preferenceScreen.removePreference(signInButton)
        }

    }

    override fun onResume() {
        super.onResume()
        mainViewModel.actionBarState.value = SETTINGS_ACTION_BAR
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        mainViewModel.chessBoardSettings.observe(viewLifecycleOwner) { chessBoardSettings ->
            learningModeSwitch.isChecked = chessBoardSettings?.learningMode ?: return@observe
        }

        mainViewModel.numGamesToUpload.observe(viewLifecycleOwner) { numGames ->
            when (numGames) {
                0, null -> {
                    archiveAllPgnButton.isEnabled = false
                    archiveAllPgnButton.summary =
                        getString(R.string.archive_all_pgn_nothing_to_archive)
                }
                else -> {
                    archiveAllPgnButton.isEnabled = true
                    archiveAllPgnButton.summary =
                        resources.getQuantityString(R.plurals.archive_all_pgn_num_files, numGames, numGames)
                }
            }
        }

        mainViewModel.uiOAuthState.observe(viewLifecycleOwner) { authState ->
            when (authState) {
                null -> {
                }
                is WebManager.UiOAuthState.NotAuthorized, is WebManager.UiOAuthState.AuthorizationLoading -> {
                    signInButton.title = getString(R.string.not_signed_in_button_primary)
                    signInButton.summary = getString(R.string.not_signed_in_button_secondary)

                }
                is WebManager.UiOAuthState.Authorized -> {
                    signInButton.title = authState.userInfo.username
                    signInButton.summary = getString(R.string.signed_in_button_secondary)
                }
            }
        }


    }
}