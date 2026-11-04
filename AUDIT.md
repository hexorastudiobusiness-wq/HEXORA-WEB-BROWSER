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
