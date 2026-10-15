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

## Development

Open the project in Android Studio and let Gradle synchronize dependencies.

Build the debug APK:

```bash
./gradlew assembleDebug
```

## License

Add the project's chosen license here before public release.
