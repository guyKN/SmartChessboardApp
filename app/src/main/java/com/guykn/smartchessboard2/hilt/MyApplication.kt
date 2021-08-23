package com.guykn.smartchessboard2.hilt

import android.app.Application
import com.melegy.redscreenofdeath.RedScreenOfDeath
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class MyApplication:Application(){
    override fun onCreate() {
        super.onCreate()
        RedScreenOfDeath.init(this)
    }
}