# HEXORA Web Browser

**HEXORA BROWSER** is a simple, elegant, resource-conscious Windows desktop browser built with **C#**, **.NET**, **WinUI 3**, and **Microsoft WebView2**.

The initial interface languages are **Persian**, **English**, and **Arabic**, with runtime language switching and complete RTL/LTR support.

> Status: architecture and product planning.

## Why this stack?

HEXORA targets Windows, so a native .NET application gives us:

- native Windows UI, accessibility, notifications, taskbar, and window management;
- direct access to the WebView2/Edge (Chromium) engine without shipping another Chromium copy;
- lower distribution size and memory overhead than Electron;
- a productive, strongly typed C# codebase;
- straightforward MSIX packaging and Windows Store distribution.

The rationale and alternatives are documented in [ADR-0001](docs/adr/0001-windows-application-stack.md).

## Proposed architecture

```text
HEXORA.sln
├── src/Hexora.Browser.App          # WinUI 3 shell and composition root
├── src/Hexora.Browser.Core         # Tabs, navigation, history, settings contracts
├── src/Hexora.Browser.WebView2     # WebView2 adapters and browser profiles
├── src/Hexora.Browser.Infrastructure # Persistence, downloads, updates, diagnostics
└── tests/                           # Unit and integration tests
```

Core browser behavior should remain independent of WinUI and WebView2 where practical. This keeps business logic testable and leaves room for future UI or engine changes.

## MVP scope

1. App window with address/search bar and back, forward, reload, and stop controls.
2. Multiple tabs, including opening and closing tabs and restoring the active session.
3. WebView2 profile with cookies, cache, permissions, and private-window isolation.
4. History, bookmarks, downloads, and configurable default search provider.
5. Keyboard shortcuts, context menus, basic accessibility, and dark/light themes.
6. Crash handling, structured local logs, signed MSIX packaging, and automated tests.

## Non-goals for the first release

- implementing a rendering engine;
- Chrome/Firefox extension compatibility;
- cross-platform support;
- account sync and cloud services;
- built-in VPN, proxy, or ad-blocking promises before a security review.

## Development prerequisites

- Windows 10 version 1809 or newer, or Windows 11;
- Visual Studio 2022 with the **Windows application development** workload;
- a supported .NET SDK (pin the exact version when the solution is bootstrapped);
- WebView2 Runtime (included with supported Windows versions and available separately).

## Delivery plan

- **Phase 0 — Foundation:** measurable performance budgets, solution scaffold, localization, security model, tests, and CI.
- **Phase 1 — Browsing MVP:** localized native shell, WebView2 lifecycle, navigation, tabs, errors, and shortcuts.
- **Phase 2 — Daily-driver essentials:** profiles, private mode, history, bookmarks, settings, downloads, and session restore.
- **Phase 3 — Efficiency and polish:** sleeping tabs, memory-pressure handling, privacy controls, accessibility, and compatibility hardening.
- **Phase 4 — Modern capabilities:** selectively add vertical tabs, workspaces, split view, web-app integration, or extensions only when each passes value, security, and resource gates.
- **Phase 5 — Stable release:** independent security review, signed packages, staged updates, localization review, and release certification.

See the detailed [product roadmap](docs/product-roadmap.md) for scope, performance budgets, exit gates, version milestones, and open product decisions.

## Architecture decisions

- [ADR-0001: Use C#/.NET, WinUI 3, and WebView2](docs/adr/0001-windows-application-stack.md)

## License

No license has been selected yet. Until one is added, all rights are reserved.
