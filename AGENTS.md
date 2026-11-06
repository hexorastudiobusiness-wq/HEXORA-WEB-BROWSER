# Hexora Browser — Guide for Future Agents and Developers

> **Read this file first.** Then read `README.md`, `ARCHITECTURE.md`, and —
> for anything release-related — `signing/README.md`.
>
> This file exists so the next agent does **not** have to rediscover the
> project's state, constraints, or release-signing setup by trial and error.
> If reality changes, update this file in the same commit as the change.

---

## 1. What this project is

Hexora is a personal Android web browser for heavy daily use. Its three
non-negotiable principles, in this order of spirit, are:

1. **Secure**
2. **Simple**
3. **Fast**

Never add a feature that sacrifices one of these. If a requested feature
risks doing so, say so explicitly before implementing it. This is a personal
browser, not a Chromium clone: do not add market-driven feature bloat.

Hard technical identity:

- Kotlin + Jetpack Compose + Material 3
- **Mozilla GeckoView** behind a `BrowserEngine` abstraction — never Android
  WebView
- Room, DataStore, Coroutines, StateFlow, Jetpack Navigation Compose
- Package / namespace: `com.example.browser`; applicationId: `com.hexora.browser`
- App label: **Hexora Browser**
- Repository: `hexorastudiobusiness-wq/HEXORA-WEB-BROWSER`

Current shipped state (update this section on every release):

- Released version **1.0.0**, `versionCode` **42** — signed with the release
  key and published as GitHub Release **`v1.0.0`** (arm64-v8a APK only)
- Development builds: GitHub prereleases named `debug-<short-sha>`
- Previous signed release before the rewrite: `v0.3.3`
- Room database version **5** (direct migrations 1→5, 2→5, 3→5 and 4→5
  exist in `DatabaseMigrations.kt`)
- `minSdk` 26, `targetSdk`/`compileSdk` 37 — the supported/tested focus is
  Android 12–17, across OEM skins and screen sizes

---

## 2. Non-negotiable engineering rules

1. **Work in the repository and finish the job.** Implement, build, and
   deliver. Do not stop at a plan or a status report when the task was to
   produce something.
2. **Never invent GeckoView APIs.** If an API is uncertain, inspect the
   official GeckoView documentation or the dependency source for the pinned
   version first. GeckoView imports belong in exactly one file:
   `browser/GeckoBrowserEngine.kt`.
3. **No GeckoView calls, database calls, or business logic in Composables.**
   UI observes `StateFlow` and sends explicit `BrowserAction`s to the
   ViewModel. The ViewModel talks to `BrowserEngine` and repositories.
4. **No WebView.** Do not replace GeckoView with Android WebView unless the
   owner explicitly instructs it. Do not rewrite the whole project when a
   targeted fix is enough.
5. **Keep the build green.** After every major step the project must compile
   and unit tests must pass (CI does this; see §7).
6. **No unnecessary dependencies, permissions, or hardcoded user-facing
   strings.** User-facing text goes in `res/values/strings.xml`.
7. **Security baseline — do not regress:**
   - `android:allowBackup="false"` in the manifest.
   - Only **http/https** navigation targets are accepted: address bar,
     `ACTION_VIEW` intents, home-screen shortcuts, and the download layer.
     Other schemes (`file:`, `intent:`, `data:`…) become search queries.
   - Never log full download URLs (they can contain private tokens).
   - Private tabs: no history, no persisted tab-preview screenshot, no
     entry in the in-app download history. The tab switcher shows a lock
     placeholder for them.
   - Room has **no destructive-migration fallback**. Schema changes must
     ship an explicit `Migration` (see `DatabaseMigrations.kt`).
   - Saved logins exist only as Android Keystore-backed AES-GCM ciphertext,
     on-device only, never transmitted, never displayed.
8. **Git hygiene.** Never commit build outputs, `.gradle`, IDE files,
   local configuration, secrets, passwords, or signing keys — **including
   into this private repository** (see §8; this is absolute).

---

## 3. Architecture invariants (do not break)

- All tabs live in `BrowserViewModel`, keyed by a stable UUID `tabId`.
  Session events are mapped back to tabs via `BrowserSession.id`.
