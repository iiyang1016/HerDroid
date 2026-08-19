package com.herdroid.app.core.runtime

import android.content.Context
import android.content.Intent
import android.os.Build

object BotModeController {
    private const val PREFS = "herdroid_bot_mode"
    private const val KEY_ENABLED = "enabled"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()

        if (enabled) start(context) else stop(context)
    }

    fun start(context: Context) {
        val intent = Intent(context, HermesBotService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun stop(context: Context) {
        context.stopService(Intent(context, HermesBotService::class.java))
    }

    fun refresh(context: Context) {
        if (!isEnabled(context)) return
        val intent = Intent(context, HermesBotService::class.java)
            .setAction(HermesBotService.ACTION_REFRESH)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }
}
