# Android Browser

A modern Android web browser built with **Kotlin**, **Jetpack Compose**, and **Mozilla GeckoView**.

## Goals

- Fast and reliable browsing
- Multiple tabs
- Bookmarks and history
- Private browsing
- Downloads
- Modern Material 3 UI, including an optional translucent Liquid Glass appearance
- Chrome-like Start Page with most-visited sites and bookmarks
- Optional offline saved-login vault (encrypted on-device, no sync)
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
│   │   ├── CredentialDao.kt
│   │   ├── CredentialEntity.kt
│   │   └── HistoryEntity.kt
│   │
│   └── repository/
│       ├── BookmarkRepository.kt
│       ├── CredentialRepository.kt
│       └── HistoryRepository.kt
│
├── ui/
│   ├── browser/
│   ├── tabs/
│   ├── bookmarks/
│   ├── history/
│   ├── passwords/
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
- Start Page (most-visited sites + bookmarks)

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
- DataStore settings: search engine, JavaScript, desktop mode,
  theme (system/light/dark/Liquid Glass), open-links-in-new-tab, and the
  local password-manager master switch
- Downloads delegated to Android `DownloadManager` (Downloads folder)
- `ACTION_VIEW` intents open http(s) links in a new tab
- Quick new-tab (`+`) button beside the tab switcher; the address bar selects
  its whole contents on first focus
- Closing the last tab is allowed: zero tabs is a valid state and shows the
  Hexora Start Page (most-visited sites from local history + bookmarks)
- Tabs render through one shared GeckoView whose session is swapped, while
  inactive live sessions are kept high priority; LRU suspension/restore is
  still the memory control for long tab lists
- Local password manager: after a login form is submitted, Hexora can ask to
  save it. Usernames/passwords are stored only as Android Keystore-backed
  AES-GCM ciphertext in the on-device Room database, are never transmitted,
  and can be deleted from Settings → Saved logins. Saved credentials can be
  offered back to login forms on the same exact origin (private tabs are
  excluded)

Known MVP limitations: site permission prompts (camera/mic/location) are not
wired yet, the JavaScript toggle applies from the next app start, cookie
toggling is stored but per-site cookie controls are planned for Phase 3, and
downloads are re-fetched by `DownloadManager` (no cookie/auth carry-over).
Saved-login autofill depends on sites exposing recognizable username/password
fields to GeckoView's autofill tree; when several usernames exist for one
origin, the most recently saved/updated one is offered first. Passwords are
never displayed in the saved-login manager. Runtime behavior of each release
still needs on-device verification.

## Development

Requirements: JDK 17 and Android SDK platform 37 (GeckoView 158 requires
compileSdk 37). Toolchain versions are pinned centrally in
`gradle/libs.versions.toml` (Gradle 9.3.1, AGP 9.1.1 with built-in Kotlin,
targeting SDK 37). The pinned GeckoView artifact is the latest stable release
build from https://maven.mozilla.org/maven2/org/mozilla/geckoview/geckoview —
bump its version there to upgrade.

Every push to the working branch also builds the debug APK on GitHub
Actions (`.github/workflows/build-debug-apk.yml`) and publishes it to a
GitHub prerelease, e.g. `releases/tag/debug-ada0c82`. Debug APKs are split
per CPU architecture (arm64-v8a for most modern phones, armeabi-v7a for old
32-bit phones, x86_64 for emulators) instead of one universal build that
bundles the GeckoView engine for every ABI (~520 MB).

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
