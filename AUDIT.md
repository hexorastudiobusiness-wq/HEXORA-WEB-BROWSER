# Debug audit — 0.4.4

## Corrected

- Fullscreen is forbidden on Start Page/zero-tab content; switching to an
  empty tab clears user fullscreen. Empty tabs create no engine session.
- Removed a duplicated, malformed tail in BrowserViewModel from unbuilt edits.
- Startup guard now actually bypasses saved engine state after an early death.
  Stability is delayed ten seconds on Main, including Start Page launches.
  This is a heuristic, not proof that Gecko cannot crash later.
- Empty normal tabs and their Start Page flag now survive persistence;
  private tabs remain excluded. Older saved tab JSON remains readable.
- Mutable tab changes explicitly invalidate derived state: copying a list
  containing identical mutable entries did not emit through StateFlow.
- Tab snapshots capture session state on Main, then write on IO. A mutex
  serializes immediate/debounced writers; IO failure does not kill browsing.
- Download retry no longer starts a duplicate while a paused request unwinds;
  completed downloads cannot be resumed as new transfers. IO exceptions
  caused by cancellation cannot overwrite CANCELLED with FAILED.
- History and shortcut read errors cannot terminate startup collectors.

## Verification and boundaries

- Added exhaustive fullscreen policy unit cases (all Boolean combinations).
- Inspected engine delegates, shared surface/session ownership, tab bootstrap,
  persistence, permissions, download transfer/cancel/resume, Activity lifecycle,
  settings, repository/Room migrations, credential encryption and manifest.
- Rechecked Mozilla Android Components TabSessionState (immutable value state)
  before changing tab invalidation. No new Gecko APIs or dependencies.
- This is a targeted static audit, **not certification that every line of
  every file is bug-free**. UI/OEM rendering, IME, native hangs, real transfers
  and process death still need device testing. No emulator/device run here.
- Build authority: GitHub Actions, `./gradlew assembleDebug --stacktrace`
  and `./gradlew testDebugUnitTest --stacktrace`. See the run for results.
- Signing keys, key caches and release workflow are unchanged. Debug only.

## Follow-up: device-reported refresh and background black surface

Unbuilt working-tree fixes (no workflow triggered):

- The old refresh code mistook vertical overscroll capability for being at
  the top. Follow Chromium's top-at-scroll-start invariant and Mozilla's
  NestedGeckoView APZ ownership/direction gates. Reject the whole gesture
  after a veto; never acquire refresh midway through ordinary scrolling.
- Root scroll position is supplied by the verified Gecko ScrollDelegate;
  unknown position relies on mandatory APZ confirmation. APZ must permit top overscroll, so
  nested scroll containers and overscroll-behavior can veto the gesture.
- Touch generations isolate late results; only DOWN/first significant MOVE
  allocate detail results (per Gecko guidance). Pending APZ replies finish before
  any reload decision. Indicator resets immediately on release/cancel;
  horizontal/upward/pinch/cancelled gestures cannot turn into refresh.
- Background recovery now recreates the AndroidView HOST using a Compose
  key, following the tab-selector exit/re-entry path reported to recover
  rendering. Removed the ineffective same-child/same-host reattach API.
  Sessions are retained; no reload, timer loop, or speculative Gecko API.
- Added eight pure gesture regression tests. NOT executed in this sandbox;
  no build was requested/triggered for this follow-up. Static diff checks
  passed. No claim of Samsung/Xiaomi device verification.

Sources inspected: Chromium ui/android/overscroll_refresh.cc;
Mozilla Android Components GeckoEngineView.kt, NestedGeckoView.kt and
concept/engine/InputResultDetail.kt; Gecko PanZoomController.java and
GeckoSession.ScrollDelegate. This is Chrome-like eligibility on Gecko,
not a claim of identical Chromium internals, animations or thresholds.


## Debug 0.4.5 follow-up

- Unknown root scroll position no longer permanently disables refresh on SPA
  documents. Known positive offsets still veto the gesture. APZ must confirm
  top eligibility; page-owned touch/overscroll restrictions are respected.
- Ten gesture regression tests now cover unknown/known root offsets, APZ veto,
  late veto, downward threshold, reversal, horizontal movement and cancellation.
