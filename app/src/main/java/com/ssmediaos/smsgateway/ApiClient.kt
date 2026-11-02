package com.ssmediaos.smsgateway

import android.content.Context
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.Executors

object ApiClient {

    private val executor = Executors.newSingleThreadExecutor()

    private const val CONNECT_TIMEOUT = 15000
    private const val READ_TIMEOUT = 20000

    private const val DEFAULT_SERVER_URL =
        "https://otp.ssmediaos.in"

    private const val DEVICE_ACTIVATE_PATH =
        "/api/v1/sms/device_activate.php"

    private const val DEVICE_POLL_PATH =
        "/api/v1/sms/device_poll.php"

    private const val DEVICE_RESULT_PATH =
        "/api/v1/sms/device_result.php"

    /*
     * This endpoint must be implemented on the Main Server.
     * It must perform the actual server-side diagnostic checks.
     */
    private const val GATEWAY_DIAGNOSTICS_PATH =
        "/api/v1/sms/gateway_diagnostics.php"

    /**
     * Verify the complete gateway connection through Main Server.
     *
     * Main Server is responsible for:
     * 1. Company API authentication.
     * 2. Client API configuration lookup.
     * 3. Client Server API connectivity.
     * 4. Client Database connectivity.
     * 5. Institution code and company relationship verification.
     * 6. Registered mobile verification, when required by the
     *    configured institution registration rules.
     *
     * The Android app must not connect directly to Client Database.
     *
     * Expected response:
     *
     * {
     *   "ok": true,
     *   "checks": {
     *     "main_server": {
     *       "status": "PASS",
     *       "code": "MAIN_SERVER_CONNECTED",
     *       "message": "Main Server connected"
     *     },
     *     "company_auth": {
     *       "status": "PASS",
     *       "code": "API_KEY_VALID",
     *       "message": "API key verified"
     *     },
     *     "client_api_config": {
     *       "status": "PASS",
     *       "code": "CLIENT_API_CONFIGURED",
     *       "message": "Client API configuration found"
     *     },
     *     "client_api": {
     *       "status": "PASS",
     *       "code": "CLIENT_API_CONNECTED",
     *       "message": "Client API connected"
     *     },
     *     "client_database": {
     *       "status": "PASS",
     *       "code": "CLIENT_DATABASE_CONNECTED",
     *       "message": "Client database connected"
     *     },
     *     "institution": {
     *       "status": "PASS",
     *       "code": "INSTITUTION_CODE_VERIFIED",
     *       "message": "Institution verified"
     *     }
     *   }
     * }
     *
     * The checks object above documents the response contract.
     * The server must return real results, not fabricated PASS values.
     */
    fun diagnoseGateway(
        companyApiKey: String,
        institutionCode: String,
        registeredMobileNumber: String,
        callback: (Result<JSONObject>) -> Unit
    ) {
        executor.execute {
            val result = runCatching {
                val context = AppContextHolder.context
                val apiKey = companyApiKey.trim()
                val code = institutionCode.trim().uppercase()
                val mobile = registeredMobileNumber.trim()

                if (apiKey.isBlank()) throw IOException("COMPANY_API_KEY_REQUIRED: Enter the Company API Key.")
                if (code.isBlank()) throw IOException("INSTITUTION_CODE_REQUIRED: Enter the Institution Code.")
                if (mobile.isBlank()) throw IOException("REGISTERED_MOBILE_REQUIRED: Enter the registered mobile number.")
                if (mobile.any { !it.isDigit() && it !in "+- ()" } || normalizeMobile(mobile).length !in 8..15) {
                    throw IOException("INVALID_REGISTERED_MOBILE: Check the registered mobile number.")
                }

                // A new verification attempt must never reuse a previous institution's activation.
                SecureConfig.clearDeviceToken(context)
                SecureConfig.clearOrganizationVerification(context)

                val deviceCode = SecureConfig.getDeviceCode(context).trim()
                val body = JSONObject()
                    .put("companyApiKey", apiKey)
                    .put("institutionCode", code)
                    .put("institution_code", code)
                    .put("organizationCode", code) // legacy Main Server compatibility
                    .put("registeredMobileNumber", mobile)
                    .put("registered_mobile", normalizeMobile(mobile))
                    .put("deviceCode", deviceCode)
                    .put("device_code", deviceCode)

                val response = request(
                    endpoint = buildEndpoint(getServerUrl(context), GATEWAY_DIAGNOSTICS_PATH),
                    method = "POST",
                    bearerToken = apiKey,
                    body = body,
                    allowHttpErrors = true
                )
                val httpStatus = response.optInt("_httpStatus", 200)
                if (httpStatus !in 200..299 || !response.optBoolean("ok", false)) {
                    throw IOException(buildServerError(response, httpStatus, "GATEWAY_DIAGNOSTICS_FAILED"))
                }

                val data = response.optJSONObject("data") ?: response
                val checks = response.optJSONObject("checks")
                    ?: data.optJSONObject("checks")
                    ?: throw IOException("DIAGNOSTIC_CHECKS_MISSING: Main Server did not return diagnostic checks.")
                val requiredChecks = listOf("main_server", "company_auth", "client_api_config", "client_api", "client_database", "institution")
                val failedChecks = requiredChecks.filter { name ->
                    !checks.optJSONObject(name)?.optString("status", "UNKNOWN").equals("PASS", true)
                }
                if (failedChecks.isNotEmpty()) {
                    throw IOException("DIAGNOSTICS_INCOMPLETE: Required checks did not pass: ${failedChecks.joinToString(", ")}.")
                }

                val institution = data.optJSONObject("institution")
                    ?: data.optJSONObject("organization")
                    ?: data.optJSONObject("hospital")
                    ?: response.optJSONObject("institution")
                    ?: response.optJSONObject("organization")
                    ?: data
                val returnedCode = firstNonBlank(
                    institution.optString("institution_code"), institution.optString("organization_code"),
                    institution.optString("hospital_code"), institution.optString("code")
                ).uppercase()
                val institutionId = firstNonBlank(
                    institution.optString("institution_id"), institution.optString("organization_id"),
                    institution.optString("hospital_id"), institution.optString("id")
                )
                val institutionName = firstNonBlank(
                    institution.optString("institution_name"), institution.optString("organization_name"),
                    institution.optString("hospital_name"), institution.optString("name")
                )
                val serverMobile = normalizeMobile(firstNonBlank(
                    institution.optString("registered_mobile"), institution.optString("registered_mobile_number"),
                    institution.optString("mobile_number"), institution.optString("mobile")
                ))
                val institutionStatus = firstNonBlank(
                    institution.optString("verification_status"), institution.optString("institution_status"),
                    institution.optString("organization_status"), institution.optString("status")
                )
                val explicitlyVerified = data.optBoolean("verified", false) ||
                    data.optBoolean("success", false) || response.optBoolean("verified", false) ||
                    response.optBoolean("success", false) ||
                    institution.optBoolean("verified", false)
                val statusVerified = institutionStatus.equals("active", true) || institutionStatus.equals("verified", true)

                if (!explicitlyVerified && !statusVerified) {
                    throw IOException("INSTITUTION_NOT_VERIFIED: Main Server did not confirm this institution.")
                }
                if (returnedCode.isBlank() || returnedCode != code) {
                    throw IOException("INSTITUTION_CODE_MISMATCH: Client Server Institution Code does not exactly match the entered code.")
                }
                if (institutionId.isBlank() || institutionName.isBlank()) {
                    throw IOException("INSTITUTION_DATA_INCOMPLETE: Main Server must return Institution ID and Institution Name.")
                }
                if (serverMobile.length !in 8..15 || normalizeMobile(mobile) != serverMobile) {
                    throw IOException("REGISTERED_MOBILE_MISMATCH: Entered mobile number does not match the verified institution record.")
                }

                // Status is retained internally only for the verification gate; it is not a UI field.
                val internalStatus = if (statusVerified) institutionStatus else "Verified"
                if (!SecureConfig.saveVerifiedOrganization(
                    context = context,
                    organizationId = institutionId,
                    organizationName = institutionName,
                    organizationCode = returnedCode,
                    registeredMobile = serverMobile,
                    organizationStatus = internalStatus
                )) {
                    throw IOException("INSTITUTION_SAVE_FAILED: Verified institution details could not be saved securely.")
                }

                // Keep the original response intact so the Settings screen can display Company + Institution details.
                response
            }
            callbackSafely(callback, result)
        }
    }

