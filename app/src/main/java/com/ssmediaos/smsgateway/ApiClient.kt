package com.ssmediaos.smsgateway

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.Executors

object ApiClient {

    private val executor = Executors.newSingleThreadExecutor()

    private const val CONNECT_TIMEOUT = 15000
    private const val READ_TIMEOUT = 15000

    private const val DEFAULT_SERVER_URL =
        "https://otp.ssmediaos.in"

    private const val DEVICE_ACTIVATE_PATH =
        "/api/v1/sms/device_activate.php"

    private const val DEVICE_POLL_PATH =
        "/api/v1/sms/device_poll.php"

    private const val DEVICE_RESULT_PATH =
        "/api/v1/sms/device_result.php"

    /**
     * Activate a registered Android gateway device.
     *
     * IMPORTANT:
     * - Device ID is read dynamically from SecureConfig.
     * - Company API token is read from SecureConfig.
     * - Activation secret must match the server configuration.
     *
     * Expected server response:
     * {
     *   "ok": true,
     *   "deviceToken": "server-issued-token"
     * }
     */
    fun activate(
        callback: (Result<JSONObject>) -> Unit
    ) {
        executor.execute {
            val result = runCatching {
                val context = AppContextHolder.context

                val serverUrl = SecureConfig.getServerUrl(context)
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: DEFAULT_SERVER_URL

                val deviceCode = SecureConfig.getDeviceCode(context)
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: throw IOException(
                        "Device ID is unavailable. Please check device configuration."
                    )

                val companyApiKey =
                    SecureConfig.getCompanyApiKey(context)
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                        ?: throw IOException(
                            "Please enter your Company API Token in settings."
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
                    body = body
                )

                if (!response.optBoolean("ok", false)) {
                    // Do not display arbitrary server diagnostics in the app UI.
                    throw IOException(
                        "Device activation was rejected. Check the Company API Key and server configuration."
                    )
                }

                val deviceToken = response.optString("deviceToken")
                    .trim()

                if (deviceToken.isBlank()) {
                    throw IOException(
                        "Server activation response did not contain a device token."
                    )
                }

                if (!SecureConfig.saveDeviceToken(context, deviceToken)) {
                    throw IOException(
                        "Activation succeeded, but the device token could not be saved."
                    )
                }

                response
            }

            callbackSafely(callback, result)
        }
    }

    /**
     * Poll the server for one queued SMS job.
     */
    fun pollGatewayJob(
        callback: (Result<JSONObject>) -> Unit
    ) {
        executor.execute {
            val result = runCatching {
                val context = AppContextHolder.context

                val serverUrl = getServerUrl(context)
                val deviceToken = getDeviceToken(context)

                val response = request(
                    endpoint = buildEndpoint(
                        serverUrl,
                        DEVICE_POLL_PATH
                    ),
                    method = "POST",
                    gatewayToken = deviceToken,
                    body = JSONObject()
                )

                ensureSuccess(response, "Gateway polling failed.")

                response
            }

            callbackSafely(callback, result)
        }
    }

