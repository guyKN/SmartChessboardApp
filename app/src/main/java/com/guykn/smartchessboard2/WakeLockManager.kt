package com.guykn.smartchessboard2

import android.content.Context
import android.os.PowerManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

class WakeLockManager @Inject constructor(@ApplicationContext private val context: Context) {
    private val powerManager = context.getSystemService(PowerManager::class.java)
    private val wakeLock = powerManager.newWakeLock(
        PowerManager.PARTIAL_WAKE_LOCK,
        "smartchessboard:BackgroundServiceWakeLock"
    ).apply {
        setReferenceCounted(false)
    }

    fun start(){
        wakeLock.acquire()
    }

    fun destroy(){
        wakeLock.release()
    }

}