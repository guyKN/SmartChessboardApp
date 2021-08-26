package com.guykn.smartchessboard2.ui

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import androidx.fragment.app.DialogFragment
import com.addisonelliott.segmentedbutton.SegmentedButtonGroup
import com.guykn.smartchessboard2.R
import com.guykn.smartchessboard2.bluetooth.GameStartRequest
import com.shawnlin.numberpicker.NumberPicker

class StartOfflineGameDialog : DialogFragment() {

    interface Callback {
        fun startOfflineGame(gameStartRequest: GameStartRequest)
    }

    companion object {
        private const val TAG = "MA_StartOfflineGameDialog"

        private const val WHITE = 0
        private const val BLACK = 1

        private const val SHARED_PREFERENCES_KEY = "aaaa"
        private const val KEY_AI_DIFFICULTY = "difficulty"
        private const val KEY_AI_COLOR = "ai_color"
    }

    private lateinit var difficultySelector: NumberPicker
    private lateinit var colorSelector: SegmentedButtonGroup

    private lateinit var sharedPreferences: SharedPreferences

    lateinit var callback: Callback

    override fun onAttach(context: Context) {
        super.onAttach(context)
        sharedPreferences =
            context.getSharedPreferences(SHARED_PREFERENCES_KEY, Context.MODE_PRIVATE)
        if (context is Callback) {
            callback = context
        } else {
            error("Context for StartOfflineGameDialog must implement StartOfflineGameDialog.Callback")
        }
    }

    @SuppressLint("InflateParams")
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val inflater = requireActivity().layoutInflater
        val view = inflater.inflate(R.layout.dialog_start_game, null)

        difficultySelector = view.findViewById(R.id.ai_difficulty_selector)!!
        difficultySelector.value = sharedPreferences.getInt(KEY_AI_DIFFICULTY, 4)

        difficultySelector.setOnValueChangedListener { _, _, aiDifficulty1 ->
            Log.d(TAG, "aiDifficulty changed: $aiDifficulty1")
            sharedPreferences.edit()
                .putInt(KEY_AI_DIFFICULTY, aiDifficulty1)
                .apply()
        }



        colorSelector = view.findViewById(R.id.buttonGroup_select_color)
        colorSelector.setPosition(
            sharedPreferences.getInt(KEY_AI_COLOR, 0),
            false
        )

        colorSelector.setOnPositionChangedListener { aiColor1 ->
            Log.d(TAG, "colorSelector changed: $aiColor1")
            sharedPreferences.edit()
                .putInt(KEY_AI_COLOR, aiColor1)
                .apply()
        }

        val alertDialog = AlertDialog.Builder(requireActivity())
            .setView(view)
            .setTitle(R.string.title_play_against_computer)
            .setPositiveButton(R.string.start_game) { _, _ ->
                callback.startOfflineGame(currentGameStartRequest())
            }
            .create()

        return alertDialog
    }

    private fun currentGameStartRequest(): GameStartRequest {
        return GameStartRequest(
            enableEngine = true,
            engineLevel = adjustEngineDifficulty(difficultySelector.value),
            // Since the dialog is asking the user for what color they want to play as, but the data
            // sent to the server is the color that the AI plays as, the colors need to be flipped.
            engineColor = if (colorSelector.position == WHITE) "black" else "white"
        )
    }

    /**
     * The ui allows picking a number from 1 to 8 as a difficulty, but the actual difficulty on the chessboard is from 1 to 20, so we must convert it.
     */
    private fun adjustEngineDifficulty(uiEngineDifficulty: Int): Int {
        return when (uiEngineDifficulty) {
            1 -> 1
            2 -> 4
            3 -> 7
            4 -> 10
            5 -> 13
            6 -> 16
            7 -> 18
            8 -> 20
            else -> error("uiEngineDifficulty must be from 1 to 8")
        }
    }


}