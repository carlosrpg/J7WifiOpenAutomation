# J7 WiFi Open Automation

[![Build](https://github.com/carlosrpg/J7WifiOpenAutomation/actions/workflows/build.yml/badge.svg)](https://github.com/carlosrpg/J7WifiOpenAutomation/actions/workflows/build.yml)
![Android 11](https://img.shields.io/badge/Android-11%20(API%2030)-3DDC84?logo=android&logoColor=white)
![Android Automotive](https://img.shields.io/badge/Android%20Automotive-head%20unit-4FC3F7)
![Kotlin](https://img.shields.io/badge/Kotlin-no%20dependencies-7F52FF?logo=kotlin&logoColor=white)

**Plug in your phone, get WiFi. No tapping through engineering menus.**

On the Jaecoo J7 head unit, wireless Android Auto / CarPlay needs a WiFi toggle that lives deep inside the
hidden **EngMode** app, and it resets. This app is a tiny accessibility service that watches the head unit's
**Android Auto** and **CarPlay** apps. The moment one of them comes up, it opens EngMode, taps **WIFI**, taps
**Open**, and closes EngMode.
A "Connecting WiFi…" screen covers it all, so you never see the menus.

<p align="center">
  <img src="docs/images/overlay.png" width="300" alt="Connecting WiFi overlay with animated WiFi symbol">
  &nbsp;&nbsp;
  <img src="docs/images/settings.png" width="300" alt="Settings page: service status, EngMode picker, Open WiFi test button">
</p>

> [!WARNING]
> This drives a manufacturer engineering menu. Use it at your own risk, and only configure it while parked.

## How it works

```mermaid
sequenceDiagram
    participant P as Android Auto / CarPlay app
    participant S as WifiOpenService
    participant E as EngMode
    P->>S: comes to the front (phone connected)
    S->>S: show "Connecting WiFi…" overlay
    S->>E: launch (fresh task)
    S->>E: click first button containing "WIFI"
    S->>E: click first button whose text is exactly "Open"
    S->>E: BACK until EngMode is gone
    S->>S: remove overlay
```

- **Watches two packages:** `com.google.android.projection.sink` (Android Auto) and
  `com.desaysv.vehicle.carplayapp` (CarPlay).
- **At most once every 10 minutes.** Neither app tells other apps "a phone connected", so the service runs when
  one comes to the front and then holds off for `COOLDOWN_MS`. Returning from EngMode or switching apps mid-drive
  never re-runs it.
- **Hidden behind an overlay.** Accessibility clicks reach EngMode underneath a full-screen accessibility overlay,
  so EngMode never shows. (Running it truly off-screen would need system privileges.)
- **Fails safe.** If a button isn't found within 15 s, it gives up, closes EngMode and removes the overlay.
- **Zero dependencies.** Plain Android framework, about 250 lines of Kotlin.

## Install on the car

1. Download the latest `J7WifiOpen-<version>.apk` from [Releases](https://github.com/carlosrpg/J7WifiOpenAutomation/releases).
2. Install it and let it switch its own accessibility service on. Android Automotive usually has no Accessibility
   page in Settings, so this one-time grant is what makes the **Start service** button work:

   ```bash
   adb install -r J7WifiOpen-1.0.2.apk
   ```
   ```bash
   adb shell pm grant com.carlosrpg.wifiopen android.permission.WRITE_SECURE_SETTINGS
   ```

3. Open **J7 WiFi Open Automation** from the app grid and tap **Start service** until the status reads
   **● Service running**.
4. Pick EngMode. It preselects the app whose name or package loosely matches `engmode`; EngMode has no
   launcher icon, which is fine, the service opens its first openable screen instead. The service does nothing
   until EngMode is set.
5. Tap **Open WiFi** to test. It runs the automation right away, ignores the cooldown, and leaves EngMode visible so
   you can watch each step. Errors (EngMode can't be opened, a button wasn't found) show up as a toast.

> [!IMPORTANT]
> If anything **force-stops** the app (App info → Force stop, or a head-unit memory cleaner), Android switches the
> accessibility service off and won't bring it back on its own. Open the app: the status turns red, and
> **Start service** fixes it.

Without the grant you can still enable it by hand. Keep the existing value, because Android Automotive enables
its own services there and `put` replaces the whole list:

```bash
adb shell settings get secure enabled_accessibility_services
```
```bash
adb shell settings put secure enabled_accessibility_services "<value from above>:com.carlosrpg.wifiopen/.WifiOpenService"
```

## Tuning for your head unit

The watched packages (`PROJECTION_PKGS`) and the cooldown (`COOLDOWN_MS`) are at the top of
[`WifiOpenService.kt`](app/src/main/java/com/carlosrpg/wifiopen/WifiOpenService.kt). Every window change is
logged, so connect a phone and look:

```bash
adb logcat -s J7WifiOpen
```

```text
D J7WifiOpen: window pkg=com.google.android.projection.sink cls=...
I J7WifiOpen: Projection connected, running EngMode WiFi automation
I J7WifiOpen: click 'WIFI' -> true
I J7WifiOpen: click 'Open' -> true
I J7WifiOpen: Done
```

| Problem | Where to look |
|---|---|
| Status says **Service not running** | Tap **Start service**. If it says "Run once from a computer", do the `pm grant` from step 2 |
| No `window pkg=` lines at all | The service isn't running: same as above |
| `window` lines, but none from the projection apps | The projection app's package differs, or it draws on another display; add what logcat shows to `PROJECTION_PKGS` |
| `EngMode is not set` | Open the app and pick EngMode |
| Stuck on the overlay for 15 s | EngMode's button text differs from `WIFI` / `Open`; check the `click` lines in logcat |

## Build

Requirements: JDK 17+ (21 recommended) and Android SDK platform 37.

```bash
./gradlew assembleDebug testDebugUnitTest
```

Release signing reads `RELEASE_STORE_FILE`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS` and
`RELEASE_KEY_PASSWORD` from a Gradle property, then `local.properties`, then environment variables. Without a
keystore, `assembleRelease` still builds, just unsigned. Keystores and `local.properties` are git-ignored.

```bash
./gradlew assembleRelease
```

## CI and releases

[`.github/workflows/build.yml`](.github/workflows/build.yml) runs the unit tests and builds the APK on every push
and pull request. Pushing a `vX.Y.Z` tag builds a signed APK and publishes it as a GitHub release:

```bash
git tag v1.0.0
```
```bash
git push origin v1.0.0
```

Signed builds need these repository secrets:

| Secret | Value |
|---|---|
| `RELEASE_KEYSTORE_BASE64` | `base64 -w0 release.keystore` |
| `RELEASE_STORE_PASSWORD` | keystore password |
| `RELEASE_KEY_ALIAS` | key alias |
| `RELEASE_KEY_PASSWORD` | key password |

## Project layout

```text
app/src/main/java/com/carlosrpg/wifiopen/
├── WifiOpenService.kt     accessibility service: detection, EngMode automation, overlay
├── ConnectionTrigger.kt   cooldown that stops repeat runs (unit tested)
├── WifiAnimationView.kt   animated WiFi symbol for the overlay
└── SettingsActivity.kt    service status / start, EngMode picker, manual test run
```
