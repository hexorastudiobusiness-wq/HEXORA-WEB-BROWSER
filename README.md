# Android Browser

A modern Android web browser built with **Kotlin**, **Jetpack Compose**, and **Mozilla GeckoView**.

## Goals

- Fast and reliable browsing
- Multiple tabs
- Bookmarks and history
- Private browsing
- Downloads
- Modern Material 3 UI
- Clean, modular architecture
- Easy future expansion for privacy and advanced browser features

## Tech Stack

- Kotlin
- Android
- Jetpack Compose
- Material 3
- Mozilla GeckoView
- Room
- DataStore
- Kotlin Coroutines
- StateFlow
- Jetpack Navigation

## Architecture

The application follows an MVVM + Repository architecture.

```text
UI (Compose)
    ↓
ViewModel
    ↓
BrowserEngine abstraction
    ↓
GeckoBrowserEngine
    ↓
GeckoView / GeckoSession
```

The UI must never communicate directly with GeckoView APIs.

## Project Structure

```text
app/src/main/java/com/example/browser/

├── MainActivity.kt
├── BrowserApplication.kt
│
├── browser/
│   ├── BrowserEngine.kt
│   ├── GeckoBrowserEngine.kt
│   ├── BrowserSession.kt
│   └── BrowserSettings.kt
│
├── data/
│   ├── database/
│   │   ├── AppDatabase.kt
│   │   ├── BookmarkDao.kt
│   │   ├── HistoryDao.kt
│   │   ├── BookmarkEntity.kt
│   │   └── HistoryEntity.kt
│   │
│   └── repository/
│       ├── BookmarkRepository.kt
│       └── HistoryRepository.kt
│
├── ui/
│   ├── browser/
│   ├── tabs/
│   ├── bookmarks/
│   ├── history/
│   ├── settings/
│   └── components/
│
└── util/
```

## Current Roadmap

### Phase 1 — MVP

- GeckoView integration
- Address bar
- URL loading
- Search
- Back / Forward / Reload
- Multiple tabs
- Basic history
- Basic bookmarks

### Phase 2

- Downloads
- Private browsing
- Settings
- Dark mode
- Desktop site
- Homepage

### Phase 3

- Ad-blocking architecture
- Custom DNS
- Advanced privacy controls
- Cookie management
- Permission management
- Find in page

### Phase 4

- Sync
- Browser profiles
- Advanced tab management
- Backup / restore
- Extension architecture

## Implementation Status

Phase 1 MVP and the core of Phase 2 are implemented:

- GeckoView integration behind the `BrowserEngine` abstraction
- Address bar with URL/search resolution (configurable search engine)
- Back / forward / reload / stop, loading progress, security indicator
- Multiple tabs (new / switch / close), one `GeckoSession` per tab
- `window.open` opens a real new tab; private tabs use GeckoView private sessions
- Room history (private tabs are never recorded) and bookmarks
- DataStore settings: search engine, homepage, JavaScript, desktop mode,
  theme (system/light/dark), open-links-in-new-tab
- Downloads delegated to Android `DownloadManager` (Downloads folder)
- `ACTION_VIEW` intents open http(s) links in a new tab

Known MVP limitations: site permission prompts (camera/mic/location) are not
wired yet, the JavaScript toggle applies from the next app start, cookie
toggling is stored but per-site cookie controls are planned for Phase 3, and
downloads are re-fetched by `DownloadManager` (no cookie/auth carry-over).

## Development

Requirements: JDK 17 and Android SDK platform 37 (GeckoView 158 requires
compileSdk 37). Toolchain versions are pinned centrally in
`gradle/libs.versions.toml` (Gradle 9.3.1, AGP 9.1.1 with built-in Kotlin,
targeting SDK 37). The pinned GeckoView artifact is the latest stable release
build from https://maven.mozilla.org/maven2/org/mozilla/geckoview/geckoview —
bump its version there to upgrade.

Every push to the working branch also builds the debug APK on GitHub
Actions (`.github/workflows/build-debug-apk.yml`) and publishes it to a
GitHub prerelease, e.g. `releases/tag/debug-52232e0`. The debug APK is a
universal build containing all GeckoView ABIs (~520 MB); release builds
should use ABI splits.

Open the project in Android Studio and let Gradle synchronize dependencies.

Build the debug APK:

```bash
./gradlew assembleDebug
```

Run the unit tests:

```bash
./gradlew testDebugUnitTest
```

## License

Add the project's chosen license here before public release.
