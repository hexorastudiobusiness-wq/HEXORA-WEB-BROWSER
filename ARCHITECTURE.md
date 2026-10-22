# Architecture Specification

> Status: this document describes the architecture **as implemented**
> (currently shipping v0.4.4), not only the original plan. When behavior
> changes, update this file in the same commit. Agent-facing working rules
> live in `AGENTS.md`; the release-signing canon is `signing/README.md`.

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

The engine exposes:

- Creating and restoring sessions (`BrowserSessionConfig`: private mode,
  desktop mode)
- A listener for engine-created sessions (`window.open` / `target=_blank`),
  which also reports the opener session id so Back can return there
- Global text zoom and the web-content preferred color scheme
- Clearing site data / all browsing data

Each `BrowserSession` in turn exposes its page state as `StateFlow`
(URL, title, loading, progress, back/forward availability, security,
fullscreen, find result) plus navigation, find, thumbnail, desktop-mode,
autofill-credential, and content-view binding operations. One-time events
(download requested, credential submitted, close requested) travel on a
separate `SharedFlow`.

The implementation is:

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

- URL/search input (selects its contents on first focus)
- Security indicator and loading progress
- Downloads action (top bar) and bookmark toggle
- Refresh lives only in the bottom bar, next to Back/Forward/Home/Tabs/Menu

System Back priority is: exit fullscreen, then web-history Back, then —
for a page-opened tab at its history root — close that tab and return to
its opener; then dismiss the Start Page overlay; only then exit the app.

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

The selected theme is also mapped to GeckoView's official
`GeckoRuntimeSettings.preferredColorScheme` (System/Light/Dark; Hexora Mode
maps to Dark) both when the runtime is created and whenever the setting
changes. This exposes the app's choice through `prefers-color-scheme` so
sites with their own dark CSS can follow it. GeckoView provides no
Chromium-style algorithmic forced-dark recoloring for sites without a dark
theme, and Hexora must not invent one. The cookie and open-links-in-new-tab
preferences are currently stored only; their Settings summaries say so
instead of pretending they change engine behavior.

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

Downloads are performed by Hexora's own download layer
(`download/DownloadHandler.kt`); Android's `DownloadManager` is only a
legacy lookup path for entries recorded by older versions (transfers
delegated to it stayed pending forever on real devices).

The layer:

1. Receives download requests from GeckoView.
2. Fetches plain HTTP(S) URLs itself, with progress, pause, and
   Range-based resume (Content-Range and If-Range are validated, and a
   transfer is only published when its byte count matches the declared
   length).
3. Consumes the engine-provided response body for blob/POST/authenticated
   downloads, which cannot be replayed as a fresh GET. Such one-shot
   stream downloads cannot be resumed after interruption.
4. Publishes finished files to the public Downloads folder (MediaStore
   on API 29+; app-specific storage with a FileProvider URI below that)
   and shows notifications when appropriate.
5. Persists each transfer's source kind, last status, byte counts and
   published file URI in Room, so the Downloads page stays truthful —
   and completed files stay openable — after a process restart.

Do not request storage permissions unless the target Android version and chosen storage API actually require them.

## 12. Permissions

Request only permissions that are genuinely required.

At minimum, declare internet access.

Do not request broad storage or unrelated permissions.

## 13. Private Browsing

The architecture must support private tabs.

Private tabs must not write normal browsing history. They must not persist
tab-preview screenshots or an in-app download-history entry either; the tab
switcher shows a lock placeholder instead of page content.

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
canBackCloseTab
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

The same choice drives web content through `preferredColorScheme` as
described in §10. Keep the initial UI clean and minimal.

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

