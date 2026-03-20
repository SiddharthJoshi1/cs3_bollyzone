# cs3_bollyzone

A CloudStream 3 extension for **BollyZone** (`bollyzone.to`) — watch Indian TV Shows and Hindi Dramas.

## Supported Content
- Latest daily episodes (home feed, paginated)
- Channel rows: SAB TV, Sony TV, Star Plus, Colors TV, Zee TV
- Full episode history per show (via category pages)
- Search across all shows

---

## How It Works

### Confirmed stream extraction chain

```
bollyzone.to/series/{episode}/
  → groundbanks.net/item.php?id=XXXXX   [Referer: bollyzone.to]
    → <a class="button button1" href="route.freeshorturls.com/g/nflix/{TOKEN}">
      → flow.tvlogy.to/nflix/{TOKEN}/   [no cookies needed, 200 OK]
        → sources JSON → parrot.tvlogy.to/.../video.m3u8?token=...
          → HLS stream (720x480, live ✅)
```

The token in the freeshorturls URL is identical to the one used by `flow.tvlogy.to`, so the entire redirect chain is bypassed. The `.m3u8` token is `Base64(UserAgent||IP)` — it is device-bound, which is fine since CloudStream fetches and plays from the same device.

---

## Dev Environment Setup (Windows)

### Prerequisites

1. **Android Studio** — download from [developer.android.com/studio](https://developer.android.com/studio)
   - During install, make sure the Android SDK and ADB are included
   - Recommended: Hedgehog (2023.1.1) or later

2. **JDK 11+** — bundled with Android Studio, no separate install needed

3. **Git** — [git-scm.com](https://git-scm.com)

4. **CloudStream pre-release APK** — install on your Android device
   - Download from [github.com/recloudstream/cloudstream/releases](https://github.com/recloudstream/cloudstream/releases)
   - Enable "Install from unknown sources" on your device

5. **ADB** — comes with Android Studio
   - Enable USB Debugging on your Android device: Settings → Developer Options → USB Debugging
   - Connect via USB and run `adb devices` to verify

---

### One-time setup: Clone the CloudStream extensions template

This project mirrors the official extension template structure. You need the Gradle wrapper from it:

```bash
# Clone the official template alongside this repo
git clone https://codeberg.org/cloudstream/cloudstream-extensions.git
```

Then copy the `gradlew`, `gradlew.bat` and `gradle/wrapper/gradle-wrapper.jar` files into this repo:

```bash
copy cloudstream-extensions\gradlew.bat cs3_bollyzone\
copy cloudstream-extensions\gradlew cs3_bollyzone\
copy cloudstream-extensions\gradle\wrapper\gradle-wrapper.jar cs3_bollyzone\gradle\wrapper\
```

---

### Clone and open this repo

```bash
git clone https://github.com/YOUR_GITHUB_USERNAME/cs3_bollyzone.git
cd cs3_bollyzone
```

Open in Android Studio: **File → Open** → select the `cs3_bollyzone` folder.

Let Gradle sync complete (first run will download dependencies).

---

### Grant file access to CloudStream (Android 11+)

Run once after installing CloudStream on your device:

```bash
adb shell appops set --uid com.lagradost.cloudstream3.prerelease MANAGE_EXTERNAL_STORAGE allow
```

---

### Build and deploy

```bash
# Windows
gradlew.bat BollyZoneProvider:make

# Mac/Linux
./gradlew BollyZoneProvider:make
```

This compiles the extension to a `.cs3` file and pushes it directly to your connected device via ADB, then launches CloudStream.

---

## Project Structure

```
cs3_bollyzone/
├── build.gradle.kts                        # Root build — CloudStream gradle plugin
├── settings.gradle.kts                     # Module declarations
├── gradle.properties                       # Android/Kotlin build flags
└── BollyZoneProvider/
    ├── build.gradle.kts                    # Extension metadata (name, version, language)
    └── src/main/kotlin/com/bollyzone/
        └── BollyZoneProvider.kt            # The provider — all logic lives here
```

## Function Map

| Function | BollyZone mapping |
|---|---|
| `getMainPage()` | Fetches `/series/page/N/` and channel category pages |
| `search()` | WordPress `?s=` search |
| `load()` | Paginates `/category/{show-slug}/` to collect all episodes |
| `loadLinks()` | item.php → freeshorturls token → flow.tvlogy.to → .m3u8 |

## TODO
- [ ] Get Android Studio set up on Windows
- [ ] Copy Gradle wrapper files from template
- [ ] First build + ADB deploy test
- [ ] Verify CSS selectors against live site in DevTools
- [ ] Test `loadLinks()` end to end on device
- [ ] Handle edge case: episodes with multiple quality sources
