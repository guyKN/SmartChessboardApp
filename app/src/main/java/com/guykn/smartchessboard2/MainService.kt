package com.guykn.smartchessboard2

import android.app.*
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.lifecycle.LifecycleService
import com.guykn.smartchessboard2.network.lichess.LichessApi
import com.guykn.smartchessboard2.ui.EventBus
import com.guykn.smartchessboard2.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainService : LifecycleService() {

    companion object {
        private const val TAG = "MA_MainService"
        private const val NOTIFICATION_CHANNEL_ID = "Notifications"
        private const val NOTIFICATION_ID = 1

        private const val GO_BACK_TO_ACTIVITY_ON_FAILURES = false
    }

    @Inject
    lateinit var repository: Repository

    @Inject
    lateinit var notificationPlayer: NotificationPlayer

    @Inject
    lateinit var wakeLockManager: WakeLockManager

    @Inject
    lateinit var coroutineScope: CoroutineScope

    inner class MainServiceBinder : Binder() {
        val service: MainService
            get() = this@MainService
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return MainServiceBinder()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    override fun onCreate() {
        Log.d(TAG, "onCreate() called")
        super.onCreate()
        createForegroundNotification()
        wakeLockManager.start()

        // When a new game in a broadcast starts while the user is viewing another broadcast,
        // take the user back to the mainActivity so they can be sent to the new broadcast.
        coroutineScope.launch {
            repository.broadcastGame.combinePairs(repository.customTabNavigationManager.isTabShown)
                .collect { (broadcastEvent, isTabShown) ->
                if (!broadcastEvent.recieved && broadcastEvent.value != null && isTabShown) {
                    goBackToActivity()
                }
            }
        }

        // if the user selects an invalid game type or the lichess api is overwhelmed, bring him back to the activity where he'll see a proper error message.
        if (GO_BACK_TO_ACTIVITY_ON_FAILURES) {
            coroutineScope.launch {
                repository.eventBus.errorEvents.collect { errorEvent ->
                    when (errorEvent) {
                        is EventBus.ErrorEvent.IllegalGameSelected, is EventBus.ErrorEvent.TooManyRequests -> {
                            if (repository.customTabNavigationManager.isTabShown.value) {
                                goBackToActivity()
                            }
                        }
                        else -> {
                        }
                    }
                }
            }
        }
    }

    private fun goBackToActivity() {
        val intent = Intent(this@MainService, MainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    private fun createForegroundNotification() {
        createNotificationChannel()

        val startActivityIntent = Intent(this, MainActivity::class.java)
        val pendingIntent =
            PendingIntent.getActivity(this, 0, startActivityIntent, PendingIntent.FLAG_IMMUTABLE)
        val notification: Notification = Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.forground_notification_title))
            .setSmallIcon(R.drawable.ic_notification_icon)
            .setContentIntent(pendingIntent)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        channel.setShowBadge(false)
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy call in main service")
        repository.destroy()
        wakeLockManager.destroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.d(TAG, "onTaskRemoved called on MainService")
        stopForeground(true)
        stopSelf()
    }
}