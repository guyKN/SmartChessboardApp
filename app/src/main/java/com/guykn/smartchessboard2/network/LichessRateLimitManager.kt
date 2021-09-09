package com.guykn.smartchessboard2.network

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.scopes.ServiceScoped
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

@ServiceScoped
class LichessRateLimitManager @Inject constructor(
    @ApplicationContext context: Context,
    private val gson: Gson,
    val coroutineScope: CoroutineScope
) {
    companion object {
        private const val KEY = "LichessRateLimitManager"
        private const val KEY_NUM_FILES_UPLOADED = "num_files_uploaded"
        private const val KEY_TIME_RESUME_UPLOADING_GAMES = "resume_upload_time"

        // lichess has a maximum number of games that can be uploaded every minute.
        private const val MAX_GAME_UPLOAD_PER_MINUTE = 4
        // the API lets you upload a certain number of games per 1 minute, but we wait 70 seconds to be safer.
        private const val DELAY_RESUME_UPLOADING_GAMES: Long = 70 * 1000

    }

    private val sharedPreferences: SharedPreferences =
        context.getSharedPreferences(KEY, Context.MODE_PRIVATE)

    fun mayUploadPgnFile(): Boolean{
        return numFilesAllowedToUpload() > 0
    }

    fun numFilesAllowedToUpload(): Int {
        return MAX_GAME_UPLOAD_PER_MINUTE - numFilesUploadedInLastMinute
    }

    fun onUploadFile() {
        numFilesUploadedInLastMinute++
        timeResumeUploadingGames = System.currentTimeMillis() + DELAY_RESUME_UPLOADING_GAMES
        coroutineScope.launch {
            delay(DELAY_RESUME_UPLOADING_GAMES)
            numFilesUploadedInLastMinute--
        }
    }

    private var numFilesUploadedInLastMinute: Int
        get() = sharedPreferences.getInt(KEY_NUM_FILES_UPLOADED, 0)
        set(value) {
            sharedPreferences.edit()
                .putInt(KEY_NUM_FILES_UPLOADED, value)
                .apply()
        }

    private var timeResumeUploadingGames: Long
        get() = sharedPreferences.getLong(KEY_TIME_RESUME_UPLOADING_GAMES, 0)
        set(value) {
            sharedPreferences.edit()
                .putLong(KEY_TIME_RESUME_UPLOADING_GAMES, value)
                .apply()
        }




    init {
        coroutineScope.launch {
            val prevNumFilesUploadedInLastMinute = numFilesUploadedInLastMinute
            val delayResumeUploading = timeResumeUploadingGames - System.currentTimeMillis()
            Log.d(TAG, "Waiting for $delayResumeUploading miliseconds")
            if (delayResumeUploading > 0){
                delay(delayResumeUploading)
            }
            Log.d(TAG, "Done waiting, may now upload files. ")
            numFilesUploadedInLastMinute -= prevNumFilesUploadedInLastMinute

            // In rare cases of data races, numFilesUploadedInLastMinute may accidentally be negetive, so make sure it's at least 0.
            if(numFilesUploadedInLastMinute < 0){
                numFilesUploadedInLastMinute = 0
            }
        }
    }
}