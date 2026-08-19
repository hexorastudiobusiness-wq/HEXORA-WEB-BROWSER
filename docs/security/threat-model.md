# Initial security and privacy threat model

- **Status:** Living document
- **Updated:** 2026-08-19
- **Scope:** Phase 0 application shell, settings, diagnostics, and embedded web content

## Trust boundaries

1. **Web content is untrusted.** Every page, frame, script, download, and web message can be hostile.
2. **The native application is trusted but fallible.** WinUI, application logic, and persistence run with the desktop user's privileges.
3. **WebView2 is a separate engine boundary.** Renderer and utility processes are managed by the Evergreen WebView2 Runtime.
4. **Local profile data is private.** Settings, future history/bookmarks, cookies, cache, and diagnostic files belong to the Windows user.
5. **Remote services are outside the current product.** HEXORA has no account, synchronization, analytics, or telemetry endpoint in this phase.

## Current data flows

```text
Address/search input -> validated navigation parser -> HTTP(S) WebView2 navigation
WebView2 events -> in-memory tab/session state -> native UI
Language selection -> atomic local settings JSON
Application lifecycle/errors -> privacy-safe bounded local diagnostic JSONL
Application start/clean close -> local run-state marker
```

No diagnostic data is transmitted by the application.

## Threats and current controls

### Native-code access from a page

- WebView host objects are disabled.
- No general-purpose page-to-native bridge is registered.
- Future web messaging must use an allowlisted origin and a narrow schema with size limits.

### Dangerous address-bar schemes

- Only `http` and `https` are treated as direct navigation.
- Inputs containing `javascript:`, `file:`, or other unsupported schemes become encoded search queries.
- External protocols require a separate confirmation policy before implementation.

### Diagnostic leakage

The local diagnostic log deliberately permits only:

- UTC timestamp;
- severity;
- fixed ASCII event name;
- random per-process session identifier;
- exception type, never exception message or stack trace;
- startup duration and host-process working-set metrics.

It must never contain URLs, search terms, page titles, page content, cookies, request/response headers, credentials, form values, downloaded filenames, local paths, or exception messages. The active log is capped at 1 MiB and four archives are retained.

### Settings corruption or partial writes

- Settings are written to a temporary file and atomically moved into place.
- Invalid or unreadable settings fall back to safe defaults.
- Settings must not be used for passwords, tokens, cookies, or other secrets.

### Unexpected termination

- A local marker is atomically created when the app starts.
- A normal final-window close removes the marker.
- A stale marker shows a localized warning on the next launch and records a local event.
- The marker contains only start time and process ID; it is not a crash dump.

### Profile and private-window isolation

- Normal tabs share one named persistent WebView2 profile and user-data folder.
- Each private window receives a unique user-data folder and an InPrivate controller profile.
- Private browsing data is not intentionally persisted by WebView2; the temporary folder is also deleted on close when file locks permit.
- Locked abandoned private folders older than seven days are removed on a later launch.
- Deletion is constrained to descendants of the dedicated private-profile root.

### Site permissions

- Every permission request defaults to deny.
- Decisions are never saved into the WebView2 profile.
- Only the active tab can display a prompt; background and concurrent requests are denied.
- An allow decision applies to the current request only.
- The prompt displays only the requesting host and localized capability name.
- Permission decisions are logged without origin, URL, or capability details.

### External protocols and pop-ups

- Web pages cannot directly navigate HEXORA to arbitrary non-web schemes.
- Only user-initiated `mailto`, `tel`, `sms`, and `webcal` requests can prompt to open another app.
- The user sees the protocol name and must explicitly confirm before Windows Launcher is invoked.
- HTTP(S) user-initiated pop-ups open as a new HEXORA tab; background pop-ups are blocked.
- `file`, `javascript`, `data`, unknown, and non-user-initiated external navigations are blocked.

### Downloads

- Every download defaults to canceled while a localized confirmation is shown.
- Concurrent or background-tab downloads are denied.
- Approved downloads continue through the standard WebView2 download UI and destination handling.
- Private-window prompts explicitly warn that downloaded files remain after private browsing ends.
- Diagnostic events never contain source URL, destination path, or filename.
- Dangerous-file classification and a first-party download manager remain future work.

### Certificates and HTTP authentication

- Certificate-error requests are canceled with no bypass option and no cached exception.
- Basic HTTP authentication is accepted only for HTTPS origins and the active tab.
- User name and password are held only long enough to populate the current WebView2 response.
- HEXORA does not persist credentials or include them in diagnostics.
- Concurrent authentication prompts are canceled.

### Resource exhaustion

- WebView controls are created lazily on first tab activation.
- Closing a tab closes and detaches its WebView.
- Diagnostic file size and retention are bounded.
- Inactive tabs receive a cancellable, event-driven 30-second sleep delay rather than a polling loop.
- Active, audio-playing, downloading, permission-granted, and security-prompt tabs are not suspended.
- WebView2 suspension is best effort and rejected suspensions do not affect browsing.
- Memory-pressure discarding remains pending because capture, WebRTC, unsaved forms, and additional activity signals require explicit policy.

## Sensitive assets not yet implemented

The following require separate designs before code is accepted:

- passwords, passkeys, payment and autofill data;
- full download manager and dangerous-file classification;
- persistent per-site permission management and screen-capture policy;
- certificate exception policy (the current implementation always blocks);
- credential storage and password management (the current implementation never stores credentials);
- private-profile verification under crash, update, and multi-instance conditions;
- content blocking and remotely updated rules;
- extension installation and permissions;
- cloud accounts and encrypted sync.

## Security verification backlog

- Add architecture tests for project dependency direction.
- Add automated checks that diagnostic records cannot accept arbitrary properties.
- Fuzz address parsing and external-protocol inputs.
- Define per-permission default, lifetime, storage, and revocation behavior.
- Define a signed update and rollback threat model.
- Test profile isolation and private-profile cleanup.
- Arrange an independent review before stable release.