There is deliberately no destructive-migration fallback: a future schema
change must ship an explicit `Migration` rather than silently deleting the
user's history, bookmarks, shortcuts or saved logins.

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
│   │   ├── AppDatabase.kt (v5)
│   │   ├── DatabaseMigrations.kt (1→5, 2→5, 3→5, 4→5)
│   │   ├── *Dao.kt / *Entity.kt (history, bookmarks, downloads,
│   │   │   credentials, start shortcuts)
│   │   └── …
│   ├── repository/ (history, bookmarks, downloads, credentials,
│   │   start shortcuts, favicon fetching)
│   ├── security/CredentialCipher.kt
│   └── settings/SettingsRepository.kt
│
├── download/ (in-app download engine, filename sanitizing)
│
├── ui/
│   ├── browser/ (BrowserScreen, BrowserViewModel, BrowserUiState)
│   ├── tabs/
│   ├── bookmarks/
│   ├── history/
│   ├── downloads/
│   ├── passwords/
│   ├── settings/
│   ├── theme/
│   └── components/ (AddressBar, ZoomSlider)
│
└── util/
```

## 22. How to Change Things Safely

The MVP sequence that built this project is complete. For new work:

1. Reproduce/locate the behavior at the owning layer (UI state →
   ViewModel → engine/session → repository), not in a Composable.
2. Verify uncertain GeckoView APIs against the pinned dependency's docs
   or source before writing code.
3. Make the smallest targeted change; keep the shared-`GeckoView`
   rendering model and the security baseline intact.
4. Add or update unit tests where pure logic changed.
5. Let CI compile and test; verify output assets; ask the owner to verify
   runtime behavior on a real device.
6. Update `README.md`, this file, `AGENTS.md`, and `signing/README.md`
   (when relevant) in the same commit.

## 23. Definition of Done

A change is done only when:

- The project compiles successfully in CI and unit tests pass.
- The main browser flow still works: launch, load an HTTPS site, search,
  Back/Forward/Reload, multiple tabs (open/switch/close, including
  closing the last tab), history, bookmarks, themes/settings.
- No obvious memory leak, unnecessary permission, or unused import is
  introduced.
- The security baseline in §24/§25 is intact.
- Limitations are stated honestly; nothing is claimed to work on-device
  without device verification.

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
- Security baseline: Android backup is disabled (`allowBackup=false`), the
  single browser Activity is `singleTask` with predictive Back opted in,
  only http(s) targets are accepted from the address bar, other apps,
  home-screen shortcuts and the download layer, and full download URLs are
  never written to logcat (they can carry private query tokens).

## 25. Build Variants, CI, and Release Signing

- **Debug**: built by `.github/workflows/build-debug-apk.yml` on every
  push (`assembleDebug` + `testDebugUnitTest`), published as a
  `debug-<short-sha>` prerelease with arm64-v8a / armeabi-v7a / x86_64
  APKs, signed with a stable cached debug keystore so debug updates
  install over each other.
- **Release**: built by `.github/workflows/build-release-apk.yml`
  (`assembleRelease` + `bundleRelease`). CI decodes the dedicated release
  keystore from GitHub Actions secrets into the runner temp directory,
  signs, verifies every APK with `apksigner` (and the AAB with
  `jarsigner`), and publishes GitHub Release `v<versionName>` with all
  APKs and the AAB. Manual dispatch requires the workflow on the default
  branch; on a working branch, pushing a change to the marker file
  `signing/build-release.request` triggers one signed build.
- **The release key never enters this repository.** Full rules, the exact
  secret names, backup/loss consequences, and troubleshooting are
  canonical in `signing/README.md` and summarized in `AGENTS.md` §8. Any
  architecture change must preserve this property.
- Release and debug signatures differ; Android will not install one over
  the other. A signed APK sideloaded from a browser/file manager still
  triggers Android's per-source "unknown apps" permission and possibly a
  Play Protect scan — that is platform behavior about install source, not
  a signing defect. Only store installation (Google Play, using the AAB)
  removes that prompt.

## 26. Compatibility Targets

- `minSdk` 26 / `targetSdk` 37; the verified focus is Android 12–17,
  foldables/large screens, and diverse OEM skins. Use only platform and
  AndroidX/GeckoView APIs; no vendor-specific behavior.
- Respect edge-to-edge insets, predictive Back, RTL, font scaling, and
  both small and large screens; keep touch targets usable even when a
  control's visible glyph is deliberately compact (e.g. tab close).

## 27. Documentation Maintenance

- `README.md` — owner/user facing: features, install, warnings, builds.
- `ARCHITECTURE.md` (this file) — how the system is built and why.
- `AGENTS.md` — binding working rules for future agents/developers,
  including the release-signing red lines.
- `signing/README.md` — the single source of truth for release signing.

Docs that contradict the code are bugs. Fix them in the same commit as
the behavior change.

Debug audit 0.4.4 (versionCode 20): see [AUDIT.md](AUDIT.md) for fixes and verification boundaries.
