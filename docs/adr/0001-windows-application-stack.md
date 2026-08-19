# ADR-0001: Use C#/.NET, WinUI 3, and WebView2

- **Status:** Proposed
- **Date:** 2026-08-19
- **Decision owners:** HEXORA maintainers

## Context

HEXORA is intended to be a Windows desktop browser. It needs a modern rendering engine, native window and taskbar behavior, accessibility, profile isolation, downloads, permission handling, and a maintainable application architecture.

Building a rendering engine is outside the project's scope. The practical choice is therefore an application shell around a maintained browser engine.

## Decision drivers

- Windows is the primary and currently exclusive target.
- Strong Windows integration and native accessibility are important.
- Installer size and baseline resource use should remain reasonable.
- The engine must receive security updates independently and frequently.
- The team should be able to implement and test application logic quickly.
- The architecture must support multiple tabs, profiles, private windows, downloads, permissions, and recovery from renderer failures.

## Considered options

### 1. C#/.NET + WinUI 3 + WebView2

Use Windows App SDK/WinUI 3 for the application shell and WebView2 for Chromium-based page rendering.

**Advantages**

- Best fit for a Windows-only product.
- Native Windows controls, accessibility, DPI handling, windowing, and packaging.
- WebView2 exposes navigation, profiles, downloads, permissions, process events, DevTools protocol access, and host/web messaging.
- The Evergreen WebView2 Runtime receives engine security updates independently of the app.
- C# provides strong tooling, async APIs, and a large Windows ecosystem.
- It avoids bundling a full second Chromium runtime in every release.

**Costs and risks**

- Not cross-platform.
- WinUI 3 has a smaller ecosystem than mature web UI frameworks.
- WebView2 is an embeddable control, not a complete browser product; tab policy, persistence, privacy UI, downloads, permissions, and crash recovery remain our responsibility.
- Some advanced browser features may require lower-level WebView2 or DevTools Protocol integration.

### 2. Electron

Use TypeScript/JavaScript and ship Chromium plus Node.js.

**Advantages**

- Mature ecosystem and excellent web developer tooling.
- Predictable bundled Chromium version.
- Cross-platform support and rapid UI development.

**Costs and risks**

- Large installer and significant baseline memory use, especially for a browser that already hosts multiple renderer processes.
- Shipping and urgently updating Chromium becomes the application's responsibility.
- Native Windows integration usually requires additional bridges.
- The Node/renderer security boundary requires careful hardening: context isolation, sandboxing, restricted IPC, and no renderer Node integration.

Electron is attractive if near-term macOS/Linux support or an existing web-focused team outweighs Windows-native efficiency. Those are not current requirements.

### 3. Tauri

Use a web UI with Rust commands and the system WebView2 runtime on Windows.

**Advantages**

- Small application bundle and lower shell overhead than Electron.
- Reuses WebView2 on Windows.
- Rust offers strong memory-safety guarantees for native code.
- Cross-platform application shell.

**Costs and risks**

- Adds a Rust/TypeScript split and an IPC boundary.
- Multi-window, multi-tab browser lifecycle and deep WebView2 integration are less direct than using the native SDK.
- Cross-platform rendering behavior differs because Tauri uses each operating system's webview.
- Its cross-platform benefit is not currently required.

Tauri is a strong option for ordinary cross-platform desktop applications, but it offers less benefit for a Windows-only product whose central feature is managing WebView2 instances.

## Decision

Use **C# and .NET** for application and domain code, **WinUI 3** for the native Windows shell, and **WebView2 Evergreen Runtime** as the rendering engine.

Adopt these boundaries:

1. `Hexora.Browser.Core` contains engine-agnostic navigation, tabs, session, history, bookmark, and settings logic.
2. `Hexora.Browser.WebView2` owns WebView2 lifecycle, profiles, permissions, downloads, process recovery, and adapter implementations.
3. `Hexora.Browser.App` owns WinUI views, commands, keyboard/accessibility behavior, and dependency composition.
4. `Hexora.Browser.Infrastructure` owns local persistence, diagnostics, update integration, and operating-system services.
5. UI code must not persist secrets directly. Sensitive values use Windows-provided secure storage where applicable.
6. Web content is untrusted. Host objects and web messaging are denied by default and exposed only through narrow, validated contracts.

The exact .NET and Windows App SDK versions will be selected and pinned in the solution-bootstrap change, using versions supported at that time.

## Consequences

- Initial development and CI require Windows runners.
- The first release can focus on Windows quality rather than cross-platform abstraction.
- Engine security fixes primarily arrive through Evergreen WebView2, while the team remains responsible for SDK updates and application-level security.
- Core application logic remains testable without launching a WebView.
- A future cross-platform version would require a new shell/engine adapter and a separate architecture decision.

## Validation checkpoints

Revisit this decision if any of the following becomes a committed requirement:

- a macOS or Linux release in the next two milestones;
- an extension model requiring APIs unavailable in WebView2;
- deterministic bundling of a specific Chromium build;
- measured WebView2 limitations that block a critical browser feature.
