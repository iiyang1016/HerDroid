package com.herdroid.app.core.runtime

import android.content.Context
import com.herdroid.app.core.hermes.HermesRuntime
import com.herdroid.app.core.hermes.LocalHermesRuntime

/**
 * Process-wide owner for the local Hermes runtime.
 *
 * The Activity/ViewModel and the foreground Bot Mode service must talk to the
 * same runtime instance so moving the app to the background does not create a
 * second agent or lose the active session state.
 */
object HermesRuntimeHost {
    @Volatile
    private var runtime: HermesRuntime? = null

    fun get(context: Context): HermesRuntime {
        runtime?.let { return it }

        return synchronized(this) {
            runtime ?: LocalHermesRuntime(context.applicationContext.filesDir).also {
                runtime = it
            }
        }
    }
}