- Activity download-start/metadata boundaries preserve coroutine cancellation
  but contain ordinary failures. Notification permission-launch failure cannot
  terminate the download collector. No URL/credential logging was added.
- Foreground service promotion SecurityException/IllegalStateException is
  caught in the service itself, not just its caller; Android dataSync timeout
  stops the service within the system grace period. If promotion is denied,
  foreground-process transfer can still run but background survival is not
  guaranteed. This is not evidence of the owner's exact crash root cause.
- Version 0.4.5 / code 21. Debug build and unit tests requested in CI; the
  earlier "unbuilt" note describes the previous follow-up, not this build.
- Still no physical-device reproduction or exact download-crash stack trace.
  Do not describe defensive guards or CI success as device verification.

## Debug audit — shared content-host ownership (0.4.5, unbuilt follow-up)

Root cause found for the owner-reported "tabs come back black after minimise":

- `GeckoBrowserSession.close()` released the content view through
  `releaseContentView()`, which also removed the single shared `GeckoView`
  from its Compose host and cleared `sharedContentHost`. The still-composed
  `AndroidView` was never re-created (its key is
  `contentViewGeneration`), so the next `update` called
  `bindContentView()` with a null cached host and silently returned: the
  shared view stayed outside the view hierarchy with no session bound and
  the content area showed the theme background until the whole screen was
  rebuilt (tab tray round trip) — the owner's "tab tray fixes it" clue.
- `bindContentView()` now treats the Compose-supplied host as the single
  source of truth and re-attaches the view; `releaseContentView()` splits
  session-initiated release (detach session only, keep the view) from
  UI-initiated release (remove the view, clear the host);
  `handleSharedViewAttached()` falls back to `view.parent`.
- Verified against GeckoView source (GeckoView.java `releaseSession`,
  `setSession`, `Display.acquire/release`; `GeckoSession.setActive`,
  `ContentDelegate.onFirstComposite/onFirstContentfulPaint/onPaintStatusReset`),
  not against a device.
- Structural tab writes: `closeTab()` now persists immediately, and
  `suspendTab()` requests a write after capturing the saved session state
  (previously the suspended state was only written if something else
  triggered a persist).
- NOT done, and deliberately left as recommendations (see `REPORT-FA.md`):
  implementing the paint-status delegates as the recovery source of truth,
  moving trim-memory policy to `Application`, flushing state with
  `setActive(false)` before `close()`, moving session-state serialization
  off the main thread, and re-keying thumbnails for restored tabs.
- No device run, no emulator run, no CI run in this sandbox. Emoji behaviour
  was not changed: Gecko uses the system emoji font and bundles none; the
  report lists the ranked hypotheses and a three-step comparison test.

## Content surface, second pass (0.4.6, `571a567`)

The 0.4.5 host-ownership theory (`bad689a`) did not fix the black tab on device.
The owner's exact signature — single tab, ~5 s minimised, **reload does not
help**, **tab switch does not help**, only the tab-tray round trip does — was
then traced to GeckoView's own source (geckoview 158, `GeckoView.java`):

