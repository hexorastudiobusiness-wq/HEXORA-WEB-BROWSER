using System.Diagnostics;
using System.Text.Json;
using Hexora.Browser.App.Localization;
using Hexora.Browser.App.ViewModels;
using Hexora.Browser.App.WebView;
using Hexora.Browser.Core.Browsing;
using Hexora.Browser.Core.Localization;
using Hexora.Browser.Core.Navigation;
using Hexora.Browser.Core.Settings;
using Hexora.Browser.Infrastructure.Diagnostics;
using Hexora.Browser.Infrastructure.Settings;
using Hexora.Browser.WebView2;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Microsoft.Web.WebView2.Core;
using Windows.Globalization;
using WebViewControl = Microsoft.UI.Xaml.Controls.WebView2;

namespace Hexora.Browser.App;

public sealed partial class MainWindow : Window
{
    private static readonly TimeSpan BackgroundTabSleepDelay = TimeSpan.FromSeconds(30);
    private static readonly HashSet<string> AllowedExternalSchemes = new(StringComparer.OrdinalIgnoreCase)
    {
        "mailto",
        "sms",
        "tel",
        "webcal",
    };

    private readonly BrowserSession _session = new();
    private readonly BrowserSettingsRepository _settingsRepository;
    private readonly LocalDiagnosticLogger _diagnostics;
    private readonly Stopwatch _startupTimer;
    private readonly AppLocalizer _localizer;
    private readonly WebViewEnvironmentProvider _environmentProvider;
    private bool _securityPromptOpen;
    private readonly bool _previousRunEndedUnexpectedly;
    private readonly Dictionary<BrowserTabId, TabRuntime> _tabsById = [];
    private readonly Dictionary<WebViewControl, TabRuntime> _tabsByView = [];
    private readonly Dictionary<CoreWebView2, TabRuntime> _tabsByCore = [];
    private BrowserSettings _settings;
    private SupportedLanguage _language;
    private bool _isRootLoaded;

    public MainWindowViewModel ViewModel { get; } = new(new NavigationInputParser());

    public WebViewProfileOptions Profile => _environmentProvider.Profile;

    public event EventHandler<PrivateWindowRequestedEventArgs>? PrivateWindowRequested;

    public MainWindow(
        BrowserSettingsRepository settingsRepository,
        BrowserSettings settings,
        SupportedLanguage language,
        LocalDiagnosticLogger diagnostics,
        Stopwatch startupTimer,
        WebViewEnvironmentProvider environmentProvider,
        bool previousRunEndedUnexpectedly)
    {
        _settingsRepository = settingsRepository;
        _settings = settings;
        _language = language;
        _diagnostics = diagnostics;
        _startupTimer = startupTimer;
        _environmentProvider = environmentProvider;
        _previousRunEndedUnexpectedly = previousRunEndedUnexpectedly;
        _localizer = new AppLocalizer(language.Tag);

        InitializeComponent();
        SystemBackdrop = new MicaBackdrop();
        ApplyLocalization();

        ViewModel.NavigationRequested += OnNavigationRequested;
        ViewModel.CommandRequested += OnCommandRequested;
        Closed += OnWindowClosed;

        var initialRuntime = CreateVisualTab(_session.ActiveTab);
        Tabs.SelectedItem = initialRuntime.Item;
    }

    private async void OnRootLoaded(object sender, RoutedEventArgs args)
    {
        if (_isRootLoaded)
        {
            return;
        }

        _isRootLoaded = true;
        await ActivateTabAsync(_session.ActiveTab.Id);

        using var currentProcess = Process.GetCurrentProcess();
        var workingSetMegabytes = currentProcess.WorkingSet64 / (1024 * 1024);
        _diagnostics.Record(
            "app.window_interactive",
            durationMilliseconds: _startupTimer.ElapsedMilliseconds,
            workingSetMegabytes: workingSetMegabytes);

        if (_previousRunEndedUnexpectedly && !StatusBar.IsOpen)
        {
            ShowWarning(_localizer.GetString("PreviousRunEndedUnexpectedly"));
        }
    }

    private TabRuntime CreateVisualTab(BrowserTabState state)
    {
        var item = new TabViewItem
        {
            Header = GetDisplayTitle(state.Title),
            IsClosable = true,
            Tag = state.Id,
        };

        var runtime = new TabRuntime(state.Id, item);
        _tabsById.Add(state.Id, runtime);
        Tabs.TabItems.Add(item);
        return runtime;
    }

