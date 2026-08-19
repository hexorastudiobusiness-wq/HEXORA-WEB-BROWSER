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

### Resource exhaustion

- WebView controls are created lazily on first tab activation.
- Closing a tab closes and detaches its WebView.
- Diagnostic file size and retention are bounded.
- Background tab suspension/discard policies remain pending because media, capture, downloads, and unsaved forms require explicit exceptions.

## Sensitive assets not yet implemented

The following require separate designs before code is accepted:

- passwords, passkeys, payment and autofill data;
- downloads and dangerous-file classification;
- camera, microphone, location, clipboard, notification, and screen-capture permissions;
- certificate errors and HTTP authentication;
- private profiles and profile deletion;
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
