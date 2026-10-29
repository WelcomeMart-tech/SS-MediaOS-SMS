private fun startGatewayService() {
    val smsGranted =
        checkSelfPermission(Manifest.permission.SEND_SMS) ==
            PackageManager.PERMISSION_GRANTED

    if (!smsGranted) {
        updateStatus("Please grant SMS permission first.")
        requestRequiredPermissions()
        return
    }

    // Organization verification is mandatory before gateway startup.
    if (!SecureConfig.isOrganizationVerified(this)) {
        updateStatus(
            "Organization is not verified. Verify it in Gateway Settings first."
        )
        showToast(
            " Gateway Settings Organization Code registered mobile verify ।"
        )
        openGatewaySettings()
        return
    }

    val serverUrl = SecureConfig.getServerUrl(this)
    val deviceCode = SecureConfig.getDeviceCode(this)
    val activationToken = SecureConfig.getActivationToken(this)
    val deviceToken = SecureConfig.getDeviceToken(this)

    if (
        serverUrl.isBlank() ||
        deviceCode.isBlank() ||
        activationToken.isNullOrBlank()
    ) {
        updateStatus(
            "Gateway settings are incomplete. Open Gateway Settings."
        )
        showToast(
            "Gateway Settings Save।"
        )
        openGatewaySettings()
        return
    }

    if (deviceToken.isNullOrBlank()) {
        updateStatus(
            "This device is not activated. Activate it in Gateway Settings."
        )
        showToast(
            "SMS  device activate ।"
        )
        openGatewaySettings()
        return
    }

    try {
        val serviceIntent = Intent(
            this,
            GatewayService::class.java
        ).apply {
            action = GatewayService.ACTION_START
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        gatewayStartRequested = true

        updateStatus(
            "Gateway start requested. Waiting for the server connection."
        )
    } catch (e: Exception) {
        gatewayStartRequested = false

        updateStatus(
            "Could not start gateway: " +
                (e.localizedMessage ?: "Unknown error")
        )

        showToast("Gateway start   Settings ।")
    }
}
