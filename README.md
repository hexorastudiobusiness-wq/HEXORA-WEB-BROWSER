# Hexora Browser

A fast, simple, and secure personal Android web browser built with
**Kotlin**, **Jetpack Compose**, and **Mozilla GeckoView**.

Current version: **0.4.4** (`versionCode` 19) — see
[Releases](https://github.com/hexorastudiobusiness-wq/HEXORA-WEB-BROWSER/releases).

## Guiding principles

Hexora is a personal browser designed around heavy daily use. Three
principles are non-negotiable, and no feature may sacrifice any of them:

1. **Secure**
2. **Simple**
3. **Fast**

## Features

- GeckoView engine behind a `BrowserEngine` abstraction (never WebView)
- Address bar with URL/search resolution and a configurable search engine
- Back / forward / reload / stop, loading progress, secure-connection indicator
- Multiple tabs, one `GeckoSession` per tab, rendered through a single
  shared `GeckoView`; inactive tabs are suspended (LRU) to save memory
- `window.open` / `target=_blank` opens a real tab; system Back at that
  tab's root closes it and returns to the tab that opened it
- Private tabs using GeckoView private sessions: no history, no persisted
  tab screenshot, no in-app download history
- Chrome-like Start Page with **only user-created shortcuts** (maximum 10,
  round “+” tile, long-press Edit/Delete, cached site favicons)
- Find in Page that searches while you type (debounced, stale-result safe)
- Slim text-zoom slider (50–200%) with a reset-to-100% button, in the
  browser menu and in Settings
- Themes: System, plain-white Light, Dark, and opaque Hexora Mode
  (`#E77E3D` on `#100B0F`). The theme also sets GeckoView's official
  `preferredColorScheme`, so websites that ship their own dark theme via
  `prefers-color-scheme` follow it
- Room history (never for private tabs) and bookmarks
- Downloads performed by Hexora itself into the Downloads folder, with
  real progress, pause/resume (Range requests for HTTP(S)), a truthful
  cancelled state, and persisted state so completed files can still be
  opened after an app restart. Blob/POST/authenticated downloads consume
  the response body the engine already received instead of replaying a
  fresh GET
- Site permissions (camera, microphone, location, notifications, screen
  share, and more) with a stored-permissions manager in Settings
- Offline password vault: logins are stored only as Android
  Keystore-backed AES-GCM ciphertext, never transmitted, never displayed,
  and can be offered back on the exact same origin (private tabs excluded);
  the vault UI stays locked until device authentication succeeds

## Download and install

Signed release builds are published on the
[Releases page](https://github.com/hexorastudiobusiness-wq/HEXORA-WEB-BROWSER/releases).

Pick the APK for your device:

| File | For |
| --- | --- |
| `app-arm64-v8a-release.apk` | Most modern phones |
| `app-armeabi-v7a-release.apk` | Old 32-bit phones |
| `app-x86_64-release.apk` | Emulators |
| `app-release.aab` | Google Play upload (cannot be installed directly) |

Debug builds (for testing the latest changes) are published as
`debug-<commit>` prereleases with the same three APK splits.

### Why does Android still warn during installation?

The release APK **is signed** — with Hexora's dedicated release key.
Signing proves the file was not modified since it was built and that
future updates really come from the same key. That is all a signature
does.

The "install unknown apps" (and occasionally Play Protect) warning is
about the **installation source**, not the signature. Android trusts
apps installed from Google Play or another managed/trusted source; an
APK downloaded with a browser or opened from a file manager is treated
as untrusted no matter how it is signed. This warning appears for every
sideloaded app, including correctly signed ones.

- To install anyway: allow "Install unknown apps" **only for the app you
  opened the APK with** (e.g. your browser or file manager). You can turn
  it off again afterwards.
- The warning only disappears completely when Hexora is installed from
  **Google Play**. That is what the signed `app-release.aab` is for:
  upload it to Play Console and let users install from the Play Store.

### Release vs. debug builds

Release and debug builds use **different signatures**, so Android
refuses to install one over the other. Uninstall the debug build before
installing the release build (and vice versa). Release builds are signed
in CI; the signing key is never stored in this repository — see
[`signing/README.md`](signing/README.md).

## Architecture

MVVM + Repository pattern. Compose UI observes `StateFlow`, sends actions
to ViewModels, and never talks to GeckoView or the database directly:

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

See [`ARCHITECTURE.md`](ARCHITECTURE.md) for the full specification and
[`AGENTS.md`](AGENTS.md) for the developer/agent guide, including the
release-signing rules that must never be broken.

## Project structure

```text
app/src/main/java/com/example/browser/
├── MainActivity.kt / BrowserApplication.kt / AppContainer.kt
├── browser/        BrowserEngine abstraction + the only GeckoView code
├── data/           Room database (+ migrations), repositories, settings
├── download/       In-app download engine (HTTP + engine streams)
├── ui/             Compose screens and ViewModels (browser, tabs,
│                   bookmarks, history, downloads, passwords, settings,
│                   theme, shared components)
└── util/           URL handling, thumbnail/shortcut-icon stores
signing/            Release-signing guide (no key material, ever)
.github/workflows/  Debug CI, signed-release CI, one-time key bootstrap
```

## Development

Requirements: JDK 17 and Android SDK platform 37. Toolchain versions are
pinned in `gradle/libs.versions.toml` (Gradle 9.3.1, AGP 9.1.1 with
built-in Kotlin, GeckoView 158, compileSdk/targetSdk 37, minSdk 26).
Supported focus: Android 12–17 across OEM skins and screen sizes.

Every push to the working branch builds the debug APK on GitHub Actions
and publishes it as a `debug-<short-sha>` prerelease. Signed release
builds are produced by the **Build signed release** workflow; the
dedicated release key exists only as GitHub Actions secrets — read
[`signing/README.md`](signing/README.md) before touching anything
release-related.

Build the debug APK:

```bash
./gradlew assembleDebug
```

Run the unit tests:

```bash
./gradlew testDebugUnitTest
```

## Known limitations

- GeckoView lets sites use their own dark CSS through
  `prefers-color-scheme`, but — unlike Chrome — it does not
  algorithmically recolor sites that ship no dark theme, so such pages
  can remain light in Dark/Hexora Mode.
- Plain-URL downloads are fetched with a fresh HTTP request, so they do
  not carry the browsing session's cookies; downloads that need the
  session (blob, POST, authenticated responses) work because they arrive
  through the engine's response-body path instead.
- One-shot stream downloads (blob/POST) cannot be paused and resumed:
  the response body exists exactly once. HTTP(S) downloads can resume.
- "Add to Home screen" creates a launcher shortcut; GeckoView does not
  expose installable-PWA manifests, so true standalone PWA installation
  is not possible through the verified engine API.
- Saved-login autofill depends on sites exposing recognizable
  username/password fields to GeckoView; with several logins for one
  origin, the most recently saved/updated one is offered first.
- Runtime behavior of each build still needs on-device verification by
  the owner; CI verifies compilation and unit tests, not real-device
  rendering.

## License

Add the project's chosen license here before public release.

Debug audit 0.4.4 (versionCode 20): see [AUDIT.md](AUDIT.md) for fixes and verification boundaries.
