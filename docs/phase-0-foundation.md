# Phase 0 — Foundation tracker

This checklist tracks the foundation exit gate defined in the [product roadmap](product-roadmap.md).

## Implemented in the initial bootstrap

- [x] Pin .NET, Windows App SDK, and WebView2 dependencies centrally.
- [x] Create App, Core, WebView2, Infrastructure, and Core test projects.
- [x] Keep Core independent of Windows and WebView2.
- [x] Add an initial MVVM presentation foundation without a runtime MVVM dependency.
- [x] Add a WinUI 3/WebView2 vertical slice with address/search navigation.
- [x] Connect the tab/session model to independently owned, lazy-created WebView2 controls.
- [x] Implement real tab creation, activation, closing, title updates, and active-tab navigation state.
- [x] Reject direct navigation to unsafe address-bar schemes.
- [x] Disable WebView host objects by default.
- [x] Add English, Persian, and Arabic WinUI resource files.
- [x] Add initial RTL language metadata.
- [x] Add cross-platform Core tests and a Windows x64 CI build.
- [x] Add Dependabot configuration for NuGet and GitHub Actions.
- [x] Add repository formatting, ignore, and deterministic-build settings.

## Remaining before Phase 0 exit

- [ ] Confirm the Windows App SDK 1.8 servicing line on real target hardware.
- [ ] Add dependency injection at the composition root without service-location in views.
- [x] Define the initial tab/session state model.
- [ ] Define window and WebView profile lifecycle models.
- [ ] Implement runtime language selection, persistence, and complete RTL layout mirroring.
- [ ] Add pseudo-localization and automated resource-key parity checks.
- [ ] Replace temporary diagnostic strings with localized error codes/messages.
- [ ] Add privacy-safe structured local logging with bounded retention.
- [ ] Add global crash boundaries and a local crash-recovery marker.
- [ ] Create the WebView2 baseline/performance harness on the reference device.
- [ ] Record cold/warm startup and one-tab memory baselines.
- [ ] Create the data-flow diagram and initial threat model.
- [ ] Define permission, download, certificate, external-protocol, and authentication policies.
- [ ] Add architecture tests to enforce project dependency direction.
- [ ] Produce a signed development MSIX proof of concept.

## Current limitations

- The UI hosts one WebView and one placeholder tab; real multi-tab ownership starts in Phase 1.
- The menu and add-tab action are placeholders.
- Language resources are present, but runtime switching is not wired yet.
- The initial search provider is DuckDuckGo and remains a configurable product decision.
- The unpackaged build expects the required Windows App Runtime and Evergreen WebView2 Runtime on the machine.
- Performance targets are not validated until measurements run on the reference Windows hardware.
