package com.ssmediaos.smsgateway

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

object ApiClient {

    private val executor = Executors.newSingleThreadExecutor()

    private const val CONNECT_TIMEOUT = 15000
    private const val READ_TIMEOUT = 15000

    /**
     * Existing GET method retained for compatibility.
     * Uses the configured server URL and adds available credentials.
     */
    fun get(
        endpoint: String,
        callback: (Result<JSONObject>) -> Unit
    ) {
        executor.execute {
            val result = runCatching {
                request(endpoint)
            }

            callbackSafely(callback, result)
        }
    }

    /**
     * GET request using the saved Server URL, API Token and Device ID.
     *
     * endpoint must be a relative API path, for example:
     * "/api/status"
     *
     * The actual endpoint and authentication header names must match
     * your server implementation.
     */
    fun getConfigured(
        endpoint: String,
        callback: (Result<JSONObject>) -> Unit
    ) {
        executor.execute {
            val result = runCatching {
                val serverUrl = SecureConfig.getServerUrl(
                    AppContextHolder.context
                ) ?: throw IOException("Server URL is not configured.")

                val token = SecureConfig.getApiToken(
                    AppContextHolder.context
                ) ?: throw IOException("API Token is not configured.")

                val deviceId = SecureConfig.getDeviceId(
                    AppContextHolder.context
                ) ?: throw IOException("Device ID is not configured.")

                request(
                    endpoint = buildEndpoint(serverUrl, endpoint),
                    apiToken = token,
                    deviceId = deviceId
                )
            }

            callbackSafely(callback, result)
        }
    }

    /**
     * Explicit-credentials GET method.
     * Does not depend on global application context.
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
                    apiToken = apiToken,
                    deviceId = deviceId
                )
            }

            callbackSafely(callback, result)
        }
    }

    private fun request(
        endpoint: String,
        apiToken: String? = null,
        deviceId: String? = null
    ): JSONObject {
        val parsedUrl = try {
            URL(endpoint)
        } catch (e: Exception) {
            throw IOException("Invalid API URL.", e)
        }

        require(
            parsedUrl.protocol.equals("https", ignoreCase = true)
        ) {
            "Use a valid HTTPS API URL."
        }

        val connection = (parsedUrl.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT
            readTimeout = READ_TIMEOUT
            instanceFollowRedirects = false

            setRequestProperty("Accept", "application/json")

            if (!apiToken.isNullOrBlank()) {
                // Confirm this header format with your server API.
                setRequestProperty("Authorization", "Bearer $apiToken")
            }

            if (!deviceId.isNullOrBlank()) {
                // Confirm this header name with your server API.
                setRequestProperty("X-Device-ID", deviceId)
            }
        }

        try {
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
                    JSONObject(responseBody).optString(
                        "message",
                        JSONObject(responseBody).optString("error", "")
                    )
                } catch (_: Exception) {
                    ""
                }

                val detail = if (serverMessage.isNotBlank()) {
                    ": $serverMessage"
                } else {
                    ""
                }

                throw IOException("Server returned HTTP $responseCode$detail")
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

        require(!path.startsWith("//")) {
            "Invalid API endpoint."
        }

        val base = serverUrl.trim().trimEnd('/')

        return if (path.startsWith("https://", true)) {
            path
        } else {
            require(!path.startsWith("http://", true)) {
                "Use HTTPS for API requests."
            }

            "$base/${path.trimStart('/')}"
        }
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
