package com.ssmediaos.smsgateway

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log

class GatewayService : Service() {

    @Volatile
    private var serviceRunning = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Log.i(TAG, "Gateway service created")
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Log.i(TAG, "Gateway stop requested")
                stopGateway()
                return START_NOT_STICKY
            }

            else -> {
                startGateway()
            }
        }

        return START_STICKY
    }

    private fun startGateway() {
        if (serviceRunning) {
            Log.i(TAG, "Gateway is already running")
            return
        }

        try {
            val notification = createNotification()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }

            serviceRunning = true

            Log.i(TAG, "Gateway service started")

            updateNotification(
                "Gateway service is running"
            )

            /*
             * API polling and SMS queue processing have not been
             * implemented because the server API contract is not
             * available yet.
             */

        } catch (exception: Exception) {
            Log.e(TAG, "Unable to start gateway service", exception)
            serviceRunning = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun stopGateway() {
        serviceRunning = false

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()

        Log.i(TAG, "Gateway service stopped")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "SMS Gateway Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description =
                    "Shows the status of the SS MediaOS SMS Gateway"
                setShowBadge(false)
            }

            val manager =
                getSystemService(NotificationManager::class.java)

            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setContentTitle("SS MediaOS SMS Gateway")
            .setContentText("Gateway service is running")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    private fun updateNotification(message: String) {
        val notification = createNotification()
            .let { original ->
                val builder =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        Notification.Builder(this, CHANNEL_ID)
                    } else {
                        @Suppress("DEPRECATION")
                        Notification.Builder(this)
                    }

                builder
                    .setContentTitle("SS MediaOS SMS Gateway")
                    .setContentText(message)
                    .setSmallIcon(android.R.drawable.stat_notify_sync)
                    .setOngoing(true)
                    .setCategory(Notification.CATEGORY_SERVICE)
                    .build()
            }

        val manager =
            getSystemService(NotificationManager::class.java)

        manager.notify(NOTIFICATION_ID, notification)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.i(TAG, "App task removed; service remains configured")
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        serviceRunning = false
        Log.i(TAG, "Gateway service destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "GatewayService"
        private const val CHANNEL_ID = "sms_gateway_service"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START =
            "com.ssmediaos.smsgateway.action.START"

        const val ACTION_STOP =
            "com.ssmediaos.smsgateway.action.STOP"
    }
}
