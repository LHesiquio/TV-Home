# TV Home — technical notes

Design and implementation details for the launcher. For the project overview,
screenshots and download links, see the [root README](../README.md).

## What it is

A single Activity written in plain Java, with **zero dependencies** (no
AndroidX, Compose, Kotlin or RecyclerView). It only uses framework views.

- **~35 KB APK** (the launcher it replaces is around 15 MB).
- **~22 MB idle RAM**.
- **No INTERNET permission**: by construction it cannot show ads or send
  telemetry.
- No services, no alarms, no database. A single package receiver, registered
  only while the launcher is visible.

## What it shows

- **Row 1**: apps with a `LEANBACK_LAUNCHER` entry, plus user apps that only
  expose a regular `LAUNCHER` icon. Order: whatever the user set with *Move*;
  otherwise `PINNED`, then alphabetical.
- **Row 2**: Settings + passthrough inputs detected through `TvInputManager`
  (sorted by name: Composite, HDMI 1, HDMI 2…). Inputs always use the generic
  screen icon, because the one reported by the firmware is null, empty or blank
  depending on the device.
- Rectangular cards with the icon centered (never stretched) and rounded.
- **Light theme by default**, optional dark theme. The sun/moon switch next to
  the clock changes it and stores the choice in `SharedPreferences`.
- Clock and date in the top-right corner, using the system format and locale.
- Horizontal carousel: with more than 5–6 apps the last icon is cut off at the
  edge and the row scrolls **only as far as needed** while navigating.
- The list refreshes itself when an app is installed or uninstalled, without
  restarting the launcher and without losing focus.

## App menu (hold OK)

With a card focused, holding OK opens a floating menu:

| Option | What it does |
|---|---|
| **Move** | ◀ ▶ move the icon; OK drops it and saves; Back cancels |
| **Info** | Opens the system app page (version, force stop, uninstall…) |

## Navigation

| Key | Action |
|---|---|
| ◀ ▶ | Move through the app row (scrolls automatically) |
| ▲ ▼ | Switch between the app row, the system row and the theme switch |
| OK | Open the focused app / toggle the theme |
| Hold OK | Card menu: Move / Info |
| Home | Back to the first icon |
| Back | Does not leave the launcher; returns to the first icon |

Focus is remembered when coming back from an app.

## Structure

```
tvhome/
├── gradlew / gradlew.bat          # Gradle wrapper (8.9)
├── settings.gradle
├── build.gradle
├── app/build.gradle
└── app/src/main/
    ├── AndroidManifest.xml
    ├── java/com/bumbu/tvhome/MainActivity.java
    └── res/
        ├── drawable/bg_light.xml     # light theme background
        ├── drawable/bg_dark.xml      # dark theme background
        ├── drawable/ic_sun.xml
        ├── drawable/ic_moon.xml
        ├── drawable/ic_move.xml      # "Move" icon
        ├── drawable/ic_info.xml      # "Info" icon
        ├── drawable/ic_input.xml     # generic TV input icon
        ├── drawable/ic_launcher.xml
        ├── values/strings.xml        # English (default)
        ├── values-es/strings.xml     # Spanish
        └── values/styles.xml
```

## Build

Requires JDK 17 and Android SDK 34. The wrapper downloads Gradle 8.9.

```powershell
cd tvhome
.\gradlew.bat assembleRelease
```

The APK is written to `app\build\outputs\apk\release\app-release.apk`.

Release signing is optional: without `keystore.properties` the project still
builds, but the APK is unsigned. Format:

```properties
storeFile=tvhome.jks
storePassword=YOUR_STORE_PASSWORD
keyAlias=YOUR_KEY_ALIAS
keyPassword=YOUR_KEY_PASSWORD
```

## Install

```bash
# build a release APK first, or use the one from the Releases page
adb install -r app/build/outputs/apk/release/app-release.apk

# make it the Home app
adb shell cmd package set-home-activity --user 0 com.bumbu.tvhome/.MainActivity

# give Home back to the stock launcher
adb shell cmd package set-home-activity --user 0 com.google.android.tvlauncher/.MainActivity

# uninstall completely
adb uninstall com.bumbu.tvhome
```

## Design constants

Everything lives in `MainActivity.java` (geometry measured from a 1280×720
mockup):

| Constant | Value | Controls |
|---|---|---|
| `CARD_W` / `CARD_H` | 150 × 87 dp | App card (rectangular) |
| `ICON` | 60 dp | Icon inside the card (centered, not stretched) |
| `GAP` | 18 dp | Space between cards |
| `CARD_R` | 15 dp | Card corner radius |
| `SYS_W` / `SYS_H` / `SYS_ICON` | 150 × 66 / 40 dp | Row 2 card |
| `SW_W` / `SW_H` / `SW_KNOB` | 72 × 30 / 24 dp | Theme switch |
| focus scale | 1.06 | Card growth on focus |
| `PINNED` | array | Pinned apps and their order |
| `HIDDEN` | array | Packages that are never shown |
| `loadPalette()` | `0xFF...` | Colors for both themes |
| `bg_light.xml` / `bg_dark.xml` | — | Gradient + halo per theme |
| menu | 252 × 54 dp | Menu width and row height |

The focused card label turns bold; the focus ring is the card border itself,
with a soft color so the light theme stays gentle (gray `#8E9297` in light,
white in dark) plus a slight shadow increase.

### Light theme palette

| Element | Color |
|---|---|
| Background | `#F6F6F6 → #E9E9E9 → #DEDEDE` gradient |
| Card / border | `#FFFFFF` / `#D5D5D5` |
| Label / focused label | `#34383D` / `#131313` |
| Focus ring | `#8E9297` |
| Clock / date / hint | `#16181A` / `#676B70` / `#8A8E93` |
| Switch track / border | `#DCDCDC` / `#C8C8C8` |
| Focused menu row | `#EFEFEF` |

### Dark theme palette

| Element | Color |
|---|---|
| Background | `#0E1420 → #131A28 → #0B1018` gradient |
| Card / border | `#1A2130` / `#2A3346` |
| Label / focused label | `#C9CFDB` / `#FFFFFF` |
| Focus ring | `#FFFFFF` |
| Clock / date / hint | `#E9EDF5` / `#8B94A8` / `#67708A` |
| Switch track / border | `#232B3A` / `#2E3850` |
| Focused menu row | `#2A3346` |

## Order and refresh

- The order chosen with *Move* is stored as a package list in
  `SharedPreferences` (key `order`). New apps go at the end.
- `refreshApps()` compares the real list with the one on screen and only
  rebuilds when something changed, keeping focus by package (not by position).

## Compatibility notes

- **Android 11+** (API 30). Tested on Android 11 (HiSmart 2K ATV4) and
  Android 14 (onn. 4K, Google TV 2K).
- If you debloat the TV, **do not remove**
  `com.google.android.leanbacklauncher.partnercustomizer`. Its
  `tvlauncher.widget` provider is used by `com.mediatek.tv.service`; without it
  that service crashes when the network changes and takes
  *Settings → Network* down with it.
- App icons and logos belong to their respective owners; TV Home only renders
  the icons already installed on the device.
