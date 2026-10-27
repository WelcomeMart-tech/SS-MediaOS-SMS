package YOUR.PACKAGE.NAME

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject

class GatewaySettingsActivity : AppCompatActivity() {

    private lateinit var serverUrlInput: EditText
    private lateinit var apiTokenInput: EditText
    private lateinit var deviceIdInput: EditText
    private lateinit var connectionStatusText: TextView
    private lateinit var saveSettingsButton: Button
    private lateinit var testConnectionButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gateway_settings)

        serverUrlInput = findViewById(R.id.serverUrlInput)
        apiTokenInput = findViewById(R.id.apiTokenInput)
        deviceIdInput = findViewById(R.id.deviceIdInput)
        connectionStatusText = findViewById(R.id.connectionStatusText)
        saveSettingsButton = findViewById(R.id.saveSettingsButton)
        testConnectionButton = findViewById(R.id.testConnectionButton)

        loadSettings()

        saveSettingsButton.setOnClickListener {
            saveSettings()
        }

        testConnectionButton.setOnClickListener {
            testConnection()
        }
    }

    private fun loadSettings() {
        serverUrlInput.setText(
            SecureConfig.getServerUrl(this).orEmpty()
        )

        apiTokenInput.setText(
            SecureConfig.getActivationToken(this).orEmpty()
        )

        deviceIdInput.setText(
            SecureConfig.getDeviceId(this).orEmpty()
        )

        updateActivationStatus()
    }

    private fun saveSettings(): Boolean {
        val serverUrl = serverUrlInput.text.toString().trim()
        val activationToken = apiTokenInput.text.toString().trim()
        val deviceId = deviceIdInput.text.toString().trim()

        if (serverUrl.isBlank()) {
            serverUrlInput.error = "Server URL required"
            serverUrlInput.requestFocus()
            return false
        }

        if (!serverUrl.startsWith("https://", ignoreCase = true)) {
            serverUrlInput.error = "HTTPS URL required"
            serverUrlInput.requestFocus()
            return false
        }

        if (activationToken.isBlank()) {
            apiTokenInput.error = "Activation token required"
            apiTokenInput.requestFocus()
            return false
        }

        if (deviceId.isBlank()) {
            deviceIdInput.error = "Device ID required"
            deviceIdInput.requestFocus()
            return false
        }

        return try {
            SecureConfig.saveActivationSettings(
                this,
                serverUrl,
                deviceId,
                activationToken
            )

            updateActivationStatus()
            Toast.makeText(
                this,
                "Settings saved successfully",
                Toast.LENGTH_SHORT
            ).show()

            true
        } catch (e: Exception) {
            connectionStatusText.text =
                "Settings save failed: ${e.message ?: "Unknown error"}"

            false
        }
    }

    private fun testConnection() {
        if (!saveSettings()) return

        connectionStatusText.text = "Checking server connection…"
        testConnectionButton.isEnabled = false

        Thread {
            var resultText: String

            try {
                val serverUrl = serverUrlInput.text.toString()
                    .trim()
                    .trimEnd('/')

                val url = java.net.URL("$serverUrl/")
                val connection =
                    url.openConnection() as java.net.HttpURLConnection

                connection.requestMethod = "GET"
                connection.connectTimeout = 15000
                connection.readTimeout = 15000
                connection.useCaches = false

                try {
                    val statusCode = connection.responseCode

                    resultText = if (statusCode in 200..299) {
                        "Server connection successful (HTTP $statusCode)"
                    } else {
                        "Server responded with HTTP $statusCode"
                    }
                } finally {
                    connection.disconnect()
                }
            } catch (e: Exception) {
                resultText =
                    "Connection failed: ${e.message ?: "Network error"}"
            }

            runOnUiThread {
                connectionStatusText.text = resultText
                testConnectionButton.isEnabled = true
                updateActivationStatus()
            }
        }.start()
    }

    private fun updateActivationStatus() {
        val deviceToken = SecureConfig.getDeviceToken(this)

        if (deviceToken.isNullOrBlank()) {
            connectionStatusText.text =
                "NOT ACTIVATED — Save settings and activate the device."
        } else {
            connectionStatusText.text = "ACTIVATED"
        }
    }
}