- **One `GeckoSession` and one `GeckoView` per tab**, never shared between
  unrelated tabs — Mozilla's own model (`GeckoEngineView` owns a GeckoView per
  tab). The engine owns a single `FrameLayout` per Activity; every live tab's
  host is a child of it and exactly one child is visible. A tab switch changes
  visibility only: no re-bind, no display release/acquire, no session restore.
  The earlier design (one shared `GeckoView`, session swapped per switch) was
  removed in 0.5.0 because it reloaded tabs and shared one compositor between
  unrelated pages. **Do not regress it** (no shared view; no priority
  demotion of live sessions).
- Memory control is **tab suspension** following Firefox's model:
  sessions are created lazily, the selected tab gets high priority, and
  low-memory/trim signals suspend colder inactive tabs (engine state
  saved, session closed, restored on switch). The selected tab plus the
  3 most recently used inactive tabs stay warm
  (`SUSPEND_KEEP_INACTIVE_TABS`); there is no fixed cap on live sessions
  in normal use.
- Closing the **last tab is allowed**: zero tabs is a valid state and shows
  the Start Page.
- `window.open` / `target=_blank` sessions are created by the engine; the
  ViewModel adopts them and records the **real opener tab** (opener session
  id passed through `newSessionCreatedListener`). Do not add another
  `loadUrl` for such sessions.
- System Back priority: exit fullscreen → web history Back → if a
  page-opened tab is at its history root, close it and return to its opener
  tab → leave Start Page overlay → exit app. `MainActivity` is
  `singleTask` with predictive Back enabled; external http(s) links arrive
  via `onNewIntent` as a new tab.
- History is recorded only on a loading true→false transition, for
  successful, non-private, http(s) pages.
- Downloads flow: Gecko `onExternalResponse` → `BrowserSessionEvent` →
  ViewModel event → `MainActivity` → `DownloadHandler` (Hexora's own
  transfer engine, public Downloads folder, no storage permission).
  Plain HTTP(S) downloads are fresh requests without session cookies;
  blob/POST/authenticated downloads consume the engine's response body
  instead (one-shot: not resumable). Transfer state is persisted in Room
  so the Downloads page survives process restarts.
- Text zoom is one global persisted setting (50–200%, default 100%),
  applied via `GeckoRuntimeSettings.fontSizeFactor`, effective on next
  reload/navigation. UI control: `ui/components/ZoomSlider.kt` (shared by
  the browser menu and Settings, includes reset-to-100%).
- The app theme also drives web content via GeckoView's official
  `preferredColorScheme` (`SYSTEM/LIGHT/DARK`; Hexora Mode maps to Dark),
  set at runtime creation and on every theme change. This lets sites use
  their own `prefers-color-scheme` dark CSS. GeckoView has **no**
  Chromium-style algorithmic forced-dark; never fake one with invented
  APIs or injected CSS hacks without an explicit owner decision.
- Find in Page searches as the user types (debounced). Results are tied to
  their query (`FindResultUi.query/completed`) and stale Gecko callbacks
  for edited text are rejected. "No results" may only be shown for a
  completed result matching the current text exactly.

The Start Page shows **only user-created shortcuts** (max 10, long-press
Edit/Delete, cached site favicon or letter placeholder). Never reintroduce
Most Visited/history-derived tiles. The bottom bar's Refresh lives only
there; the top bar's trailing slot is Downloads. Themes are exactly:
System, plain-white Light, Dark, and opaque Hexora Mode
(`#E77E3D` on `#100B0F`) — no Liquid Glass, no translucency effects.

---

## 4. Code map (where things live)

```text
app/src/main/java/com/example/browser/
├── MainActivity.kt          singleTask Activity, NavHost, intent/download routing
├── BrowserApplication.kt    AppContainer holder, debug StrictMode
├── AppContainer.kt          manual DI, Room builder WITH migrations
├── browser/
│   ├── BrowserEngine.kt     engine interface + WebColorScheme (no Gecko imports)
│   ├── BrowserSession.kt    session interface, page state, session events
│   ├── BrowserSettings.kt   session config
│   └── GeckoBrowserEngine.kt  ONLY file importing GeckoView
├── data/
│   ├── database/            Room entities, DAOs, AppDatabase (v5), DatabaseMigrations
│   ├── repository/          history/bookmarks/downloads/credentials/shortcuts/favicon
│   ├── security/            CredentialCipher (Keystore AES-GCM)
│   └── settings/            DataStore SettingsRepository + ThemeMode
├── download/                DownloadHandler (own transfer engine), DownloadFileName
├── ui/                      Compose screens + ViewModels per feature
│   └── components/          AddressBar, ZoomSlider (shared controls only)
└── util/                    UrlUtils, stores (thumbnails/shortcut icons), shortcuts
```

