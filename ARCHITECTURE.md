# Architecture Specification

## 1. Project Goal

Build a production-oriented Android web browser using:

- Kotlin
- Jetpack Compose
- Mozilla GeckoView
- MVVM
- Repository pattern
- Room
- DataStore
- Kotlin Coroutines
- StateFlow

The first milestone is a working browser MVP. Do not over-engineer the initial implementation.

## 2. Core Architecture

```text
Composable UI
    ↓
ViewModel
    ↓
BrowserEngine interface
    ↓
GeckoBrowserEngine
    ↓
GeckoView / GeckoSession
```

The UI must not call GeckoView APIs directly.

This abstraction is mandatory because it keeps the application independent from the concrete browser engine.

## 3. Browser Engine

Create:

```kotlin
interface BrowserEngine
```

It should expose operations for:

- Creating sessions
- Loading URLs
- Reloading
- Going back
- Going forward
- Stopping loading
- Evaluating JavaScript
- Clearing browsing data
- Reading current URL
- Reading page title
- Checking back/forward availability

The first implementation is:

```text
GeckoBrowserEngine
```

Use the current stable GeckoView APIs available at implementation time. Do not invent APIs.

## 4. Browser Sessions

Each tab owns its own browser session. Rendering uses one shared,
attached GeckoView owned by the engine layer: switching tabs replaces the
session bound to that view (`renderContentView`) instead of creating a new
surface-backed view per tab. Live sessions are kept at high priority;
suspension (below) — not priority demotion — is the memory control.

A browser tab should contain:

- Unique tab ID
- Browser session
- Current URL
- Page title
- Loading state
- Loading progress
- Can-go-back state
- Can-go-forward state
- Private/incognito state

Do not use one global browser session for all tabs.

## 5. Tab Management

The browser must support:

- New tab
- Close tab (including the last tab; zero tabs is valid and shows the
  Start Page)
- Switch tab
- Reload tab

Prepare the architecture for:

- Duplicate tab
- Restore previous tabs

A central browser/tab manager may coordinate active sessions, but individual session state must remain associated with its tab.

## 6. UI

The main screen is:

```text
BrowserScreen
```

Recommended layout:

```text
┌─────────────────────────────┐
│ Address Bar                 │
├─────────────────────────────┤
│                             │
│        GeckoView            │
│                             │
│                             │
├─────────────────────────────┤
│ Back Forward Home Tabs Menu │
└─────────────────────────────┘
```

The address bar should provide:

- URL/search input
- Security indicator
- Clear action
- Loading indicator

## 7. URL and Search Handling

If the entered text is a valid URL, navigate directly.

Otherwise, send it to the configured search engine.

Example:

```text
cats
```

becomes a search URL such as:

```text
https://www.google.com/search?q=cats
```

The search engine must be configurable.

Do not hardcode search-engine logic throughout the UI.

## 8. History

Use Room.

Entity:

```text
HistoryEntity
- id
- url
- title
- timestamp
```

Record successful navigation.

Support:

- View history
- Delete one entry
- Clear all history

Private browsing must not write normal history entries.

## 9. Bookmarks

Use Room.

Entity:

```text
BookmarkEntity
- id
- url
- title
- createdAt
- faviconUrl
```

Support:

- Add bookmark
- Remove bookmark
- List bookmarks
- Open bookmark

## 10. Settings

Use DataStore Preferences for simple settings.

Initial settings:

- Default search engine
- JavaScript enabled
- Cookies enabled
- Dark mode
- Open links in new tab
- Desktop site

Settings should be isolated from UI implementation.

The Start Page replaces a configurable homepage. It appears for new tabs,
when the Home action is used, and when no tab is open. It shows only up to
ten shortcuts explicitly created by the user through the round + action;
it must not derive entries from browsing history or bookmarks. Shortcuts can
be edited or deleted from a long-press menu. Their site icons are fetched
best-effort only after a matching site finishes loading, then cached in
private app storage; undecodable icons keep the letter placeholder. The
dedicated Hexora logo is used for the Start Page header and, on Android 12+,
the system splash icon instead of the launcher icon.

Saved logins are an optional, offline-only vault. GeckoView autofill
events surface credential submissions to the ViewModel, which asks for
explicit consent before `CredentialRepository` stores username/password
as Android Keystore-backed AES-GCM ciphertext in Room (exact-origin
matching; private tabs excluded). Passwords are never synced, transmitted,
or shown in the management UI.

