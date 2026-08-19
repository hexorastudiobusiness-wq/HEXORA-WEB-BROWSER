using Hexora.Browser.App.ViewModels;
using Hexora.Browser.Core.Browsing;
using Hexora.Browser.Core.Navigation;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Microsoft.Web.WebView2.Core;
using WebViewControl = Microsoft.UI.Xaml.Controls.WebView2;

namespace Hexora.Browser.App;

public sealed partial class MainWindow : Window
{
    private const string NewTabTitle = "New tab";

    private readonly BrowserSession _session = new();
    private readonly Dictionary<BrowserTabId, TabRuntime> _tabsById = [];
    private readonly Dictionary<WebViewControl, TabRuntime> _tabsByView = [];
    private readonly Dictionary<CoreWebView2, TabRuntime> _tabsByCore = [];
    private bool _isRootLoaded;

    public MainWindowViewModel ViewModel { get; } = new(new NavigationInputParser());

    public MainWindow()
    {
        InitializeComponent();
        SystemBackdrop = new MicaBackdrop();

        ViewModel.NavigationRequested += OnNavigationRequested;
        ViewModel.CommandRequested += OnCommandRequested;

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
            await view.EnsureCoreWebView2Async();
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
            ShowError(exception.Message);
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
    }

    private async Task ActivateTabAsync(BrowserTabId id)
    {
        if (!_tabsById.TryGetValue(id, out var runtime))
        {
            return;
        }

        _session.Activate(id);
        await EnsureWebViewAsync(runtime);

        foreach (var candidate in _tabsById.Values)
        {
            if (candidate.View is not null)
            {
                candidate.View.Visibility = candidate.Id == id ? Visibility.Visible : Visibility.Collapsed;
            }
        }

        UpdateRuntimeState(runtime);
        AddressBox.Focus(FocusState.Programmatic);
    }

    private void CloseTab(BrowserTabId id)
    {
        if (!_tabsById.Remove(id, out var runtime))
        {
            return;
        }

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
        catch (ArgumentException exception)
        {
            ShowError(exception.Message);
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

    private void OnNavigationStarting(WebViewControl sender, CoreWebView2NavigationStartingEventArgs args)
    {
        if (!_tabsByView.TryGetValue(sender, out var runtime))
        {
            return;
        }

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
            ShowError($"Navigation failed: {args.WebErrorStatus}");
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
            ShowError($"Web content process failed: {args.ProcessFailedKind}");
        }
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

    private static string GetDisplayTitle(string? title) =>
        string.IsNullOrWhiteSpace(title) ? NewTabTitle : title;

    private void ShowError(string message)
    {
        StatusBar.Message = message;
        StatusBar.IsOpen = true;
    }

    private sealed class TabRuntime(BrowserTabId id, TabViewItem item)
    {
        public BrowserTabId Id { get; } = id;

        public TabViewItem Item { get; } = item;

        public WebViewControl? View { get; set; }

        public CoreWebView2? Core { get; set; }

        public bool IsLoading { get; set; }
    }
}
