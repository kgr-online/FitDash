# FitDash Project Summary

## What is FitDash
A personal Android fitness dashboard app that reads data from Health Connect and displays it in a WebView-based UI. Built from scratch in this conversation. Not on the Play Store — sideloaded via ADB.

## Devices
- **Primary phone**: Pixel 9 Pro, stock OS beta + FolkPatch root
- **Secondary phone**: BlackBerry Key2 ("athena"), LineageOS 22 + APatch root, NikGApps
- **Watches**: Galaxy Watch 6 Classic (primary), Pixel Watch 3 (secondary), Galaxy Watch 5 Pro
- **Mac**: MacBook Air M3 13"

## App Structure
```
fitdash/
├── app/                          # Phone app
│   └── src/main/
│       ├── java/com/fitdash/
│       │   ├── MainActivity.kt           # WebView host, JS bridge, HC reads
│       │   ├── HealthConnectManager.kt   # All Health Connect API calls
│       │   ├── HealthSyncWorker.kt       # WorkManager background sync (30 min)
│       │   ├── StepsWidgetProvider.kt    # Home screen widget
│       │   └── PermissionsRationaleActivity.kt
│       ├── assets/index.html             # Full dashboard UI (HTML/CSS/JS)
│       └── AndroidManifest.xml
└── wear/                         # Watch complication app
    └── src/main/java/com/fitdash/wear/
        ├── StepsComplicationService.kt   # Wear OS complication
        └── PhoneDataListenerService.kt   # Receives data from phone
```

## Tech Stack
- **Phone app**: Kotlin + WebView + Jetpack (WorkManager, Lifecycle)
- **Dashboard UI**: Plain HTML/CSS/JS served from assets — no React, no bundler
- **Watch app**: Kotlin + Wear OS complications API
- **Data**: Health Connect API (androidx.health.connect 1.1.0)
- **Build**: AGP 8.9.1, Kotlin 2.0.0, compileSdk 36

## Current Version
- versionCode = 3, versionName = "3.0"
- Both phone app and wear app use applicationId = "com.fitdash"

## Key Build Config

### Root build.gradle.kts
```kotlin
plugins {
    id("com.android.application") version "8.9.1" apply false
    id("org.jetbrains.kotlin.android") version "2.0.0" apply false
}
```

### wear/build.gradle.kts — signing config (ALWAYS INCLUDE)
```kotlin
signingConfigs {
    create("release") {
        storeFile = file("/Users/kgr/Android/kgr.jks")
        storePassword = "12261980!"
        keyAlias = "key0"
        keyPassword = "12261980!"
    }
}
buildTypes {
    release {
        isMinifyEnabled = true
        signingConfig = signingConfigs.getByName("release")
        ...
    }
}
```

### MainActivity.kt — permissions line (ALWAYS USE THIS)
```kotlin
requestPermissions.launch(hcManager.permissions + hcManager.historyPermission)
// NOTE: do NOT add + hcManager.backgroundPermission — it doesn't exist
```

### AndroidManifest.xml — background permission (ALWAYS OUTSIDE <application>)
```xml
<uses-permission android:name="android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND" />
<!-- This MUST be before <application>, not inside it -->
```

## Data Flow
```
Pixel Watch → Fitbit app → Health Connect
Galaxy Watch → Samsung Health (watch) → Samsung Health (phone) → Health Connect
                                                                        ↓
                                                                   FitDash reads HC
                                                                        ↓
                                                    Phone widget + Watch complication
```

## Health Connect Data Reading Strategy
- **Steps/Calories/Distance**: `readRecords()` from ALL sources and sum them
  - Phone activity tracking is OFF in both Fitbit and Samsung Health
  - So all records come from physical watches worn at different times
  - Simple `sumOf` is correct — no deduplication needed
- **Sleep**: `readRecords()` with a 30-hour lookback from now (catches sessions starting evening before)
- **Heart rate, SpO2, VO2 Max**: standard `readRecords()` or `aggregate()`

## Dashboard Tabs
1. **Today** — steps ring, active cal, sleep, resting HR, heart rate, distance, SpO2, VO2 Max
2. **Fitness** — weekly steps/calories charts, exercise time, daily activity
3. **Sleep** — duration, breakdown (deep/REM/light), key metrics
4. **Health** — vitals, HR zones, metrics table
5. **History** — personal bests, monthly averages, day-by-day scrollable list (90 days)
6. **Sources** — per-source breakdown (Fitbit, Samsung Health) for today and week

## Home Screen Widget
- 2×2 arc widget showing steps progress
- Reads from SharedPreferences cache (`fitdash_widget`)
- Cache written by MainActivity on every HC read
- WorkManager pushes fresh data every 30 min
- Widget does NOT call HC directly (blocked without background permission)

## Watch Complication
- Wear OS complication served by `StepsComplicationService`
- Supports RANGED_VALUE, SHORT_TEXT, LONG_TEXT types
- Data pushed from phone via Wearable Data Layer (`/fitdash/steps`)
- Received by `PhoneDataListenerService` on watch
- Triggers complication refresh via `ComplicationDataSourceUpdateRequester`

## Watch App Installation
```bash
./gradlew :wear:assembleRelease
adb connect <watch-ip>:<port>
adb -s <watch-ip>:<port> install -r wear/build/outputs/apk/release/wear-release.apk
```

## Health Connect Permissions Requested
- Steps, Heart Rate, Resting Heart Rate, Sleep, Active Calories, Total Calories
- Distance, VO2 Max, Oxygen Saturation, Exercise
- READ_HEALTH_DATA_HISTORY (optional, for 90-day history)
- READ_HEALTH_DATA_IN_BACKGROUND (must be granted manually in HC settings)

## Background Permission Setup
After installing, user must manually enable in:
Health Connect → App permissions → FitDash → Additional access → Access data in the background

## Known Issues / Notes
- Samsung Health on phone only tracks phone steps (35), not Galaxy Watch steps
- Galaxy Watch steps sync via Samsung Health phone app → Health Connect
- Fitbit writes 1-minute interval records; Samsung Health writes larger blocks
- HC does NOT combine sources by default — FitDash reads all records and sums
- History tab loads in background after main data (~30-60 seconds first load)
- Sources tab also loads in background after main data
- Watch complication rendering is controlled by the watch face, not the app

## App Icon
- Walking figure icon, tinted grey (#b0b0b0)
- Original file: body_system_200dp_E3E3E3_FILL0_wght400_GRAD0_opsz48.png
- Full adaptive icon set (mdpi through xxxhdpi)

## ADB Watch Connection
```bash
# Galaxy Watch 6 Classic
adb kill-server && adb start-server
adb pair <watch-ip>:<pairing-port>   # First time only
adb connect <watch-ip>:<port>

# Pixel Watch 3
# Settings → System → About → tap Build number 7x
# Developer options → Wireless debugging
```