## 11. Downloads

Prepare a download manager layer.

It should:

1. Receive download requests from GeckoView.
2. Delegate appropriate downloads to Android's download facilities.
3. Show notifications when appropriate.
4. Save files to an appropriate user-visible Downloads location.

Do not request storage permissions unless the target Android version and chosen storage API actually require them.

## 12. Permissions

Request only permissions that are genuinely required.

At minimum, declare internet access.

Do not request broad storage or unrelated permissions.

## 13. Private Browsing

The architecture must support private tabs.

Private tabs must not write normal browsing history.

When a private tab is closed, its private session/data should be released according to GeckoView's supported private-session behavior.

Do not pretend that deleting history alone makes a session private. Use the appropriate GeckoView private browsing/session APIs.

## 14. State Management

Use StateFlow.

A browser UI state can contain:

```text
tabs
activeTabId
currentUrl
pageTitle
isLoading
loadingProgress
canGoBack
canGoForward
isPrivate
```

Use explicit actions/events such as:

```text
LoadUrl
GoBack
GoForward
Reload
StopLoading
NewTab
CloseTab
SwitchTab
AddBookmark
RemoveBookmark
```

UI observes state and sends actions.

## 15. Navigation

Use Jetpack Navigation Compose.

Initial destinations:

```text
browser
tabs
bookmarks
history
downloads
passwords
settings
```

Avoid unnecessary navigation complexity in the MVP.

## 16. Theme

Use Material 3.

Support:

- Light (plain white, neutral surfaces)
- Dark
- System default
- Hexora Mode (opaque dark theme using #E77E3D on #100B0F)

Keep the initial UI clean and minimal.

## 17. Error Handling

Handle at least:

- Invalid URL
- Network failure
- Page loading failure
- SSL/security errors according to GeckoView's APIs
- Download errors
- GeckoView initialization errors

A recoverable browser error must not crash the Activity.

Do not silently ignore exceptions.

## 18. Lifecycle

Handle browser sessions correctly across:

- Activity start
- Activity stop
- Configuration changes
- Activity destruction

Avoid memory leaks.

Follow GeckoView's current lifecycle requirements rather than guessing lifecycle behavior.

## 19. Database

Use Room.

Keep database entities, DAOs, and repositories separated.

UI/ViewModels must not execute raw database queries.

## 20. Repository Layer

Repositories provide the application-facing API for persistent data.

Example:

```text
BookmarkRepository
HistoryRepository
```

Repositories should hide Room implementation details from ViewModels.

## 21. Suggested Package Structure

```text
com.example.browser

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
│   ├── settings/
│   └── components/
│
└── util/
```

## 22. Implementation Order

Implement in this order:

1. Create Android project.
2. Configure Kotlin and Compose.
3. Add GeckoView using the current stable version.
4. Create BrowserEngine abstraction.
5. Implement GeckoBrowserEngine.
6. Create BrowserSession.
7. Implement one working browser tab.
8. Build BrowserScreen.
9. Add address bar and navigation controls.
10. Add multiple tabs.
11. Add Room history.
12. Add Room bookmarks.
13. Add DataStore settings.
14. Add downloads.
15. Add private browsing.
16. Run a full build and fix all compilation/runtime issues.

## 23. Definition of Done for MVP

The MVP is complete only when:

- The project compiles successfully.
- The app launches without crashing.
- A browser tab opens on startup.
- A real HTTPS website can be loaded.
- Search from the address bar works.
- Back works.
- Forward works.
- Reload works.
- Multiple tabs work.
- Tabs can be closed.
- History works.
- Bookmarks work.
- Dark/light/system theme works.
- No obvious memory leak is introduced.
- No unnecessary permissions are requested.
- No major TODO placeholder replaces required functionality.

## 24. Important Engineering Rules

- Kotlin only for application code.
- Use current stable APIs.
- Do not invent GeckoView methods/classes.
- If GeckoView API behavior is uncertain, inspect the official/current documentation or dependency source before implementing.
- Do not place GeckoView logic directly in Composables.
- Do not put database queries in Composables.
- Do not put business logic in Composables.
- Do not introduce unnecessary dependencies.
- Avoid premature abstraction beyond the BrowserEngine boundary.
- Keep code readable for a beginner-maintainable project.
