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
import android.os.PowerManager
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

    // Poll state is tracked separately from SMS/result work. If a callback is
    // unexpectedly lost, the watchdog can safely release only the stale poll.
    @Volatile
    private var pollRequestInFlight = false

    @Volatile
    private var pollRequestStartedAtMs = 0L

    @Volatile
    private var pollAttemptSequence = 0L

    private val handler = Handler(Looper.getMainLooper())

    private var currentStartId = 0
    private var receiverRegistered = false

    // Keep the CPU awake while the user has explicitly enabled the always-on SMS gateway.
    // Released whenever the foreground service stops or is destroyed.
    private var gatewayWakeLock: PowerManager.WakeLock? = null

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

    /**
     * Keep a claimed job's final result until the Main Server acknowledges it.
     */
    private data class PendingGatewayResult(
        val jobId: Long,
        val requestId: String,
        val success: Boolean,
        val errorCode: String?
    )

    private val pendingResultLock = Any()

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!serviceRunning) return

            if (!requestInProgress) {
                pollServer()
            }
        }
    }

    /**
     * Recover only a stale HTTP poll. SMS submission and result reporting may
     * legitimately take longer, so they are never reset by this watchdog.
     */
    private val pollWatchdogRunnable = object : Runnable {
        override fun run() {
            if (!serviceRunning) return

            val startedAt = pollRequestStartedAtMs
            val age = if (startedAt > 0L) {
                System.currentTimeMillis() - startedAt
            } else {
                0L
            }

            if (pollRequestInFlight && age > POLL_REQUEST_STALE_TIMEOUT_MS) {
                val staleAttempt = pollAttemptSequence
                pollAttemptSequence += 1L // Ignore any late callback from this attempt.
                pollRequestInFlight = false
                pollRequestStartedAtMs = 0L
                if (!resultReporting) requestInProgress = false

                Log.e(
                    TAG,
                    "Poll watchdog recovered stale request: attempt=$staleAttempt age_ms=$age"
                )
                setServerState(STATE_FAILED)
                updateStatus("Main Server poll timed out — reconnecting")
                scheduleImmediatePoll()
            }

            handler.removeCallbacks(this)
            handler.postDelayed(this, POLL_WATCHDOG_INTERVAL_MS)
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
            Log.i(TAG, "Gateway is already running; ensuring poll loop is scheduled")
            if (gatewayWakeLock?.isHeld != true) acquireGatewayWakeLock()
            handler.removeCallbacks(pollRunnable)
            handler.post(pollRunnable)
            handler.removeCallbacks(pollWatchdogRunnable)
            handler.postDelayed(pollWatchdogRunnable, POLL_WATCHDOG_INTERVAL_MS)
            return
        }

        val serverUrl: String = SecureConfig.getServerUrl(this).orEmpty().trim()
        val deviceCode: String = SecureConfig.getDeviceCode(this).orEmpty().trim()
        val companyApiKey: String = SecureConfig.getCompanyApiKey(this).orEmpty().trim()
        val deviceToken: String = SecureConfig.getDeviceToken(this).orEmpty().trim()

        if (
            serverUrl.isBlank() ||
            deviceCode.isBlank() ||
            companyApiKey.isBlank()
        ) {
            Log.w(TAG, "Gateway start blocked: required settings missing")

            saveServiceRunning(false)
            setServerState(STATE_FAILED)
            updateStatus("Gateway not started: save the Main Server URL, Device Code and Company API Key first")

            stopSelf(startId)
            return
        }

        if (!SecureConfig.isOrganizationVerified(this)) {
            Log.w(TAG, "Gateway start blocked: institution/mobile is not verified")

            saveServiceRunning(false)
            setServerState(STATE_FAILED)
            updateStatus("Gateway not started: verify the institution and registered mobile first")
            stopSelf(startId)
            return
        }

        // A gateway must be bound to exactly one verified institution.
        // Do not start polling if the authoritative institution identity is incomplete.
        val verifiedInstitutionId = SecureConfig.getOrganizationId(this).trim()
        val verifiedInstitutionName = SecureConfig.getOrganizationName(this).trim()
        val verifiedInstitutionCode = SecureConfig.getOrganizationCode(this).trim()
        if (verifiedInstitutionId.isBlank() || verifiedInstitutionName.isBlank() || verifiedInstitutionCode.isBlank()) {
            Log.w(TAG, "Gateway start blocked: verified institution identity is incomplete")
            saveServiceRunning(false)
            setServerState(STATE_FAILED)
            updateStatus("Institution ID, name and code must be verified first")
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

        if (deviceToken.isBlank()) {
            Log.w(TAG, "Gateway start blocked: device is not activated")

            saveServiceRunning(false)
            setServerState(STATE_FAILED)
            updateStatus("Gateway not started: activate this device with Main Server first")

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
            updateStatus("Gateway not started: allow SMS permission in Android App Settings")

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
            acquireGatewayWakeLock()

            saveServiceRunning(true)
            setServerState(STATE_CONNECTING)

            updateStatus("Activated — connecting to server")

            Log.i(TAG, "Gateway service started")

            handler.removeCallbacks(pollRunnable)
            handler.post(pollRunnable)
            handler.removeCallbacks(pollWatchdogRunnable)
            handler.postDelayed(pollWatchdogRunnable, POLL_WATCHDOG_INTERVAL_MS)

        } catch (exception: Exception) {
            Log.e(TAG, "Unable to start gateway service", exception)

            serviceRunning = false
            requestInProgress = false
            releaseGatewayWakeLock()

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

        // Report a previously claimed job before claiming another one.
        if (loadPendingResult() != null) {
            sendPendingResult()
            return
        }

        requestInProgress = true
        pollRequestInFlight = true
        pollRequestStartedAtMs = System.currentTimeMillis()
        val attemptId = ++pollAttemptSequence
        setServerState(STATE_CONNECTING)

        Log.i(TAG, "Starting authenticated Main Server poll: attempt=$attemptId")

        ApiClient.pollGatewayJob { result ->
            // A watchdog-recovered request may still deliver a late callback.
            // Ignore it so it cannot interfere with a newer poll or SMS job.
            if (attemptId != pollAttemptSequence) {
                Log.w(TAG, "Ignoring late poll callback: attempt=$attemptId")
                return@pollGatewayJob
            }

            pollRequestInFlight = false
            pollRequestStartedAtMs = 0L

            if (!serviceRunning) {
                requestInProgress = false
                return@pollGatewayJob
            }

            try {
                result.fold(
                    onSuccess = { response ->
                        val job = response.optJSONObject("job")

                        if (!response.optBoolean("ok", false)) {
                            requestInProgress = false
                            setServerState(STATE_FAILED)
                            updateStatus("Invalid server response")
                            scheduleRetryPoll()
                            return@fold
                        }

                        // A successful response from device_poll.php confirms
                        // token authentication and Main Server reachability.
                        setServerState(STATE_CONNECTED)

                        val diagnosticCode = response.optString("diagnostic_code", "POLL_OK")
                            .ifBlank { "POLL_OK" }
                        val diagnosticMessage = response.optString("diagnostic_message", "")
                            .replace(Regex("[\\r\\n\\t]+"), " ")
                            .take(220)
                        val nextAction = response.optString("next_action", "")
                            .replace(Regex("[\\r\\n\\t]+"), " ")
                            .take(180)

                        Log.i(
                            TAG,
                            "Main Server poll accepted: http=${response.optInt("_httpStatus", 200)}, " +
                                "poll_request_id=${response.optString("poll_request_id", "")}, " +
                                "code=$diagnosticCode, " +
                                "device=${response.optJSONObject("device")?.optString("code", "unknown")}, " +
                                "message=$diagnosticMessage, next=$nextAction"
                        )

                        if (job == null) {
                            requestInProgress = false
                            if (response.optBoolean("busy", false)) {
                                updateStatus("Connected — previous SMS result is pending")
                                scheduleRetryPoll()
                            } else {
                                val visibleDiagnostic = when (diagnosticCode) {
                                    "SMS_ASSIGNED_TO_DIFFERENT_DEVICE" -> "SMS queue is assigned to another device"
                                    "QUEUED_JOB_NOT_CLAIMED" -> "SMS is queued but Main Server could not claim it"
                                    "NO_QUEUED_SMS_FOR_DEVICE" -> "Connected — no SMS assigned to this device"
                                    else -> diagnosticMessage.ifBlank { "Connected — waiting for SMS jobs" }
                                }
                                updateStatus(visibleDiagnostic)
                                scheduleImmediatePoll()
                            }
                            return@fold
                        }

                        updateStatus("SMS job received from Main Server — preparing selected SIM")
                        processJob(job)
                    },

                    onFailure = { exception ->
                        requestInProgress = false

                        val detail = exception.message
                            ?.replace(Regex("[\\r\\n\\t]+"), " ")
                            ?.take(220)
                            ?.ifBlank { exception.javaClass.simpleName }
                            ?: exception.javaClass.simpleName

                        Log.e(TAG, "Gateway polling failed: $detail", exception)
                        setServerState(STATE_FAILED)
                        updateStatus("Main Server poll failed: $detail — retrying")
                        scheduleRetryPoll()
                    }
                )
            } catch (exception: Exception) {
                // ApiClient deliberately isolates callback exceptions. Handle
                // them here so an unexpected parsing/job error cannot leave
                // requestInProgress=true forever and silently stop polling.
                requestInProgress = false
                Log.e(TAG, "Unexpected error while handling poll response", exception)
                setServerState(STATE_FAILED)
                updateStatus("Poll response handling failed — retrying")
                scheduleRetryPoll()
            }
        }
    }

    /**
     * Keep this rule identical to message_enqueue.php:
     * ignore only underscore, hyphen and whitespace; compare case-insensitively.
     */
    private fun normalizeInstitutionCode(value: String): String {
        return value.trim()
            .replace(Regex("[_\\-\\s]+"), "")
            .uppercase(java.util.Locale.ROOT)
    }

    /**
     * Validate a claimed server job and submit it to Android.
     */
    private fun processJob(job: JSONObject) {
        // Validate the server claim first so every identifiable claimed job can
        // receive a terminal result instead of being silently abandoned.
        val jobId = job.optLong("id", 0L)
        val requestId = job.optString("request_id", "").orEmpty().trim()
        val phoneNumber = job.optString("to", "").orEmpty().trim()
        val message = job.optString("message", "").orEmpty()

        if (
            jobId <= 0L ||
            !requestId.matches(Regex("^[a-f0-9]{32}$")) ||
            phoneNumber.isBlank() ||
            message.isBlank()
        ) {
            Log.e(TAG, "Received invalid SMS job")
            rejectClaimedJob(jobId, requestId, "INVALID_JOB", "Invalid SMS job received")
            return
        }

        // Re-check authorization immediately before every send. If a claimed
        // job cannot be sent, report a failure to Main Server so it is not left
        // indefinitely in a claimed/processing state.
        if (!SecureConfig.isOrganizationVerified(this)) {
            Log.w(TAG, "SMS job blocked: organization/mobile is not verified")
            rejectClaimedJob(
                jobId, requestId, "ORGANIZATION_NOT_VERIFIED",
                "SMS blocked: verify organization and registered mobile"
            )
            return
        }

        val selectedSubscriptionId = SecureConfig.getSelectedSimSubscriptionId(this)
        if (selectedSubscriptionId == null) {
            Log.w(TAG, "SMS job blocked: no SIM is selected")
            rejectClaimedJob(
                jobId, requestId, "SIM_NOT_SELECTED",
                "SMS blocked: select an assigned SIM"
            )
            return
        }

        // Institution isolation: both the immutable database ID and exact
        // institution code must match the one verified on this gateway.
        val verifiedInstitutionId = SecureConfig.getOrganizationId(this).trim()
        val verifiedInstitutionCode = SecureConfig.getOrganizationCode(this).trim()

        val jobInstitutionId = sequenceOf(
            job.optString("institution_id", ""),
            job.optString("institutionId", ""),
            job.optString("organization_id", ""),
            job.optString("organizationId", "")
        ).map { it.trim() }.firstOrNull { it.isNotBlank() }.orEmpty()

        val jobInstitutionCode = sequenceOf(
            job.optString("institution_code", ""),
            job.optString("institutionCode", ""),
            job.optString("organization_code", ""),
            job.optString("organizationCode", "")
        ).map { it.trim() }.firstOrNull { it.isNotBlank() }.orEmpty()

        // device_poll.php authenticates the gateway token and selects queue rows
        // scoped to that device's company_id + institution_id. Some deployed
        // versions return only id/request_id/recipient/message in the job JSON,
        // so institution fields may legitimately be absent. Require the locally
        // verified identity, and compare server-returned identity whenever it is
        // present; do not reject a tenant-scoped job merely because older poll
        // responses omit duplicate institution metadata.
        val institutionIdMismatch =
            jobInstitutionId.isNotBlank() &&
                jobInstitutionId != verifiedInstitutionId
        val institutionCodeMismatch =
            jobInstitutionCode.isNotBlank() &&
                normalizeInstitutionCode(jobInstitutionCode) !=
                normalizeInstitutionCode(verifiedInstitutionCode)

        if (
            verifiedInstitutionId.isBlank() ||
            verifiedInstitutionCode.isBlank() ||
            institutionIdMismatch ||
            institutionCodeMismatch
        ) {
            Log.e(TAG, "SMS job blocked: verified institution identity is missing or server identity mismatched")
            rejectClaimedJob(
                jobId, requestId, "INSTITUTION_MISMATCH",
                "SMS blocked: job does not belong to this verified institution"
            )
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
            scheduleImmediatePoll()
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

    /** Report a claimed job that was rejected before Android SMS submission. */
    private fun rejectClaimedJob(
        jobId: Long,
        requestId: String,
        errorCode: String,
        userMessage: String
    ) {
        requestInProgress = false
        updateStatus(userMessage)

        if (jobId > 0L && requestId.matches(Regex("^[a-f0-9]{32}$"))) {
            reportResult(
                jobId = jobId,
                requestId = requestId,
                success = false,
                errorCode = errorCode
            )
        } else {
            Log.e(TAG, "Cannot report rejected job: invalid job ID or request ID")
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
        if (jobId <= 0L || requestId.isBlank()) {
            Log.e(TAG, "Cannot save invalid SMS result")
            requestInProgress = false
            return
        }

        val pending = PendingGatewayResult(
            jobId = jobId,
            requestId = requestId,
            success = success,
            errorCode = errorCode
        )

        synchronized(pendingResultLock) {
            val existing = loadPendingResult()

            if (existing != null &&
                (existing.jobId != jobId || existing.requestId != requestId)
            ) {
                Log.e(
                    TAG,
                    "A different SMS result is still pending; refusing to overwrite it"
                )
                requestInProgress = false
                return
            }

            savePendingResult(pending)
        }

        if (serviceRunning) {
            sendPendingResult()
        } else {
            Log.w(TAG, "SMS result saved locally; service is not running")
            requestInProgress = false
        }
    }

    /**
     * Submit the locally persisted result. Keep it until the server confirms it.
     */
    private fun sendPendingResult() {
        if (!serviceRunning || resultReporting) return

        val pending = loadPendingResult() ?: return

        resultReporting = true
        requestInProgress = true

        ApiClient.submitGatewayResult(
            jobId = pending.jobId,
            requestId = pending.requestId,
            success = pending.success,
            errorCode = pending.errorCode
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
                            "Server rejected result for job ${pending.jobId}; keeping it for retry"
                        )
                        requestInProgress = false
                        updateStatus("Result report rejected — retrying")
                        scheduleRetryPoll()
                        return@fold
                    }

                    synchronized(pendingResultLock) {
                        val current = loadPendingResult()
                        if (
                            current?.jobId == pending.jobId &&
                            current.requestId == pending.requestId
                        ) {
                            clearPendingResult()
                        }
                    }

                    Log.i(TAG, "Server accepted result for job ${pending.jobId}")
                    requestInProgress = false
                    scheduleImmediatePoll()

                    updateStatus(
                        if (pending.success) {
                            "SMS result accepted — waiting for next job"
                        } else {
                            "SMS failure reported — waiting for next job"
                        }
                    )
                },

                onFailure = { exception ->
                    Log.e(
                        TAG,
                        "Could not report result for job ${pending.jobId} " +
                            "(${exception.javaClass.simpleName})"
                    )
                    // Keep the pending result. pollServer() retries it.
                    requestInProgress = false
                    updateStatus("Result report failed — retrying")
                    scheduleRetryPoll()
                }
            )
        }
    }

    /** Schedule the next queue check promptly after a completed job. */
    private fun scheduleImmediatePoll() {
        if (!serviceRunning) return
        handler.removeCallbacks(pollRunnable)
        handler.postDelayed(pollRunnable, IMMEDIATE_POLL_DELAY_MS)
    }

    /** Back off briefly on network/API failures; never spin a tight retry loop. */
    private fun scheduleRetryPoll() {
        if (!serviceRunning) return
        handler.removeCallbacks(pollRunnable)
        handler.postDelayed(pollRunnable, RETRY_POLL_DELAY_MS)
    }

    private fun savePendingResult(result: PendingGatewayResult) {
        val json = JSONObject().apply {
            put("job_id", result.jobId)
            put("request_id", result.requestId)
            put("success", result.success)
            put("error_code", result.errorCode ?: JSONObject.NULL)
        }

        getSharedPreferences(statusPrefsName, MODE_PRIVATE)
            .edit()
            .putString(PREF_PENDING_RESULT, json.toString())
            .apply()
    }

    private fun loadPendingResult(): PendingGatewayResult? {
        val raw = getSharedPreferences(statusPrefsName, MODE_PRIVATE)
            .getString(PREF_PENDING_RESULT, null)
            ?: return null

        return try {
            val json = JSONObject(raw)
            val jobId = json.optLong("job_id", 0L)
            val requestId = json.optString("request_id", "").trim()

            if (jobId <= 0L || requestId.isBlank()) {
                Log.e(TAG, "Stored pending SMS result is invalid")
                null
            } else {
                val errorCode = if (json.isNull("error_code")) {
                    null
                } else {
                    json.optString("error_code")
                }

                PendingGatewayResult(
                    jobId = jobId,
                    requestId = requestId,
                    success = json.optBoolean("success", false),
                    errorCode = errorCode
                )
            }
        } catch (exception: Exception) {
            Log.e(TAG, "Could not read pending SMS result", exception)
            null
        }
    }

    private fun clearPendingResult() {
        getSharedPreferences(statusPrefsName, MODE_PRIVATE)
            .edit()
            .remove(PREF_PENDING_RESULT)
            .apply()
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
        // Persist the latest human-readable gateway detail so MainActivity can
        // show the real reason (token rejected, network timeout, mapping issue,
        // missing permissions, etc.) instead of only a generic FAILED label.
        getSharedPreferences(statusPrefsName, MODE_PRIVATE)
            .edit()
            .putString(PREF_LAST_STATUS_MESSAGE, message.take(240))
            .putLong(PREF_LAST_STATUS_AT, System.currentTimeMillis())
            .apply()

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
        handler.removeCallbacks(pollWatchdogRunnable)
        pollAttemptSequence += 1L
        pollRequestInFlight = false
        pollRequestStartedAtMs = 0L
        releaseGatewayWakeLock()

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

    /**
     * A foreground service alone does not guarantee the CPU stays awake while
     * the screen is off. Hold a partial wake lock only while the user-enabled
     * gateway is active so its long-poll loop can continue in screen-off use.
     */
    private fun acquireGatewayWakeLock() {
        try {
            val existing = gatewayWakeLock
            if (existing?.isHeld == true) return

            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            gatewayWakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "$packageName:SmsGatewayPolling"
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
            Log.i(TAG, "Gateway partial wake lock acquired")
        } catch (exception: Exception) {
            Log.e(TAG, "Could not acquire gateway wake lock", exception)
            updateStatus("Gateway is running, but Android could pause polling while the screen is off")
        }
    }

    private fun releaseGatewayWakeLock() {
        try {
            gatewayWakeLock?.let { lock ->
                if (lock.isHeld) lock.release()
            }
        } catch (exception: Exception) {
            Log.w(TAG, "Could not release gateway wake lock", exception)
        } finally {
            gatewayWakeLock = null
        }
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
        handler.removeCallbacks(pollWatchdogRunnable)
        pollAttemptSequence += 1L
        pollRequestInFlight = false
        pollRequestStartedAtMs = 0L
        releaseGatewayWakeLock()

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

        private const val IMMEDIATE_POLL_DELAY_MS = 150L
        private const val RETRY_POLL_DELAY_MS = 3000L
        private const val POLL_WATCHDOG_INTERVAL_MS = 15000L
        // Connect timeout (15s) + read timeout (45s) leaves room for hosting latency.
        private const val POLL_REQUEST_STALE_TIMEOUT_MS = 75000L

        private const val PREF_SERVICE_RUNNING = "service_running"
        private const val PREF_SERVER_STATE = "server_state"
        private const val PREF_SERVER_CHECK_AT = "server_check_at"
        private const val PREF_LAST_STATUS_MESSAGE = "last_status_message"
        private const val PREF_LAST_STATUS_AT = "last_status_at"
        private const val PREF_PENDING_RESULT = "pending_gateway_result"

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
