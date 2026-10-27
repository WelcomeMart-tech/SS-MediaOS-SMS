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

    private const val DEVICE_ACTIVATE_PATH =
        "/api/v1/sms/device_activate.php"

    private const val DEVICE_POLL_PATH =
        "/api/v1/sms/device_poll.php"

    private const val DEVICE_RESULT_PATH =
        "/api/v1/sms/device_result.php"

    /**
     * Activate the registered Android gateway device.
     *
     * Server response must contain:
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
                    ?: throw IOException("Server URL is not configured.")

                val deviceCode = SecureConfig.getDeviceCode(context)
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: throw IOException("Device Code is not configured.")

                val activationToken =
                    SecureConfig.getActivationToken(context)
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                        ?: throw IOException(
                            "Activation Token is not configured."
                        )

                val response = request(
                    endpoint = buildEndpoint(
                        serverUrl,
                        DEVICE_ACTIVATE_PATH
                    ),
                    method = "POST",
                    body = JSONObject()
                        .put("deviceCode", deviceCode)
                        .put("activationToken", activationToken)
                )

                if (!response.optBoolean("ok", true)) {
                    throw IOException(
                        response.optString(
                            "message",
                            "Device activation failed."
                        )
                    )
                }

                val deviceToken = response.optString("deviceToken")
                    .trim()

                if (deviceToken.isBlank()) {
                    throw IOException(
                        "Activation response did not contain a device token."
                    )
                }

                if (!SecureConfig.saveDeviceToken(context, deviceToken)) {
                    throw IOException(
                        "Server activated the device, but the token could not be saved."
                    )
                }

                response
            }

            callbackSafely(callback, result)
        }
    }

    /**
     * Poll the server for one queued SMS job.
     * Authentication header: X-Gateway-Token
     */
    fun pollGatewayJob(
        callback: (Result<JSONObject>) -> Unit
    ) {
        executor.execute {
            val result = runCatching {
                val context = AppContextHolder.context

                val serverUrl = SecureConfig.getServerUrl(context)
                    ?: throw IOException("Server URL is not configured.")

                val deviceToken = SecureConfig.getDeviceToken(context)
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: throw IOException(
                        "Device is not activated. Activate it first."
                    )

                val response = request(
                    endpoint = buildEndpoint(
                        serverUrl,
                        DEVICE_POLL_PATH
                    ),
                    method = "POST",
                    gatewayToken = deviceToken,
                    body = JSONObject()
                )

                if (!response.optBoolean("ok", false)) {
                    throw IOException(
                        response.optString(
                            "message",
                            "Gateway polling failed."
                        )
                    )
                }

                response
            }

            callbackSafely(callback, result)
        }
    }

    /**
     * Submit the SMS job result to the server.
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

                val serverUrl = SecureConfig.getServerUrl(context)
                    ?: throw IOException("Server URL is not configured.")

                val deviceToken = SecureConfig.getDeviceToken(context)
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: throw IOException(
                        "Device is not activated. Activate it first."
                    )

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

                if (!response.optBoolean("ok", false)) {
                    throw IOException(
                        response.optString(
                            "message",
                            "Failed to submit SMS result."
                        )
                    )
                }

                response
            }

            callbackSafely(callback, result)
        }
    }

    /**
     * Existing unauthenticated GET method retained.
     */
    fun get(
        endpoint: String,
        callback: (Result<JSONObject>) -> Unit
    ) {
        executor.execute {
            val result = runCatching {
                val serverUrl = SecureConfig.getServerUrl(
                    AppContextHolder.context
                ) ?: throw IOException("Server URL is not configured.")

                request(
                    endpoint = buildEndpoint(serverUrl, endpoint),
                    method = "GET"
                )
            }

            callbackSafely(callback, result)
        }
    }

    /**
     * Existing GET method using Bearer authentication.
     */
    fun getConfigured(
        endpoint: String,
        callback: (Result<JSONObject>) -> Unit
    ) {
        executor.execute {
            val result = runCatching {
                val context = AppContextHolder.context

                val serverUrl = SecureConfig.getServerUrl(context)
                    ?: throw IOException("Server URL is not configured.")

                val deviceToken = SecureConfig.getDeviceToken(context)
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: throw IOException(
                        "Device is not activated. Activate it first."
                    )

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
     * Existing POST method using Bearer authentication.
     */
    fun postConfigured(
        endpoint: String,
        body: JSONObject,
        callback: (Result<JSONObject>) -> Unit
    ) {
        executor.execute {
            val result = runCatching {
                val context = AppContextHolder.context

                val serverUrl = SecureConfig.getServerUrl(context)
                    ?: throw IOException("Server URL is not configured.")

                val deviceToken = SecureConfig.getDeviceToken(context)
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: throw IOException(
                        "Device is not activated. Activate it first."
                    )

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
            throw IOException("HTTPS is required for API requests.")
        }

        if (parsedUrl.userInfo != null) {
            throw IOException("API URL must not contain credentials.")
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
                val serverMessage = try {
                    val json = JSONObject(responseBody)
                    json.optString(
                        "message",
                        json.optString(
                            "error",
                            json.optString("detail", "")
                        )
                    )
                } catch (_: Exception) {
                    ""
                }

                val detail = if (serverMessage.isNotBlank()) {
                    ": $serverMessage"
                } else {
                    ""
                }

                throw IOException(
                    "Server returned HTTP $responseCode$detail"
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
                !path.contains('\\')
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

    /**
     * Prevent callback errors from terminating the worker.
     */
    private fun <T> callbackSafely(
        callback: (Result<T>) -> Unit,
        result: Result<T>
    ) {
        try {
            callback(result)
        } catch (_: Exception) {
            // Callback exceptions must not terminate the worker.
        }
    }
}