    private async Task EnsureWebViewAsync(TabRuntime runtime)
    {
        if (runtime.View is not null)
        {
            return;
        }

        var view = new WebViewControl
        {
            HorizontalAlignment = HorizontalAlignment.Stretch,
            VerticalAlignment = VerticalAlignment.Stretch,
            Visibility = Visibility.Collapsed,
        };

        runtime.View = view;
        _tabsByView.Add(view, runtime);
        view.NavigationStarting += OnNavigationStarting;
        view.NavigationCompleted += OnNavigationCompleted;
        BrowserHost.Children.Add(view);

        try
        {
            await _environmentProvider.InitializeAsync(view);
            var core = view.CoreWebView2;
            runtime.Core = core;
            _tabsByCore.Add(core, runtime);
            ConfigureWebView(core);

            var state = FindState(runtime.Id);
            if (state?.Source is not null)
            {
                view.Source = state.Source;
            }
        }
        catch (Exception exception)
        {
            _diagnostics.Record("webview.initialization_failed", DiagnosticLevel.Error, exception);
            ShowError(_localizer.Format("InitializationFailedFormat", exception.Message));
        }
    }

    private void ConfigureWebView(CoreWebView2 core)
    {
        core.Settings.AreHostObjectsAllowed = false;
        core.Settings.IsStatusBarEnabled = false;
        core.Settings.IsZoomControlEnabled = true;
        core.DocumentTitleChanged += OnDocumentTitleChanged;
        core.SourceChanged += OnCoreSourceChanged;
        core.ProcessFailed += OnWebViewProcessFailed;
        core.PermissionRequested += OnPermissionRequested;
        core.NewWindowRequested += OnNewWindowRequested;
        core.DownloadStarting += OnDownloadStarting;
        core.BasicAuthenticationRequested += OnBasicAuthenticationRequested;
        core.ServerCertificateErrorDetected += OnServerCertificateErrorDetected;
    }

    private async Task ActivateTabAsync(BrowserTabId id)
    {
        if (!_tabsById.TryGetValue(id, out var runtime))
        {
            return;
        }

        _session.Activate(id);
        CancelScheduledSleep(runtime);
        await EnsureWebViewAsync(runtime);
        ResumeTab(runtime);

        foreach (var candidate in _tabsById.Values)
        {
            if (candidate.View is not null)
            {
                candidate.View.Visibility = candidate.Id == id ? Visibility.Visible : Visibility.Collapsed;
            }

            if (candidate.Id != id)
            {
                ScheduleTabSleep(candidate);
            }
        }

        UpdateRuntimeState(runtime);
        AddressBox.Focus(FocusState.Programmatic);
    }

    private void OnWindowClosed(object sender, WindowEventArgs args)
    {
        foreach (var runtime in _tabsById.Values)
        {
            CancelScheduledSleep(runtime);
            runtime.View?.Close();
        }

        _tabsByCore.Clear();
        _tabsByView.Clear();
        _tabsById.Clear();
        BrowserHost.Children.Clear();
    }

    private void CloseTab(BrowserTabId id)
    {
        if (!_tabsById.Remove(id, out var runtime))
        {
            return;
        }

        CancelScheduledSleep(runtime);
        Tabs.TabItems.Remove(runtime.Item);
        if (runtime.View is not null)
        {
            _tabsByView.Remove(runtime.View);
            BrowserHost.Children.Remove(runtime.View);
            runtime.View.Close();
        }

        if (runtime.Core is not null)
        {
            _tabsByCore.Remove(runtime.Core);
        }

        _session.Close(id);

        if (_tabsById.Count == 0)
        {
            CreateVisualTab(_session.ActiveTab);
        }

        Tabs.SelectedItem = _tabsById[_session.ActiveTab.Id].Item;
    }

    private void OnAddressQuerySubmitted(AutoSuggestBox sender, AutoSuggestBoxQuerySubmittedEventArgs args)
    {
        var input = args.QueryText?.Trim();
        if (string.IsNullOrEmpty(input))
        {
            return;
        }

        try
        {
            ViewModel.NavigateFromInput(input);
        }
        catch (ArgumentException)
        {
            ShowError(_localizer.GetString("InvalidAddressInput"));
        }
    }