    /**
     * Activate a registered Android gateway device.
     *
     * Activation is accepted only when Main Server confirms success
     * and returns a valid device token.
     */
    fun activate(callback: (Result<JSONObject>) -> Unit) {
        executor.execute {
            val result = runCatching {
                val context = AppContextHolder.context
                if (!SecureConfig.isOrganizationVerified(context)) {
                    throw IOException("INSTITUTION_NOT_VERIFIED: Verify one institution before device activation.")
                }
                val deviceCode = SecureConfig.getDeviceCode(context).trim()
                    .takeIf { it.isNotEmpty() }
                    ?: throw IOException("DEVICE_CODE_REQUIRED: Device ID is unavailable.")
                val companyApiKey = SecureConfig.getCompanyApiKey(context)?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: throw IOException("COMPANY_API_KEY_REQUIRED: Enter the Company API Key.")
                val institutionId = SecureConfig.getOrganizationId(context).trim()
                val institutionName = SecureConfig.getOrganizationName(context).trim()
                val institutionCode = SecureConfig.getOrganizationCode(context).trim()
                val registeredMobile = SecureConfig.getRegisteredMobile(context).trim()
                if (institutionId.isBlank() || institutionName.isBlank() || institutionCode.isBlank()) {
                    throw IOException("INSTITUTION_DETAILS_MISSING: Verify the institution again before activation.")
                }

                val body = JSONObject()
                    .put("companyApiKey", companyApiKey)
                    .put("deviceCode", deviceCode)
                    .put("device_code", deviceCode)
                    .put("institutionId", institutionId)
                    .put("institution_id", institutionId)
                    .put("institutionName", institutionName)
                    .put("institution_name", institutionName)
                    .put("institutionCode", institutionCode)
                    .put("institution_code", institutionCode)
                    .put("registeredMobileNumber", registeredMobile)

                val response = request(
                    endpoint = buildEndpoint(getServerUrl(context), DEVICE_ACTIVATE_PATH),
                    method = "POST",
                    bearerToken = companyApiKey,
                    body = body,
                    allowHttpErrors = true
                )
                val httpStatus = response.optInt("_httpStatus", 200)
                if (httpStatus !in 200..299 || !response.optBoolean("ok", false)) {
                    SecureConfig.clearDeviceToken(context)
                    throw IOException(buildServerError(response, httpStatus, "DEVICE_ACTIVATION_REJECTED"))
                }

                val data = response.optJSONObject("data") ?: response
                val returnedCode = firstNonBlank(
                    data.optString("institution_code"), data.optString("institutionCode"),
                    data.optString("organization_code"), data.optString("deviceInstitutionCode")
                )
                val returnedId = firstNonBlank(
                    data.optString("institution_id"), data.optString("institutionId"),
                    data.optString("organization_id")
                )
                if (returnedCode.isNotBlank() && !returnedCode.equals(institutionCode, true)) {
                    SecureConfig.clearDeviceToken(context)
                    throw IOException("ACTIVATION_INSTITUTION_MISMATCH: Server activated a different institution.")
                }
                if (returnedId.isNotBlank() && returnedId != institutionId) {
                    SecureConfig.clearDeviceToken(context)
                    throw IOException("ACTIVATION_INSTITUTION_MISMATCH: Server institution ID does not match this gateway.")
                }
                val deviceToken = firstNonBlank(
                    response.optString("deviceToken"), response.optString("device_token"),
                    data.optString("deviceToken"), data.optString("device_token")
                )
                if (deviceToken.isBlank()) {
                    SecureConfig.clearDeviceToken(context)
                    throw IOException("DEVICE_TOKEN_MISSING: Main Server did not return a device token.")
                }
                if (!SecureConfig.saveDeviceToken(context, deviceToken)) {
                    SecureConfig.clearDeviceToken(context)
                    throw IOException("DEVICE_TOKEN_SAVE_FAILED: Could not securely save the device token.")
                }
                response
            }
            callbackSafely(callback, result)
        }
    }

