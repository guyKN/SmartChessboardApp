package com.guykn.smartchessboard2.ui

import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import androidx.fragment.app.activityViewModels
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.guykn.smartchessboard2.R
import com.guykn.smartchessboard2.bluetooth.ChessBoardSettings
import com.guykn.smartchessboard2.ui.MainViewModel.ActionBarState.*

class ExtraOptionsFragment : PreferenceFragmentCompat() {

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

        testLedsButton.setOnPreferenceClickListener{
            mainViewModel.blinkLeds()
            true
        }

        archiveAllPgnButton.setOnPreferenceClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("Are you sure?")
                .setMessage("All saved games that have not been uploaded to Lichess will be deleted. Games that were already uploaded to Lichess will stay there. ")
                .setCancelable(true)
                .setPositiveButton("OK") { _, _ ->
                    mainViewModel.archiveAllPgn()
                }
                .setNegativeButton("Cancel"){_, _-> }
                .show()
            true
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
    }
}