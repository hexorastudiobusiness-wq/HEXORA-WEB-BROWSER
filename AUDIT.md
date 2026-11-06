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


## One GeckoView per tab — the content-layer migration (0.5.0, `d31ca77`→`0ea58ea`)

Owner's verdict on 0.4.10: tabs still reload on switch, some sites come back
scrambled and need a manual refresh, Home-screen "Add shortcut" does nothing,
and Google's sign-in lands on the retry card. The decision was to stop patching
the shared-surface design and adopt Firefox's model.

### What changed

* **One `GeckoView` per tab.** `GeckoBrowserSession` creates its own
  `SwipeableHostLayout` + `HexoraGeckoView` (`obtainSurface`) the first time the
  tab is shown, and keeps it afterwards. The engine owns a single `FrameLayout`
  per Activity (`obtainContainer`); every live tab's host is a child, exactly
  one is `VISIBLE`, the rest are `INVISIBLE` (`present`). A tab switch now only
  changes visibility: no display release/acquire, no session re-bind, no
  restore from saved state.
* **Removed:** `sharedContentView`, `sharedContentHost`, `renderedSession`,
  `sessionToRestoreOnAttach`, `bindContentView`, `releaseContentView`,
  `handleSharedViewDetached/Attached`, `isRenderedSession`, and the
  release+`setSession` "display repair" that used to run on every foreground
  return. Two tabs can no longer share a compositor, which is what produced the
  cross-tab repaint ("sites get scrambled").
* **Thumbnails** are captured from the tab's own visible surface, so a preview
  can no longer show another tab's page.
* **Text zoom** is a process-wide Gecko setting; the selected site's factor is
  re-applied when a tab becomes visible (`activateTab`), so the page on screen
  matches the percentage its panel shows.
* **Superseded loads are not failures.** `NS_BINDING_ABORTED` (0x804B0002) is
  what a redirect or a federated sign-in round-trip looks like to Gecko; it no
  longer raises the retry card.
* **Third-party cookie partitioning (TCP) disabled.** Without the Storage
  Access API that Firefox pairs with it, it bounces Google's sign-in into a
  retry loop.
* **Shortcut writes are no longer silent.** Duplicate, limit (10), invalid
  address and success each produce a toast; the previous code returned without
  a word, which is indistinguishable from a dead button.
* **Global text-zoom setting retired** (it was already ignored for rendering
  since 0.4.9); the dead plumbing was removed with it.
* Version 0.5.0 (27), **arm64-v8a APK only** as instructed.

### Reviews performed

1. **Content layer:** every session-close path (`closeTab`, `suspendTab`,
   `handleDeadSession`, close-all, private cleanup) now drops the tab's surface
   through `detachSurface`; `surfaceOwners` is unbound in the same call, so no
   stale view→session entries survive. `present` tolerates a host that belongs
   to a previous Activity by re-creating it (context check in `obtainSurface`).
2. **UI/Compose:** the container `AndroidView` is stable for the Activity and
   never returns a view that still has a parent (same protection as before);
   the Start Page, crash card, error card and zoom panel still draw on top of
   the container, so nothing that used to unmount the surface does now.
3. **Whole app:** leftover references to the shared-view API are gone (one
   stale comment fixed), `strings.xml` has no duplicate names, the unused
   global-zoom key/flow were removed, and `ARCHITECTURE.md`/`AGENTS.md` now
   document the per-tab model as an invariant instead of the old one.

### Boundaries

* Built by GitHub Actions: `assembleDebug` + `testDebugUnitTest` green on
  `2e558ce`, `dfcc9d6` and `0ea58ea`.
* **Not device-verified.** Surface lifecycle, real memory pressure and OEM
  behaviour cannot be exercised in this sandbox; the owner's phone is the only
  place these four reports can be closed.
* If a tab still reloads after this, the reload is no longer ours to remove:
  the tab's `GeckoSession` is never closed on a switch, so the remaining cause
  would be Gecko/Android dropping the document — Visible in Diagnostics as
  `restored` vs `suspended`.

## Zoom ownership and switch-time jank (0.5.1, `21ba34b`)

Device feedback on 0.5.0: everything is much better, but there is occasional
lag, and at 70% zoom a tab sometimes showed 100% for a moment (or "reset" after
a switch/refresh).

* **Root cause of the zoom flicker:** `setFontSizeFactor` is a *runtime*
  setting, not a session one, and `onPageStart` applied it unconditionally. Any
  background tab finishing a load therefore re-pushed its own factor and the
  page in front of the user reflowed to it — the "jumps to 100% then snaps
  back" report. Only the session that is currently on screen may set the
  factor now (`isActiveSession`), and it is applied **before** a navigation
  starts (`loadUrl`, `reload`) so the first layout is already correct rather
  than snapping after `onPageStart`.
* **Second cause of jank:** every push reflows every open document. Identical
  factors are no longer pushed; the applied value is remembered — and only
  after it really reached the runtime, so an early call cannot mask the real
  one.
* `present()` is now a no-op when the session is already on screen. It runs on
  every UI state emission (title, progress, scroll), and it used to walk all
  children and call `bringChildToFront` each time, forcing needless layout
  passes.
* Tab switches no longer perform a GPU surface readback for thumbnails; tiles
  are refreshed after a load and when the tab tray opens.
* Session priority hints are only pushed when they change (each push is an IPC
  round-trip, and a switch walks the whole strip).
* Diagnostics now also prints the applied zoom and the number of live surfaces.

### Review round (structural/logic)

1. **Zoom:** every call site audited — `SetSiteZoom`, panel reset, tab
   activation, `loadUrl`, `reload`, `onPageStart`, `present`. Only the visible
   session can change the factor; the panel's percentage and the rendered page
   cannot disagree any more than the runtime allows.