    /**
     * Poll Main Server for one queued SMS job.
     */
    fun pollGatewayJob(
        callback: (Result<JSONObject>) -> Unit
    ) {
        executor.execute {
            val result = runCatching {
                val context = AppContextHolder.context

                if (!SecureConfig.isOrganizationVerified(context)) {
                    throw IOException("INSTITUTION_NOT_VERIFIED: SMS polling is blocked until an institution is verified.")
                }
                val response = request(
                    endpoint = buildEndpoint(getServerUrl(context), DEVICE_POLL_PATH),
                    method = "POST",
                    gatewayToken = getDeviceToken(context),
                    body = JSONObject()
                        .put("institution_id", SecureConfig.getOrganizationId(context))
                        .put("institution_code", SecureConfig.getOrganizationCode(context))
                        .put("device_code", SecureConfig.getDeviceCode(context))
                )

                ensureSuccess(
                    response,
                    "GATEWAY_POLL_FAILED: Could not retrieve the SMS queue."
                )

                response
            }

            callbackSafely(callback, result)
        }
    }

    /**
     * Report the Android SMS sending result to Main Server.
     */
    fun submitGatewayResult(
        jobId: Long,
        requestId: String,
        success: Boolean,
        providerMessageId: String? = null,
        errorCode: String? = null,
        callback: (Result<JSONObject>) -> Unit
    ) {
        executor.execute {
            val result = runCatching {
                if (jobId <= 0L) {
                    throw IOException("INVALID_JOB_ID: Invalid SMS job ID.")
                }

                if (!requestId.matches(Regex("^[a-fA-F0-9]{32}$"))) {
                    throw IOException("INVALID_REQUEST_ID: Request ID must be a 32-character hexadecimal value.")
                }

                val context = AppContextHolder.context

                val body = JSONObject()
                    .put("job_id", jobId)
                    .put("request_id", requestId)
                    .put("success", success)
                    .put("institution_id", SecureConfig.getOrganizationId(context))
                    .put("institution_code", SecureConfig.getOrganizationCode(context))
                    .put("device_code", SecureConfig.getDeviceCode(context))
                    .put("provider_message_id", providerMessageId?.trim()?.take(150) ?: "")
                    .put(
                        "error_code",
                        errorCode?.trim()?.replace(Regex("[^A-Za-z0-9_.-]"), "_")?.take(100) ?: ""
                    )

                val response = request(
                    endpoint = buildEndpoint(
                        getServerUrl(context),
                        DEVICE_RESULT_PATH
                    ),
                    method = "POST",
                    gatewayToken = getDeviceToken(context),
                    body = body
                )

                ensureSuccess(
                    response,
                    "SMS_RESULT_REPORT_FAILED: Could not report the SMS result."
                )

                response
            }

            callbackSafely(callback, result)
        }
    }

