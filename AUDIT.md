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
