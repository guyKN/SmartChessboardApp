package com.guykn.smartchessboard2

import android.content.Context
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.util.Log
import androidx.annotation.IdRes
import androidx.annotation.RawRes
import com.guykn.smartchessboard2.bluetooth.ChessBoardModel
import com.guykn.smartchessboard2.network.lichess.LichessApi
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.scopes.ServiceScoped
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import javax.inject.Inject

@ServiceScoped
class NotificationPlayer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val chessBoardModel: ChessBoardModel,
    private val repository: Repository,
    private val coroutineScope: CoroutineScope
) {

    companion object{
        private const val TAG = "MA_MoveNotifier"
        private const val PLAY_AUDIO = false
    }

    private var prevGameState: LichessApi.LichessGameState? = null

    private fun getRingtoneFromResource(@RawRes resourceId: Int): Ringtone {
        return RingtoneManager.getRingtone(
            context,
            Uri.parse("android.resource://${context.packageName}/$resourceId")
        )!!.apply {
            audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        }
    }

    private val moveSound = getRingtoneFromResource(R.raw.sound_move)
//    private val captureSound = getRingtoneFromResource(R.raw.sound_capture)
    private val gameEndSound = getRingtoneFromResource(R.raw.sound_game_end)
    private val moveGameEndSound = getRingtoneFromResource(R.raw.sound_move_game_end)

    init {
        coroutineScope.launch {
            repository.lichessGameState.collect { currentGameState ->
                if (!PLAY_AUDIO){
                    Log.d(TAG, "not playing audio. ")
                    return@collect
                }
                ifLet(currentGameState, prevGameState){ (currentGameState, prevGameState)->
                    val isNewMove = currentGameState.numMoves > prevGameState.numMoves
                    if (currentGameState.isGameOver){
                        if (isNewMove){
                            Log.d(TAG, "playing audio: moveGameEndSound. ")
                            moveGameEndSound.play()
                        }else{
                            Log.d(TAG, "playing audio: gameEndSound. ")
                            gameEndSound.play()
                        }
                    }else{
                        if (isNewMove){
                            Log.d(TAG, "playing audio: moveSound. ")
                            moveSound.play()
                        }else{
                            Log.w(TAG, "The lichess game state has been changed, but the amount of moves did not and the game is not over. ")
                        }
                    }
                }

                prevGameState = currentGameState

            }
        }
    }
}