    private async void OnNavigationRequested(object? sender, NavigationRequestedEventArgs args)
    {
        var runtime = GetActiveRuntime();
        if (runtime is null)
        {
            return;
        }

        await EnsureWebViewAsync(runtime);
        if (runtime.View is not null)
        {
            runtime.View.Source = args.Request.Target;
        }
    }

    private void OnCommandRequested(object? sender, BrowserCommandRequestedEventArgs args)
    {
        var view = GetActiveRuntime()?.View;
        if (view is null)
        {
            return;
        }

        switch (args.Command)
        {
            case BrowserCommand.GoBack when view.CanGoBack:
                view.GoBack();
                break;
            case BrowserCommand.GoForward when view.CanGoForward:
                view.GoForward();
                break;
            case BrowserCommand.Reload:
                view.Reload();
                break;
            case BrowserCommand.Stop:
                view.CoreWebView2?.Stop();
                break;
        }
    }

    private async void OnNavigationStarting(WebViewControl sender, CoreWebView2NavigationStartingEventArgs args)
    {
        if (!_tabsByView.TryGetValue(sender, out var runtime))
        {
            args.Cancel = true;
            return;
        }

        if (!TryCreateAllowedWebUri(args.Uri, out _) && !IsBlankPage(args.Uri))
        {
            args.Cancel = true;
            UpdateRuntimeState(runtime, isLoading: false);

            if (args.IsUserInitiated && TryCreateAllowedExternalUri(args.Uri, out var externalUri))
            {
                await PromptAndLaunchExternalUriAsync(externalUri);
            }
            else
            {
                _diagnostics.Record("navigation.external_blocked", DiagnosticLevel.Warning);
            }
            return;
        }

        runtime.HasSensitiveActivity = false;
        StatusBar.IsOpen = false;
        UpdateRuntimeState(runtime, isLoading: true);
    }

    private void OnNavigationCompleted(WebViewControl sender, CoreWebView2NavigationCompletedEventArgs args)
    {
        if (!_tabsByView.TryGetValue(sender, out var runtime))
        {
            return;
        }

        UpdateRuntimeState(runtime, isLoading: false);
        if (!args.IsSuccess && runtime.Id == _session.ActiveTab.Id)
        {
            _diagnostics.Record("webview.navigation_failed", DiagnosticLevel.Warning);
            ShowError(_localizer.Format("NavigationFailedFormat", args.WebErrorStatus));
        }
    }

    private void OnCoreSourceChanged(CoreWebView2 sender, CoreWebView2SourceChangedEventArgs args)
    {
        if (_tabsByCore.TryGetValue(sender, out var runtime))
        {
            UpdateRuntimeState(runtime);
        }
    }

    private void OnDocumentTitleChanged(CoreWebView2 sender, object args)
    {
        if (_tabsByCore.TryGetValue(sender, out var runtime))
        {
            UpdateRuntimeState(runtime);
        }
    }

    private void OnWebViewProcessFailed(CoreWebView2 sender, CoreWebView2ProcessFailedEventArgs args)
    {
        if (_tabsByCore.TryGetValue(sender, out var runtime) && runtime.Id == _session.ActiveTab.Id)
        {
            _diagnostics.Record("webview.process_failed", DiagnosticLevel.Error);
            ShowError(_localizer.Format("WebContentProcessFailedFormat", args.ProcessFailedKind));
        }
    }

