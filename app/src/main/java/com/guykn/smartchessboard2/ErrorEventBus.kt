package com.guykn.smartchessboard2

import android.util.EventLog
import com.guykn.smartchessboard2.ui.util.Event
import dagger.hilt.android.scopes.ServiceScoped
import kotlinx.coroutines.flow.MutableStateFlow
import javax.inject.Inject

@ServiceScoped
class ErrorEventBus @Inject constructor() {
    sealed class ErrorEvent: Event(){
        class SignInError: ErrorEvent()
        class NoLongerAuthorizedError: ErrorEvent()
        class WriteSettingsError: ErrorEvent()
        class StartOfflineGameError: ErrorEvent()
    }

    val errorEvents: MutableStateFlow<ErrorEvent?> = MutableStateFlow(null)
}