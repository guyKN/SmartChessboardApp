package com.guykn.smartchessboard2

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.liveData
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.scopes.ActivityRetainedScoped
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine


@ActivityRetainedScoped
class ServiceConnector @Inject constructor(@ApplicationContext private val context: Context) {

    // TODO: 5/5/2021 See why it takes so long for service to connect?

    companion object {
        const val TAG = "MA_ServiceConnector"
    }

    private inner class MainServiceConnection : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            Log.d(TAG, "Service connected")
            (binder as MainService.MainServiceBinder).service.let {
                mainService = it
                for (callback in serviceConnectedCallbacks) {
                    callback(it.repository)
                }
                serviceConnectedCallbacks.clear()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            mainService = null
            if (!isDestroyed) {
                Log.w(TAG, "Service stopped unexpectedly")
                // todo: does this actually work? See if unexpected service disconnects are possible, and handle them properly.
                startAndBindService()
            }
        }
    }

    private var mainService: MainService? = null
    private val mainServiceConnection = MainServiceConnection()
    private val serviceConnectedCallbacks = arrayListOf<((Repository) -> Unit)>()

    private var isDestroyed = false


    init {
        startAndBindService()
    }

    private fun startAndBindService() {
        // TODO: 5/9/2021 is Starting the service necessary, or is it OK to just bind
        val intent = Intent(context, MainService::class.java)
        context.startForegroundService(intent)
        val success = context.bindService(
            intent,
            mainServiceConnection,
            Context.BIND_AUTO_CREATE or Context.BIND_ABOVE_CLIENT
        )
        if (!success) {
            throw RuntimeException("Failed to connect to service")
        }
    }


    fun destroy() {
        if (isDestroyed) {
            return
        }
        isDestroyed = true
        mainService = null
        val intent = Intent(context, MainService::class.java)
        context.unbindService(mainServiceConnection)
        context.stopService(intent)
    }


    fun callWhenConnected(callback: (Repository) -> Unit) {
        check(!isDestroyed) { "Tried to call callWhenConnected() after serviceConnector was destroyed." }
        mainService?.repository.let {
            if (it != null) {
                callback(it)
            } else {
                serviceConnectedCallbacks.add(callback)
            }
        }
    }

    suspend fun awaitConnected(): Repository = suspendCoroutine { continuation ->
        callWhenConnected { repository ->
            continuation.resume(repository)
        }
    }



    // TODO: 6/28/2021 Check if this causes memory leaks.
    /**
     * Utility function that takes a flow scoped in a repository and provides liveData that
     * has the same value as the return value stateFlowProvider if the service is active, or null if it is not active.
     */
    fun <T> copyLiveData(stateFlowProvider: (Repository) -> Flow<T>): LiveData<T> = liveData {
        val repository = awaitConnected()
        val stateFlow = stateFlowProvider(repository)
        stateFlow.collect {
            emit(it)
        }
    }
}