# cs3_bollyzone

> **This repository is now archived.** The BollyZone provider has been contributed to and merged into [Phisher98's cloudstream-extensions-phisher](https://github.com/phisher98/cloudstream-extensions-phisher) repository, which is actively maintained and contains 70+ providers.

## Install via Phisher98's Repository

Use Phisher98's repository instead — it includes BollyZone alongside a large collection of other providers:

Add this URL in CloudStream → Settings → Extensions → Add Repository:

```
https://raw.githubusercontent.com/phisher98/cloudstream-extensions-phisher/master/repo.json
```

Then install **Bollyzone** from the extension list.

---

## About This Repo

This repository was used to develop and iterate on the BollyZone CloudStream provider. The work done here — including stream extraction chain analysis, the groundbanks referer fix, the `flow.tvlogy.to` token mechanism, and the robust `loadLinks` fallback chain — was contributed upstream to Phisher98's repo.

The contribution fixed the `#Proceed a[href]` selector issue that caused "link not found" errors for some users by adding a fallback to `a.button.button1` and replacing the broken `resolveIframeSrc` path with direct token extraction from the freeshorturls URL path.

The original repository and its CI/CD pipeline remain here for reference.

---

## How It Works

### Stream extraction chain

```
bollyzone.to/series/{episode}/
  → groundbanks.net/item.php?id=XXXXX   [Referer: bollyzone.to required]
    → <a class="button button1" href="route.freeshorturls.com/g/{type}/{TOKEN}">
      → token + type extracted from href path
        → flow.tvlogy.to/{type}/{TOKEN}/
          → parrot.tvlogy.to/.../video.m3u8?token=...
            → HLS stream ✅
```

### Why the redirect isn't followed

`route.freeshorturls.com` uses a JavaScript redirect (`google.navigateTo(parent, window, redirectUrl)`) targeting the parent frame — not a standard HTTP 301/302. CloudStream's HTTP client cannot execute JavaScript, so the redirect can never be followed programmatically. Instead the token and player type are extracted directly from the freeshorturls URL path, which maps 1:1 to the `flow.tvlogy.to` URL pattern.

### Why some users got "link not found"

The groundbanks page serves different HTML depending on the request. Some users see a `#Proceed` wrapper around the button, others see a bare `a.button.button1`. The original implementation only looked for `#Proceed a[href]`, so users who got the bare button had no links extracted. The fix tries both selectors.

---

## Original Project Structure

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
        └── BollyZoneProvider.kt            # The provider
```
