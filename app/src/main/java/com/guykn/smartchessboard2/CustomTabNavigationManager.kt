package com.guykn.smartchessboard2

import android.os.Bundle
import androidx.browser.customtabs.CustomTabsCallback
import dagger.hilt.android.scopes.ServiceScoped
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@ServiceScoped
class CustomTabNavigationManager @Inject constructor(): CustomTabsCallback() {
    private val _isTabShown : MutableStateFlow<Boolean> = MutableStateFlow(false)
    val isTabShown: StateFlow<Boolean> = _isTabShown

    override fun onNavigationEvent(navigationEvent: Int, extras: Bundle?) {
        when(navigationEvent){
            TAB_HIDDEN->{
                _isTabShown.value = false
            }
            TAB_SHOWN ->{
                _isTabShown.value = true
            }
        }
    }
}