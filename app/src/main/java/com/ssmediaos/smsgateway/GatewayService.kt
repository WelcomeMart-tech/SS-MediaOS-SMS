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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log

class GatewayService : Service() {

    @Volatile
    private var serviceRunning = false

    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var requestInProgress = false

    private var currentStartId = 0

    private var lastStatus = "Gateway is starting"

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!serviceRunning) return

            if (!requestInProgress) {
                pollServer()
            }

            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

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
                currentStartId = startId
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

        val serverUrl = SecureConfig.getServerUrl(this)
        val deviceCode = SecureConfig.getDeviceCode(this)
        val activationToken = SecureConfig.getActivationToken(this)
        val deviceToken = SecureConfig.getDeviceToken(this)

        if (
            serverUrl.isNullOrBlank() ||
            deviceCode.isNullOrBlank() ||
            activationToken.isNullOrBlank()
        ) {
            Log.w(TAG, "Gateway start blocked: activation settings missing")
            stopSelf(startId)
            return
        }

        if (deviceToken.isNullOrBlank()) {
            Log.w(TAG, "Gateway start blocked: device is not activated")
            stopSelf(startId)
            return
        }

        if (
            checkSelfPermission(Manifest.permission.SEND_SMS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "Gateway start blocked: SEND_SMS permission missing")
            stopSelf(startId)
            return
        }

        try {
            val notification = createNotification("Gateway is starting")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }

            serviceRunning = true
            requestInProgress = false

            updateStatus("Activated — connecting to server")

            Log.i(TAG, "Gateway service started")

            handler.removeCallbacks(pollRunnable)
            handler.post(pollRunnable)

        } catch (exception: Exception) {
            Log.e(TAG, "Unable to start gateway service", exception)

            serviceRunning = false
            requestInProgress = false

            try {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } catch (stopException: Exception) {
                Log.w(TAG, "Could not remove foreground notification", stopException)
            }

            stopSelf(startId)
        }
    }

    /**
     * Requests one queued job from device_poll.php.
     *
     * ApiClient.pollGatewayJob() must send X-Gateway-Token.
     */
    private fun pollServer() {
        if (!serviceRunning || requestInProgress) return

        requestInProgress = true

        ApiClient.pollGatewayJob { result ->
            if (!serviceRunning) {
                requestInProgress = false
                return@pollGatewayJob
            }

            result.fold(
                onSuccess = { response ->
                    val job = response.optJSONObject("job")

                    if (job == null || job == org.json.JSONObject.NULL) {
                        requestInProgress = false
                        updateStatus("Connected — waiting for SMS jobs")
                        return@fold
                    }

                    processJob(job)
                },
                onFailure = { exception ->
                    requestInProgress = false

                    Log.e(
                        TAG,
                        "Gateway polling failed: ${exception.message}",
                        exception
                    )

                    updateStatus("Server connection failed — retrying")
                }
            )
        }
    }

    /**
     * Validates and submits the SMS job to Android's SMS service.
     */
    private fun processJob(job: org.json.JSONObject) {
        val jobId = job.optLong("id", 0L)
        val requestId = job.optString("request_id").trim()
        val phoneNumber = job.optString("to").trim()
        val message = job.optString("message")

        if (
            jobId <= 0L ||
            requestId.isBlank() ||
            phoneNumber.isBlank() ||
            message.isBlank()
        ) {
            Log.e(TAG, "Received invalid SMS job")

            if (jobId > 0L && requestId.isNotBlank()) {
                reportResult(
                    jobId = jobId,
                    requestId = requestId,
                    success = false,
                    errorCode = "INVALID_JOB"
                )
            } else {
                requestInProgress = false
                updateStatus("Invalid job received — check server")
            }

            return
        }

        updateStatus("Processing SMS job")

        Log.i(TAG, "Processing SMS job ID: $jobId")

        when (
            val sendResult = SmsHelper.sendSms(
                context = this,
                phoneNumber = phoneNumber,
                message = message
            )
        ) {
            is SmsHelper.SendResult.Success -> {
                /*
                 * Success here means Android accepted the SMS submission.
                 * It does not prove final delivery to the recipient.
                 */
                reportResult(
                    jobId = jobId,
                    requestId = requestId,
                    success = true
                )
            }

            is SmsHelper.SendResult.Failure -> {
                Log.e(
                    TAG,
                    "SMS submission failed: ${sendResult.reason}"
                )

                reportResult(
                    jobId = jobId,
                    requestId = requestId,
                    success = false,
                    errorCode = sendResult.reason.take(200)
                )
            }
        }
    }

    /**
     * Reports the SMS submission result through device_result.php.
     */
    private fun reportResult(
        jobId: Long,
        requestId: String,
        success: Boolean,
        errorCode: String? = null
    ) {
        ApiClient.submitGatewayResult(
            jobId = jobId,
            requestId = requestId,
            success = success,
            errorCode = errorCode
        ) { result ->
            if (!serviceRunning) {
                requestInProgress = false
                return@submitGatewayResult
            }

            result.fold(
                onSuccess = { response ->
                    Log.i(
                        TAG,
                        "Server accepted result for job $jobId"
                    )

                    requestInProgress = false

                    if (success) {
                        updateStatus("SMS submitted — waiting for next job")
                    } else {
                        updateStatus("SMS failed — waiting for next job")
                    }
                },
                onFailure = { exception ->
                    /*
                     * Do not immediately resend the SMS here.
                     * The server may already have processed the result,
                     * and retrying the SMS could send a duplicate.
                     */
                    Log.e(
                        TAG,
                        "Could not report result for job $jobId",
                        exception
                    )

                    requestInProgress = false
                    updateStatus("Result report failed — check server")
                }
            )
        }
    }

    private fun updateStatus(message: String) {
        lastStatus = message

        if (!serviceRunning) return

        try {
            updateNotification(message)
        } catch (exception: Exception) {
            Log.w(TAG, "Unable to update gateway notification", exception)
        }
    }

    private fun stopGateway() {
        serviceRunning = false
        requestInProgress = false

        handler.removeCallbacks(pollRunnable)

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
        requestInProgress = false

        handler.removeCallbacks(pollRunnable)

        Log.i(TAG, "Gateway service destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "GatewayService"
        private const val CHANNEL_ID = "sms_gateway_service"
        private const val NOTIFICATION_ID = 1001

        private const val POLL_INTERVAL_MS = 5000L

        const val ACTION_START =
            "com.ssmediaos.smsgateway.action.START"

        const val ACTION_STOP =
            "com.ssmediaos.smsgateway.action.STOP"
    }
}
