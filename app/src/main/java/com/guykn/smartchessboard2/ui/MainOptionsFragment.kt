package com.guykn.smartchessboard2.ui

import android.os.Bundle
import android.view.View
import android.widget.Button
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.guykn.smartchessboard2.R

class MainOptionsFragment: Fragment(R.layout.fragment_main_options) {
    private lateinit var startOfflineGameButton: Button
    private lateinit var startOnlineGameButton: Button
    private lateinit var startBroadcastButton: Button
    private lateinit var viewSavedGamesButton: Button

    private val mainViewModel: MainViewModel by activityViewModels()

    private val uiActionCallbacks: UiActionCallbacks
        get() {
            return requireContext() as? UiActionCallbacks ?: error("All activities must implement uiActionCallbacks")
        }


    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        startOfflineGameButton = view.findViewById(R.id.start_offline_game)
        startOnlineGameButton = view.findViewById(R.id.start_online_game)
        startBroadcastButton = view.findViewById(R.id.start_broadcast)
        viewSavedGamesButton = view.findViewById(R.id.view_saved_games)

        startOfflineGameButton.setOnClickListener {
            uiActionCallbacks.startOfflineGame()
        }

        startOnlineGameButton.setOnClickListener {
            uiActionCallbacks.startOnlineGame()
        }

        startBroadcastButton.setOnClickListener {
            uiActionCallbacks.startBroadcast()
        }

        viewSavedGamesButton.setOnClickListener {
            uiActionCallbacks.viewSavedGames()
        }
    }

    override fun onResume() {
        super.onResume()
        mainViewModel.actionBarState.value = MainViewModel.ActionBarState.NORMAL_ACTION_BAR
    }
}
