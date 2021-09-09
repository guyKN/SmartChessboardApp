package com.guykn.smartchessboard2.network

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.guykn.smartchessboard2.network.lichess.LichessApi
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.scopes.ServiceScoped
import javax.inject.Inject

@ServiceScoped
class SavedBroadcastTournament @Inject constructor(
    @ApplicationContext context: Context,
    private val gson: Gson
) {
    companion object {
        private const val KEY_TOURNAMENT = "tournament"
        private const val KEY_TOURNAMENT_INFO = "tournament_info"
    }

    private val sharedPreferences: SharedPreferences =
        context.getSharedPreferences(KEY_TOURNAMENT, Context.MODE_PRIVATE)

    var broadcastTournament: LichessApi.BroadcastTournament? = loadTournament()
        set(value) {
            field = value
            sharedPreferences.edit()
                .putString(KEY_TOURNAMENT_INFO, value?.let { gson.toJson(it) })
                .apply()
        }

    private fun loadTournament(): LichessApi.BroadcastTournament? {
        return sharedPreferences.getString(KEY_TOURNAMENT_INFO, null)?.let {
            gson.fromJson(it, LichessApi.BroadcastTournament::class.java)
        }
    }
}