# cs3_bollyzone

A CloudStream 3 extension for **BollyZone** (`bollyzone.to`) — watch Indian TV Shows and Hindi Dramas.

## Install on CloudStream

Add this repository URL in CloudStream → Settings → Extensions → Add Repository:

```
https://raw.githubusercontent.com/SiddharthJoshi1/cs3_bollyzone/main/repo.json
```

Then install **BollyZone** from the extension list. Updates are pushed automatically on every commit to `main`.

---

## Supported Content

- Latest daily episodes (home feed, paginated)
- Channel rows: SAB TV, Sony TV, Star Plus, Colors TV, Zee TV, And TV, MTV
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

2. **JDK 16+** — set this as the project JDK in Android Studio if Gradle complains

3. **Git** — [git-scm.com](https://git-scm.com)

4. **CloudStream pre-release APK** — install on your Android device
   - Download from [github.com/recloudstream/cloudstream/releases](https://github.com/recloudstream/cloudstream/releases)
   - Enable "Install from unknown sources" on your device

5. **ADB** — comes with Android Studio
   - Enable USB Debugging on your Android device: Settings → Developer Options → USB Debugging
   - Connect via USB and run `adb devices` to verify

---

### One-time setup: Gradle wrapper files

This repo does not include the Gradle wrapper binary. You need to copy 3 files from the official CloudStream extensions template:

```bash
# Clone the template alongside this repo
git clone https://codeberg.org/cloudstream/cloudstream-extensions.git
```

Then copy these files into `cs3_bollyzone`:

```
cloudstream-extensions/gradlew          → cs3_bollyzone/gradlew
cloudstream-extensions/gradlew.bat      → cs3_bollyzone/gradlew.bat
cloudstream-extensions/gradle/wrapper/gradle-wrapper.jar
                                        → cs3_bollyzone/gradle/wrapper/gradle-wrapper.jar
```

---

### Clone and open this repo

```bash
git clone https://github.com/SiddharthJoshi1/cs3_bollyzone.git
cd cs3_bollyzone
```

Open in Android Studio: **File → Open** → select the `cs3_bollyzone` folder.

Let Gradle sync complete (first run will download dependencies, may take a few minutes).

---

### Grant file access to CloudStream (Android 11+)

Run once after installing CloudStream on your device:

```bash
adb shell appops set --uid com.lagradost.cloudstream3.prerelease MANAGE_EXTERNAL_STORAGE allow
```

---

### Build and deploy to a connected device

```bash
# Windows
gradlew.bat BollyZoneProvider:make

# Mac/Linux
./gradlew BollyZoneProvider:make
```

This compiles the extension to a `.cs3` file and pushes it directly to your ADB-connected device, then launches CloudStream.

---

## CI/CD — GitHub Actions

Every push to `main` that touches provider or build files triggers a GitHub Action that:

1. Builds the `.cs3` file
2. Deploys it to the `builds` branch alongside an updated `plugins.json`

This means the CloudStream repository URL stays up to date automatically — no manual ADB required for TV installs.

---

## Project Structure

```
cs3_bollyzone/
├── .github/workflows/
│   └── build.yml                           # Auto-build and deploy on push
├── build.gradle.kts                        # Root build — CloudStream gradle plugin
├── settings.gradle.kts                     # Module declarations
├── gradle.properties                       # Android/Kotlin build flags
├── repo.json                               # CloudStream repository manifest
└── BollyZoneProvider/
    ├── build.gradle.kts                    # Extension metadata (name, version, language)
    └── src/main/kotlin/com/bollyzone/
        └── BollyZoneProvider.kt            # The provider — all logic lives here
```

## Function Map

| Function        | BollyZone mapping                                                    |
| --------------- | -------------------------------------------------------------------- |
| `getMainPage()` | Latest episodes feed + channel anchor pages                          |
| `search()`      | WordPress `?s=` search with URL encoding                             |
| `load()`        | Paginates `/category/{show-slug}/` (max 4 pages) to collect episodes |
| `loadLinks()`   | item.php → freeshorturls token → flow.tvlogy.to → .m3u8              |

## Known Behaviour

- Episode lists are capped at 4 pages (~80 episodes) per load to prevent timeouts on long-running shows like TMKOC
- The `.m3u8` stream token is IP and User-Agent bound — works correctly on the local device, cannot be shared externally
- Channel rows (Star Plus, Sony etc.) are static anchor pages with no pagination

## TODO
- [ ] Handle edge case: episodes with multiple quality sources
- [ ] Add show-level poster from category page header image
