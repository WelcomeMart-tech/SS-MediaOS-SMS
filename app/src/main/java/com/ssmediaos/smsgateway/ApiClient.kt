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

    /**
     * Activate a registered device using the SS MediaOS server.
     *
     * Expected request:
     * POST /v1/device/activate
     * {
     *   "deviceCode": "...",
     *   "activationToken": "..."
     * }
     *
     * Expected response:
     * {
     *   "deviceToken": "..."
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
                    ?: throw IOException("Device Code is not configured.")

                val activationToken =
                    SecureConfig.getActivationToken(context)
                        ?: throw IOException("Activation Token is not configured.")

                val response = request(
                    endpoint = buildEndpoint(
                        serverUrl,
                        "/v1/device/activate"
                    ),
                    method = "POST",
                    body = JSONObject()
                        .put("deviceCode", deviceCode)
                        .put("activationToken", activationToken)
                )

                val deviceToken = response.optString("deviceToken")
                    .trim()

                if (deviceToken.isBlank()) {
                    throw IOException(
                        "Activation response did not contain a device token."
                    )
                }

                if (!SecureConfig.saveDeviceToken(context, deviceToken)) {
                    throw IOException(
                        "Device activated, but its token could not be saved."
                    )
                }

                response
            }

            callbackSafely(callback, result)
        }
    }

    /**
     * Existing GET method retained for compatibility.
     * Use a relative path such as /health.
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
     * GET request authenticated using the activated device token.
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
     * Authenticated POST request using the activated device token.
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

    private fun request(
        endpoint: String,
        method: String,
        bearerToken: String? = null,
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
                connection.outputStream.bufferedWriter(Charsets.UTF_8).use {
                    it.write(body.toString())
                }
            }

            val responseCode = connection.responseCode

            val stream = if (responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }

            val responseBody = stream?.bufferedReader()?.use {
                it.readText()
            } ?: ""

            if (responseCode !in 200..299) {
                val serverMessage = try {
                    val json = JSONObject(responseBody)
                    json.optString(
                        "message",
                        json.optString("error", "")
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

        // Only allow relative API paths to prevent sending credentials
        // to a different host.
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
            // Keep callback errors from terminating the worker.
        }
    }
}
