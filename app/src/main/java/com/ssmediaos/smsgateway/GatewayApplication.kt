package com.ssmediaos.smsgateway

import android.app.Application
import android.content.Context

/**
 * Application entry point for SS MediaOS SMS Gateway.
 *
 * ApiClient uses AppContextHolder.context from its background executor.
 * Initialize the holder before any Activity or Service starts so API calls
 * always use the Application context and never retain an Activity instance.
 */
class GatewayApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        AppContextHolder.initialize(this)
    }
}

/**
 * Process-wide application context provider.
 * Keep this in the same package as ApiClient and GatewayApplication.
 */
object AppContextHolder {

    @Volatile
    private var applicationContext: Context? = null

    /** Returns the initialized Application context. */
    val context: Context
        get() = applicationContext
            ?: throw IllegalStateException(
                "APP_CONTEXT_NOT_INITIALIZED: GatewayApplication has not initialized AppContextHolder."
            )

    /** Called once from GatewayApplication.onCreate(). */
    fun initialize(context: Context) {
        applicationContext = context.applicationContext
    }
}
