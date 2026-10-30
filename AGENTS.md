# Console Launcher — Agent Guide

## Project Overview
Android TV / gamepad-oriented launcher written in Kotlin with Jetpack Compose (Material3). Single module (`:app`).

## Key Commands
```bash
./gradlew assembleDebug          # Build debug APK
./gradlew installDebug           # Install on connected device/emulator
./gradlew test                   # Run local unit tests (JUnit 4)
./gradlew connectedAndroidTest   # Run instrumented tests (requires device)
./gradlew lint                   # Static analysis
./gradlew help --task :app:tasks # List all app tasks
```

## Build Configuration
- **Gradle**: Kotlin DSL, configuration cache enabled (`gradle.properties`)
- **JDK Toolchain**: Foojay resolver (auto-downloads matching JDK)
- **AGP**: 9.4.1 | **Kotlin**: 2.2.10 | **Compose BOM**: 2025.12.01
- **minSdk**: 26 | **targetSdk**: 37 | **compileSdk**: 37
- **Java/Kotlin**: 11 compatibility

## Architecture
```
MainActivity (entry, HOME intent filter)
  └─ LauncherRoot (state, tabs, key handling)
      ├─ AppRepository (loads launchable apps via PackageManager on IO dispatcher)
      ├─ RecentAppsStore (SharedPreferences, newline-separated package list, sync commit)
      └─ LauncherGrid (LazyVerticalGrid, 4 columns, focus management)
      └─ LauncherGameSystems (GAMES tab: one horizontal row per system/playlist)
      └─ DrawerOverlay (collapsible left drawer, slides above the launcher; items: HOME, SETUP, SCREENSHOTS, STEAM, SETTINGS)
```

## Launcher-Specific Details
- **Intent filters**: `MAIN/LAUNCHER` + `HOME/DEFAULT` → appears in "Home app" picker
- **Orientation**: `landscape` locked in manifest
- **Launch mode**: `singleTask`, `excludeFromRecents`, `stateNotNeeded`
- **Fullscreen**: Edge-to-edge, transient system bars (swipe to reveal)
- **Input**: D-pad/arrow keys navigate, Enter launches, hamburger icon in the top bar or MENU/Y toggles the drawer, L1/R1 switch tabs, Back → close drawer → Home tab
- **Drawer**: opens as an overlay (scrim + sliding panel) on top of the launcher content; the entry of the open screen stays accent-highlighted and receives the D-pad focus when the drawer opens. The top-bar tab strip only appears on the HOME menu; closing the drawer bumps a `focusEpoch` counter so the screen resumes D-pad focus

## Data Layer
- `AppRepository.loadLaunchableApps()`: Queries `ACTION_MAIN + CATEGORY_LAUNCHER`, filters self, caches icons in `ConcurrentHashMap`
- `RecentAppsStore`: Persists max 24 entries; `commit()` (not `apply()`) to survive process death on app launch

## Testing
- Unit tests: `src/test` (JUnit 4, runs on host JVM)
- Instrumented: `src/androidTest` (Espresso, runs on device)
- No custom test suites — only template examples exist

## Theme & UI
- Custom dark "console" palette in `ui/theme/Theme.kt` (no light theme)
- 4-column grid, 100dp card height, section headers span full width
- Focus requester auto-focuses first item on tab switch (retries 10×150ms)

## Common Gotchas
- **Configuration cache** enabled — avoid `gradle.properties` changes without invalidation
- **Icon loading** runs on `Dispatchers.IO`; UI shows "Loading..." until first batch returns
- **Recent apps** only tracks apps launched *by this launcher* (platform recents API restricted)
- **Landscape-only** — UI assumes horizontal layout; portrait not tested