Tests: `app/src/test/...` (JUnit 4) — URL resolution, download filenames,
credential origins. There are no instrumented tests; runtime behavior is
verified on a real device by the owner.

---

## 5. Toolchain (pinned — change deliberately, not casually)

From `gradle/libs.versions.toml` and `app/build.gradle.kts`:

- Gradle 9.3.1, AGP 9.1.1 (built-in Kotlin; do **not** re-add the
  kotlin-android plugin — it breaks AGP 9), JDK 17
- compileSdk/targetSdk 37, minSdk 26
- Compose BOM 2024.12.01, Room 2.7.2, DataStore 1.1.1, Coroutines 1.9.0
- GeckoView `158.0.20261007201254` (Mozilla Maven)
- ABI splits: arm64-v8a, armeabi-v7a, x86_64; no universal APK

---

## 6. Working agreements

- The owner communicates in **Persian** and is not a developer. Explain in
  plain Persian, avoid unexplained jargon, and when something needs the
  owner, reduce it to the fewest possible clicks — ideally in the browser,
  with nothing to install.
- **Never ask the owner for passwords, tokens, or secret values**, and
  never send secret values through chat.
- Keep changes targeted and buildable. Prefer fixing at the ViewModel /
  engine layer over patching Composables.
- Update docs (`README.md`, `ARCHITECTURE.md`, this file, and
  `signing/README.md` when relevant) in the same commit as behavior
  changes.
- Final report for every task, in Persian: what was implemented, files
  changed, build result, tests actually performed, remaining limitations,
  and the exact debug build command `./gradlew assembleDebug`. **Never
  claim runtime behavior works unless it was verified** — CI compile plus
  unit tests do not prove on-device behavior.

---

## 7. Build, CI, and the working branch

- Local sandbox machines may have no JDK/Android SDK: the build authority
  is **GitHub Actions** (`.github/workflows/build-debug-apk.yml`). Every
  push builds `./gradlew assembleDebug`, runs `./gradlew
  testDebugUnitTest`, and publishes a `debug-<short-sha>` prerelease with
  the three split APKs. Debug APKs share a stable cached debug keystore so
  updates install over each other.
- Commit logically, push only the session/working branch you were given,
  watch the CI run to completion, and verify the release assets exist
  before telling the owner a build is ready. If CI fails, the workflow
  opens an issue containing the Gradle log tail — read it and fix forward.
- Do not force-push over the remote branch; it may contain commits your
  local clone has not fetched. Fetch and integrate instead.

---

## 8. RELEASE SIGNING — READ BEFORE TOUCHING ANYTHING

**There is a dedicated release key. It already exists. Do not create a new
one, and never commit key material anywhere.**

Canonical reference: **`signing/README.md`** — keep it in sync.

The essentials, so nobody has to argue about this again:

- Release builds are signed in CI (`.github/workflows/build-release-apk.yml`)
  with a dedicated PKCS#12 keystore (alias `hexora-release`). The keystore
  exists **only** as Base64 in GitHub Actions secrets. Gradle reads it from
  CI environment variables (`app/build.gradle.kts`); there is no keystore
  file in this repository and there must never be one. `.gitignore` blocks
  `*.p12/*.pfx/*.jks/*.keystore` — do not circumvent it.
- The configured secret names (the owner chose them; **use exactly these**):
  - `HEXORA_WEB_BROWSER_RELEASE_KEYSTORE_BASE64`
  - `HEXORA_WEB_BROWSER_RELEASE_KEYSTORE_PASSWORD`
  (These are repository *or* organization secrets scoped to this repo —
  either works. Secret *names* are safe to discuss; **values never are**.)
