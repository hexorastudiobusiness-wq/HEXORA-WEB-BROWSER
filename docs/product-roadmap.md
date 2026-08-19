# HEXORA BROWSER — Product Roadmap

- **Product name:** HEXORA BROWSER
- **Platform:** Windows desktop
- **Initial UI languages:** Persian (`fa`), English (`en`), Arabic (`ar`)
- **Layout directions:** right-to-left and left-to-right
- **Rendering engine:** Microsoft WebView2 Evergreen (Chromium)
- **Document status:** Draft for product review
- **Last updated:** 2026-08-19

## 1. Product vision

HEXORA BROWSER is a simple, elegant, modern Windows browser that prioritizes speed, low resource usage, broad website compatibility, privacy, and a first-class multilingual experience.

The browser should feel calm rather than crowded: essential actions are immediately available, advanced actions are discoverable, and optional features do not consume resources until enabled or used.

## 2. Product principles

1. **Fast by measurement:** every release is checked against explicit startup, memory, CPU, and interaction budgets.
2. **Simple by default:** the primary toolbar contains only navigation, address/search, tabs, and the main menu.
3. **Native on Windows:** use native windowing, accessibility, input, notifications, and packaging instead of imitating them in a web shell.
4. **Compatible by inheritance:** use the Evergreen WebView2 Chromium engine and avoid modifying web standards behavior.
5. **Private by design:** collect no browsing history, page contents, or search terms remotely. Diagnostics are local by default and telemetry is opt-in.
6. **Secure by default:** treat all page content as untrusted, expose no general native bridge, and update dependencies promptly.
7. **Multilingual from the first screen:** Persian, English, Arabic, LTR, RTL, fonts, keyboard navigation, and locale-aware formatting are foundation work, not a later translation task.
8. **Features pay rent:** a feature must justify its UI complexity, memory, CPU, security, and maintenance cost.

## 3. Initial product specification

### 3.1 Identity and experience

- Display name: **HEXORA BROWSER**.
- Minimal visual identity with a compact toolbar, generous spacing, restrained animation, and clear active states.
- Light, dark, and system themes.
- Compact and comfortable density modes may be evaluated after the MVP; only one polished default is needed initially.
- Fluent Design should be used selectively. Effects such as Mica/Acrylic must automatically fall back when disabled, unsupported, on battery saver, or expensive on the current device.

### 3.2 Language and direction

- Users can switch the UI language from Settings without reinstalling the app.
- Persian and Arabic use complete RTL layout mirroring, not only translated text.
- URLs, code, version numbers, and mixed-direction content remain readable inside RTL screens.
- UI text is stored in resource files; no user-facing string is hard-coded in views or view models.
- Dates, numbers, plural forms, sorting, and search are locale-aware.
- The first-run language defaults to the Windows application language, with English as fallback.
- Language changes should apply immediately where safe; if a restart is required, the app clearly explains it and restores the session.
- Typography must be tested with Persian and Arabic glyph shaping at 100%, 125%, 150%, and 200% display scaling.

### 3.3 Core browsing

- Combined address and search field with explicit search-provider selection.
- Back, forward, reload/stop, home, and secure-connection/page-information affordances.
- Multiple tabs, tab reordering, reopening closed tabs, pinned tabs, duplicate tab, and session restore.
- New-window and private-window modes with isolated ephemeral data.
- History, bookmarks/favorites, downloads, find-in-page, print, zoom, full screen, and view source/DevTools for advanced users.
- Permission controls for location, camera, microphone, notifications, clipboard, pop-ups, and downloads.
- Password and autofill integration is deferred until a dedicated threat model and secure-storage design are approved.
- Keyboard, mouse, touchpad, touch, high-DPI, screen reader, and high-contrast support.

### 3.4 Compatibility

- Rely on the Evergreen WebView2 Runtime for standards and codec compatibility available through the installed runtime.
- Test against representative categories rather than a few hand-picked sites: search, email, office suites, video, audio, conferencing, social networks, shopping, banking, government, maps, WebGL, WebAssembly, PWA, and RTL sites.
- Do not rewrite user agents or inject scripts globally. Site-specific workarounds require a documented issue, minimal scope, expiry criteria, and regression tests.
- Provide a clear runtime/version page and a safe way to report a broken site without silently uploading its URL or contents.

## 4. Performance and resource budgets

“Lightweight” is a release criterion. Measurements use a documented reference device and are compared with the WebView2 baseline so the application shell is not credited or blamed for engine behavior it does not control.

### Reference profile

- Windows 11 on a 4-core CPU, 8 GB RAM, SSD, integrated graphics, balanced power mode.
- Clean user profile and stable network.
- Median of at least five release-build runs; cold and warm starts are recorded separately.