    private async void OnPermissionRequested(CoreWebView2 sender, CoreWebView2PermissionRequestedEventArgs args)
    {
        args.SavesInProfile = false;
        args.State = CoreWebView2PermissionState.Deny;
        args.Handled = true;
        var deferral = args.GetDeferral();
        var ownsPrompt = false;

        try
        {
            if (_securityPromptOpen ||
                !_tabsByCore.TryGetValue(sender, out var runtime) ||
                runtime.Id != _session.ActiveTab.Id ||
                RootLayout.XamlRoot is null)
            {
                _diagnostics.Record("permission.denied_without_prompt", DiagnosticLevel.Warning);
                return;
            }

            _securityPromptOpen = true;
            ownsPrompt = true;
            var permissionName = GetPermissionDisplayName(args.PermissionKind);
            var requestingHost = GetRequestingHost(args.Uri);
            var dialog = new ContentDialog
            {
                XamlRoot = RootLayout.XamlRoot,
                Title = _localizer.GetString("PermissionDialogTitle"),
                Content = _localizer.Format("PermissionRequestFormat", requestingHost, permissionName),
                PrimaryButtonText = _localizer.GetString("PermissionAllow"),
                CloseButtonText = _localizer.GetString("PermissionDeny"),
                DefaultButton = ContentDialogButton.Close,
            };

            var result = await dialog.ShowAsync();
            if (result == ContentDialogResult.Primary)
            {
                args.State = CoreWebView2PermissionState.Allow;
                runtime.HasSensitiveActivity = true;
                _diagnostics.Record("permission.allowed_once");
            }
            else
            {
                _diagnostics.Record("permission.denied");
            }
        }
        catch (Exception exception)
        {
            _diagnostics.Record("permission.prompt_failed", DiagnosticLevel.Error, exception);
        }
        finally
        {
            if (ownsPrompt)
            {
                _securityPromptOpen = false;
            }
            deferral.Complete();
        }
    }

    private string GetPermissionDisplayName(CoreWebView2PermissionKind permissionKind)
    {
        var resourceName = permissionKind.ToString() switch
        {
            "Camera" => "PermissionCamera",
            "Microphone" => "PermissionMicrophone",
            "Geolocation" => "PermissionLocation",
            "Notifications" => "PermissionNotifications",
            "ClipboardRead" => "PermissionClipboard",
            "MultipleAutomaticDownloads" => "PermissionDownloads",
            _ => "PermissionOther",
        };
        return _localizer.GetString(resourceName);
    }

    private static string GetRequestingHost(string value) =>
        Uri.TryCreate(value, UriKind.Absolute, out var uri) && !string.IsNullOrWhiteSpace(uri.IdnHost)
            ? uri.IdnHost
            : "—";

    private async void OnNewWindowRequested(CoreWebView2 sender, CoreWebView2NewWindowRequestedEventArgs args)
    {
        args.Handled = true;
        if (!args.IsUserInitiated)
        {
            _diagnostics.Record("navigation.popup_blocked", DiagnosticLevel.Warning);
            return;
        }

        if (TryCreateAllowedWebUri(args.Uri, out var webUri))
        {
            OpenTab(webUri);
            _diagnostics.Record("navigation.popup_opened_tab");
            return;
        }

        if (TryCreateAllowedExternalUri(args.Uri, out var externalUri))
        {
            await PromptAndLaunchExternalUriAsync(externalUri);
        }
        else
        {
            _diagnostics.Record("navigation.popup_blocked", DiagnosticLevel.Warning);
        }
    }

    private async void OnDownloadStarting(CoreWebView2 sender, CoreWebView2DownloadStartingEventArgs args)
    {
        args.Cancel = true;
        args.Handled = true;
        var deferral = args.GetDeferral();
        var ownsPrompt = false;

        try
        {
            if (_securityPromptOpen ||
                !_tabsByCore.TryGetValue(sender, out var runtime) ||
                runtime.Id != _session.ActiveTab.Id ||
                RootLayout.XamlRoot is null)
            {
                _diagnostics.Record("download.denied_without_prompt", DiagnosticLevel.Warning);
                return;
            }

            _securityPromptOpen = true;
            ownsPrompt = true;
            var suggestedName = Path.GetFileName(args.ResultFilePath);
            var messageResource = Profile.IsPrivate
                ? "PrivateDownloadRequestFormat"
                : "DownloadRequestFormat";
            var dialog = CreateConfirmationDialog(
                "DownloadDialogTitle",
                _localizer.Format(messageResource, suggestedName),
                "DownloadAllow",
                "DownloadCancel");

            var result = await dialog.ShowAsync();
            if (result == ContentDialogResult.Primary)
            {
                args.Cancel = false;
                args.Handled = false;
                TrackDownload(runtime, args.DownloadOperation);
                _diagnostics.Record("download.allowed");
            }
            else
            {
                _diagnostics.Record("download.denied");
            }
        }
        catch (Exception exception)
        {
            _diagnostics.Record("download.prompt_failed", DiagnosticLevel.Error, exception);
        }
        finally
        {
            if (ownsPrompt)
            {
                _securityPromptOpen = false;
            }
            deferral.Complete();
        }
    }

