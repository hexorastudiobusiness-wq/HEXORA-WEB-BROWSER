using System.Diagnostics;
using System.Globalization;
using Hexora.Browser.Core.Localization;
using Hexora.Browser.Infrastructure.Diagnostics;
using Hexora.Browser.Infrastructure.Settings;
using Microsoft.UI.Xaml;
using Windows.Globalization;

namespace Hexora.Browser.App;

public partial class App : Application
{
    private readonly Stopwatch _startupTimer = Stopwatch.StartNew();
    private readonly LocalDiagnosticLogger _diagnostics = LocalDiagnosticLogger.CreateDefault();
    private readonly RunStateTracker _runState = RunStateTracker.CreateDefault();
    private Window? _window;

    public App()
    {
        InitializeComponent();
        UnhandledException += (_, args) =>
            _diagnostics.Record("app.unhandled_exception", DiagnosticLevel.Critical, args.Exception);
    }

    protected override async void OnLaunched(LaunchActivatedEventArgs args)
    {
        var previousRunEndedUnexpectedly = _runState.BeginRun();
        _diagnostics.Record("app.started");
        if (previousRunEndedUnexpectedly)
        {
            _diagnostics.Record("app.previous_run_unexpected", DiagnosticLevel.Warning);
        }

        var settingsRepository = BrowserSettingsRepository.CreateDefault();
        var settings = await settingsRepository.LoadAsync();
        var language = settings.LanguageTag is null
            ? SupportedLanguages.GetBestMatch(ApplicationLanguages.Languages)
            : SupportedLanguages.GetOrDefault(settings.LanguageTag);

        ApplicationLanguages.PrimaryLanguageOverride = language.Tag;
        ApplyCulture(language.Tag);

        _window = new MainWindow(
            settingsRepository,
            settings,
            language,
            _diagnostics,
            _startupTimer,
            previousRunEndedUnexpectedly);
        _window.Closed += (_, _) =>
        {
            _diagnostics.Record("app.closed");
            _runState.CompleteRun();
        };
        _window.Activate();
    }

    internal static void ApplyCulture(string languageTag)
    {
        var culture = CultureInfo.GetCultureInfo(languageTag);
        CultureInfo.CurrentCulture = culture;
        CultureInfo.CurrentUICulture = culture;
    }
}
