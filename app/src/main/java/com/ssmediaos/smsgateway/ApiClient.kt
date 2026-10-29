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
                val code = institutionCode.trim()
                val mobile = registeredMobileNumber.trim()

                if (apiKey.isBlank()) {
                    throw IOException(
                        "COMPANY_API_KEY_REQUIRED: Enter the Company API Key."
                    )
                }

                if (code.isBlank()) {
                    throw IOException(
                        "INSTITUTION_CODE_REQUIRED: Enter the Institution Code."
                    )
                }

                if (
                    mobile.isNotBlank() &&
                    (
                        mobile.any { !it.isDigit() } ||
                        mobile.length !in 8..15
                    )
                ) {
                    throw IOException(
                        "INVALID_REGISTERED_MOBILE: Check the registered mobile number."
                    )
                }

                /*
                 * Remove an old activation token before a new diagnostic.
                 * A previous successful verification must not authorize
                 * a new institution code or API key.
                 */
                SecureConfig.clearDeviceToken(context)

                val deviceCode = SecureConfig
                    .getDeviceCode(context)
                    .trim()

                val body = JSONObject()
                    .put("companyApiKey", apiKey)
                    .put("institutionCode", code)
                    .put("organizationCode", code)
                    .put("registeredMobileNumber", mobile)
                    .put("deviceCode", deviceCode)

                request(
                    endpoint = buildEndpoint(
                        getServerUrl(context),
                        GATEWAY_DIAGNOSTICS_PATH
                    ),
                    method = "POST",
                    bearerToken = apiKey,
                    body = body,
                    allowHttpErrors = true
                )
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
    fun activate(
        callback: (Result<JSONObject>) -> Unit
    ) {
        executor.execute {
            val result = runCatching {
                val context = AppContextHolder.context

                val serverUrl = getServerUrl(context)

                val deviceCode = SecureConfig
                    .getDeviceCode(context)
                    .trim()
                    .takeIf { it.isNotEmpty() }
                    ?: throw IOException(
                        "DEVICE_CODE_REQUIRED: Device ID is unavailable."
                    )

                val companyApiKey = SecureConfig
                    .getCompanyApiKey(context)
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: throw IOException(
                        "COMPANY_API_KEY_REQUIRED: Enter the Company API Key."
                    )

                val body = JSONObject()
                    .put("deviceCode", deviceCode)
                    .put("companyApiKey", companyApiKey)

                val response = request(
                    endpoint = buildEndpoint(
                        serverUrl,
                        DEVICE_ACTIVATE_PATH
                    ),
                    method = "POST",
                    bearerToken = companyApiKey,
                    body = body,
                    allowHttpErrors = true
                )

                val httpStatus = response.optInt("_httpStatus", 200)

                if (
                    httpStatus !in 200..299 ||
                    !response.optBoolean("ok", false)
                ) {
                    SecureConfig.clearDeviceToken(context)

                    throw IOException(
                        buildServerError(
                            response,
                            httpStatus,
                            "DEVICE_ACTIVATION_REJECTED"
                        )
                    )
                }

                val deviceToken = response
                    .optString("deviceToken")
                    .trim()

                if (deviceToken.isBlank()) {
                    SecureConfig.clearDeviceToken(context)

                    throw IOException(
                        "DEVICE_TOKEN_MISSING: Server did not return a device token."
                    )
                }

                if (!SecureConfig.saveDeviceToken(context, deviceToken)) {
                    SecureConfig.clearDeviceToken(context)

                    throw IOException(
                        "DEVICE_TOKEN_SAVE_FAILED: Could not securely save the device token."
                    )
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

                val response = request(
                    endpoint = buildEndpoint(
                        getServerUrl(context),
                        DEVICE_POLL_PATH
                    ),
                    method = "POST",
                    gatewayToken = getDeviceToken(context),
                    body = JSONObject()
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

                if (requestId.isBlank()) {
                    throw IOException("REQUEST_ID_REQUIRED: Request ID is missing.")
                }

                val context = AppContextHolder.context

                val body = JSONObject()
                    .put("job_id", jobId)
                    .put("request_id", requestId)
                    .put("success", success)
                    .put("provider_message_id", providerMessageId ?: "")
                    .put("error_code", errorCode ?: "")

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