    private async void OnBasicAuthenticationRequested(
        CoreWebView2 sender,
        CoreWebView2BasicAuthenticationRequestedEventArgs args)
    {
        args.Cancel = true;
        var deferral = args.GetDeferral();
        var ownsPrompt = false;

        try
        {
            if (_securityPromptOpen ||
                !_tabsByCore.TryGetValue(sender, out var runtime) ||
                runtime.Id != _session.ActiveTab.Id ||
                !Uri.TryCreate(args.Uri, UriKind.Absolute, out var requestUri) ||
                requestUri.Scheme != Uri.UriSchemeHttps ||
                RootLayout.XamlRoot is null)
            {
                _diagnostics.Record("authentication.denied_without_prompt", DiagnosticLevel.Warning);
                return;
            }

            _securityPromptOpen = true;
            ownsPrompt = true;
            var userNameBox = new TextBox
            {
                Header = _localizer.GetString("AuthenticationUserName"),
                MaxLength = 256,
            };
            var passwordBox = new PasswordBox
            {
                Header = _localizer.GetString("AuthenticationPassword"),
                MaxLength = 1024,
            };
            var content = new StackPanel { Spacing = 12 };
            content.Children.Add(new TextBlock
            {
                Text = _localizer.Format("AuthenticationRequestFormat", requestUri.IdnHost),
                TextWrapping = TextWrapping.Wrap,
            });
            content.Children.Add(userNameBox);
            content.Children.Add(passwordBox);

            var dialog = new ContentDialog
            {
                XamlRoot = RootLayout.XamlRoot,
                Title = _localizer.GetString("AuthenticationDialogTitle"),
                Content = content,
                PrimaryButtonText = _localizer.GetString("AuthenticationContinue"),
                CloseButtonText = _localizer.GetString("AuthenticationCancel"),
                DefaultButton = ContentDialogButton.Close,
            };

            var result = await dialog.ShowAsync();
            if (result == ContentDialogResult.Primary && !string.IsNullOrWhiteSpace(userNameBox.Text))
            {
                args.Response.UserName = userNameBox.Text;
                args.Response.Password = passwordBox.Password;
                args.Cancel = false;
                _diagnostics.Record("authentication.submitted");
            }
            else
            {
                _diagnostics.Record("authentication.denied");
            }
        }
        catch (Exception exception)
        {
            _diagnostics.Record("authentication.prompt_failed", DiagnosticLevel.Error, exception);
        }
        finally
        {
            if (ownsPrompt)
            {
                _securityPromptOpen = false;
            }
            deferral.Complete();
        }
    }

    private void OnServerCertificateErrorDetected(
        CoreWebView2 sender,
        CoreWebView2ServerCertificateErrorDetectedEventArgs args)
    {
        args.Action = CoreWebView2ServerCertificateErrorAction.Cancel;
        _diagnostics.Record("certificate.request_blocked", DiagnosticLevel.Warning);

        if (_tabsByCore.TryGetValue(sender, out var runtime) && runtime.Id == _session.ActiveTab.Id)
        {
            ShowError(_localizer.Format("CertificateErrorFormat", args.ErrorStatus));
        }
    }

    private async Task PromptAndLaunchExternalUriAsync(Uri externalUri)
    {
        if (_securityPromptOpen || RootLayout.XamlRoot is null)
        {
            _diagnostics.Record("navigation.external_denied_without_prompt", DiagnosticLevel.Warning);
            return;
        }

        try
        {
            _securityPromptOpen = true;
            var dialog = CreateConfirmationDialog(
                "ExternalProtocolDialogTitle",
                _localizer.Format("ExternalProtocolRequestFormat", externalUri.Scheme),
                "ExternalProtocolOpen",
                "ExternalProtocolCancel");
            var result = await dialog.ShowAsync();
            if (result != ContentDialogResult.Primary)
            {
                _diagnostics.Record("navigation.external_denied");
                return;
            }

            var launched = await Windows.System.Launcher.LaunchUriAsync(externalUri);
            _diagnostics.Record(launched ? "navigation.external_opened" : "navigation.external_failed");
        }
        catch (Exception exception)
        {
            _diagnostics.Record("navigation.external_failed", DiagnosticLevel.Error, exception);
        }
        finally
        {
            _securityPromptOpen = false;
        }
    }

