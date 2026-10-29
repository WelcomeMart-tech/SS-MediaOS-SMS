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
    private const val READ_TIMEOUT = 15000

    private const val DEFAULT_SERVER_URL =
        "https://otp.ssmediaos.in"

    private const val ORGANIZATION_VERIFY_PATH =
        "/api/v1/sms/organization_verify.php"

    private const val DEVICE_ACTIVATE_PATH =
        "/api/v1/sms/device_activate.php"

    private const val DEVICE_POLL_PATH =
        "/api/v1/sms/device_poll.php"

    private const val DEVICE_RESULT_PATH =
        "/api/v1/sms/device_result.php"


    /**
     * Verify Organization Code with the server.
     *
     * The organization code is checked by the PHP API.
     * Local storage alone is NOT treated as verification.
     *
     * Expected successful server response:
     *
     * {
     *   "ok": true,
     *   "verified": true,
     *   "organizationName": "Registered Organization"
     * }
     *
     * The PHP endpoint must validate the code against the
     * actual organization database and reject inactive records.
     */
    fun verifyOrganization(
        organizationCode: String,
        registeredMobile: String,
        callback: (Result<JSONObject>) -> Unit
    ) {
        executor.execute {

            val result = runCatching {

                val context = AppContextHolder.context

                val code = organizationCode.trim()
                val mobile = registeredMobile.trim()

                if (code.isBlank()) {
                    throw IOException(
                        "Please enter your Organization Code."
                    )
                }

                if (mobile.isBlank()) {
                    throw IOException(
                        "Please enter your registered mobile number."
                    )
                }

                if (!mobile.matches(Regex("^[0-9]{10,15}$"))) {
                    throw IOException(
                        "Please enter a valid registered mobile number."
                    )
                }

                val deviceCode = SecureConfig
                    .getDeviceCode(context)
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: throw IOException(
                        "Device ID is unavailable. Please restart the app."
                    )

                val body = JSONObject()
                    .put("organizationCode", code)
                    .put("registeredMobile", mobile)
                    .put("deviceCode", deviceCode)

                val response = request(
                    endpoint = buildEndpoint(
                        getServerUrl(context),
                        ORGANIZATION_VERIFY_PATH
                    ),
                    method = "POST",
                    body = body
                )

                val verified = response.optBoolean(
                    "verified",
                    false
                )

                if (!response.optBoolean("ok", false) || !verified) {

                    val errorCode = response
                        .optString("errorCode", "")
                        .uppercase()

                    val message = when (errorCode) {
                        "ORG_NOT_FOUND" ->
                            "Organization Code was not found."

                        "ORG_INACTIVE" ->
                            "This organization is inactive. Please contact support."

                        "MOBILE_MISMATCH" ->
                            "Mobile number does not match the registered organization."

                        "ORG_UNAUTHORIZED" ->
                            "This organization is not authorized to use the SMS gateway."

                        "DEVICE_DISABLED" ->
                            "This gateway device has been disabled."

                        else ->
                            "Organization verification failed. Please check your details."
                    }

                    throw IOException(message)
                }

                val organizationName = response
                    .optString("organizationName", "")
                    .trim()

                if (organizationName.isBlank()) {
                    throw IOException(
                        "The server did not return the registered organization name."
                    )
                }

                /*
                 * Save organization identity only after the server
                 * confirms verification.
                 */
                val identitySaved = SecureConfig
                    .saveOrganizationIdentity(
                        context,
                        code,
                        mobile
                    )

                if (!identitySaved) {
                    throw IOException(
                        "Organization was verified, but its details could not be saved."
                    )
                }

                val verificationSaved = SecureConfig
                    .saveOrganizationVerification(
                        context,
                        true,
                        organizationName
                    )

                if (!verificationSaved) {
                    SecureConfig.saveOrganizationVerification(
                        context,
                        false,
                        ""
                    )

                    throw IOException(
                        "Could not save the organization verification status."
                    )
                }

                response
            }

            callbackSafely(callback, result)
        }
    }


    /**
     * Activate the registered Android SMS gateway device.
     *
     * NOTE:
     * The PHP activation endpoint must accept the same request
     * fields used here. Activation must be rejected unless the
     * organization is verified and authorized on the server.
     */
    fun activate(
        callback: (Result<JSONObject>) -> Unit
    ) {
        executor.execute {

            val result = runCatching {

                val context = AppContextHolder.context

                if (!SecureConfig.isOrganizationVerified(context)) {
                    throw IOException(
                        "Please verify your Organization Code before activation."
                    )
                }

                val serverUrl = getServerUrl(context)

                val deviceCode = SecureConfig
                    .getDeviceCode(context)
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: throw IOException(
                        "Device ID is unavailable. Please check device configuration."
                    )

                val activationToken = SecureConfig
                    .getCompanyApiKey(context)
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: throw IOException(
                        "Please enter the authorized activation token in settings."
                    )

                val body = JSONObject()
                    .put("deviceCode", deviceCode)
                    .put("activationToken", activationToken)

                val response = request(
                    endpoint = buildEndpoint(
                        serverUrl,
                        DEVICE_ACTIVATE_PATH
                    ),
                    method = "POST",
                    body = body
                )

                if (!response.optBoolean("ok", false)) {
                    throw IOException(
                        "Device activation was rejected. Please check the activation details."
                    )
                }

                val deviceToken = response
                    .optString("deviceToken", "")
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

                if (!SecureConfig.isOrganizationVerified(context)) {
                    throw IOException(
                        "Organization verification is required before sending SMS."
                    )
                }

                val deviceToken = getDeviceToken(context)

                val response = request(
                    endpoint = buildEndpoint(
                        getServerUrl(context),
                        DEVICE_POLL_PATH
                    ),
                    method = "POST",
                    gatewayToken = deviceToken,
                    body = JSONObject()
                )

                ensureSuccess(
                    response,
                    "Gateway polling failed."
                )

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
                val deviceToken = getDeviceToken(context)

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

                request(
                    endpoint = buildEndpoint(
                        getServerUrl(context),
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

                val context = AppContextHolder.context
                val deviceToken = getDeviceToken(context)

                request(
                    endpoint = buildEndpoint(
                        getServerUrl(context),
                        endpoint
                    ),
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
                val deviceToken = getDeviceToken(context)

                request(
                    endpoint = buildEndpoint(
                        getServerUrl(context),
                        endpoint
                    ),
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


    private fun getServerUrl(context: Context): String {

        return SecureConfig.getServerUrl(context)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: DEFAULT_SERVER_URL
    }


    private fun getDeviceToken(context: Context): String {

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
            throw IOException(
                "Secure HTTPS connection is required."
            )
        }

        if (parsedUrl.userInfo != null) {
            throw IOException("Invalid server configuration.")
        }

        val expectedHost = URI(DEFAULT_SERVER_URL).host

        if (!parsedUrl.host.equals(expectedHost, ignoreCase = true) ||
            (parsedUrl.port != -1 && parsedUrl.port != 443)
        ) {
            throw IOException(
                "Unrecognized server. Check the app configuration."
            )
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

            // Do not follow redirects carrying authentication data.
            instanceFollowRedirects = false

            setRequestProperty(
                "Accept",
                "application/json"
            )

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

                val errorCode = try {
                    JSONObject(responseBody)
                        .optString("errorCode", "")
                } catch (_: Exception) {
                    ""
                }

                val message = when (errorCode.uppercase()) {
                    "ORG_NOT_FOUND" ->
                        "Organization Code was not found."

                    "ORG_INACTIVE" ->
                        "This organization is inactive."

                    "MOBILE_MISMATCH" ->
                        "Registered mobile number does not match."

                    "ORG_UNAUTHORIZED" ->
                        "Organization is not authorized."

                    else ->
                        "Server request failed (HTTP $responseCode). Please try again."
                }

                throw IOException(message)
            }

            if (responseBody.isBlank()) {
                throw IOException(
                    "Server returned an empty response."
                )
            }

            return try {
                JSONObject(responseBody)
            } catch (e: Exception) {
                throw IOException(
                    "Server response is not valid JSON.",
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

        val base = URI(
            serverUrl.trim().trimEnd('/')
        )

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