### Initial budgets (to validate during Phase 0)

| Metric | Initial target |
| --- | --- |
| Warm launch to interactive window | p50 ≤ 1.2 s; p95 ≤ 2.0 s |
| Cold launch to interactive window | p50 ≤ 2.5 s |
| Shell overhead above equivalent WebView2 baseline | ≤ 120 MB working set with one new tab |
| Background tab CPU after quiescence | median < 0.5% per suspended/idle tab |
| UI response to common commands | visual response ≤ 100 ms |
| Restoring a 10-tab session | active tab interactive first; remaining tabs lazy-created |
| App package excluding WebView2 Runtime | target ≤ 60 MB |
| Idle battery impact | no timers or polling loops without a measured need |

These are engineering targets, not public promises, until repeatable CI/performance-lab measurements exist.

### Resource strategy

- Create WebView instances lazily; session restoration must not navigate every tab at startup.
- Keep only the active and explicitly needed tabs fully active.
- Use WebView2 lifecycle APIs to suspend eligible background tabs and discard them under memory pressure while retaining restorable state.
- Never discard tabs with active audio, video, downloads, form edits, device capture, screen sharing, or unsaved state without an explicit safe policy.
- Coalesce persistence writes and avoid frequent synchronous disk access.
- Use event-driven updates; avoid background polling.
- Load optional modules only when invoked.
- Measure process groups, not only the WinUI host process, because WebView2 uses multiple processes.
- Add a built-in, local-only task manager after the core lifecycle is stable so users can identify expensive tabs.

## 5. Delivery phases

The estimates assume a focused team of 2–4 engineers plus part-time design/QA. Each phase exits on quality gates, not only elapsed time.

### Phase 0 — Product and engineering foundation (2–3 weeks)

**Goal:** turn product claims into testable constraints before building visible features.

**Deliverables**

- Confirm supported Windows versions and hardware baseline.
- Bootstrap the .NET/WinUI 3 solution and pin SDK/runtime dependencies.
- Establish Core, App, WebView2 adapter, Infrastructure, and test boundaries.
- Define navigation/tab state models and WebView ownership/lifetime rules.
- Build localization infrastructure with Persian, English, and Arabic smoke resources.
- Create RTL/LTR visual test pages and pseudo-localization support.
- Add structured local logging, crash boundaries, and privacy-safe diagnostics.
- Configure Windows CI for build, unit tests, analyzers, dependency review, and package artifacts.
- Create a repeatable performance harness and record raw WebView2 baselines.
- Produce initial threat model and data-flow diagram.

**Exit gate**

- Release build launches an empty localized shell in all three languages.
- Automated tests run in CI.
- Baseline startup and memory measurements are reproducible.
- No unreviewed native bridge is exposed to page content.

### Phase 1 — Browsing MVP / vertical slice (4–6 weeks)

**Goal:** provide a small but complete browsing loop.

**Deliverables**

- Main window, compact toolbar, address/search box, and one WebView2 tab.
- Secure URL parsing and search fallback.
- Back, forward, reload/stop, new tab, close tab, and basic tab switching.
- Loading, error, certificate-error, process-failure, and offline states.
- Find-in-page, zoom, basic keyboard shortcuts, and external-protocol confirmation.
- Theme support and complete Persian/English/Arabic localization for delivered screens.
- Permission prompts that default to deny and remember only explicit choices.
- Automated unit tests plus navigation and localization smoke tests.

**Exit gate**

- Core test-site matrix works without browser-specific global hacks.
- RTL and LTR flows pass keyboard and screen-reader smoke tests.
- Startup and shell-overhead budgets pass on the reference profile.
- Renderer failures can recover without taking down the whole application.

### Phase 2 — Daily-driver essentials (5–7 weeks)

**Goal:** make HEXORA usable as a primary browser for normal daily tasks.

**Deliverables**

- Full tab operations: reorder, duplicate, pin, reopen, recently closed, and lazy session restore.
- Multiple windows and truly isolated private windows/profiles.
- History with retention controls and clear-data workflow.
- Bookmarks with folders and import/export.
- Downloads UI, pause/cancel/open/show-in-folder, dangerous-file warnings, and collision handling.
- Configurable search provider and startup/new-tab behavior.
- Printing, full screen, page information, per-site permissions, and basic media indicators.
- Settings with search and language switching.
- MSIX packaging, signing plan, update-channel design, and recovery after interrupted updates.

**Exit gate**

- Session survives normal shutdown, crash simulation, and update simulation.
- Private-window data is removed after the last private window closes.
- Download and permission abuse cases pass security tests.
- A 10-tab restore creates background WebViews lazily and remains within budget.

### Phase 3 — Efficiency, privacy, and polish (4–6 weeks)

