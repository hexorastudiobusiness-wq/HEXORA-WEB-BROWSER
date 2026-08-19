# Development guide

## Supported development environment

The desktop shell requires Windows 11 and the Windows application toolchain. The engine-independent Core project and its tests are cross-platform.

- Windows 11, x64
- Visual Studio 2022 with the **Windows application development** workload, or a compatible newer Visual Studio release
- .NET 10 SDK; `global.json` allows the latest installed .NET 10 feature band
- Windows 11 SDK 10.0.22621 or newer
- WebView2 Evergreen Runtime

## Pinned platform dependencies

Versions are managed centrally in `Directory.Packages.props`.

- .NET target: `net10.0` / `net10.0-windows10.0.22621.0`
- Microsoft Windows App SDK: `1.8.260804001`
- Microsoft WebView2 SDK: `1.0.4129.50`

.NET 10 is an LTS release. The Windows App SDK is intentionally pinned to the current servicing line selected for the first vertical slice rather than silently floating to new platform releases. Dependabot proposes upgrades, which must pass the Windows build and compatibility checks before merge.

## Build and test

From a Developer PowerShell prompt:

```powershell
dotnet restore HEXORA.slnx -p:Platform=x64
dotnet build HEXORA.slnx --configuration Debug -p:Platform=x64
dotnet test tests/Hexora.Browser.Core.Tests/Hexora.Browser.Core.Tests.csproj --configuration Release
```

Run the unpackaged application:

```powershell
dotnet run --project src/Hexora.Browser.App/Hexora.Browser.App.csproj --configuration Debug -p:Platform=x64
```

The current project is unpackaged for a short development loop. Production distribution will use signed MSIX packages and a separately reviewed update strategy.

## Project boundaries

- `Hexora.Browser.Core`: browser rules and state with no Windows or WebView dependency.
- `Hexora.Browser.WebView2`: WebView2 lifecycle, profiles, permissions, and engine adapters.
- `Hexora.Browser.Infrastructure`: persistence and operating-system service implementations.
- `Hexora.Browser.App`: WinUI views, view models, composition, and application lifecycle.
- `Hexora.Browser.Core.Tests`: fast tests for engine-independent behavior.

Dependencies point inward: App may compose all projects, adapters depend on Core, and Core must not depend on UI, persistence, or WebView2.

## Localization rules

- Default language: English (`en-US`).
- Initial additional languages: Persian (`fa-IR`) and Arabic (`ar-SA`).
- WinUI resources live in `src/Hexora.Browser.App/Strings/<language-tag>/Resources.resw`.
- User-facing strings must not be hard-coded in view models or code-behind. The current vertical slice still has temporary diagnostic text; it must be moved to resources before Phase 1 exits.
- Every UI change must be checked in English LTR, Persian RTL, Arabic RTL, and at 200% scaling.
- URL fields must preserve readable bidirectional behavior even when the surrounding UI is RTL.

## Security rules

- Page content is untrusted.
- Only `http` and `https` are accepted as direct address-bar navigation in the initial parser. Other input becomes a search query.
- WebView host objects are disabled. Do not add a general-purpose native bridge.
- External protocols, file access, downloads, permissions, certificates, and authentication require explicit policy and tests before shipping.
- Secrets must not be stored in the JSON settings store.
- Diagnostics must not record full URLs, search input, page contents, cookies, credentials, or form data.

## Continuous integration

`.github/workflows/ci.yml` runs:

1. Core tests on Linux to enforce the engine-independent boundary.
2. A complete x64 Windows solution build to validate WinUI and WebView2 integration.

The Linux workspace used by Arena cannot compile the WinUI desktop project. The Windows GitHub Actions job is therefore the authoritative build check for the shell.
