package com.ssmediaos.smsgateway

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

class GatewayService : Service() {

    @Volatile
    private var serviceRunning = false

    @Volatile
    private var requestInProgress = false

    @Volatile
    private var resultReporting = false

    private val handler = Handler(Looper.getMainLooper())

    private var currentStartId = 0
    private var receiverRegistered = false

    private val statusPrefsName = "ss_mediaos_gateway_status"

    private data class SmsJobState(
        val requestId: String,
        @Volatile var totalParts: Int = 0,
        val results: MutableMap<Int, Boolean> = mutableMapOf(),
        @Volatile var errorCode: String? = null,
        @Volatile var completed: Boolean = false
    )

    private val smsJobs =
        ConcurrentHashMap<Long, SmsJobState>()

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!serviceRunning) return

            if (!requestInProgress) {
                pollServer()
            }

            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    private val smsResultReceiver = object : BroadcastReceiver() {

        override fun onReceive(context: Context, intent: Intent) {

            if (
                intent.action !=
                SmsSentReceiver.ACTION_SMS_PART_RESULT
            ) {
                return
            }

            val jobId = intent.getLongExtra(
                SmsSentReceiver.EXTRA_JOB_ID,
                -1L
            )

            val requestId = intent.getStringExtra(
                SmsSentReceiver.EXTRA_GATEWAY_REQUEST_ID
            ).orEmpty()

            val partIndex = intent.getIntExtra(
                SmsSentReceiver.EXTRA_PART_INDEX,
                -1
            )

            val totalParts = intent.getIntExtra(
                SmsSentReceiver.EXTRA_TOTAL_PARTS,
                1
            )

            val success = intent.getBooleanExtra(
                SmsSentReceiver.EXTRA_SUCCESS,
                false
            )

            val callbackResult = intent.getStringExtra(
                SmsSentReceiver.EXTRA_RESULT
            ).orEmpty()

            val state = smsJobs[jobId]

            if (state == null) {
                Log.w(TAG, "Ignoring callback for unknown job: $jobId")
                return
            }

            var finalSuccess: Boolean? = null
            var finalError: String? = null

            synchronized(state) {

                if (state.completed) {
                    Log.w(TAG, "Ignoring callback for completed job $jobId")
                    return
                }

                if (state.requestId != requestId) {
                    Log.w(TAG, "Request ID mismatch for job $jobId")
                    return
                }

                if (totalParts < 1 || partIndex !in 0 until totalParts) {
                    Log.w(TAG, "Invalid multipart callback for job $jobId")
                    return
                }

                if (
                    state.totalParts != 0 &&
                    state.totalParts != totalParts
                ) {
                    Log.w(TAG, "Multipart count mismatch for job $jobId")
                    return
                }

                state.totalParts = totalParts

                if (state.results.containsKey(partIndex)) {
                    Log.w(
                        TAG,
                        "Duplicate callback ignored: job=$jobId part=$partIndex"
                    )
                    return
                }

                state.results[partIndex] = success

                if (!success && state.errorCode == null) {
                    state.errorCode = callbackResult
                        .replace(Regex("[^A-Za-z0-9_.-]"), "_")
                        .take(100)
                        .ifBlank { "SMS_SEND_FAILED" }
                }

                Log.i(
                    TAG,
                    "SMS part result: job=$jobId, " +
                        "part=${partIndex + 1}/$totalParts, " +
                        "success=$success"
                )

                if (state.results.size == state.totalParts) {

                    state.completed = true

                    finalSuccess =
                        state.results.values.all { it }

                    finalError = if (finalSuccess == true) {
                        null
                    } else {
                        state.errorCode ?: "SMS_SEND_FAILED"
                    }

                    smsJobs.remove(jobId, state)
                }
            }

            if (finalSuccess != null) {
                reportResult(
                    jobId = jobId,
                    requestId = requestId,
                    success = finalSuccess == true,
                    errorCode = finalError
                )
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        createNotificationChannel()
        registerSmsResultReceiver()

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

    private fun registerSmsResultReceiver() {
        if (receiverRegistered) return

        val filter = IntentFilter(
            SmsSentReceiver.ACTION_SMS_PART_RESULT
        )

        try {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(
                    smsResultReceiver,
                    filter,
                    Context.RECEIVER_NOT_EXPORTED
                )
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(smsResultReceiver, filter)
            }

            receiverRegistered = true

        } catch (e: Exception) {
            Log.e(TAG, "Unable to register SMS result receiver", e)
        }
    }

    private fun startGateway(startId: Int) {

        if (serviceRunning) {
            Log.i(TAG, "Gateway is already running")
            return
        }

        val serverUrl = SecureConfig.getServerUrl(this)
        val deviceCode = SecureConfig.getDeviceCode(this)
        val companyApiKey = SecureConfig.getCompanyApiKey(this)
        val deviceToken = SecureConfig.getDeviceToken(this)

        if (
            serverUrl.isNullOrBlank() ||
            deviceCode.isNullOrBlank() ||
            companyApiKey.isNullOrBlank()
        ) {
            Log.w(TAG, "Gateway start blocked: required settings missing")

            saveServiceRunning(false)
            setServerState(STATE_FAILED)

            stopSelf(startId)
            return
        }

        if (!SecureConfig.isOrganizationVerified(this)) {
            Log.w(TAG, "Gateway start blocked: organization/mobile is not verified")

            saveServiceRunning(false)
            setServerState(STATE_FAILED)
            updateStatus("Organization and registered mobile must be verified first")
            stopSelf(startId)
            return
        }

        if (SecureConfig.getSelectedSimSubscriptionId(this) == null) {
            Log.w(TAG, "Gateway start blocked: no SIM has been selected")

            saveServiceRunning(false)
            setServerState(STATE_FAILED)
            updateStatus("Select an assigned SIM before starting the gateway")
            stopSelf(startId)
            return
        }

        if (deviceToken.isNullOrBlank()) {
            Log.w(TAG, "Gateway start blocked: device is not activated")

            saveServiceRunning(false)
            setServerState(STATE_FAILED)

            stopSelf(startId)
            return
        }

        if (
            checkSelfPermission(Manifest.permission.SEND_SMS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "Gateway start blocked: SEND_SMS permission missing")

            saveServiceRunning(false)
            setServerState(STATE_FAILED)

            stopSelf(startId)
            return
        }

        if (!receiverRegistered) {
            registerSmsResultReceiver()
        }

        if (!receiverRegistered) {
            Log.e(TAG, "Gateway start blocked: callback receiver unavailable")

            saveServiceRunning(false)
            setServerState(STATE_FAILED)

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
            resultReporting = false

            saveServiceRunning(true)
            setServerState(STATE_CONNECTING)

            updateStatus("Activated — connecting to server")

            Log.i(TAG, "Gateway service started")

            handler.removeCallbacks(pollRunnable)
            handler.post(pollRunnable)

        } catch (exception: Exception) {
            Log.e(TAG, "Unable to start gateway service", exception)

            serviceRunning = false
            requestInProgress = false

            saveServiceRunning(false)
            setServerState(STATE_FAILED)

            try {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } catch (stopException: Exception) {
                Log.w(
                    TAG,
                    "Could not remove foreground notification",
                    stopException
                )
            }

            stopSelf(startId)
        }
    }

    /**
     * Fetch one queued job from device_poll.php.
     *
     * A successful authenticated polling response marks the server
     * as connected. Failed requests mark it as failed.
     */
    private fun pollServer() {
        if (!serviceRunning || requestInProgress) return

        requestInProgress = true
        setServerState(STATE_CONNECTING)

        ApiClient.pollGatewayJob { result ->

            if (!serviceRunning) {
                requestInProgress = false
                return@pollGatewayJob
            }

            result.fold(
                onSuccess = { response ->

                    val job = response.optJSONObject("job")

                    if (!response.optBoolean("ok", false)) {
                        requestInProgress = false

                        setServerState(STATE_FAILED)
                        updateStatus("Invalid server response")

                        return@fold
                    }

                    // Server has accepted the authenticated poll request.
                    setServerState(STATE_CONNECTED)

                    if (job == null) {
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
                        "Gateway polling failed (${exception.javaClass.simpleName})"
                    )

                    setServerState(STATE_FAILED)
                    updateStatus("Server connection failed — retrying")
                }
            )
        }
    }

    /**
     * Validate a claimed server job and submit it to Android.
     */
    private fun processJob(job: JSONObject) {

        // Re-check immediately before each SMS job, not only when the service starts.
        // This prevents queued jobs from sending after verification/SIM settings change.
        if (!SecureConfig.isOrganizationVerified(this)) {
            Log.w(TAG, "SMS job blocked: organization/mobile is not verified")
            requestInProgress = false
            updateStatus("SMS blocked: verify organization and registered mobile")
            return
        }

        val selectedSubscriptionId = SecureConfig.getSelectedSimSubscriptionId(this)
        if (selectedSubscriptionId == null) {
            Log.w(TAG, "SMS job blocked: no SIM is selected")
            requestInProgress = false
            updateStatus("SMS blocked: select an assigned SIM")
            return
        }

        val jobId = job.optLong("id", 0L)
        val requestId = job.optString("request_id").trim()
        val phoneNumber = job.optString("to").trim()
        val message = job.optString("message")

        if (
            jobId <= 0L ||
            !requestId.matches(Regex("^[a-f0-9]{32}$")) ||
            phoneNumber.isBlank() ||
            message.isBlank()
        ) {
            Log.e(TAG, "Received invalid SMS job")

            if (
                jobId > 0L &&
                requestId.matches(Regex("^[a-f0-9]{32}$"))
            ) {
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

        // Register the job BEFORE asking Android to send the SMS.
        // A sent PendingIntent callback can arrive very quickly; registering
        // after sendSms() creates a race where that callback is lost.
        val jobState = SmsJobState(requestId = requestId)
        val existingState = smsJobs.putIfAbsent(jobId, jobState)
        if (existingState != null) {
            Log.e(TAG, "Duplicate in-flight SMS job ignored: $jobId")
            requestInProgress = false
            updateStatus("Duplicate SMS job ignored — check server")
            return
        }

        when (
            val sendResult = SmsHelper.sendSms(
                context = this,
                phoneNumber = phoneNumber,
                message = message,
                jobId = jobId,
                requestId = requestId
            )
        ) {
            is SmsHelper.SendResult.Success -> {
                updateStatus("SMS submitted — awaiting Android callback")
            }

            is SmsHelper.SendResult.Failure -> {
                // No Android callback is expected after submission failure.
                smsJobs.remove(jobId, jobState)
                Log.e(
                    TAG,
                    "SMS submission failed: ${sendResult.reason}"
                )

                reportResult(
                    jobId = jobId,
                    requestId = requestId,
                    success = false,
                    errorCode = "SMS_SUBMISSION_FAILED"
                )
            }
        }
    }

    /**
     * Report the final result to device_result.php.
     */
    private fun reportResult(
        jobId: Long,
        requestId: String,
        success: Boolean,
        errorCode: String? = null
    ) {
        if (!serviceRunning) {
            requestInProgress = false
            return
        }

        if (resultReporting) {
            Log.w(TAG, "A result report is already in progress")
            return
        }

        resultReporting = true

        ApiClient.submitGatewayResult(
            jobId = jobId,
            requestId = requestId,
            success = success,
            errorCode = errorCode
        ) { result ->

            resultReporting = false

            if (!serviceRunning) {
                requestInProgress = false
                return@submitGatewayResult
            }

            result.fold(
                onSuccess = { response ->

                    if (!response.optBoolean("ok", false)) {
                        Log.e(
                            TAG,
                            "Server returned an unsuccessful result response"
                        )

                        requestInProgress = false
                        updateStatus("Result report rejected — check server")
                        return@fold
                    }

                    Log.i(
                        TAG,
                        "Server accepted result for job $jobId"
                    )

                    requestInProgress = false

                    if (success) {
                        updateStatus(
                            "SMS sent callback successful — waiting for next job"
                        )
                    } else {
                        updateStatus(
                            "SMS failed — waiting for next job"
                        )
                    }
                },

                onFailure = { exception ->

                    Log.e(
                        TAG,
                        "Could not report result for job $jobId (${exception.javaClass.simpleName})"
                    )

                    requestInProgress = false
                    updateStatus("Result report failed — check server")
                }
            )
        }
    }

    /**
     * Save whether the Android foreground gateway service is running.
     */
    private fun saveServiceRunning(running: Boolean) {
        getSharedPreferences(
            statusPrefsName,
            MODE_PRIVATE
        ).edit()
            .putBoolean(PREF_SERVICE_RUNNING, running)
            .apply()
    }

    /**
     * Save server state and the time of the latest state change.
     *
     * MainActivity uses this timestamp to avoid showing an old
     * connection result as a current connection.
     */
    private fun setServerState(state: String) {
        getSharedPreferences(
            statusPrefsName,
            MODE_PRIVATE
        ).edit()
            .putString(PREF_SERVER_STATE, state)
            .putLong(PREF_SERVER_CHECK_AT, System.currentTimeMillis())
            .apply()
    }

    private fun updateStatus(message: String) {
        if (!serviceRunning) return

        try {
            updateNotification(message)
        } catch (exception: Exception) {
            Log.w(
                TAG,
                "Unable to update gateway notification",
                exception
            )
        }
    }

    private fun stopGateway() {

        serviceRunning = false
        requestInProgress = false
        resultReporting = false

        handler.removeCallbacks(pollRunnable)

        saveServiceRunning(false)
        setServerState(STATE_STOPPED)
        smsJobs.clear()

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
        resultReporting = false

        handler.removeCallbacks(pollRunnable)

        saveServiceRunning(false)
        setServerState(STATE_STOPPED)
        smsJobs.clear()

        if (receiverRegistered) {
            try {
                unregisterReceiver(smsResultReceiver)
            } catch (exception: IllegalArgumentException) {
                Log.w(
                    TAG,
                    "SMS result receiver was not registered",
                    exception
                )
            }

            receiverRegistered = false
        }

        Log.i(TAG, "Gateway service destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {

        private const val TAG = "GatewayService"
        private const val CHANNEL_ID = "sms_gateway_service"
        private const val NOTIFICATION_ID = 1001

        private const val POLL_INTERVAL_MS = 5000L

        private const val PREF_SERVICE_RUNNING = "service_running"
        private const val PREF_SERVER_STATE = "server_state"
        private const val PREF_SERVER_CHECK_AT = "server_check_at"

        private const val STATE_CONNECTED = "CONNECTED"
        private const val STATE_FAILED = "FAILED"
        private const val STATE_CONNECTING = "CONNECTING"
        private const val STATE_STOPPED = "STOPPED"

        const val ACTION_START =
            "com.ssmediaos.smsgateway.action.START"

        const val ACTION_STOP =
            "com.ssmediaos.smsgateway.action.STOP"
    }
}
