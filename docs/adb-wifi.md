# ADB over Wi-Fi

This guide covers connecting to your Android device wirelessly, installing the LudicrousLink APK, and viewing logs.

## Prerequisites

- Android 11+ device (for native wireless debugging)
- ADB installed on your computer (`adb` in PATH)
- Device and computer on the **same Wi-Fi network**

!!! tip "ADB included with Android SDK"
    If you've run `./gradlew setupAndroidSdk`, ADB is at `.android-sdk/platform-tools/adb`.

---

## Enable Wireless Debugging

### Android 11+ (Recommended)

1. Open **Settings → Developer Options**.
2. Enable **Wireless debugging**.
3. Tap **Wireless debugging** to enter its settings.
4. Tap **Pair device with pairing code**.
5. Note the **pairing code** and **IP:port** shown on screen.

### Enable Developer Options (if hidden)

1. Go to **Settings → About phone**.
2. Tap **Build number** 7 times.
3. Developer Options will appear in Settings.

---

## Connect via ADB

### Step 1: Pair (first time only)

```bash
adb pair <IP>:<PAIRING_PORT>
# Enter the pairing code when prompted
```

Example:

```bash
adb pair 192.168.1.50:37123
# Enter pairing code: 482956
```

### Step 2: Connect

After pairing, connect using the **IP:port** shown under "Wireless debugging" (this is a different port than the pairing port):

```bash
adb connect <IP>:<PORT>
```

Example:

```bash
adb connect 192.168.1.50:41567
```

### Verify Connection

```bash
adb devices
# Should show:
# 192.168.1.50:41567    device
```

---

## Install the APK

### From a pre-built APK

```bash
adb install path/to/ludicrouslink-debug.apk
```

### Build and install with Gradle

```bash
./gradlew :ludicrouslinkAndroid:app:installDebug
```

This builds the APK and installs it on the connected device in one step.

---

## Viewing Logs

### All LudicrousLink logs

```bash
adb logcat -s MainActivity:* MediaCodecStreamer:* DisplayService:*
```

### Full logcat (verbose)

```bash
adb logcat
```

### Filter by log level

```bash
# Info and above
adb logcat *:I

# Errors only
adb logcat *:E
```

### Save logs to a file

```bash
adb logcat -s MainActivity:* MediaCodecStreamer:* > ludicrouslink.log
```

### In-App Log Window

The LudicrousLink Android app includes a built-in log window at the bottom of the screen that shows connection events, encoder status, and errors in real-time — no ADB required.

---

## Troubleshooting

| Issue | Solution |
|-------|----------|
| `adb pair` fails | Ensure both devices are on the same network and the pairing dialog is still open |
| `adb connect` refuses | Check the port — it changes each time wireless debugging is re-enabled |
| `device unauthorized` | Check the device for an authorization dialog and tap **Allow** |
| Connection drops | Wi-Fi may have switched — re-run `adb connect` |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | Uninstall the old version first: `adb uninstall com.cesicorp.ludicrouslink` |
