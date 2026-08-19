package com.herdroid.app.core.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.herdroid.app.MainActivity
import com.herdroid.app.core.hermes.ProviderConfigStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class HermesBotService : Service() {
    private val serviceJob: Job = SupervisorJob()
    private val scope = CoroutineScope(serviceJob + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Starting local Hermes…"))
        refreshRuntime()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                getSharedPreferences(BOT_PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(BOT_ENABLED_KEY, false)
                    .apply()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_REFRESH -> refreshRuntime()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun refreshRuntime() {
        val runtime = HermesRuntimeHost.get(this)
        val provider = ProviderConfigStore(this).load()
        scope.launch {
            runCatching {
                runtime.stop()
                if (provider.isConfigured) {
                    runtime.start(provider)
                    updateNotification("Bot Mode active · ${provider.model}")
                } else {
                    updateNotification("Bot Mode waiting for provider setup")
                }
            }.onFailure {
                updateNotification("Bot Mode error · ${it.message.orEmpty().take(80)}")
            }
        }
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "HerDroid Bot Mode",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Keeps the local Hermes runtime active for messaging and background agent work."
                setShowBadge(false)
            },
        )
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, HermesBotService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentTitle("HerDroid Bot Mode")
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "Stop", stopIntent)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    companion object {
        const val ACTION_STOP = "com.herdroid.app.action.STOP_BOT_MODE"
        const val ACTION_REFRESH = "com.herdroid.app.action.REFRESH_BOT_RUNTIME"
        const val CHANNEL_ID = "herdroid_bot_mode"
        const val NOTIFICATION_ID = 4201
        private const val BOT_PREFS = "herdroid_bot_mode"
        private const val BOT_ENABLED_KEY = "enabled"
    }
}
