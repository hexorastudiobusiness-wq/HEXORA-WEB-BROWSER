# Android Browser

A modern Android web browser built with **Kotlin**, **Jetpack Compose**, and **Mozilla GeckoView**.

## Goals

- Fast and reliable browsing
- Multiple tabs
- Bookmarks and history
- Private browsing
- Downloads
- Modern Material 3 UI with System, Light, Dark, and solid Hexora Mode themes
- Start Page with up to ten user-created shortcuts
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
- Start Page (manual shortcuts)

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
- `window.open` opens a real new tab; hardware Back at that tab's root
  closes it and returns to the tab that opened it. Private tabs use
  GeckoView private sessions, never record history, never persist a tab
  screenshot, and their downloads are not written to the in-app download
  history (the downloaded file itself still exists, as the user requested it)
- Room history (private tabs are never recorded) and bookmarks
- DataStore settings: search engine, JavaScript, desktop mode,
  theme (System/Light/Dark/Hexora Mode), open-links-in-new-tab, and the
  local password-manager master switch. Light is plain white with neutral
  surfaces; Hexora Mode is an opaque dark studio theme built from
  #E77E3D and #100B0F. The theme also sets GeckoView's official
  `preferredColorScheme`, so sites that provide their own dark theme via
  `prefers-color-scheme` follow the app theme (Dark and Hexora Mode request
  the site's dark theme)
- Downloads delegated to Android `DownloadManager` (Downloads folder)
- Find in Page searches while typing (debounced) and only reports
  “No results” after Gecko answers for the exact current text; the slim
  text-zoom slider (browser menu and Settings) has a reset-to-100% button
- Address-bar input, external intents, home-screen shortcuts and downloads
  are restricted to http(s) navigation targets; Android backup is disabled
  and Room has no destructive-migration fallback
- `ACTION_VIEW` intents open http(s) links in a new tab
- Quick new-tab (`+`) button beside the tab switcher; the address bar selects
  its whole contents on first focus
- Closing the last tab is allowed: zero tabs is a valid state and shows the
  Hexora Start Page. The Start Page contains only shortcuts the user creates
  with its round + action (maximum ten); it never derives sites from history.
  Long-pressing a shortcut opens a small menu to edit or delete it. Once a
  shortcut's site has finished loading, its tile caches and shows the site's
  own favicon instead of the initial-letter placeholder.
- The Start Page header and Android 12+ system splash use the dedicated
  Hexora logo artwork rather than the launcher icon. The address bar and
  tab-tile close controls use compact sizing for faster one-handed use.
- Tabs render through one shared GeckoView whose session is swapped, while
  inactive live sessions are kept high priority; LRU suspension/restore is
  still the memory control for long tab lists
- The launcher icon uses the complete supplied artwork (including its
  wordmark), contained without cropping, with Android adaptive-icon masking
  on modern launchers and pre-rounded legacy icons on older versions.
- Local password manager: after a login form is submitted, Hexora can ask to
  save it. Usernames/passwords are stored only as Android Keystore-backed
  AES-GCM ciphertext in the on-device Room database, are never transmitted,
  and can be deleted from Settings → Saved logins. Saved credentials can be
  offered back to login forms on the same exact origin (private tabs are
  excluded)

Known MVP limitations: site permission prompts (camera/mic/location) are not
wired yet, the JavaScript toggle applies from the next app start, the cookie
and open-links-in-new-tab toggles are stored preferences that are not applied
to the engine yet, and downloads are re-fetched by `DownloadManager`
(no cookie/auth carry-over). GeckoView's preferred-color-scheme support lets
sites use their own dark CSS; unlike Chrome it does not algorithmically
recolor sites that ship no dark theme, so such pages can remain light in
Dark/Hexora Mode.
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