    /**
     * Existing unauthenticated GET method retained for compatibility.
     */
    fun get(
        endpoint: String,
        callback: (Result<JSONObject>) -> Unit
    ) {
        executor.execute {
            val result = runCatching {
                request(
                    endpoint = buildEndpoint(
                        getServerUrl(AppContextHolder.context),
                        endpoint
                    ),
                    method = "GET"
                )
            }

            callbackSafely(callback, result)
        }
    }

    /**
     * Existing GET method using device-token authentication.
     */
    fun getConfigured(
        endpoint: String,
        callback: (Result<JSONObject>) -> Unit
    ) {
        executor.execute {
            val result = runCatching {
                request(
                    endpoint = buildEndpoint(
                        getServerUrl(AppContextHolder.context),
                        endpoint
                    ),
                    method = "GET",
                    bearerToken = getDeviceToken(
                        AppContextHolder.context
                    )
                )
            }

            callbackSafely(callback, result)
        }
    }

    /**
     * Existing POST method using device-token authentication.
     */
    fun postConfigured(
        endpoint: String,
        body: JSONObject,
        callback: (Result<JSONObject>) -> Unit
    ) {
        executor.execute {
            val result = runCatching {
                request(
                    endpoint = buildEndpoint(
                        getServerUrl(AppContextHolder.context),
                        endpoint
                    ),
                    method = "POST",
                    bearerToken = getDeviceToken(
                        AppContextHolder.context
                    ),
                    body = body
                )
            }

            callbackSafely(callback, result)
        }
    }