    private ContentDialog CreateConfirmationDialog(
        string titleResource,
        string content,
        string primaryButtonResource,
        string closeButtonResource) =>
        new()
        {
            XamlRoot = RootLayout.XamlRoot,
            Title = _localizer.GetString(titleResource),
            Content = new TextBlock
            {
                Text = content,
                TextWrapping = TextWrapping.Wrap,
            },
            PrimaryButtonText = _localizer.GetString(primaryButtonResource),
            CloseButtonText = _localizer.GetString(closeButtonResource),
            DefaultButton = ContentDialogButton.Close,
        };

    private void OpenTab(Uri source)
    {
        var state = _session.OpenTab(source);
        var runtime = CreateVisualTab(state);
        Tabs.SelectedItem = runtime.Item;
    }

    private static bool TryCreateAllowedWebUri(string value, out Uri uri)
    {
        if (Uri.TryCreate(value, UriKind.Absolute, out var candidate) &&
            candidate.Scheme is "http" or "https")
        {
            uri = candidate;
            return true;
        }

        uri = null!;
        return false;
    }

    private static bool TryCreateAllowedExternalUri(string value, out Uri uri)
    {
        if (Uri.TryCreate(value, UriKind.Absolute, out var candidate) &&
            AllowedExternalSchemes.Contains(candidate.Scheme))
        {
            uri = candidate;
            return true;
        }

        uri = null!;
        return false;
    }

    private static bool IsBlankPage(string value) =>
        string.Equals(value, "about:blank", StringComparison.OrdinalIgnoreCase);

    private void TrackDownload(TabRuntime runtime, CoreWebView2DownloadOperation download)
    {
        runtime.IsDownloading = true;
        download.StateChanged += OnStateChanged;

        void OnStateChanged(CoreWebView2DownloadOperation operation, object eventArgs)
        {
            if (operation.State == CoreWebView2DownloadState.InProgress || operation.CanResume)
            {
                return;
            }

            operation.StateChanged -= OnStateChanged;
            runtime.IsDownloading = false;
            if (_tabsById.ContainsKey(runtime.Id) && runtime.Id != _session.ActiveTab.Id)
            {
                ScheduleTabSleep(runtime);
            }
        }
    }

    private void ScheduleTabSleep(TabRuntime runtime)
    {
        CancelScheduledSleep(runtime);
        if (runtime.Core is null || runtime.Id == _session.ActiveTab.Id)
        {
            return;
        }

        runtime.SleepCancellation = new CancellationTokenSource();
        _ = SuspendTabAfterDelayAsync(runtime, runtime.SleepCancellation.Token);
    }

    private async Task SuspendTabAfterDelayAsync(TabRuntime runtime, CancellationToken cancellationToken)
    {
        try
        {
            await Task.Delay(BackgroundTabSleepDelay, cancellationToken);
            if (cancellationToken.IsCancellationRequested ||
                !_tabsById.ContainsKey(runtime.Id) ||
                runtime.Core is null)
            {
                return;
            }

            var activity = new TabActivityState(
                IsActive: runtime.Id == _session.ActiveTab.Id,
                IsInitialized: true,
                IsPlayingAudio: runtime.Core.IsDocumentPlayingAudio,
                IsDownloading: runtime.IsDownloading,
                HasSensitiveActivity: runtime.HasSensitiveActivity,
                IsSecurityPromptOpen: _securityPromptOpen);
            if (!TabSleepPolicy.CanSuspend(activity))
            {
                return;
            }

            runtime.IsSuspended = await runtime.Core.TrySuspendAsync();
            if (runtime.IsSuspended)
            {
                _diagnostics.Record("tab.suspended");
            }
        }
        catch (OperationCanceledException)
        {
            // Selecting or closing the tab cancels its pending sleep operation.
        }
        catch (Exception exception)
        {
            _diagnostics.Record("tab.suspend_failed", DiagnosticLevel.Warning, exception);
        }
    }

    private static void CancelScheduledSleep(TabRuntime runtime)
    {
        runtime.SleepCancellation?.Cancel();
        runtime.SleepCancellation?.Dispose();
        runtime.SleepCancellation = null;
    }

