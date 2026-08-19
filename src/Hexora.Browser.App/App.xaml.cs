using System.Diagnostics;
using System.Globalization;
using Hexora.Browser.App.WebView;
using Hexora.Browser.Core.Localization;
using Hexora.Browser.Core.Settings;
using Hexora.Browser.Infrastructure.Diagnostics;
using Hexora.Browser.Infrastructure.Settings;
using Hexora.Browser.WebView2;
using Microsoft.UI.Xaml;
using Windows.Globalization;

namespace Hexora.Browser.App;

public partial class App : Application
{
    private readonly Stopwatch _startupTimer = Stopwatch.StartNew();
    private readonly LocalDiagnosticLogger _diagnostics = LocalDiagnosticLogger.CreateDefault();
    private readonly RunStateTracker _runState = RunStateTracker.CreateDefault();
    private readonly WebViewProfileManager _profileManager = WebViewProfileManager.CreateDefault();
    private readonly HashSet<MainWindow> _windows = [];
    private BrowserSettingsRepository? _settingsRepository;
    private bool _hasUnhandledException;

    public App()
    {
        InitializeComponent();
        UnhandledException += (_, args) =>
        {
            _hasUnhandledException = true;
            _diagnostics.Record("app.unhandled_exception", DiagnosticLevel.Critical, args.Exception);
        };
    }

    protected override async void OnLaunched(LaunchActivatedEventArgs args)
    {
        var previousRunEndedUnexpectedly = _runState.BeginRun();
        _diagnostics.Record("app.started");
        if (previousRunEndedUnexpectedly)
        {
            _diagnostics.Record("app.previous_run_unexpected", DiagnosticLevel.Warning);
        }

        _profileManager.PurgeAbandonedPrivateProfiles();
        _settingsRepository = BrowserSettingsRepository.CreateDefault();
        var settings = await _settingsRepository.LoadAsync();
        var language = settings.LanguageTag is null
            ? SupportedLanguages.GetBestMatch(ApplicationLanguages.Languages)
            : SupportedLanguages.GetOrDefault(settings.LanguageTag);

        ApplicationLanguages.PrimaryLanguageOverride = language.Tag;
        ApplyCulture(language.Tag);

        var environment = new WebViewEnvironmentProvider(_profileManager.GetDefaultProfile());
        CreateWindow(
            environment,
            settings,
            language,
            _startupTimer,
            previousRunEndedUnexpectedly);
    }

    internal static void ApplyCulture(string languageTag)
    {
        var culture = CultureInfo.GetCultureInfo(languageTag);
        CultureInfo.CurrentCulture = culture;
        CultureInfo.CurrentUICulture = culture;
    }

    private void CreateWindow(
        WebViewEnvironmentProvider environment,
        BrowserSettings settings,
        SupportedLanguage language,
        Stopwatch startupTimer,
        bool previousRunEndedUnexpectedly)
    {
        if (_settingsRepository is null)
        {
            throw new InvalidOperationException("Application settings are not initialized.");
        }

        var window = new MainWindow(
            _settingsRepository,
            settings,
            language,
            _diagnostics,
            startupTimer,
            environment,
            previousRunEndedUnexpectedly);
        window.PrivateWindowRequested += OnPrivateWindowRequested;
        window.Closed += OnWindowClosed;
        _windows.Add(window);
        window.Activate();
    }

    private void OnPrivateWindowRequested(object? sender, PrivateWindowRequestedEventArgs args)
    {
        var profile = _profileManager.CreatePrivateProfile();
        var environment = new WebViewEnvironmentProvider(profile);
        var settings = new BrowserSettings(args.Language.Tag);
        _diagnostics.Record("window.private_opened");
        CreateWindow(
            environment,
            settings,
            args.Language,
            Stopwatch.StartNew(),
            previousRunEndedUnexpectedly: false);
    }

    private void OnWindowClosed(object sender, WindowEventArgs args)
    {
        if (sender is not MainWindow window)
        {
            return;
        }

        window.PrivateWindowRequested -= OnPrivateWindowRequested;
        window.Closed -= OnWindowClosed;
        _windows.Remove(window);

        if (window.Profile.IsPrivate)
        {
            _profileManager.DeleteProfileData(window.Profile);
            _diagnostics.Record("window.private_closed");
        }

        if (_windows.Count != 0 || _hasUnhandledException)
        {
            return;
        }

        _diagnostics.Record("app.closed");
        _runState.CompleteRun();
    }
}