**Goal:** differentiate through calm UX and disciplined resource use.

**Deliverables**

- Sleeping tabs and memory-pressure-aware tab discarding with exception policies.
- Local browser task manager for tabs and WebView2 process groups.
- Startup optimization, deferred services, database tuning, and profile maintenance.
- Tracking-protection/content-blocking feasibility prototype using a maintained rules format; ship only if compatibility and update security meet the gate.
- Clear privacy dashboard for site permissions and stored site data.
- Accessibility audit, high contrast, reduced motion, touch targets, and 200% scaling.
- Refined Fluent visuals, micro-interactions, empty states, and first-run experience.
- Import from common browsers where permitted and reliable.
- Expanded broken-site and media/WebRTC/WebGL compatibility suite.

**Exit gate**

- Performance regression suite blocks releases that exceed approved thresholds.
- No known critical accessibility or security issue.
- Content blocking, if enabled, has an obvious per-site bypass and measured breakage rate.
- Usability sessions succeed in Persian, English, and Arabic.

### Phase 4 — Modern capabilities, selectively (4–8 weeks)

**Goal:** add current features without turning the browser into a heavy suite.

Candidate capabilities are individually gated:

- vertical tabs and tab search;
- tab groups/workspaces stored locally;
- split view with strict memory limits;
- picture-in-picture and global media controls;
- install/open supported web apps where WebView2 capabilities permit;
- QR/share integration using Windows share APIs;
- optional encrypted synchronization only after account, cryptography, recovery, and server security are separately designed;
- extension support only after WebView2 API maturity, permission UX, store/update trust, and resource impact are proven.

**Exit gate**

- Each capability has usage value, performance cost, threat model, accessibility coverage, and an off switch.
- Features not meeting the gate remain experimental or are removed.

### Phase 5 — Release hardening and stable launch (3–4 weeks)

**Goal:** ship a trustworthy 1.0 rather than a feature-complete but fragile browser.

**Deliverables**

- Beta cohort, feedback triage, crash-free and hang-rate dashboards using opt-in privacy-preserving telemetry.
- Independent security review and remediation.
- Signed production packages, rollback procedure, staged updates, and release channels.
- Compatibility, accessibility, localization, performance, and battery certification passes.
- Privacy policy, terms, open-source notices, vulnerability-reporting process, support lifecycle, and incident runbook.

**Release gates**

- No open critical/high security defect.
- Approved crash-free, startup, memory, CPU, and battery thresholds are met.
- Persian, English, and Arabic have complete reviewed translations and no blocking RTL defect.
- Update and rollback have been exercised end to end.

## 6. Version scope

### Version 0.1 — Technical preview

Localized native shell, address/search, basic navigation, tabs, error handling, themes, and developer diagnostics.

### Version 0.5 — Private beta

Session restore, profiles/private mode, history, bookmarks, downloads, permissions, settings, and signed test packages.

### Version 0.8 — Public beta

Sleeping tabs, performance guardrails, privacy controls, import, accessibility hardening, broad compatibility suite, and staged updates.

### Version 1.0 — Stable

A polished, secure daily-driver browser with measured performance, reviewed localization, reliable updates, and no experimental feature enabled by default.

## 7. Features deliberately deferred

The following should not enter the critical path for 1.0:

- AI assistant or page summarization;
- user accounts and cloud sync;
- cryptocurrency wallet;
- VPN or proxy service;
- a proprietary advertising or recommendation feed;
- a custom rendering engine;
- broad extension compatibility;
- password manager and payment autofill without dedicated security staffing.

They increase attack surface, operational cost, background work, or product complexity before the browser earns trust in its core job.

## 8. Product and engineering scorecard

Review this scorecard at the end of every phase:

- startup p50/p95 and time to first usable page;
- total process-group memory for 1, 5, 10, and 25 tabs;
- idle/background CPU and battery discharge;
- crash-free sessions, renderer-recovery rate, hangs, and update failures;
- compatibility pass rate by site category;
- accessibility defects and keyboard-complete flows;
- translation completeness and RTL visual defects;
- package size and download/update delta size;
- security findings, dependency age, and patch turnaround;
- feature usage versus measured resource cost.

## 9. Open decisions before implementation

1. Official OS floor: Windows 11 only, or Windows 10 22H2 as a documented legacy target despite its ended support lifecycle.
2. Distribution: Microsoft Store, direct signed MSIX, or both.
3. Default search-provider policy by locale and whether sponsorship is allowed.
4. Diagnostic policy: local-only for preview builds or explicit opt-in upload from the beginning.
5. Whether tracking protection belongs in 1.0 after the Phase 3 feasibility gate.
6. Product license and governance model.
