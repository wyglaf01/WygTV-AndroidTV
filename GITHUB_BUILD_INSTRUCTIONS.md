# WygTV Steampunk APK — GitHub cloud build

1. Extract this ZIP, then upload **the contents of WygTv_v2_0_HomeScreen** to the root of your GitHub repository, preserving `.github/workflows/build-apk.yml` and `app/`.
2. On GitHub open **Actions → Build WygTV Steampunk APK → Run workflow** (or push to `main`).
3. Open the successful run, under **Artifacts** download `WygTV-v2-Steampunk-debug-APK`.
4. Unzip the artifact to get `app-debug.apk`. Transfer it to an Android TV device and install it with a file manager or `adb install -r app-debug.apk`.

This is a **debug-signed** APK for testing. An iPad cannot install Android APKs. If compilation fails, open the failed Gradle step and copy its log for troubleshooting.

Local build: Install Android SDK platform 35, JDK 17 and Gradle 8.9, then run `gradle assembleDebug` in the project root.