    private void ResumeTab(TabRuntime runtime)
    {
        if (!runtime.IsSuspended || runtime.Core is null)
        {
            return;
        }

        runtime.Core.Resume();
        runtime.IsSuspended = false;
        _diagnostics.Record("tab.resumed");
    }

    private void OnAddTabButtonClick(TabView sender, object args)
    {
        var state = _session.OpenTab();
        var runtime = CreateVisualTab(state);
        Tabs.SelectedItem = runtime.Item;
    }

    private async void OnTabSelectionChanged(object sender, SelectionChangedEventArgs args)
    {
        if (!_isRootLoaded || Tabs.SelectedItem is not TabViewItem { Tag: BrowserTabId id })
        {
            return;
        }

        await ActivateTabAsync(id);
    }

    private void OnTabCloseRequested(TabView sender, TabViewTabCloseRequestedEventArgs args)
    {
        if (args.Tab.Tag is BrowserTabId id)
        {
            CloseTab(id);
        }
    }

    private async void OnPerformanceSnapshotClick(object sender, RoutedEventArgs args)
    {
        if (_securityPromptOpen || RootLayout.XamlRoot is null)
        {
            return;
        }

        try
        {
            _securityPromptOpen = true;
            var environment = await _environmentProvider.GetEnvironmentAsync();
            var snapshot = WebViewProcessSampler.Capture(environment);
            _diagnostics.Record(
                "webview.process_snapshot",
                durationMilliseconds: snapshot.TotalProcessorTimeMilliseconds,
                workingSetMegabytes: snapshot.WorkingSetMegabytes);

            var dialog = new ContentDialog
            {
                XamlRoot = RootLayout.XamlRoot,
                Title = _localizer.GetString("PerformanceSnapshotTitle"),
                Content = new TextBlock
                {
                    Text = _localizer.Format(
                        "PerformanceSnapshotFormat",
                        snapshot.ReportedProcessCount,
                        snapshot.SampledProcessCount,
                        snapshot.WorkingSetMegabytes,
                        snapshot.PrivateMemoryMegabytes,
                        snapshot.TotalProcessorTimeMilliseconds,
                        snapshot.HostWorkingSetMegabytes),
                    TextWrapping = TextWrapping.Wrap,
                },
                CloseButtonText = _localizer.GetString("PerformanceSnapshotClose"),
            };
            await dialog.ShowAsync();
        }
        catch (Exception exception)
        {
            _diagnostics.Record("webview.process_snapshot_failed", DiagnosticLevel.Error, exception);
            ShowError(_localizer.GetString("PerformanceSnapshotFailed"));
        }
        finally
        {
            _securityPromptOpen = false;
        }
    }

    private void OnNewPrivateWindowClick(object sender, RoutedEventArgs args)
    {
        _diagnostics.Record("window.private_requested");
        PrivateWindowRequested?.Invoke(this, new PrivateWindowRequestedEventArgs(_language));
    }

    private async void OnLanguageItemClick(object sender, RoutedEventArgs args)
    {
        var selectedLanguage = sender switch
        {
            _ when ReferenceEquals(sender, EnglishLanguageItem) => SupportedLanguages.English,
            _ when ReferenceEquals(sender, PersianLanguageItem) => SupportedLanguages.Persian,
            _ when ReferenceEquals(sender, ArabicLanguageItem) => SupportedLanguages.Arabic,
            _ => null,
        };
        if (selectedLanguage is null)
        {
            return;
        }
        if (selectedLanguage == _language)
        {
            UpdateLanguageChecks();
            return;
        }

        try
        {
            var settings = _settings with { LanguageTag = selectedLanguage.Tag };
            await _settingsRepository.SaveAsync(settings);

            _settings = settings;
            _language = selectedLanguage;
            ApplicationLanguages.PrimaryLanguageOverride = selectedLanguage.Tag;
            App.ApplyCulture(selectedLanguage.Tag);
            _localizer.SetLanguage(selectedLanguage.Tag);
            ApplyLocalization();
            _diagnostics.Record("settings.language_changed");
        }
        catch (Exception exception) when (exception is IOException or UnauthorizedAccessException or JsonException)
        {
            _diagnostics.Record("settings.save_failed", DiagnosticLevel.Error, exception);
            ShowError(_localizer.GetString("SettingsSaveFailed"));
            UpdateLanguageChecks();
        }
    }