    /**
     * Existing explicit-credentials GET method retained for compatibility.
     */
    fun get(
        endpoint: String,
        apiToken: String,
        deviceId: String,
        callback: (Result<JSONObject>) -> Unit
    ) {
        executor.execute {
            val result = runCatching {
                request(
                    endpoint = endpoint,
                    method = "GET",
                    bearerToken = apiToken,
                    deviceId = deviceId
                )
            }

            callbackSafely(callback, result)
        }
    }

    private fun firstNonBlank(vararg values: String): String =
        values.firstOrNull { it.isNotBlank() && !it.equals("null", true) }
            ?.trim()
            .orEmpty()

    private fun normalizeMobile(value: String): String {
        val digits = value.filter { it.isDigit() }
        return when {
            digits.length == 14 && digits.startsWith("0091") -> digits.takeLast(10)
            digits.length == 12 && digits.startsWith("91") -> digits.takeLast(10)
            digits.length == 11 && digits.startsWith("0") -> digits.takeLast(10)
            else -> digits
        }
    }

    private fun getServerUrl(context: Context): String {
        val configured = SecureConfig
            .getServerUrl(context)
            .trim()

        return configured.takeIf { it.isNotBlank() }
            ?: DEFAULT_SERVER_URL
    }

    private fun getDeviceToken(context: Context): String {
        return SecureConfig
            .getDeviceToken(context)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: throw IOException(
                "DEVICE_NOT_ACTIVATED: Activate the device before polling."
            )
    }

    private fun ensureSuccess(
        response: JSONObject,
        defaultMessage: String
    ) {
        val httpStatus = response.optInt("_httpStatus", 200)

        if (
            httpStatus !in 200..299 ||
            !response.optBoolean("ok", false)
        ) {
            throw IOException(
                buildServerError(
                    response,
                    httpStatus,
                    defaultMessage
                )
            )
        }
    }

    /**
     * Extract a safe, structured server error for the app UI.
     * The API must never send secrets or database credentials here.
     */
    private fun buildServerError(
        response: JSONObject,
        httpStatus: Int,
        fallbackCode: String
    ): String {
        val code = response
            .optString("code", fallbackCode)
            .trim()
            .takeIf { it.isNotBlank() }
            ?: fallbackCode

        val message = response
            .optString("message", "Request was rejected by the server.")
            .trim()
            .takeIf { it.isNotBlank() }
            ?: "Request was rejected by the server."

        return "$code: $message (HTTP $httpStatus)"
    }

    /**
     * Shared HTTPS request handler.
     *
     * allowHttpErrors=true returns the JSON body for non-2xx responses,
     * allowing diagnostic and activation screens to display server codes.
     * Normal API calls continue to fail on non-2xx responses.
     */
    private fun request(
        endpoint: String,
        method: String,
        bearerToken: String? = null,
        gatewayToken: String? = null,
        deviceId: String? = null,
        body: JSONObject? = null,
        allowHttpErrors: Boolean = false
    ): JSONObject {

        val parsedUrl = try {
            URL(endpoint)
        } catch (e: Exception) {
            throw IOException("INVALID_API_URL: API URL is invalid.", e)
        }

        if (!parsedUrl.protocol.equals("https", ignoreCase = true)) {
            throw IOException(
                "HTTPS_REQUIRED: A secure HTTPS connection is required."
            )
        }

        if (parsedUrl.userInfo != null) {
            throw IOException(
                "INVALID_SERVER_CONFIGURATION: URL credentials are not allowed."
            )
        }

        val expectedHost = URI(DEFAULT_SERVER_URL).host

        if (
            !parsedUrl.host.equals(expectedHost, ignoreCase = true) ||
            (parsedUrl.port != -1 && parsedUrl.port != 443)
        ) {
            throw IOException(
                "UNRECOGNIZED_SERVER: The configured API host is not allowed."
            )
        }

        if (method != "GET" && method != "POST") {
            throw IOException(
                "UNSUPPORTED_HTTP_METHOD: API request method is not supported."
            )
        }

        val connection = (
            parsedUrl.openConnection() as HttpURLConnection
        ).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT
            readTimeout = READ_TIMEOUT
            instanceFollowRedirects = false
            useCaches = false

            setRequestProperty("Accept", "application/json")

            if (!bearerToken.isNullOrBlank()) {
                setRequestProperty(
                    "Authorization",
                    "Bearer $bearerToken"
                )
            }

            if (!gatewayToken.isNullOrBlank()) {
                setRequestProperty(
                    "X-Gateway-Token",
                    gatewayToken
                )
            }

            if (!deviceId.isNullOrBlank()) {
                setRequestProperty(
                    "X-Device-ID",
                    deviceId
                )
            }

            if (body != null) {
                doOutput = true

                setRequestProperty(
                    "Content-Type",
                    "application/json; charset=utf-8"
                )
            }
        }

