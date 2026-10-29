package com.ssmediaos.smsgateway

import android.app.Application
import android.content.Context
import android.util.Log

class GatewayApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        try {
            AppContextHolder.initialize(this)
            Log.i(TAG, "SS MediaOS SMS Gateway initialized.")
        } catch (e: Exception) {
            Log.e(TAG, "Gateway application initialization failed.", e)
            throw e
        }
    }

    companion object {
        private const val TAG = "GatewayApplication"
    }
}

/**
 * Provides application context to components that need it.
 *
 * Application context is used to avoid retaining Activity or Service
 * references longer than necessary.
 */
object AppContextHolder {

    @Volatile
    private var appContext: Context? = null

    val context: Context
        get() = appContext
            ?: throw IllegalStateException(
                "Application context has not been initialized."
            )

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    fun isInitialized(): Boolean {
        return appContext != null
    }
}