    /**
     * Submit the result of an SMS job.
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
                    throw IOException("Invalid SMS job ID.")
                }

                if (requestId.isBlank()) {
                    throw IOException("Request ID is missing.")
                }

                val context = AppContextHolder.context
                val serverUrl = getServerUrl(context)
                val deviceToken = getDeviceToken(context)

                val body = JSONObject()
                    .put("job_id", jobId)
                    .put("request_id", requestId)
                    .put("success", success)
                    .put("provider_message_id", providerMessageId ?: "")
                    .put("error_code", errorCode ?: "")

                val response = request(
                    endpoint = buildEndpoint(
                        serverUrl,
                        DEVICE_RESULT_PATH
                    ),
                    method = "POST",
                    gatewayToken = deviceToken,
                    body = body
                )

                ensureSuccess(
                    response,
                    "Failed to submit SMS result."
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
                val context = AppContextHolder.context
                val serverUrl = getServerUrl(context)

                request(
                    endpoint = buildEndpoint(serverUrl, endpoint),
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
                val context = AppContextHolder.context
                val serverUrl = getServerUrl(context)
                val deviceToken = getDeviceToken(context)

                request(
                    endpoint = buildEndpoint(serverUrl, endpoint),
                    method = "GET",
                    bearerToken = deviceToken
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
                val context = AppContextHolder.context
                val serverUrl = getServerUrl(context)
                val deviceToken = getDeviceToken(context)

                request(
                    endpoint = buildEndpoint(serverUrl, endpoint),
                    method = "POST",
                    bearerToken = deviceToken,
                    body = body
                )
            }

            callbackSafely(callback, result)
        }
    }

    /**
     * Existing explicit-credentials GET method retained.
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

    private fun getServerUrl(context: android.content.Context): String {
        return SecureConfig.getServerUrl(context)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: DEFAULT_SERVER_URL
    }

    private fun getDeviceToken(
        context: android.content.Context
    ): String {
        return SecureConfig.getDeviceToken(context)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: throw IOException(
                "Device is not activated. Please activate it first."
            )
    }

    private fun ensureSuccess(
        response: JSONObject,
        defaultMessage: String
    ) {
        if (!response.optBoolean("ok", false)) {
            // Server-provided error text may contain internal implementation details.
            throw IOException(defaultMessage)
        }
    }

    /**
     * Shared HTTPS request handler.
     */
    private fun request(
        endpoint: String,
        method: String,
        bearerToken: String? = null,
        gatewayToken: String? = null,
        deviceId: String? = null,
        body: JSONObject? = null
    ): JSONObject {

        val parsedUrl = try {
            URL(endpoint)
        } catch (e: Exception) {
            throw IOException("Invalid API URL.", e)
        }

        if (!parsedUrl.protocol.equals("https", ignoreCase = true)) {
            throw IOException("Secure connection required. Please try again.")
        }

        if (parsedUrl.userInfo != null) {
            throw IOException("Invalid server configuration.")
        }

        // This app is intended to communicate only with the configured SS MediaOS API.
        // This also protects the explicit-credentials compatibility overload from
        // accidentally sending tokens to a different HTTPS host.
        val expectedHost = URI(DEFAULT_SERVER_URL).host
        if (!parsedUrl.host.equals(expectedHost, ignoreCase = true) ||
            (parsedUrl.port != -1 && parsedUrl.port != 443)) {
            throw IOException("Unrecognized server. Check the app configuration.")
        }

        if (method != "GET" && method != "POST") {
            throw IOException("Unsupported API request.")
        }

        val connection = (
            parsedUrl.openConnection() as HttpURLConnection
        ).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT
            readTimeout = READ_TIMEOUT
            instanceFollowRedirects = false

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
                setRequestProperty("X-Device-ID", deviceId)
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

            val responseCode = connection.responseCode

            val stream = if (responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }

            val responseBody = stream
                ?.bufferedReader(Charsets.UTF_8)
                ?.use { reader -> reader.readText() }
                ?: ""

            if (responseCode !in 200..299) {
                // Keep server diagnostics out of user-visible errors.
                throw IOException(
                    "Server request failed (HTTP $responseCode). Please try again."
                )
            }

            if (responseBody.isBlank()) {
                return JSONObject()
            }

            return try {
                JSONObject(responseBody)
            } catch (e: Exception) {
                throw IOException(
                    "Server response is not a valid JSON object.",
                    e
                )
            }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Build a safe endpoint from the configured HTTPS server URL.
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
                !path.contains("\n")
        ) {
            "Invalid API endpoint."
        }

        require(
            !path.startsWith("https://", ignoreCase = true) &&
                !path.startsWith("http://", ignoreCase = true)
        ) {
            "Provide a relative API path, not a full URL."
        }

        val base = URI(serverUrl.trim().trimEnd('/'))

        require(
            base.scheme.equals("https", ignoreCase = true) &&
                !base.host.isNullOrBlank() &&
                base.userInfo == null &&
                base.rawQuery == null &&
                base.rawFragment == null
        ) {
            "Invalid HTTPS server URL."
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
            // Prevent callback exceptions from terminating the worker.
        }
    }
}