2. **Surfaces:** `present`/`obtainSurface`/`detachSurface` re-read against every
   session lifecycle path; a closed session returns no surface, and a surface
   is only ever added to the single engine container.
3. **App-wide:** no leftover shared-view references, no duplicate string
   resources, no new API outside the pinned GeckoView, and the diagnostics
   counters still describe the per-tab model.

## The Start Page "+" tile looked dead (0.5.3, local)

Device: tapping the "+" tile on the Start Page did nothing, pressing Back then
closed the app (so no dialog was on screen), and a second tap sometimes worked.

The dialog's open/closed state lived in a `remember` inside `StartPage`. The
Start Page is an overlay drawn over the web surface and recomposes on every
browser state emission (title, progress, scroll), so the request could be lost
between the tap and the frame that should have shown the dialog — matching
"first tap does nothing, second sometimes works".

* The dialog request (`ShortcutDialogRequest`: add when `id == null`, edit
  otherwise) is now ViewModel state and the dialog is composed next to the
  other app-level dialogs in `BrowserScreen`, outside the Start Page subtree.
* The Start Page only dispatches `ShowAddShortcutDialog` /
  `ShowEditShortcutDialog`; the tile no longer owns a dialog.
* The tile uses an explicit `Modifier.clickable` on the clipped tile area
  instead of the Material3 `Surface(onClick)` overload.
* The dialog closes when the write actually succeeds; a rejected URL (invalid,
  duplicate, cap) keeps it open with the toast explaining why.

## Start Page buttons were dead because the web surface took the touch (0.5.4)

Everything else — dialog state in the ViewModel, an explicit clickable — did not
help, which ruled out the dialog path: the tap never reached Compose at all.
The web surface is a real `GeckoView` inside an `AndroidView`; while the Start
Page is drawn above it, that View received the `MotionEvent` first and consumed
it. Since 0.5.0 the surface stays mounted under the Start Page (that is what
stopped the black screens), which is exactly when the buttons stopped working.

Two changes, both needed:

* `HexoraGeckoView.dispatchTouchEvent` returns false (no consumption) while the
  engine says content touch is disabled, and the engine disables it whenever an
  overlay is on top (Start Page, crash card, error card). The page gets touches
  back the moment the overlay goes away; no view is detached, so no surface
  churn and no black frames.
* The Start Page's touch-blocking consumer now consumes on the **Main** pass
  instead of **Final**: children (the tiles) still run first, but the event is
  already marked handled before the dispatch can reach interop views.

Plus a reliability net: the overflow menu carries an "Add shortcut" entry while
another shortcut may be added, opening the same dialog from the chrome, so the
feature is reachable even if an overlay ever swallows a gesture again.

## Start Page touch ownership, third attempt (0.5.5)

Device evidence that settled it: **the "Add shortcut" entry in the overflow menu
worked, the "+" tile did not.** The dialog, the ViewModel action, the database
and the Start Page refresh were all fine; only the tile — the one control drawn
over the web surface — was dead.

