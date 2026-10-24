# SS MediaOS SMS Gateway (Android source)

This is the Android Studio source project for the SS MediaOS SMS Gateway.

## Open and build
1. Extract this ZIP.
2. Open the `SSMediaOSAndroidGateway-main` folder in Android Studio (not the `app` folder).
3. Use JDK 17 and allow Android Studio to download Gradle 8.9 and Android Gradle Plugin dependencies.
4. Build with **Build > Build Bundle(s) / APK(s) > Build APK(s)**, or run `./gradlew :app:assembleDebug` (Windows: `gradlew.bat :app:assembleDebug`).
5. The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Configure
- Use your server's HTTPS base URL, registered device code, and the device token shown once by the server.
- The server must implement `/api/v1/sms/device_poll.php` and `/api/v1/sms/device_result.php` and authenticate `X-Gateway-Token`.
- Do not share the device token. If exposed, revoke/rotate it on the server.
- Queue jobs must include `id`, `request_id`, `to` and `message`. Phone numbers may be supplied as digits with country code or E.164 `+` format. The app normalizes digits-only international numbers by adding `+`.

## Permissions and distribution
The app requires the user to grant Android's `SEND_SMS` runtime permission. The app cannot force Android to show an Allow option or bypass restricted settings, device policy, carrier limitations, or Google Play rules. Google Play limits SMS permissions to eligible core-use cases and may require declarations/review. Test sideloaded builds on each intended device before deployment.

## Policy content
The app includes in-app Privacy Policy, Terms & Conditions, and SMS Spam & Abuse Prevention Policy screens. Before public release, replace the placeholder support/contact statements with real verified business contact details, publish the Privacy Policy at a publicly accessible HTTPS URL, and ensure it matches actual server data handling and retention. Policy text alone does not guarantee Google Play approval.

## Ownership
Owned by SS MediaOS.