1. `coverUntilFirstPaint(color)` is not cosmetic. It stores the colour in
   `mLastCoverColor`, pushes it to the session compositor as the clear colour
   (`setClearColor`), and paints an **opaque** SurfaceView-wrapper background
   (`coverUntilFirstPaintInternal`). Only `uncover()` (registered as the
   compositor's FIRST_PAINT callback in `setSession`) restores transparency.
   FIRST_PAINT fires "for the first content composite after a start or a
   restart of the compositor" — on Hexora's single long-lived view the
   compositor is not restarted per tab/resume, so a cover applied at the wrong
   moment can never be removed. Reloading and switching sessions repaint
   *under* the cover; recreating the view restarts the compositor and removes
   it. That reproduces the reported behaviour exactly.
2. `GeckoView.setSession()` returns early when the session is already set,
   acquires the display only when the view is attached (`onAttachedToWindow`
   re-acquires it), and `releaseSession()` drops the display, the first-paint
   callback, autofill/selection/print delegates, focus and session ownership.
   Detaching the view therefore deactivates the session; re-binding it forces a
   full repaint — which is what looked like "tabs reload on switch".

Changes: the content host and GeckoView are engine-owned, created once per
Activity and reused for its whole life; the composable never recreates or
unmounts them; the Start Page/crash card are drawn on top of the surface; no
`coverUntilFirstPaint` call remains; `onFirstComposite` and
`onPaintStatusReset` feed a paint-status flag and, if no frame arrives after
the window has a surface again, the engine re-acquires the display
(release + `setSession`, no reload). `contentViewGeneration` and the 30 s
background-rebind timer are gone.

Pull-to-refresh was rebuilt on `SwipeRefreshLayout` plus a `NestedScrollingChild`
GeckoView following `NestedGeckoView`: `onTouchEventForDetailResult` →
`PanZoomController.InputResultDetail` decides whether a gesture may leave the
page; `requestDisallowInterceptTouchEvent` is released only when Gecko reports
the content cannot scroll up and the site did not consume the touch. The custom
indicator, `SwipeableHostLayout.onDraw` painting, `rootScrollYProvider` and
`PullToRefreshGesture` were removed.

Still unverified on device: everything above is source-level reasoning. The
next diagnostic step, if the black pane survives, is the SurfaceView path
(`GeckoDisplay.surfaceChanged/surfaceDestroyed`, SurfaceView punch-out vs.
Compose overlays) with a paint-event-only log (no URLs).

## Load failures, retry and the password vault (0.4.7, `a9d7bb2`)

Owner-reported, carried over from 0.4.6: pages that come up blank with
"This page could not be loaded." on several sites (Google sign-in,
arena.ai), Retry doing nothing and then turning into "This tab crashed",
and the password vault writing a previously saved Gmail address *and*
password into a page without asking.

What the code actually did:

1. `onPageStop(success = false)` set `loadFailed` and that was all: the
   engine never implemented `NavigationDelegate.onLoadError`, so Gecko's
   `WebRequestError` (code + category) — the only description of *why* a
   load failed — was discarded. The card could therefore only say
   "could not be loaded".
2. `reload()` called `GeckoSession.reload()`. After a failed load Gecko
   halts the navigation and leaves no document, so reload is a no-op: the
   Retry button could never work. Retrying a session whose content process
   was gone is also what produced the crash card.
3. `Autofill.Delegate.onNodeFocus` called `fillSavedCredential`, which
   wrote the stored username and password into the page immediately. No
   prompt, no tap, no consent.

Changes: `onLoadError` is implemented and records code/category (URI is
never stored or logged); the card shows a localized reason (network,
offline, security, redirect loop, renderer stopped) plus the numeric code
in small print; `reload()` re-loads the failed URL and Retry restores a
dead tab instead of reloading it; filling requires an explicit tap on the
new suggestion bar (`SavedLoginOffered` → `fillSavedCredentialNow()`), with
the exact-origin check repeated at fill time.

Still open: the *cause* of the failing loads themselves is not yet known.
The next report will carry the numeric code, which distinguishes a network
failure, a certificate problem, a renderer crash, a redirect loop, and a
navigation denied by the app's own scheme allowlist (code 0 + nothing in
the page = the app refused the navigation).

## Scrolling regression, app-side reloads, unconditional display repair (0.4.8)

Device feedback on 0.4.7: the page could not be scrolled at all, tabs still
reloaded on every switch, and the pane still went black after ~10 s minimised.

* The SwipeRefreshLayout wrapper was the scrolling regression. A parent that
  intercepts touches needs a synchronous "can the child scroll up?" answer; a
  GeckoView has none, so ordinary downward drags were turned into refresh
  pulls and page scrolling stopped. Removed: the GeckoView is now wrapped in a
  plain FrameLayout and keeps every touch event. Pull-to-refresh is recognised
  *inside* the GeckoView from Gecko's own `InputResultDetail`
  (`SCROLLABLE_FLAG_TOP` / `INPUT_RESULT_HANDLED_CONTENT` decided on DOWN), and
  only reports progress to the UI, which draws the indicator.
* Removed every app-side path that rebuilt a live session (rebuilding a session
  is a real reload): `onAppForegrounded` no longer polls `isUsable()`,
  `switchTab` only restores tabs that have no session, `onBrowserResumed` only
  restores without a session, `bindContentView` no longer calls
  `ensureOpen()`/`restoreState()`, and `releaseContentView` no longer releases
  the bound session when the Compose node goes away.