* An empty Start Page tab has **no session**: sessions are created lazily
  (`createTab`: "an engine session is only created when the tab is actually
  going to load something"). So `state.activeSession` is null there.
* The 0.5.4 attempt routed the fix through the session
  (`state.activeSession?.setContentTouchEnabled(false)`), which on that screen
  is a no-op: the call never happened, and nothing logged a warning.
* The container also keeps the *previous* tab's surface `VISIBLE` in that state,
  so a live `GeckoView` is still a child view of the Compose host, and a real
  View takes the gesture before Compose content drawn over it.

The flag is now driven from the ViewModel through `BrowserEngine`
(`setContentTouchAllowed`), computed from tab and page state on every revision,
tab change and page-state change, plus explicitly when the Start Page flag flips
or a tab crashes. Surfaces created later inherit the current value in
`bindSurface`. Nothing is detached and no visibility changes, so the black
screen work is untouched.

## Start Page tile: hide the container and measure (0.5.6)

Refusing touches was not enough. Compose resolves the tap inside the content
area to the interop view, so nothing drawn over a live web surface receives it —
even when that view returns false from `dispatchTouchEvent`. The surface is now
made `INVISIBLE` (engine-level, from the same overlay state) while an overlay is
on top: an invisible view is skipped by hit-testing entirely. Nothing is
detached and the session is untouched; only the surface is released, which the
engine already recovers from (`ensureContentDisplayed`, paint-status recovery).

This build also carries the measurement that ends the guessing, printed in the
Diagnostics dialog:

* `shortcutTaps=` — taps that physically reached the "+" tile (counted on the
  Initial pointer pass, non-consuming, so the button's own handling is
  unaffected).
* `touchBlocked=` — gestures the web surface refused while an overlay was up.
* `contentHidden=` — whether the container was hidden at that moment.

Reading it: taps > 0 with no dialog means the tap arrives and the dialog/state
path is at fault; taps = 0 means Compose never saw the touch; touches blocked on
a hidden container would mean the surface is still on the hit path.

## The tap arrives, the click does not (0.5.7)

Diagnostics from the device on a Start Page tab:

```
version=0.5.6 (33) bindings=0 surfaces=0 sessions=0 activeSession=suspended
touchBlocked=0 contentHidden=true shortcutTaps=39
```

That kills the interop theory outright: with **no session and no surface at
all**, there is no `GeckoView` on the hit path, and `shortcutTaps=39` proves the
gesture does reach the Compose node (the counter sits on the tile and runs on
the non-consuming Initial pass). So the touch is delivered, but the standard
`Modifier.clickable` never turns it into a click — its tap only completes when
nothing consumes the gesture on the way back up, and something above the tile
does.

The tile therefore completes the tap itself: the same watcher that counts the
down also decides a tap (down → stay inside `touchSlop` → up) and calls the
same `onClick`. It consumes nothing, so list scrolling is untouched, and
`clickable` stays for ripple/semantics (calling the action twice is a no-op:
the dialog request is a value that dedupes).

_CI note: the 0.5.7 build itself was green; only the "publish APK to a prerelease" step failed, so the same commit is rebuilt here to publish it._

## 0.5.8 — black tabs after a return, and the app around the browser

### The regression: tabs came back black

The owner's report, twice in one session: the app *minimised itself* with no
input, and on return the tabs were still open and still un-refreshed — but
black. Refresh did nothing, switching tabs did nothing; only opening the tab
tray and selecting a tab brought the content back.

What the code says, and what that means:

* the tray repair works because selecting a tab calls `setSession()` /
  `present()` again — it re-acquires the *display*, not the document;
* `ensureContentDisplayed()` (0.5.2) only rebinds surfaces whose `session`
  is `null`. A session that is alive but whose painted content was dropped is
  never touched by it;
* GeckoView reports exactly that case: `GeckoSession.ContentDelegate
  .onPaintStatusReset`, and there is no `onFirstComposite` afterwards. On the
  healthy device session `paintResets=0`, so the counter is meaningful.

The 0.5.8 repair has three parts, all of them "re-acquire the display", never
"reload the page":

1. `noteAppBackgrounded()` snapshots the paint-reset counter in `onStop`.
2. `verifyPaintAfterResume()` runs ~1.2 s after the window is back. If the
   counter moved *and* no frame was composed in that window, the surface is
   re-bound exactly the way re-selecting the tab did it (`setSession(null)` →
   `setSession(session)`), counted as `recoveries=`.
3. `setContentHidden()` now re-applies the container's visibility even when the
   flag did not change. A container left `INVISIBLE` (hidden under an overlay
   when the app went away) stayed invisible on return, because nothing about the
   overlay state had changed to trigger a fresh call. That is a second, purely
   mechanical way to come back to a black pane.

Also on resume: `syncContentTouch()` is called again, so the overlay state that
decides visibility and touch ownership is always re-asserted.

### Diagnostics removed

The window, the overflow entry, the action, both state fields, the tap counter
and its plumbing in the Start Page are gone. The engine counters stay — they
now feed the bug report instead of a dialog. The `+` tile keeps the
self-completing watcher from 0.5.7: `Modifier.clickable` alone still does not
complete a tap there.

### Default browser, on first launch and in Settings

* `BrowserRole` wraps `RoleManager` (`android.app.role.BROWSER`). The dialog is
  only requested from a tap. A request that the system answers with "no" — or
  auto-denies after two declines, which is what happens on `targetSdk` 36+ when
  the prompt is never answered — lands on the settings route (app details,
  then the system default-apps list) instead of a dead button.
* Asked once per install (`hexora_default_browser` prefs), never in the
  background, and never when the role is already held. Settings keeps a manual
  entry.

### Appearing in the "open with" list

The launcher filter now carries `CATEGORY_APP_BROWSER`. That category is what
the launcher and the vendor skins use to decide that an app *is* a browser:
without it the app is not listed next to Chrome/Firefox in the chooser, and it
cannot qualify for the browser role at all. A `WEB_SEARCH` filter was added as
well, answered by the app's own search-engine setting.

### Contact, About, and shake-to-report

* Settings → About: `Developer & Designed By: HEXORA-STUDIO`, the address
  `Hexora.Studio.Business@Gmail.com` shown as text **and** as a `mailto:` link
  that opens the owner's composer pre-addressed (no report payload).
* `ShakeDetector`: gravity high-pass, three peaks above 9 m/s² inside 1.2 s,
  2.5 s debounce, registered only while resumed. A single hard jolt (putting the
  phone down) does not qualify.
* `BugReport` assembles: the owner's own description, the device block
  (`DeviceInfo`: build, ROM properties read with `getprop`, display, refresh
  rate, ABIs, locale), the app-state counters, the in-memory `AppLog` (250
  entries, plus the last uncaught exception), and hands it to the mail app with
  `ACTION_SEND`. Nothing is transmitted by the app itself, and there is a
  "copy" button for owners without a mail client.

### Compatibility pass (Android 12 → 16, vendor skins)

Checked and already correct: `android:exported` on every component; immutable
`PendingIntent`s; `FOREGROUND_SERVICE_DATA_SYNC` for the download service;
`POST_NOTIFICATIONS` requested only when a download needs it; SAF-based file
pickers (no storage permissions); `BackHandler` everywhere (predictive back is
enabled and Android 16 forces it); edge-to-edge via `enableEdgeToEdge()`;
`configChanges` covering size/orientation/uiMode for the foldable and
split-screen cases; downloads on API 26–28 going through the app's own
`getExternalFilesDir` (no `WRITE_EXTERNAL_STORAGE` needed); GeckoView 158, which
is 16 KB-page-size clean for Android 15/16 devices.

Skin-specific behaviour that is *not* code-fixable (documented, not "fixed"):
One UI's sleeping apps, HyperOS autostart, ColorOS/OxygenOS background freezes
all kill sessions while away. That is exactly the case the suspension/restore
path and the new paint-resume repair exist for.

## 0.5.8 (cont.) — the error card whose Retry did nothing

Owner's report: once, a page showed the load-failed card. Retry did nothing,
the toolbar refresh did nothing, and selecting the tab from the tab tray fixed
it — the same "the tray is the only cure" signature as the black tabs.

Reading the code, three separate defects line up with that signature:

1. **Retry had no path for a tab without a session.** `BrowserAction.Reload`
   read `entry?.session` and, when it was `null`, fell through to
   `session?.reload()` — a no-op. A tab whose content process died while the
   app was away (`handleDeadSession` with `witnessed = false`) keeps
   `loadFailed = true` from `onSessionDied` while `entry.session` becomes
   `null`, so the card stayed up and its only button did nothing.
   The tab tray healed it because `switchTab()` restores a session-less tab.
2. **`reload()` is a no-op for a load Gecko halted.** The load-error handler
   returns `null` on purpose (so Gecko's English error page never shows), which
   halts that navigation. Retrying it with `reload()` asks Gecko to reload a
   navigation that no longer exists. Retry now navigates to the failed URL
   again instead.
3. **A session that is referenced but already closed** was treated as healthy by
   the resume path (`active.session == null` was the only check), so coming back
   left the card up with nothing able to clear it.

`recoverActivePage()` now serves Retry, the toolbar refresh and pull-to-refresh:
no session → restore (the same thing the tray does); unusable session → the dead
session is suspended and the tab restored; live session with a halted failure →
re-navigate to the page's own URL; then `verifyDisplayAfterReload()` waits for a
frame and re-acquires the display (`setSession` on the tab's surface, or
`present()` when that surface is not in the container) if none arrives. Nothing
in that path reloads a healthy page.

`onBrowserResumed()` also treats a referenced-but-closed session as dead now.

## 0.5.8 (cont.) — black tabs on a plain tab switch, no minimise involved

Owner's report: switching quickly between three tabs left tabs 2 and 3 black.
Not minimised, not suspended, not refreshed — and, exactly like the earlier
reports, **nothing in the browser fixed it; only selecting the tab from the tab
tray did.**

That last sentence is the whole diagnosis. The tray is not magic: leaving the
browser route disposes the `AndroidView`, which detaches the content container,
which detaches every tab surface (`HexoraGeckoView.onDetachedFromWindow` →
`releaseSession()`), and returning re-attaches them (`onViewAttached` →
`setSession()`). In other words the cure has always been *"detach the surface
and re-bind the session"*. Everything else — reload, tab switch, the error
card's Retry — never touches the surface/session binding, so it cannot help.

Why a plain switch leaves the pane black: `forceShow()` hides every non-active
tab with `View.INVISIBLE`. A hidden surface is destroyed by the platform. When
the tab is shown again it gets a *new* platform surface, and GeckoView does not
re-create its compositor for it in every case — the document is alive, the
session is alive, nothing is loading, and the pane shows nothing. Gecko says so
itself: `onPaintStatusReset` (the painted content is no longer displayed) with
no `onFirstComposite` behind it. No previous release had any code that listened
to that per tab, which is why six releases of "fixes" never touched the real
thing.

0.5.8 keeps a **per-tab** paint state, driven only by Gecko's own callbacks
(`onFirstComposite` sets it, `onPaintStatusReset` clears it), and acts on it in
four places:

1. **At the switch, before the empty pane can be shown.** If the tab's paint
   state says "the painted content is gone" (which is what happens when its
   surface was destroyed while hidden), the tab's host is removed from the
   container and added back — `fullReattach()`, the literal tray round-trip:
   detach, `releaseSession()`, attach, `setSession()`. A tab whose paint state
   is intact is shown as before, with no extra work and no flash.
2. **At +700 ms and +2200 ms after a switch**, if no frame was composed for
   that tab, `reacquireDisplay()` re-binds the session; the later check
   escalates to `fullReattach()` again and logs
   `tab switch: pane still unpainted`.
3. **While the tab is on screen**: `onPaintStatusReset` on the active session
   (the minimise case, a GPU reset, a surface loss) schedules the same
   re-attach after 350 ms unless a frame arrives first.
4. **On resume and after a reload/retry**, the same per-tab state decides, so
   those paths cannot leave an empty pane either.

Two counters are added to the diagnostics line for the next bug report:
`switchRebinds=` (re-binds because a frame never arrived) and
`switchRepairs=` (full re-attaches). Event lines go to `AppLog` too, so the
shake report answers "did the repair run?" without a cable.

The truthful limit: the repair is driven by Gecko reporting that the painted
content is gone. If a device loses the surface *silently* — no reset, no frame —
nothing here can tell it apart from an intact pane, and the evidence for the
next step will have to be the log. What the counters already answer: whether the
repair ran (`switchRepairs`/`switchRebinds` growing), which tells us whether the
remaining black pane is this defect or a different one.

## 0.5.8 (cont.) — the Google sign-in page and its dead Retry

The owner's note that the load-failure card appeared **only on Google's sign-in
page** is the useful part, because that page is not a page: it is a chain of
POSTs and redirects across `accounts.google.com`. Two separate defects show up
there and nowhere else.

**1. A card for a navigation that had already lost the race.** `onLoadError`
fired for a request that a later redirect had superseded, while the session had
already moved on to the next URL. `NS_BINDING_ABORTED` was filtered (that is the
common shape of the race), but the same race also arrives as other codes, and
the card was painted over a page that was fine — or over a load that was still
in flight.

The filter is now about *state*, not about a code list: an error is treated as
stale when the tab finished a successful load less than 1.5 s ago, the session's
current URL is a real http(s) page, and that URL differs from the URI that
failed. Nothing is hidden otherwise — an HTTP/DNS failure for the URL on screen
still shows the card.

**2. Retry could not help a POST-backed failure.** The old Retry re-navigated
the failed URL (after 0.5.8's first pass) — but Google's flow is a POST result,
which cannot be replayed by navigating to its URL again (and re-navigating after
a *halted* load was why reload looked dead in the first place). Retry now tries
a real `reload()` first, which preserves the request, and only if no load has
started 800 ms later does it navigate to the URL again and log
`retry: reload did not start, re-navigating`.

The error code and category of every failure are still recorded in `AppLog`
(`load error code=… category=…`), so the next shake report says which of the two
it was.

## 0.5.8 (cont.) — the resource layer, checked line by line against Firefox

This pass was not written from memory. The shipped sources were read first:
android-components `components/browser/state/.../engine/middleware/`
(`TrimMemoryMiddleware.kt`, `SessionPrioritizationMiddleware.kt`,
`SuspendMiddleware.kt`), `fenix/app/.../FenixApplication.kt`, and GeckoView's
own javadoc for `GeckoSession.setActive`. Three things came out of it.

**1. Firefox keeps ONE session high priority; this app kept four.** The rule is
literal, in `SessionPrioritizationMiddleware`: `PRIORITY_HIGH` on the selected
tab, `PRIORITY_DEFAULT` on the tab that was selected before it. Nothing else is
boosted. GeckoView's contract for the hint is "keep this session's state as long
as possible", and GeckoView gives every session its own content process — so the
old warm-set policy (selected tab plus `SUSPEND_KEEP_INACTIVE_TABS` = four
sessions) asked Gecko to keep four documents and four processes fully resident.
That is the footprint an OEM task killer hunts, and being killed in the
background is what turns "my tabs stay open" into "every tab reloaded". The
policy is now `applySessionPriorities()`: exactly the selected tab is high, and
`setPriorityHigh` still only emits a hint when the value changes, so walking the
whole strip on a UI emission costs one comparison per tab.

**2. The trim policy was already exact parity — and two things said about it
earlier were wrong.** `TrimMemoryMiddleware.shouldCloseEngineSessions` reacts
only to `TRIM_MEMORY_RUNNING_CRITICAL` and `TRIM_MEMORY_COMPLETE`;
`determineTabsToSuspend` never suspends the selected tab, orders the rest by
last access and keeps the 3 most recent (`MIN_ACTIVE_TABS`). This app does the
same, at the same levels, with the same warm set. So: Firefox does **not**
release tabs at `RUNNING_MODERATE`/`RUNNING_LOW`/`BACKGROUND`, and Firefox has no
cap on simultaneously loaded tabs. Adding either would have been a deviation
dressed up as parity, so both proposals are withdrawn.

Two consequences worth writing down. Since API 34 Android no longer delivers
`RUNNING_LOW`/`RUNNING_CRITICAL` to apps at all, so on Android 14+ the foreground
half of this policy barely fires — for Firefox exactly as for this app; the
background levels (`BACKGROUND`/`MODERATE`/`COMPLETE`) still arrive, and
`COMPLETE` is the one that matters when the phone is about to kill the process.
And the memory that is actually saved when a tab is suspended is the *document*
in its content process, with its engine state written to
`TabSessionStore` so selecting the tab restores instead of reloading.

**3. `setActive` is what Firefox gets for free, and this app got not at all.**
Firefox keeps one `GeckoView` and swaps sessions into it; the display of the tab
that leaves is released, and `GeckoDisplay.release()` calls
`GeckoSession.setActive(false)` for it. This app keeps one surface per tab
instead, so the outgoing tab's display is only released if the platform tears
its surface down — and no code ever told Gecko that the tab had left the screen.
`present()` now marks the outgoing session inactive and the incoming one active
(`GeckoBrowserSession.setSessionActive`), once per switch, in the same frame the
tab is hidden, and a session reopened after a crash is re-applied as inactive.
`setActive(false)` is the call that lets Gecko drop that document's memory and
flush its engine state; `setActive(true)` is what makes the returning tab paint.

Related, found while reading: suspension used to capture a thumbnail per tab — a
GPU readback of the whole surface plus a decode and a disk write — *inside* the
low-memory callback, spending the memory the callback exists to free. Firefox's
middleware only closes sessions. The trim path now takes no previews and keeps
whatever is already on disk.

What this does **not** change, stated plainly: Firefox has one compositor, this
app has one per shown tab, and that difference is what the black-pane class of
bugs is made of. Converging means rewriting the surface layer to Mozilla's
single-view model (`engineView.setCurrentSession`), which cannot be judged
without a build and a device; it is the next step, not a side effect of this one.

## 0.5.9 — the brand icon, from the artwork itself

The owner's file (`file_0000000097e08210866fb96a12738cb5.png`, 1254×1254, the
"K" mark above the `Hexora BROWSER` wordmark) is now the app's icon. Nothing of
the old icon is left: every launcher asset, the adaptive foreground, the in-app
logo and the Android 12 splash foreground were regenerated from that file, and
the old files were replaced in place.

How the artwork is used, and why:

- **the mark, not the wordmark.** A launcher icon is drawn at 48 dp; the wordmark
  is unreadable there, so the icon is the square around the "K", taken from the
  artwork at its native resolution (no scaling of the mark itself).
- **the artwork's own background is kept.** Keying the near-white background out
  to get a transparent mark is possible but leaves the tinted drop shadows as a
  halo on a dark tile (three thresholds were tried and all three left visible
  residue). The tile is instead the artwork's own pixels, extended outwards by
  edge replication so the mark has breathing room inside the 66 dp safe zone.
- **one square, five densities.** `mipmap-*/ic_launcher.png` is the tile with an
  18 % corner radius (48/72/96/144/192 px); `mipmap-*/ic_launcher_foreground.png`
  is the same tile at 78 % of the 108 dp canvas centred on transparency, which is
  larger than the 72 dp the launcher shows, so the mask reveals the tile
  edge-to-edge — no dark ring and no seam (the tinted mark sits at ~70 % of the
  visible area). `@color/ic_launcher_background` stays `#0A141F`; it is never
  visible under that foreground and keeps the pre-splash window dark.
- **in-app**: `hexora_logo.png` (512 px) is the same square — the Start Page
  already crops and rounds it (72 dp, 18 dp radius).
- **splash**: `hexora_logo_splash_foreground.png` is the tile at 72 % of a
  1024 px canvas; `mipmap-anydpi-v31/ic_splash.xml` still masks it, so Android 12
  shows the light tile in a circle over the black splash background.

`versionCode` 36 / `versionName` 0.5.9, so the new debug APK installs over the
0.5.8 build on the phone instead of being refused.

## 0.6.0 — Settings as a sidebar, About as a screen, and the real logo

**Settings was one page.** Search engine, theme, four protection switches, the
password vault, desktop mode, link handling, clear-data, default browser, bug
reports, the developer address and the version number were stacked in a single
`verticalScroll`. Finding one of them meant scrolling past the rest, and the
page had no map of itself.

It is now a sidebar of sections, the way Chrome splits Settings: a rail on the
left (`Appearance`, `Search`, `Privacy & security`, `Tabs & links`, `Data`,
`App`) and one pane on the right. Tapping a section switches the pane and the
pane starts at its own top — `key(pane)` gives every section a fresh
`rememberScrollState`, so nothing inherits the previous section's offset. The
selected item is `secondaryContainer` with `onSecondaryContainer` content, and
labels wrap to two short lines instead of ellipsising: a section name that
cannot be read is a section that cannot be found. The selection survives
rotation (`rememberSaveable`).

What moved where:

| Section | Contents |
|---|---|
| Appearance | the four themes |
| Search | engine list, custom engine name + template |
| Privacy & security | ETP, HTTPS-Only, Total Cookie Protection, JavaScript, Site permissions, saved-login switch + Manage |
| Tabs & links | open links in a new tab, desktop site by default, the per-site zoom note |
| Data | clear browsing data (confirm dialog) and exactly what it clears |
| App | default browser, report a bug, About |

**About is a destination, not a heading.** It has its own route
(`Routes.ABOUT`), its own `TopAppBar` with a back arrow, and is reached from the
`App` section as a row with a chevron. The old inline block is gone.

The screen now says something: what Hexora is (GeckoView engine, no account, no
sync, no analytics SDK), the protections that are on before the user touches
anything (ETP, HTTPS-Only, Total Cookie Protection, per-site permissions,
private tabs), where each kind of data lives (AES-GCM logins keyed in the
Android Keystore, local database, bug reports handed to the owner's mail app),
what the browser does (per-tab engine state restored instead of reloaded,
suspension instead of discarding, tab tray, downloads, find in page, PDF and
print), the engine and its licenses (GeckoView/MPL 2.0, AndroidX/Compose/Room/
DataStore/Apache 2.0), and the developer block with the address shown as text
as well as a button. Every claim maps to code in this repository — no
"fast and secure" filler. `about_clear_data_scope` matches what
`clearBrowsingData()` actually does (`StorageController.ClearFlags.ALL` plus the
history table; bookmarks, logins and downloads are untouched).

**The icon kept only half the artwork.** The owner's file is a lockup: the "K"
mark *with* the name "Hexora BROWSER" beneath it. 0.5.9 cropped to the mark,
because a wordmark at 48 dp is unreadable — a defensible call, but not what was
asked for, and the same crop was used for the in-app logo where the name has
room. All launcher assets, the adaptive foreground, the splash foreground and
`hexora_logo.png` are now generated from a square that contains the whole
lockup (mark + name), padded by edge replication so nothing touches the tile
edge. On the Start Page the logo is drawn `ContentScale.Fit` at 148 dp — never
`Crop` — and the separate "Hexora" heading is gone, since the artwork already
carries the name; the "Fast, private, yours" line stays.

`versionCode` 37 / `versionName` 0.6.0.

## 0.6.1 — density in the settings pane, copy that reads like a person, and the sweep

**The pane was sized for a tablet.** Next to a 104 dp sidebar a phone leaves
~230 dp of content, and Material's defaults were written for something three
times that wide: `ListItem` at 16 sp with 16 dp of padding top and bottom, a
52×32 dp `Switch`, `OutlinedButton` at full `ButtonDefaults` height. Six rows
filled a screen. The pane now uses one row type of its own (`SettingsRow`:
14 sp label, 12 sp summary, 5 dp vertical padding), a switch scaled to 0.78
inside a 44×28 dp box, radio buttons at 0.82, and 36 dp buttons with 14 dp
horizontal padding. Section headings dropped from `titleMedium` to
`titleSmall`. Same information, roughly two thirds of the height.

This is deliberately local to the settings pane: dialogs, the browser chrome and
the About screen were not touched, because they are not the crowded surface.

**The About copy was written like a release note.** Every sentence was a
spec-sheet clause with an em dash in the middle, which is exactly how a machine
writes when nobody is talking. It is rewritten in a voice that could plausibly
belong to the person who built the app: contractions, shorter sentences, one
idea per bullet, and concrete detail instead of adjectives ("a key Android keeps
in the Keystore and will not hand out" rather than "strong device-bound
encryption"). No claim changed: each one still maps to code in this repository.

**The icon had no breathing room at the bottom.** The lockup was padded by edge
replication of the *cropped* square, and the last row of that crop carried a
faint vignette line, which replication then repeated across the bottom of the
tile — the "slightly cut off" the owner saw. The tile is now composed properly:
the lockup is cropped with 60 px of its own background on every side, feathered
over a canvas filled with that background's average colour, and placed with
~13 % margin at the top and ~17 % at the bottom (a text lockup wants the extra
room under the baseline). Content occupies ~71 % of the tile instead of 77 %.

**The sweep.** What it actually checked, and what it found:

- every `R.string.*` used from Kotlin exists in `strings.xml` (none missing),
  and every `R.drawable.*`/`R.mipmap.*` resolves to a file (the two hits are
  `android.R.drawable.stat_sys_download*`, platform resources — not a defect);
- braces and parentheses balance in the four files this build touches, against
  their `HEAD` baseline (unchanged);
- copy versus behaviour, one string at a time. One lie found and fixed:
  `settings_open_links_new_tab_summary` still said "Stored preference — not
  applied yet", while `handleExternalUrl()` has honoured it for two releases
  (`BrowserViewModel`, the `pendingExternalUrl` path and the direct one).
- eleven strings are dead (no `R.string.` and no `@string/` reference anywhere):
  `action_reset_zoom`, `action_tabs`, `action_undo`, `bug_report_shake_hint`,
  `default_browser_already`, `file_picker_title`, `settings_cookies`,
  `settings_cookies_summary`, `settings_text_zoom`, `settings_text_zoom_summary`,
  `tabs_closed_message`. Left in place: removing them is safe but unrelated to
  this build, and two of them (`settings_cookies*`, `settings_text_zoom*`) look
  like settings that were designed and never wired — worth a decision, not a
  silent deletion.

`versionCode` 38 / `versionName` 0.6.1.

---

## 27. 0.6.2 — the About page, corrected against the code

The About copy written for 0.6.1 was reviewed twice: once for accuracy and
once for whether it reads like a person. Both reviews came back with the same
kind of finding, so this build fixes content and voice in one pass rather than
two.

### Claims that did not survive the audit

- **"Nothing syncing in the background" was false.** GeckoView keeps its own
  traffic alive whatever the app does: tracking-protection lists and CRLite
  filters come from Mozilla Remote Settings
  (`services.settings.poll_interval` = 24 h), and Google Safe Browsing lists
  come from `safebrowsing.googleapis.com`
  (`browser.safebrowsing.features.malware/phishing.update = true` in
  `geckoview-prefs.js`; there is no GeckoView API that turns it off).
  The paragraph now says what the traffic is and that it is lists and hashes,
  not a browsing record.
- **"Third-party cookies are kept apart per site" was false.** Partitioning is
  deliberately off — `GeckoBrowserEngine.kt` explains why: without the Storage
  Access API, Google sign-in loops. The bullet now says cookies stay with the
  site that set them and points at the limitations section.
- **"If a site can do HTTPS, Hexora connects over HTTPS" implied always-on.**
  `httpsOnlyMode` defaults to `false` (`SettingsRepository.kt:66`). Rewritten as
  a switch that is off until the user turns it on.
- **"Nothing to leak" / "Uninstalling takes the rest with it."** Data on the
  phone can still be read by anything that gets at the phone, and files written
  to Downloads outlive an uninstall (`download/` has no private-mode branch, and
  saved files go to the shared folder). Both sentences rewritten.
- **"No server on my end collecting anything"** hid the obvious: a report that
  the user sends arrives in the maintainer's inbox, with their address on it.
  Now said in those words. The part that *was* true is kept: the payload is
  device details, counters and an app log — `AppLog` and `buildDiagnostics`
  write no URLs, titles or page text.
- **Licence paragraph read as if Hexora's own code were Apache 2.0.** It is
  not: no LICENSE file exists, so the app's own code is unlicensed. The
  paragraph now separates GeckoView (MPL 2.0), the Android libraries (Apache
  2.0) and Hexora's own code (© HEXORA-STUDIO, no open-source licence).

### Voice

No first person anywhere. The page speaks as the studio ("we", "our"), which
also fixes the mismatch with "Contact us" and the HEXORA-STUDIO credit in the
same screen. Rhythm was evened out as well: bullets are no longer
claim-plus-because in every line, the one triadic list that repeated was cut
back, and the slogan sentences that closed three paragraphs are gone.

### New screen

`LicencesScreen.kt` (`Routes.LICENCES`, reached from the engine card on About):
GeckoView with the exact source tree and the MPL 2.0 text, the Apache 2.0
library list with a link to the licence, and the note about Hexora's own code.
This is also what MPL 2.0 §3.2 asks for in practice — recipients are told where
the covered source is.

### New section on About

"What isn't here yet": no third-party cookie partitioning, no fingerprinting
protection, no sync, no extensions, no tab hand-off between devices, and the
plain statement that a site you are signed in to still sees what you do there.

`versionCode` 39 / `versionName` 0.6.2.

---

## 28. 0.6.3 — the dead strings, and what the sweep found around them

### Wired, not invented

- `action_tabs` — the tab counter in the bottom bar draws its number as text
  inside a `clickable` container, and `clickable` merges descendant semantics.
  TalkBack read a bare digit and never said what the button opens. The button
  now carries `contentDescription = "<label>, <count>"`.
- `action_reset_zoom` — the reset button already existed and already worked;
  the string is now its spoken label, because the button's visible text is the
  value it resets to ("100%"), which reads as a value, not as an action.
- `default_browser_already` — `startDefaultBrowserRequest()` used to launch a
  role request the system answers with nothing when the role is already held.
  It now says so before doing anything.
- `action_undo`, `tabs_closed_message` — `UndoCloseAllTabs`, `undoCloseAllTabs()`
  and the `lastCloseAllIds` batch had existed in the ViewModel with no caller.
  The tray's "Close all" now shows a snackbar with Undo; the action restores the
  batch in its original order and returns to the browser. No ViewModel change
  was needed, which is the point: the feature was already built, only unwired.

### Deleted

`bug_report_shake_hint`, `file_picker_title`, `settings_cookies`,
`settings_cookies_summary`, `settings_text_zoom`, `settings_text_zoom_summary`.
The shake detector (`ShakeDetector`, started in `MainActivity`) and the file
pickers (`OpenDocument`, `OpenMultipleDocuments`, `PickVisualMedia`,
`PickMultipleVisualMedia`, `OpenDocumentTree`) are untouched: those two strings
had no reference, so the gallery/file-manager chooser behaves exactly as before.

Per-site cookie control exists already, as "Clear site data" in the overflow
menu (`ClearSiteData` → `engine.clearSiteData(host)`), which is why the planned
settings row was redundant rather than missing.

### Found by the sweep and fixed

- **Settings carried the same false claim About just lost**: a static row
  "Total Cookie Protection — Always on: third-party cookies are partitioned by
  site". Partitioning is off. Row and both strings removed.
- `settings_javascript_summary` said "Applies to new browsing sessions after
  restart"; `applyContentControls()` pushes `setJavaScriptEnabled` to the live
  runtime as soon as the switch moves. Rewritten.
- `settings_https_only_summary` said "Upgrades or blocks"; the mode this app
  sets is `HTTPS_ONLY`, i.e. it blocks. Rewritten.
- `settings_data_intro` said "Nothing syncs anywhere, because there is nowhere
  to sync to" — the same overclaim corrected on the About page. Rewritten.
- `BrowserUiState.recentlyClosedCount` was declared, copied through the slice
  pipeline and never written: always zero. The field and its slice plumbing are
  gone.

### Sweep results on this tree

305 strings, 0 unreferenced, 0 referenced-but-missing; every `R.drawable`,
`R.mipmap`, `R.xml` reference resolves to a file; every XML parses; 0 `TODO`;
0 private members with no second mention; 0 `IconButton` blocks without a
spoken label; all 63 `BrowserAction` cases declared and handled in `onAction`;
braces and parentheses balanced in every touched file.

`versionCode` 40 / `versionName` 0.6.3.

---

## 29. 0.6.4 — the theme set, rebuilt around the current logo

### Hexora Mode was drawn for the previous logo

The scheme was orange-on-plum (`#E77E3D` accent, `#100B0F` ink). Those are
colours from the logo this app no longer uses, so the theme sat next to the new
icon like a different product. Sampled from
`drawable-nodpi/hexora_logo.png`, the current mark is:

| Element | Sampled |
|---|---|
| mark, blue | `#0F7BF0` |
| mark, amber | `#FFA614` |
| wordmark navy | `#04132E` |
| lockup background | `#F1F3FA` |

`HexoraColors` is now built from those: near-black navy background, light blue
primary (`#7FB8FF` on dark surfaces, `#0A3E82` container), amber as the single
accent, and surfaces in the same navy family instead of plum. Blue carries the
interface and amber is used sparingly, which is the proportion the logo itself
uses.

### Paper

`ThemeMode.PAPER`: a light scheme with no accent colour at all — warm stock
(`#F2E9D8`) behind sheets of the same paper (`#FAF3E4`), ink `#33291D`, muted
ink `#6B5B45`, and every container a shade between them. Errors are a muted
brick (`#8C3B2E`) so the one colour that has to survive still reads as
"something went wrong".

Two things make it read as paper rather than as a beige theme:

- **Serif typography.** `PaperTypography` maps every display, headline, title
  and body style to `FontFamily.Serif`. Labels stay sans: they are chrome
  (counters, buttons, bars) and a serif label at 11 sp only loses legibility.
- **The logo is printed in ink.** `LocalPaperTheme` + `logoColorFilter()` draw
  the start-page and About logos through a zero-saturation `ColorMatrix`. A
  blue-and-amber tile on a sheet of paper would be the one graphic the theme
  exists to remove.

`toWebColorScheme()` maps PAPER to `LIGHT`, so pages that honour
`prefers-color-scheme` do not render their dark variant on a paper background.

### Aurora

`ThemeMode.AURORA`: deep pine (`#06110F`) rather than another blue-black, mint
(`#6FE3C4`) as the leading colour, violet (`#B49BFF`) as the second. Chosen so
it is distinguishable from both Dark and Hexora Mode at a glance, and so the
palette still has somewhere to go for accents without becoming a rainbow.

### Wiring

Two enum entries, two settings rows after "Hexora Mode", two scheme entries in
`HexoraBrowserTheme`, one exhaustive `when` updated in the engine colour-scheme
mapping. No hardcoded colours existed outside `ui/theme`, so every screen
follows the new schemes without changes.

`versionCode` 41 / `versionName` 0.6.4.

---

## 30. The 29 open issues — what they were, and why they will not come back

Every open issue in the repository was filed by `app/github-actions`. None was
a report from a person, and none described a defect that is still in the app:

| Group | Count | What it actually was | State |
|---|---|---|---|
| `CI build failure <sha>` | 23 | compile errors from development commits between 2026-10-15 and 2026-11-06 — a broken `settings.gradle.kts`, AGP/Gradle/Kotlin version mismatches, missing imports, unresolved references, and the 0.6.1 aapt apostrophe failure | each fixed by the commit after it; `main` builds green |
| `CI release build failure <sha>` | 1 | the same class of failure on the signed-release path, plus an `sdkmanager` licence prompt that was never answered | the setup step now runs `yes \| sdkmanager --licenses` |
| `Emulator smoke report <sha>` | 5 | the emulator never booted (`HVF error: HV_UNSUPPORTED`, `failed to initialize HVF`), so there was no logcat: the whole body was "fresh-logcat.txt missing" | not app crashes at all |

The real defect was in the workflows, in three places:

1. **Nothing ever closed a failure issue.** A build failed, the next commit fixed
   it, and the report stayed open forever. Both build workflows now close their
   own failure reports on the next green run, and a re-run of the same commit no
   longer files a duplicate.
2. **The smoke workflow filed an issue unconditionally** (`if: always()`). It
   now first decides whether there is anything to report — a `FATAL EXCEPTION`
   in either logcat, or a process that was not running after the launch — and
   only then files. A run where the emulator never booted stays in the Actions
   tab where it belongs.
3. **The smoke workflow did not record its own verdicts.** `FRESH_CURRENT_DEAD`
   and `UPGRADED_DEAD` were printed to the run log and lost; they are written to
   `verdicts.txt` now, which is also what the new gate reads.

The 29 existing issues were closed with a comment naming the build that
superseded them.
