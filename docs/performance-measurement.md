# Performance measurement and sleeping-tab policy

- **Status:** Initial instrumentation
- **Updated:** 2026-08-19

## Measurement principles

HEXORA treats “lightweight” as a measured release constraint. Measurements must distinguish the native host process from the complete WebView2 process group.

The main menu contains **Performance snapshot**, which reads process IDs from the active window's `CoreWebView2Environment` and reports only aggregate values:

- process count reported by WebView2;
- process count still available when Windows metrics are sampled;
- aggregate working set;
- aggregate private memory;
- aggregate cumulative processor time;
- HEXORA host-process working set.

No process ID, process kind, URL, page title, profile path, or per-tab information is written to diagnostics. The local diagnostic entry contains only aggregate WebView working set and cumulative CPU time.

## Repeatable baseline procedure

Use the reference device from the [product roadmap](product-roadmap.md) and a Release build.

1. Reboot and wait for Windows startup activity to settle.
2. Use the same Windows power mode and WebView2 Runtime version for all compared runs.
3. Start with a clean test profile when measuring first-run behavior.
4. Record five cold launches and five warm launches.
5. For each run, record the local `app.window_interactive` event.
6. Capture process snapshots for a new tab and for fixed 1, 5, 10, and 25-page test sets.
7. Wait at least 60 seconds after the final navigation before an idle snapshot.
8. Report median and p95 where applicable; do not publish the best run.
9. Compare against an equivalent minimal WebView2 host to separate engine cost from HEXORA shell cost.

A snapshot is diagnostic input, not a benchmark result by itself. Network, page behavior, extensions, media, antivirus, and WebView2 updates can materially change the numbers.

## Initial sleeping-tab policy

A background tab is considered for suspension 30 seconds after it becomes inactive. Suspension is event-driven through one cancellable delay per initialized background tab; there is no continuous polling loop.

A tab is not suspended when any of these conditions is true:

- it is the active tab;
- its WebView has not been initialized;
- audio is playing;
- a download is active;
- a site permission was granted on the current page;
- a security prompt is open;
- the tab was closed or selected before the delay expired.

`TrySuspendAsync` remains best effort. WebView2 may reject suspension when the page or browser engine is not eligible. Selecting a suspended tab resumes it before use. Navigating to a new page clears the conservative sensitive-activity marker.

## Remaining work before performance gates are enforced

- measure complete process groups on the physical reference device;
- add page-set definitions that contain no personal accounts or data;
- sample CPU over a time interval rather than showing only cumulative CPU time;
- account for shared processes when several normal windows use the same profile;
- add media, capture, WebRTC, form-edit, and screen-sharing eligibility signals;
- define memory-pressure-based discarding separately from sleeping;
- add CI/lab regression thresholds only after stable baselines exist.
