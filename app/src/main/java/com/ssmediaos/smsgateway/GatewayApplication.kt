package com.ssmediaos.smsgateway

import android.app.Application
import android.content.Context

class GatewayApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        AppContextHolder.initialize(this)
    }
}

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
}