        try {
            if (body != null) {
                connection.outputStream
                    .bufferedWriter(Charsets.UTF_8)
                    .use { writer ->
                        writer.write(body.toString())
                    }
            }

            val responseCode = try {
                connection.responseCode
            } catch (e: Exception) {
                throw IOException(
                    "API_CONNECTION_FAILED: Could not obtain the server response.",
                    e
                )
            }

            val stream = if (responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }

            val responseBody = stream
                ?.bufferedReader(Charsets.UTF_8)
                ?.use { reader -> reader.readText() }
                ?: ""

            if (responseBody.isBlank()) {
                if (!allowHttpErrors && responseCode !in 200..299) {
                    throw IOException(
                        "HTTP_REQUEST_FAILED: Server returned HTTP $responseCode."
                    )
                }

                return JSONObject()
                    .put("_httpStatus", responseCode)
                    .put(
                        "ok",
                        responseCode in 200..299
                    )
                    .put(
                        "code",
                        if (responseCode in 200..299) {
                            "EMPTY_SERVER_RESPONSE"
                        } else {
                            "HTTP_$responseCode"
                        }
                    )
                    .put(
                        "message",
                        if (responseCode in 200..299) {
                            "Server returned an empty response."
                        } else {
                            "Server rejected the request."
                        }
                    )
            }

            val response = try {
                JSONObject(responseBody)
            } catch (e: Exception) {
                throw IOException(
                    "INVALID_SERVER_RESPONSE: Server did not return valid JSON.",
                    e
                )
            }

            response.put("_httpStatus", responseCode)

            if (!allowHttpErrors && responseCode !in 200..299) {
                throw IOException(
                    buildServerError(
                        response,
                        responseCode,
                        "HTTP_REQUEST_FAILED"
                    )
                )
            }

            return response

        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException(
                "API_CONNECTION_FAILED: The request could not be completed.",
                e
            )
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Build an endpoint from the configured HTTPS server URL.
     * Only relative API paths are accepted.
     */
    private fun buildEndpoint(
        serverUrl: String,
        endpoint: String
    ): String {
        val path = endpoint.trim()

        require(path.isNotBlank()) {
            "API endpoint cannot be empty."
        }

        require(
            !path.startsWith("//") &&
            !path.contains('\\') &&
            !path.contains("\r") &&
            !path.contains("\n") &&
            !path.startsWith("https://", ignoreCase = true) &&
            !path.startsWith("http://", ignoreCase = true)
        ) {
            "Invalid API endpoint. Use a relative path."
        }

        val base = URI(serverUrl.trim().trimEnd('/'))

        require(
            base.scheme.equals("https", ignoreCase = true) &&
            !base.host.isNullOrBlank() &&
            base.userInfo == null &&
            base.rawQuery == null &&
            base.rawFragment == null
        ) {
            "Invalid HTTPS server configuration."
        }

        return "${base.toASCIIString()}/${path.trimStart('/')}"
    }

    private fun <T> callbackSafely(
        callback: (Result<T>) -> Unit,
        result: Result<T>
    ) {
        try {
            callback(result)
        } catch (_: Exception) {
            // A UI callback exception must not terminate the worker.
        }
    }
}
