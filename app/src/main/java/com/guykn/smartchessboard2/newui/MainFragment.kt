package com.guykn.smartchessboard2.newui

import android.os.Bundle
import androidx.fragment.app.activityViewModels
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.guykn.smartchessboard2.R
import com.guykn.smartchessboard2.newui.viewmodels.MainViewModel

class MainFragment : PreferenceFragmentCompat() {

    val mainViewModel: MainViewModel by activityViewModels()

    private lateinit var signInInfo: Preference
    private lateinit var playAgainstAiButton: Preference
    private lateinit var playOnlineButton: Preference
    private lateinit var startBroadcastButton: Preference
    private lateinit var uploadGamesButton: Preference
    private lateinit var testConnectionButton: Preference
    private lateinit var learningModeSwitch: SwitchPreferenceCompat

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.root_preferences, rootKey)
        signInInfo = findPreference("sign_in_info")!!
        playAgainstAiButton = findPreference("play_against_ai")!!
        playOnlineButton = findPreference("play_online")!!
        startBroadcastButton = findPreference("start_broadcast")!!
        uploadGamesButton = findPreference("upload_games")!!
        testConnectionButton = findPreference("test_connection")!!
        learningModeSwitch = findPreference("learning_mode")!!
    }
}