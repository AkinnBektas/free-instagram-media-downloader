# Instagram Media Downloader

Completely free Instagram media download app.

To build, run these commands in order:

```powershell
cd instagram-media-downloader
.\gradlew.bat assembleDebug
```

Transfer the APK to your phone from this path:

```
app\build\outputs\apk\debug\app-debug.apk
```

## Installing on your phone

This app is not from Google Play. Android may show a security warning (for example **"Unsafe app"**, **"App not Play Protect certified"**, or **"For your security, installation from this source is blocked"**). That is normal for sideloaded APKs.

1. Copy `app-debug.apk` to your phone and open it.
2. If prompted, allow installation from your file manager or browser (**Install unknown apps**).
3. When Play Protect warns you, tap **More info** (on some phones: **More details**).
4. Tap **Install anyway** (on some phones: **Install** or **Install unknown app**).

The app is open source — you can review the code yourself. No release keystore or extra signing setup is required; the debug build is signed automatically.

Requirements: JDK 17+ and Android SDK (easiest via Android Studio).
