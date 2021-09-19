package com.guykn.smartchessboard2.ui

import com.guykn.smartchessboard2.bluetooth.ChessBoardSettings
import com.guykn.smartchessboard2.network.lichess.LichessApi
import com.guykn.smartchessboard2.ui.util.Event
import dagger.hilt.android.scopes.ServiceScoped
import kotlinx.coroutines.flow.MutableStateFlow
import javax.inject.Inject

@ServiceScoped
class EventBus @Inject constructor() {
    sealed class ErrorEvent: Event(){
        class MiscError(val description: String): ErrorEvent() // An error not represented by other error types. These errors should generally never happen.

        class TooManyRequests(val timeForValidRequests: Long): ErrorEvent()
        class SignInError: ErrorEvent()
        class NoLongerAuthorizedError: ErrorEvent()
        class IllegalGameSelected: ErrorEvent() // when a user tries to use the chessboard in a game that isn't rapid or classic time control, or a game with a rules variant.

        class BluetoothIOError: ErrorEvent()
        class InternetIOError: ErrorEvent()
    }

    val errorEvents: MutableStateFlow<ErrorEvent?> = MutableStateFlow(null)

    sealed class SuccessEvent: Event(){
        class SignInSuccess(val userInfo: LichessApi.UserInfo) : SuccessEvent()
        class StartOfflineGameSuccess: SuccessEvent()
        class UploadGamesSuccess: SuccessEvent()
        class UploadGamesPartialSuccess : SuccessEvent() // when some games were uploaded, but because of lichess's limit not all of them were.
        class ChangeSettingsSuccess(val settings: ChessBoardSettings): SuccessEvent()
        class BlinkLedsSuccess: SuccessEvent()
        class ArchiveAllPgnSuccess(val numFiles: Int): SuccessEvent()
        class SignOutSuccess: SuccessEvent()
    }

    val successEvents: MutableStateFlow<SuccessEvent?> = MutableStateFlow(null)
}