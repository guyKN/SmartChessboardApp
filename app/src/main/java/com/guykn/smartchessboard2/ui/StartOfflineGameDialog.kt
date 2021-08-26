package com.guykn.smartchessboard2.ui

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.Dialog
import android.os.Bundle
import android.widget.NumberPicker
import androidx.fragment.app.DialogFragment
import com.guykn.smartchessboard2.R
import com.guykn.smartchessboard2.bluetooth.GameStartRequest

class StartOfflineGameDialog(
    val gameStartRequestCallback: (GameStartRequest) -> Unit
) : DialogFragment() {

    companion object {
        private const val WHITE = 0
        private const val BLACK = 1
    }

    lateinit var difficultySelector: NumberPicker
    lateinit var colorSelector: NumberPicker

    @SuppressLint("InflateParams")
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val inflater = requireActivity().layoutInflater
        val view = inflater.inflate(R.layout.dialog_start_game, null)

        difficultySelector = view.findViewById<NumberPicker>(R.id.ai_difficulty_selector).apply{
            minValue = 1
            maxValue = 20
            value = 10
            wrapSelectorWheel = false
        }
        colorSelector = view.findViewById<NumberPicker>(R.id.ai_color_selector).apply {
            minValue = 0
            maxValue = 1
            value = 0
            displayedValues = context.resources.getStringArray(R.array.player_color_names)
            wrapSelectorWheel = true
        }


        return AlertDialog.Builder(requireActivity())
            .setView(view)
            .setTitle(R.string.title_play_against_computer)
            .setPositiveButton(R.string.start_game) { _, _ ->
                gameStartRequestCallback(currentGameStartRequest())
            }
            .create()
    }

    private fun currentGameStartRequest(): GameStartRequest {
        return GameStartRequest(
            enableEngine = true,
            engineLevel = difficultySelector.value,
            // Since the dialog is asking the user for what color they want to play as, but the data
            // sent to the server is the color that the AI plays as, the colors need to be flipped.
            engineColor = if (colorSelector.value == WHITE) "black" else "white"
        )
    }

}