* The display is now re-acquired unconditionally on every foreground return
  (release + `setSession`), which is the same repair a freshly created view
  performs, with no timers and no paint-callback guesswork.
* New Diagnostics dialog in the overflow menu: content binds, paint resets,
  first paints, display recoveries, sessions created/restored/suspended, active
  session liveness and the last load error code/category. Counters only; no
  URLs or

  page titles. This exists because the two remaining reports cannot be settled
  from source alone.

## Per-site zoom and desktop mode, floating zoom panel (0.4.9, `d77c3bf`)

Owner's report: after a full app restart a page was rendered at 100% while the
zoom UI still showed 75%, and it only corrected once the slider was touched.

* Root cause: zoom was a single global value applied **once**, from the
  settings flow, after the engine was created. Any page whose `onPageStart`
  ran before that (a restored tab, a cold start, a tab switch to an already
  loaded page) kept rendering at the previous factor. The UI and the page
  therefore disagreed, and both were "right" from their own point of view.
  `GeckoSessionSettings` has no font-size field either, so the factor cannot be
  baked into a saved session — it has to be applied per page start.
* Fix: new `data/settings/SitePreferencesStore` — a second DataStore
  (`site_preferences`) keyed by `zoom/<host>` and `desktop/<host>`, host
  lowercased with a leading `www.` removed. No URLs, no titles, no paths are
  stored, and a value equal to the default (100% / desktop off) is deleted
  rather than written, so the file cannot grow without bound.
* `GeckoBrowserEngine.applySiteZoom(url)` runs as the first statement of
  `onPageStart` and resolves the site's zoom through `siteZoomProvider`. On cold
  start the ViewModel **awaits** `zoomByHost.first()` before creating the
  engine, so the very first restored page already renders at the stored zoom.
  The global `textZoomPercent` setting is retired (its key is still read, but
  nothing applies it).
* Desktop site is per domain too: applied before a navigation starts
  (`openUrl`, `LoadInput`, link/new-tab creation) and to tabs restored on
  launch, so the mode never has to be re-applied by reloading the page.
* UI: the overflow menu no longer contains a slider. It has a **Zoom** item
  that closes the menu and opens `SiteZoomPanel`, a translucent vertical
  slider docked to the right edge (76x320dp, centred), with the percentage, a
  visible filled track, a large thumb and a `100%` reset button. Percent steps
  are 5% because every change reflows the page; a drag therefore issues at most
  ~30 factor changes instead of one per pixel. The Settings screen no longer
  has a zoom slider at all; it explains that zoom is per site.
* Bounds and defaults: 50–200%, default 100%, clamped in the store, in the
  ViewModel and in the engine.

### General pass alongside 0.4.9

* Removed the now-dead `ZoomSlider` composable and every `SetTextZoom` /
  `textZoomPercent` UI state and action, so no second path can apply a
  different zoom than the panel shows.
* Writing a site preference no longer goes through the settings flow, so
  dragging a slider cannot re-emit (and re-apply) the whole settings object to
  every open session.
* Tab restore now prefers the site's stored desktop mode over the mode saved
  with the tab; the saved value stays as the fallback for private/blank tabs.
* Persisting a zoom change is debounced (350ms) during a drag and flushed
  immediately when the panel is closed, so a drag writes once but a close never
  loses the last value.

### Verification and boundaries

* 0.4.9 was built by GitHub Actions: `assembleDebug` and
  `testDebugUnitTest` are green on `d77c3bf`; the APK is the
  `debug-d77c3bf` prerelease (versionCode 25, versionName 0.4.9).
* The first push of this round failed compilation (`onDrag` is
  `onVerticalDrag` in `detectVerticalDragGestures`); the failure was read from
  the auto-filed CI issue and fixed in `d77c3bf`. That is the only compile
  error of the round.
* Still not device-verified: the panel's reachability with a real thumb, how
  live reflow feels on a heavy site, and whether any site ignores a text
  factor. Those need the owner's phone, not a sandbox.
* No new dependencies. Signing, workflow and release process unchanged; debug
  build only.

