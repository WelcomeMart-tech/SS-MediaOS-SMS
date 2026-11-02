package com.ssmediaos.smsgateway

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object SecureConfig {

    private const val PREFS_NAME = "ssmediaos_gateway_config"

    const val DEFAULT_SERVER_URL = "https://otp.ssmediaos.in"
    const val DEFAULT_DEVICE_CODE = ""

    private const val KEY_SERVER_URL = "server_url"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_API_TOKEN = "api_token"
    private const val KEY_DEVICE_TOKEN = "device_token"

    private const val KEY_ORGANIZATION_CODE = "organization_code"
    private const val KEY_REGISTERED_MOBILE = "registered_mobile"
    private const val KEY_ORGANIZATION_NAME = "organization_name"
    private const val KEY_ORGANIZATION_ID = "organization_id"
    private const val KEY_ORGANIZATION_STATUS = "organization_status"
    private const val KEY_ORGANIZATION_VERIFIED = "organization_verified"

    // Authoritative company identity returned by Main Server.
    private const val KEY_COMPANY_ID = "company_id"
    private const val KEY_COMPANY_NAME = "company_name"

    // Bind the verification to the SIM selected at verification time.
    private const val KEY_VERIFIED_SIM_SUBSCRIPTION_ID =
        "verified_sim_subscription_id"

    private const val KEY_SELECTED_SIM_SUBSCRIPTION_ID =
        "selected_sim_subscription_id"

    private const val LEGACY_DEVICE_CODE = "Device-01"

    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val KEYSTORE_ALIAS =
        "ssmediaos_gateway_secret_key_v1"

    private const val ENCRYPTED_PREFIX = "enc:v1:"
    private const val GCM_TAG_LENGTH_BITS = 128


    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        )


    /**
     * The application uses the official configured HTTPS server.
     */
    fun getServerUrl(context: Context): String =
        DEFAULT_SERVER_URL


    /**
     * Generate a persistent device ID when one does not exist.
     */
    @Synchronized
    fun getDeviceCode(context: Context): String {

        val prefs = preferences(context)

        val savedId = prefs
            .getString(KEY_DEVICE_ID, null)
            ?.trim()

        if (
            !savedId.isNullOrBlank() &&
            savedId != LEGACY_DEVICE_CODE
        ) {
            return savedId
        }

        val newId = "SSM-${UUID.randomUUID()}"

        val saved = prefs.edit()
            .putString(KEY_DEVICE_ID, newId)
            .remove(KEY_DEVICE_TOKEN)
            .commit()

        if (!saved) {
            throw IllegalStateException(
                "Could not save the gateway device ID."
            )
        }

        return newId
    }


    fun getDeviceId(context: Context): String =
        getDeviceCode(context)


    fun getActivationToken(context: Context): String? =
        readSecret(context, KEY_API_TOKEN)


    fun getApiToken(context: Context): String? =
        getActivationToken(context)


    fun getCompanyApiKey(context: Context): String? =
        getActivationToken(context)


    fun getDeviceToken(context: Context): String? =
        readSecret(context, KEY_DEVICE_TOKEN)

    fun getCompanyId(context: Context): String =
        preferences(context).getString(KEY_COMPANY_ID, "").orEmpty()

    fun getCompanyName(context: Context): String =
        preferences(context).getString(KEY_COMPANY_NAME, "").orEmpty()


    /**
     * Save activation credentials securely.
     *
     * The server URL cannot be changed by this method.
     */
    @Synchronized
    fun saveSettings(
        context: Context,
        serverUrl: String,
        apiToken: String,
        deviceId: String
    ): Boolean {

        val activationToken = apiToken.trim()

        if (activationToken.isBlank()) {
            return false
        }

        val actualDeviceId = getDeviceCode(context)
        val suppliedDeviceId = deviceId.trim()

        if (
            suppliedDeviceId.isNotBlank() &&
            suppliedDeviceId != actualDeviceId &&
            suppliedDeviceId != LEGACY_DEVICE_CODE
        ) {
            return false
        }

        val encryptedToken =
            encryptSecret(activationToken) ?: return false

        val prefs = preferences(context)
        val oldToken = readSecret(context, KEY_API_TOKEN)

        val editor = prefs.edit()
            .putString(KEY_SERVER_URL, DEFAULT_SERVER_URL)
            .putString(KEY_DEVICE_ID, actualDeviceId)
            .putString(KEY_API_TOKEN, encryptedToken)

        if (oldToken != activationToken) {
            editor.remove(KEY_DEVICE_TOKEN)
                .putBoolean(KEY_ORGANIZATION_VERIFIED, false)
                .remove(KEY_ORGANIZATION_NAME)
                .remove(KEY_ORGANIZATION_ID)
                .remove(KEY_ORGANIZATION_STATUS)
                .remove(KEY_COMPANY_ID)
                .remove(KEY_COMPANY_NAME)
                .remove(KEY_VERIFIED_SIM_SUBSCRIPTION_ID)
        }

        return editor.commit()
    }


    fun saveActivationSettings(
        context: Context,
        serverUrl: String,
        deviceCode: String,
        activationToken: String
    ): Boolean {

        return saveSettings(
            context = context,
            serverUrl = DEFAULT_SERVER_URL,
            apiToken = activationToken,
            deviceId = deviceCode
        )
    }


    /**
     * Save the code/mobile entered by the operator before verification.
     * This does NOT mark the organization as verified.
     */
    @Synchronized
    fun saveOrganizationIdentity(
        context: Context,
        organizationCode: String,
        registeredMobile: String
    ): Boolean {
        val code = organizationCode.trim().uppercase()
        val mobile = registeredMobile.filter { it.isDigit() }
        if (code.isBlank() || mobile.length !in 8..15) return false

        val prefs = preferences(context)
        val oldCode = prefs.getString(KEY_ORGANIZATION_CODE, "").orEmpty()
        val oldMobile = prefs.getString(KEY_REGISTERED_MOBILE, "").orEmpty()
        val changed = oldCode != code || oldMobile != mobile
        val editor = prefs.edit()
            .putString(KEY_ORGANIZATION_CODE, code)
            .putString(KEY_REGISTERED_MOBILE, mobile)

        if (changed) {
            editor.putBoolean(KEY_ORGANIZATION_VERIFIED, false)
                .remove(KEY_ORGANIZATION_NAME)
                .remove(KEY_ORGANIZATION_ID)
                .remove(KEY_ORGANIZATION_STATUS)
                .remove(KEY_COMPANY_ID)
                .remove(KEY_COMPANY_NAME)
                .remove(KEY_VERIFIED_SIM_SUBSCRIPTION_ID)
                .remove(KEY_DEVICE_TOKEN)
        }
        return editor.commit()
    }

    fun getOrganizationCode(context: Context): String =
        preferences(context).getString(KEY_ORGANIZATION_CODE, "").orEmpty()

    fun getRegisteredMobile(context: Context): String =
        preferences(context).getString(KEY_REGISTERED_MOBILE, "").orEmpty()

    fun getOrganizationName(context: Context): String =
        preferences(context).getString(KEY_ORGANIZATION_NAME, "").orEmpty()

    fun getOrganizationId(context: Context): String =
        preferences(context).getString(KEY_ORGANIZATION_ID, "").orEmpty()

    fun getOrganizationStatus(context: Context): String =
        preferences(context).getString(KEY_ORGANIZATION_STATUS, "").orEmpty()

    fun isOrganizationVerified(context: Context): Boolean {
        val prefs = preferences(context)
        val selectedSim = getSelectedSimSubscriptionId(context) ?: return false
        val verifiedSim = prefs.getInt(KEY_VERIFIED_SIM_SUBSCRIPTION_ID, -1)
        return prefs.getBoolean(KEY_ORGANIZATION_VERIFIED, false) &&
            verifiedSim >= 0 && verifiedSim == selectedSim &&
            prefs.getString(KEY_ORGANIZATION_CODE, "").orEmpty().isNotBlank() &&
            prefs.getString(KEY_ORGANIZATION_ID, "").orEmpty().isNotBlank() &&
            prefs.getString(KEY_ORGANIZATION_NAME, "").orEmpty().isNotBlank() &&
            prefs.getString(KEY_REGISTERED_MOBILE, "").orEmpty().isNotBlank() &&
            (prefs.getString(KEY_ORGANIZATION_STATUS, "").orEmpty().equals("active", true) ||
                prefs.getString(KEY_ORGANIZATION_STATUS, "").orEmpty().equals("verified", true))
    }

    /**
     * Atomically persist the authoritative organization object returned by
     * Main Server after its Client Database lookup and exact code match.
     * Call this only after checking the API's verified/success flags.
     */
    @Synchronized
    fun saveVerifiedOrganization(
        context: Context,
        organizationId: String,
        organizationName: String,
        organizationCode: String,
        registeredMobile: String,
        organizationStatus: String,
        companyId: String = "",
        companyName: String = "",
        verifiedSimSubscriptionId: Int? = getSelectedSimSubscriptionId(context)
    ): Boolean {
        val id = organizationId.trim()
        val name = organizationName.trim()
        val code = organizationCode.trim().uppercase()
        val mobile = registeredMobile.filter { it.isDigit() }
        val status = organizationStatus.trim()

        if (id.isBlank() || name.isBlank() || code.isBlank() ||
            mobile.length !in 8..15 || verifiedSimSubscriptionId == null || verifiedSimSubscriptionId < 0 ||
            !(status.equals("active", true) || status.equals("verified", true))) {
            clearOrganizationVerification(context)
            return false
        }

        val prefs = preferences(context)
        val previousCode = prefs.getString(KEY_ORGANIZATION_CODE, "").orEmpty()
        val previousId = prefs.getString(KEY_ORGANIZATION_ID, "").orEmpty()
        val identityChanged = previousCode != code || previousId != id
        val editor = prefs.edit()
            .putString(KEY_ORGANIZATION_ID, id)
            .putString(KEY_ORGANIZATION_NAME, name)
            .putString(KEY_ORGANIZATION_CODE, code)
            .putString(KEY_REGISTERED_MOBILE, mobile)
            .putString(KEY_ORGANIZATION_STATUS, if (status.equals("verified", true)) "Verified" else "Active")
            .putInt(KEY_VERIFIED_SIM_SUBSCRIPTION_ID, verifiedSimSubscriptionId)
            .putBoolean(KEY_ORGANIZATION_VERIFIED, true)

        if (companyId.isNotBlank()) editor.putString(KEY_COMPANY_ID, companyId.trim())
        if (companyName.isNotBlank()) editor.putString(KEY_COMPANY_NAME, companyName.trim())
        if (identityChanged) editor.remove(KEY_DEVICE_TOKEN)
        return editor.commit()
    }

    /**
     * Legacy compatibility method.
     *
     * A local caller must never be able to grant organization verification.
     * Only saveVerifiedOrganization(), after ApiClient has validated the
     * authoritative Main Server response and all required diagnostic checks,
     * may set KEY_ORGANIZATION_VERIFIED=true.
     *
     * Passing verified=false remains supported so older callers can clear
     * a verification state safely. Passing true returns false and changes
     * no verification data.
     */
    @Deprecated(
        message = "Use saveVerifiedOrganization() only after Main Server verification."
    )
    @Synchronized
    fun saveOrganizationVerification(
        context: Context,
        verified: Boolean,
        organizationName: String = ""
    ): Boolean {
        if (!verified) return clearOrganizationVerification(context)
        return false
    }

    fun clearOrganizationVerification(context: Context): Boolean =
        preferences(context).edit()
            .putBoolean(KEY_ORGANIZATION_VERIFIED, false)
            .remove(KEY_ORGANIZATION_NAME)
            .remove(KEY_ORGANIZATION_ID)
            .remove(KEY_ORGANIZATION_STATUS)
            .remove(KEY_COMPANY_ID)
            .remove(KEY_COMPANY_NAME)
            .remove(KEY_VERIFIED_SIM_SUBSCRIPTION_ID)
            .remove(KEY_DEVICE_TOKEN)
            .commit()

    /**
     * SIM selection.
     */
    fun getSelectedSimSubscriptionId(context: Context): Int? {

        val value = preferences(context)
            .getInt(KEY_SELECTED_SIM_SUBSCRIPTION_ID, -1)

        return value.takeIf { it >= 0 }
    }


    fun saveSelectedSimSubscriptionId(
        context: Context,
        subscriptionId: Int
    ): Boolean {

        if (subscriptionId < 0) {
            return false
        }

        val prefs = preferences(context)
        val previous = prefs.getInt(KEY_SELECTED_SIM_SUBSCRIPTION_ID, -1)
        val editor = prefs.edit()
            .putInt(KEY_SELECTED_SIM_SUBSCRIPTION_ID, subscriptionId)

        if (previous != subscriptionId) {
            editor.putBoolean(KEY_ORGANIZATION_VERIFIED, false)
                .remove(KEY_ORGANIZATION_NAME)
                .remove(KEY_ORGANIZATION_ID)
                .remove(KEY_ORGANIZATION_STATUS)
                .remove(KEY_COMPANY_ID)
                .remove(KEY_COMPANY_NAME)
                .remove(KEY_VERIFIED_SIM_SUBSCRIPTION_ID)
                .remove(KEY_DEVICE_TOKEN)
        }
        return editor.commit()
    }


    fun clearSelectedSimSubscriptionId(
        context: Context
    ): Boolean {

        return preferences(context)
            .edit()
            .remove(KEY_SELECTED_SIM_SUBSCRIPTION_ID)
            .putBoolean(KEY_ORGANIZATION_VERIFIED, false)
            .remove(KEY_ORGANIZATION_NAME)
            .remove(KEY_ORGANIZATION_ID)
            .remove(KEY_ORGANIZATION_STATUS)
            .remove(KEY_COMPANY_ID)
            .remove(KEY_COMPANY_NAME)
            .remove(KEY_VERIFIED_SIM_SUBSCRIPTION_ID)
            .remove(KEY_DEVICE_TOKEN)
            .commit()
    }


    /**
     * Save the server-issued gateway token securely.
     */
    fun saveDeviceToken(
        context: Context,
        deviceToken: String
    ): Boolean {

        val value = deviceToken.trim()

        if (value.isBlank()) {
            return false
        }

        val encrypted = encryptSecret(value) ?: return false

        return preferences(context)
            .edit()
            .putString(KEY_DEVICE_TOKEN, encrypted)
            .commit()
    }


    fun clearDeviceToken(context: Context): Boolean =
        preferences(context)
            .edit()
            .remove(KEY_DEVICE_TOKEN)
            .commit()


    /**
     * Server URL is fixed to the official HTTPS endpoint.
     */
    fun saveServerUrl(
        context: Context,
        serverUrl: String
    ): Boolean {

        val prefs = preferences(context)

        val oldUrl = prefs.getString(KEY_SERVER_URL, null)

        val editor = prefs.edit()
            .putString(KEY_SERVER_URL, DEFAULT_SERVER_URL)

        if (
            oldUrl != null &&
            oldUrl.trimEnd('/') != DEFAULT_SERVER_URL
        ) {
            editor.remove(KEY_DEVICE_TOKEN)
        }

        return editor.commit()
    }


    /**
     * Device IDs are generated locally and are not arbitrary credentials.
     */
    fun saveDeviceId(
        context: Context,
        deviceId: String
    ): Boolean {

        val actualId = getDeviceCode(context)
        val suppliedId = deviceId.trim()

        return suppliedId == actualId ||
            suppliedId == LEGACY_DEVICE_CODE
    }


    fun saveDeviceCode(
        context: Context,
        deviceCode: String
    ): Boolean =
        saveDeviceId(context, deviceCode)


    fun saveApiToken(
        context: Context,
        apiToken: String
    ): Boolean {

        val value = apiToken.trim()

        if (value.isBlank()) {
            return false
        }

        val encrypted = encryptSecret(value) ?: return false
        val oldToken = readSecret(context, KEY_API_TOKEN)

        val editor = preferences(context)
            .edit()
            .putString(KEY_API_TOKEN, encrypted)

        if (oldToken != value) {
            editor.remove(KEY_DEVICE_TOKEN)
                .putBoolean(KEY_ORGANIZATION_VERIFIED, false)
                .remove(KEY_ORGANIZATION_NAME)
                .remove(KEY_ORGANIZATION_ID)
                .remove(KEY_ORGANIZATION_STATUS)
                .remove(KEY_COMPANY_ID)
                .remove(KEY_COMPANY_NAME)
                .remove(KEY_VERIFIED_SIM_SUBSCRIPTION_ID)
        }

        return editor.commit()
    }


    fun saveActivationToken(
        context: Context,
        activationToken: String
    ): Boolean =
        saveApiToken(context, activationToken)


    /**
     * Explicitly clears local configuration.
     * Android Keystore key is retained so encrypted-data handling
     * does not unexpectedly invalidate other app data.
     */
    fun clearSettings(context: Context): Boolean =
        preferences(context).edit().clear().commit()


    /**
     * Read encrypted credentials.
     *
     * Older plaintext values are migrated to encrypted storage.
     * If encryption fails, a plaintext secret is not returned.
     */
    private fun readSecret(
        context: Context,
        key: String
    ): String? {

        val prefs = preferences(context)

        val stored = prefs
            .getString(key, null)
            ?.takeIf { it.isNotBlank() }
            ?: return null

        if (stored.startsWith(ENCRYPTED_PREFIX)) {
            return decryptSecret(stored)
                ?.takeIf { it.isNotBlank() }
        }

        val encrypted = encryptSecret(stored)

        if (encrypted != null) {
            val migrated = prefs.edit()
                .putString(key, encrypted)
                .commit()

            if (migrated) {
                return stored
            }
        }

        return null
    }


    private fun encryptSecret(plainText: String): String? {

        return try {

            val cipher = Cipher.getInstance(
                "AES/GCM/NoPadding"
            )

            cipher.init(
                Cipher.ENCRYPT_MODE,
                getOrCreateSecretKey()
            )

            val iv = cipher.iv

            val ciphertext = cipher.doFinal(
                plainText.toByteArray(Charsets.UTF_8)
            )

            ENCRYPTED_PREFIX +
                Base64.encodeToString(iv, Base64.NO_WRAP) +
                ":" +
                Base64.encodeToString(
                    ciphertext,
                    Base64.NO_WRAP
                )

        } catch (_: Exception) {
            null
        }
    }


    private fun decryptSecret(encoded: String): String? {

        return try {

            val payload = encoded.removePrefix(
                ENCRYPTED_PREFIX
            )

            val separator = payload.indexOf(':')

            if (
                separator <= 0 ||
                separator == payload.lastIndex
            ) {
                return null
            }

            val iv = Base64.decode(
                payload.substring(0, separator),
                Base64.NO_WRAP
            )

            val ciphertext = Base64.decode(
                payload.substring(separator + 1),
                Base64.NO_WRAP
            )

            val cipher = Cipher.getInstance(
                "AES/GCM/NoPadding"
            )

            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateSecretKey(),
                GCMParameterSpec(
                    GCM_TAG_LENGTH_BITS,
                    iv
                )
            )

            String(
                cipher.doFinal(ciphertext),
                Charsets.UTF_8
            )

        } catch (_: Exception) {
            null
        }
    }


    /**
     * Android Keystore key creation and retrieval.
     */
    private fun getOrCreateSecretKey(): SecretKey {

        val keyStore = KeyStore
            .getInstance("AndroidKeyStore")
            .apply {
                load(null)
            }

        val existing = keyStore.getKey(
            KEYSTORE_ALIAS,
            null
        )

        if (existing is SecretKey) {
            return existing
        }

        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            KEYSTORE_PROVIDER
        )

        generator.init(
            KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or
                    KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(
                    KeyProperties.BLOCK_MODE_GCM
                )
                .setEncryptionPaddings(
                    KeyProperties.ENCRYPTION_PADDING_NONE
                )
                .setRandomizedEncryptionRequired(true)
                .build()
        )

        return generator.generateKey()
    }



}
