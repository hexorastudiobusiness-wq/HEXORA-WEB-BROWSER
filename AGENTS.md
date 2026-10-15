# Coding Agent Instructions

You are implementing this Android browser repository.

Read `README.md` and `ARCHITECTURE.md` before changing code.

## Primary Objective

Build a working Android browser MVP using:

- Kotlin
- Jetpack Compose
- Material 3
- Mozilla GeckoView
- Room
- DataStore
- Coroutines
- StateFlow

## Execution Rules

1. Work directly in the repository.
2. Do not only describe code. Implement it.
3. Keep the project buildable after each major step.
4. Prefer simple, maintainable implementations.
5. Do not add dependencies unless they are actually required.
6. Use current stable versions compatible with the installed Android/Gradle toolchain.
7. Never invent GeckoView APIs.
8. If an API is uncertain, inspect the current official GeckoView documentation or dependency source.
9. Do not replace GeckoView with Android WebView unless explicitly instructed.
10. Do not rewrite the entire project when a targeted fix is sufficient.

## Required Implementation Sequence

### Step 1 — Project Setup

Create/configure the Android project with:

- Kotlin
- Compose
- Material 3
- AndroidX
- GeckoView

Make sure Gradle sync/build works.

### Step 2 — Browser Engine

Create:

```text
BrowserEngine
GeckoBrowserEngine
BrowserSession
```

Keep GeckoView implementation behind the BrowserEngine abstraction.

### Step 3 — First Working Tab

Before implementing history, bookmarks, or settings, make one real web page load successfully.

The first milestone is:

```text
App launches
    ↓
Browser tab opens
    ↓
URL entered
    ↓
Website loads
```

### Step 4 — Browser Controls

Implement:

- Address bar
- Search
- Back
- Forward
- Reload
- Stop loading where appropriate
- Basic loading progress

### Step 5 — Tabs

Implement independent browser sessions per tab.

Support:

- New tab
- Switch tab
- Close tab

Do not share one GeckoSession between unrelated tabs.

### Step 6 — History and Bookmarks

Implement Room entities, DAOs, and repositories.

Keep database operations out of Composables.

### Step 7 — Settings

Implement DataStore-backed settings.

### Step 8 — Downloads and Private Browsing

Implement using supported Android/GeckoView APIs.

Do not fake private browsing by merely deleting history.

## Code Quality

Every completed feature should satisfy:

- No compilation errors
- No unused imports
- No obvious memory leaks
- No unnecessary permissions
- No hardcoded user-facing strings where resources should be used
- No business logic inside Composables
- No direct database calls from UI
- No direct GeckoView calls from UI

## Testing

After implementation:

1. Run the Gradle build.
2. Fix compilation errors.
3. Run available unit/instrumentation tests.
4. Fix obvious runtime issues.
5. Verify the main browser flow.
6. Verify tab creation/closing/switching.
7. Verify history and bookmarks.
8. Verify theme/settings behavior.

If emulator/device testing is available, use it.

## Git Hygiene

Do not commit:

- Build outputs
- `.gradle`
- IDE-local files
- Secrets
- Signing keys
- Local machine configuration

Keep commits logically grouped when possible.

## Final Report

At the end, report:

1. What was implemented.
2. Which files were created/changed.
3. Build result.
4. Tests performed.
5. Any remaining limitations.
6. Exact command used to build the debug APK.

Do not claim a feature works unless it was actually implemented and verified.
