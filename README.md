# Instagram Media Downloader

Completely free Instagram media download app.

To build, run these commands in order:

```powershell
cd free-instagram-media-downloader
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

---

## Türkçe

Tamamen ücretsiz Instagram medya indirme uygulaması.

Derlemek için sırayla şu komutları çalıştırın:

```powershell
cd free-instagram-media-downloader
.\gradlew.bat assembleDebug
```

APK dosyasını telefona şu konumdan aktarın:

```
app\build\outputs\apk\debug\app-debug.apk
```

### Telefona kurulum

Bu uygulama Google Play'den gelmiyor. Android güvenlik uyarısı gösterebilir (örneğin **"Güvenli değil"**, **"Play Protect sertifikalı değil"** veya **"Güvenliğiniz için bu kaynaktan yükleme engellendi"**). Play Store dışı APK'larda bu normaldir.

1. `app-debug.apk` dosyasını telefona kopyalayın ve açın.
2. İstenirse dosya yöneticisi veya tarayıcıdan yüklemeye izin verin (**Bilinmeyen uygulamaları yükle**).
3. Play Protect uyarı verirse **Daha fazla bilgi**'ye dokunun (bazı telefonlarda: **Ayrıntılar**).
4. **Yine de yükle**'ye dokunun (bazı telefonlarda: **Yükle** veya **Bilinmeyen uygulamayı yükle**).

Uygulama açık kaynaklıdır — kodu kendiniz inceleyebilirsiniz. Release keystore veya ekstra imza ayarı gerekmez; debug derlemesi otomatik imzalanır.

Gereksinimler: JDK 17+ ve Android SDK (en kolay yol Android Studio).