    private void ApplyLocalization()
    {
        var appTitle = _localizer.GetString("AppTitle");
        Title = _environmentProvider.Profile.IsPrivate
            ? _localizer.Format("PrivateWindowTitleFormat", appTitle)
            : appTitle;
        NewPrivateWindowItem.Text = _localizer.GetString("NewPrivateWindow");
        PerformanceSnapshotItem.Text = _localizer.GetString("PerformanceSnapshotMenu");
        RootLayout.Language = _language.Tag;
        RootLayout.FlowDirection = _language.IsRightToLeft
            ? FlowDirection.RightToLeft
            : FlowDirection.LeftToRight;

        // Web content and URL input keep their natural left-to-right coordinate system.
        BrowserHost.FlowDirection = FlowDirection.LeftToRight;
        AddressBox.FlowDirection = FlowDirection.LeftToRight;
        AddressBox.PlaceholderText = _localizer.GetString("AddressPlaceholder");
        StatusBar.Title = _localizer.GetString("StatusTitle");
        LanguageMenuItem.Text = _localizer.GetString("LanguageMenuHeader");

        AutomationProperties.SetName(BackButton, _localizer.GetString("BackButtonName"));
        AutomationProperties.SetName(ForwardButton, _localizer.GetString("ForwardButtonName"));
        AutomationProperties.SetName(ReloadButton, _localizer.GetString("ReloadButtonName"));
        AutomationProperties.SetName(MenuButton, _localizer.GetString("MenuButtonName"));

        ViewModel.SetNewTabTitle(_localizer.GetString("NewTabTitle"));
        foreach (var runtime in _tabsById.Values)
        {
            var state = FindState(runtime.Id);
            if (string.IsNullOrWhiteSpace(state?.Title))
            {
                runtime.Item.Header = GetDisplayTitle(null);
            }
        }

        UpdateLanguageChecks();
    }

    private void UpdateLanguageChecks()
    {
        EnglishLanguageItem.IsChecked = _language == SupportedLanguages.English;
        PersianLanguageItem.IsChecked = _language == SupportedLanguages.Persian;
        ArabicLanguageItem.IsChecked = _language == SupportedLanguages.Arabic;
    }

    private void UpdateRuntimeState(TabRuntime runtime, bool? isLoading = null)
    {
        var view = runtime.View;
        var core = runtime.Core;
        var source = view?.Source;
        var title = core?.DocumentTitle;

        if (isLoading.HasValue)
        {
            runtime.IsLoading = isLoading.Value;
        }

        _session.UpdateNavigation(runtime.Id, source, title);
        runtime.Item.Header = GetDisplayTitle(title);

        if (runtime.Id == _session.ActiveTab.Id)
        {
            ViewModel.UpdateNavigationState(
                source,
                title,
                runtime.IsLoading,
                view?.CanGoBack ?? false,
                view?.CanGoForward ?? false);
        }
    }

    private BrowserTabState? FindState(BrowserTabId id) =>
        _session.Tabs.FirstOrDefault(tab => tab.Id == id);

    private TabRuntime? GetActiveRuntime() =>
        _tabsById.GetValueOrDefault(_session.ActiveTab.Id);

    private string GetDisplayTitle(string? title) =>
        string.IsNullOrWhiteSpace(title) ? _localizer.GetString("NewTabTitle") : title;

    private void ShowError(string message)
    {
        StatusBar.Severity = InfoBarSeverity.Error;
        StatusBar.Message = message;
        StatusBar.IsOpen = true;
    }

    private void ShowWarning(string message)
    {
        StatusBar.Severity = InfoBarSeverity.Warning;
        StatusBar.Message = message;
        StatusBar.IsOpen = true;
    }

    private sealed class TabRuntime(BrowserTabId id, TabViewItem item)
    {
        public BrowserTabId Id { get; } = id;

        public TabViewItem Item { get; } = item;

        public WebViewControl? View { get; set; }

        public CoreWebView2? Core { get; set; }

        public CancellationTokenSource? SleepCancellation { get; set; }

        public bool IsLoading { get; set; }

        public bool IsDownloading { get; set; }

        public bool HasSensitiveActivity { get; set; }

        public bool IsSuspended { get; set; }
    }
}
