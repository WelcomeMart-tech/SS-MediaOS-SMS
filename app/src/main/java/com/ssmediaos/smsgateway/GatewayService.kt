package com.ssmediaos.smsgateway

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
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

            ACTION_START, null -> {
                startGateway(startId)
            }

            else -> {
                Log.w(TAG, "Unknown service action")
                stopSelf(startId)
                return START_NOT_STICKY
            }
        }

        return if (serviceRunning) {
            START_STICKY
        } else {
            START_NOT_STICKY
        }
    }

    private fun startGateway(startId: Int) {
        if (serviceRunning) {
            Log.i(TAG, "Gateway is already running")
            return
        }

        // Require saved server and activation details.
        val serverUrl = SecureConfig.getServerUrl(this)
        val deviceCode = SecureConfig.getDeviceCode(this)
        val activationToken = SecureConfig.getActivationToken(this)

        if (
            serverUrl.isNullOrBlank() ||
            deviceCode.isNullOrBlank() ||
            activationToken.isNullOrBlank()
        ) {
            Log.w(TAG, "Gateway start blocked: activation settings are missing")
            stopSelf(startId)
            return
        }

        // A saved Device Token is required for an activated session.
        val deviceToken = SecureConfig.getDeviceToken(this)

        if (deviceToken.isNullOrBlank()) {
            Log.w(TAG, "Gateway start blocked: device is not activated")
            stopSelf(startId)
            return
        }

        // This service is intended to send SMS, so check permission before
        // starting the gateway. It does not read incoming SMS.
        if (
            checkSelfPermission(Manifest.permission.SEND_SMS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "Gateway start blocked: SEND_SMS permission is missing")
            stopSelf(startId)
            return
        }

        try {
            val notification = createNotification(
                "Gateway is starting"
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(
                    NOTIFICATION_ID,
                    notification
                )
            }

            serviceRunning = true

            updateNotification(
                "Activated — waiting for gateway processing"
            )

            Log.i(TAG, "Activated gateway service started")

            /*
             * IMPORTANT:
             * Server polling and SMS queue processing are not implemented
             * in this service yet.
             *
             * Do not report queued SMS as sent or delivered.
             * Implement authenticated API polling and SMS sending separately,
             * with server-side authorization and proper delivery callbacks.
             */

        } catch (exception: Exception) {
            Log.e(TAG, "Unable to start gateway service", exception)
            serviceRunning = false

            try {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } catch (stopException: Exception) {
                Log.w(TAG, "Could not remove foreground notification", stopException)
            }

            stopSelf(startId)
        }
    }

    private fun stopGateway() {
        serviceRunning = false

        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (exception: Exception) {
            Log.w(TAG, "Error while stopping foreground service", exception)
        }

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

    private fun createNotification(message: String): Notification {
        val builder =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(this, CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(this)
            }

        return builder
            .setContentTitle("SS MediaOS SMS Gateway")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    private fun updateNotification(message: String) {
        val manager =
            getSystemService(NotificationManager::class.java)

        manager.notify(
            NOTIFICATION_ID,
            createNotification(message)
        )
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.i(TAG, "App task removed")
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
