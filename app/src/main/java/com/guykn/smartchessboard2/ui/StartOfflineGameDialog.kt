package com.guykn.smartchessboard2.ui

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.Window
import android.widget.Button
import android.widget.NumberPicker
import com.guykn.smartchessboard2.R
import com.guykn.smartchessboard2.bluetooth.GameStartRequest

class StartOfflineGameDialog(
    context: Context,
    val gameStartRequestCallback: (GameStartRequest) -> Unit
) : Dialog(context) {

    companion object {
        private const val WHITE = 0
        private const val BLACK = 1
    }

    private lateinit var difficultySelector: NumberPicker
    private lateinit var colorSelector: NumberPicker
    private lateinit var startButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(R.layout.dialog_start_game)
        difficultySelector = findViewById(R.id.ai_difficulty_selector)
        colorSelector = findViewById(R.id.ai_color_selector)
        startButton = findViewById(R.id.start_game)

        difficultySelector.minValue = 1
        difficultySelector.maxValue = 20
        difficultySelector.value = 10
        difficultySelector.wrapSelectorWheel = false

        colorSelector.minValue = 0
        colorSelector.maxValue = 1
        colorSelector.value = 0
        colorSelector.displayedValues = context.resources.getStringArray(R.array.player_color_names)
        colorSelector.wrapSelectorWheel = true

        startButton.setOnClickListener {
            dismiss()
            gameStartRequestCallback(currentGameStartRequest())
        }
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