- The owner keeps the offline backup (`hexora-release-key-backup` ZIP:
  keystore, password, Base64 text, fingerprint). **If that backup and the
  secrets are both lost, the app can never be updated again** — a new key
  means a new identity and existing installs cannot upgrade. Treat any
  request to "just make a new key" as a last-resort, owner-confirmed
  decision with that consequence stated plainly.
- The key-generator workflow has been **deleted from the repository**. It
  produced a keystore and published it as a workflow artifact, which is
  unacceptable on a public repo. Generate a replacement offline, if the owner
  ever abandons the current identity, and never publish the file.
- Shipping a signed release:
  1. Bump `versionName` and **`versionCode`** (must increase) in
     `app/build.gradle.kts`; update the docs' version references.
  2. Commit and push; wait for the debug CI to go green.
  3. Run the release build: **Actions → Build signed release → Run
     workflow**, on `main`. The job refuses any other ref, so a release can
     only be built from merged code — the keystore is decoded on that
     runner.
  4. Confirm in the run: "Verify APK signatures" passed (it runs
     `apksigner verify` on every APK and `jarsigner -verify` on the AAB),
     and the GitHub Release `v<versionName>` contains all three APKs plus
     `app-release.aab`.
  5. Give the owner the release links. Remind them: release and debug
     builds have different signatures, so the debug app must be
     uninstalled before installing the release build (and vice versa).
- Sideload warning: a signed APK installed from a browser/file manager
  still triggers Android's "unknown apps" permission and possibly a Play
  Protect scan. **That is expected and is not a signing problem** —
  signing proves integrity and update identity, not store trust. The
  warning only disappears when the app is installed from Google Play (use
  the signed AAB + Play App Signing) or another trusted/managed source.
  Do not try to "fix" it by weakening security settings.

---

## 9. Known limitations (honest list — keep current)

- Sites without their own dark CSS stay light in Dark/Hexora Mode
  (no algorithmic forced-dark in GeckoView).
- Plain-URL downloads are fresh HTTP requests without cookie/auth
  carry-over from the browsing session; blob/POST/authenticated
  downloads work through the engine's one-shot response body, which
  cannot be paused/resumed or replayed after interruption.
- Saved-login autofill depends on sites exposing recognizable fields to
  GeckoView's autofill tree; the most recently saved login per exact
  origin is offered.
- "Add to Home screen" is a launcher shortcut, not an installable PWA
  (GeckoView does not expose web-app manifests to the embedder).
- No embedder-facing Safe Browsing or web-notification display surface
  was found in the pinned GeckoView API; do not claim either feature.
- On-device runtime behavior (rendering, Back flows, Find, theming) must
  be verified by the owner on real hardware — CI cannot prove it.

## 10. Definition of done

- [ ] Code compiles in CI and unit tests pass
- [ ] No new permissions, dependencies, or Gecko imports outside the engine
- [ ] Security baseline (§2.7) intact
- [ ] Docs updated (README / ARCHITECTURE / this file / signing guide)
- [ ] Version bumped if shipping a build
- [ ] CI run watched to success and output assets verified
- [ ] Persian final report with limitations stated honestly

Debug audit 0.4.5 (versionCode 21): see [AUDIT.md](AUDIT.md) for fixes and verification boundaries.

## 0.5.8

* `util/AppLog` is the app's own log (250-line ring buffer, last crash). Log
  events worth reporting; never log URLs, titles or credentials.
* `util/DeviceInfo` reads device/build/ROM identity (`getprop` for vendor
  versions). `util/ShakeDetector` is the shake gesture; `util/BugReport` builds
  the report and the mail/contact intents. `util/BrowserRole` owns
  `RoleManager` and the default-browser flow.
* `ui/components/ReportDialogs.kt` holds the bug-report and default-browser
  dialogs. They are shown from `MainActivity`, because both need the Activity
  (launchers, mail intent) and the ViewModel (app state).
* No in-app diagnostics UI. Engine counters (`BrowserEngine.diagnostics()`) are
  only consumed by the bug report now.
* After a return from the background the engine re-asserts the display:
  `noteAppBackgrounded()` snapshots the paint-reset counter,
  `verifyPaintAfterResume()` re-binds a surface that never painted again, and
  `setContentHidden()` always re-applies visibility. Never "fix" a black tab by
  reloading it.
