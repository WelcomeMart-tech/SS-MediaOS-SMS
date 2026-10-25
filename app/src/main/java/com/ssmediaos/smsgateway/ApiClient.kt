package com.ssmediaos.smsgateway

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

object ApiClient {

    private val executor = Executors.newSingleThreadExecutor()

    /**
     * Makes an HTTP request without blocking the Android main thread.
     *
     * Replace the placeholder server URL with your actual HTTPS API endpoint
     * before using this client.
     */
    fun get(
        endpoint: String,
        callback: (Result<JSONObject>) -> Unit
    ) {
        executor.execute {
            val result = runCatching {
                request(endpoint)
            }

            callback(result)
        }
    }

    private fun request(endpoint: String): JSONObject {
        require(endpoint.startsWith("https://")) {
            "Configure a valid HTTPS server URL."
        }

        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15000
            readTimeout = 15000
            setRequestProperty("Accept", "application/json")
        }

        try {
            val responseCode = connection.responseCode
            val stream = if (responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }

            val responseBody = stream?.bufferedReader()?.use { it.readText() }
                ?: ""

            if (responseCode !in 200..299) {
                throw IOException("Server returned HTTP $responseCode")
            }

            return if (responseBody.isBlank()) {
                JSONObject()
            } else {
                JSONObject(responseBody)
            }
        } finally {
            connection.disconnect()
        }
    }
}
