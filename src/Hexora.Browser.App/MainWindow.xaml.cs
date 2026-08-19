using Hexora.Browser.App.ViewModels;
using Hexora.Browser.Core.Navigation;
using Microsoft.UI.Composition.SystemBackdrops;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.Web.WebView2.Core;

namespace Hexora.Browser.App;

public sealed partial class MainWindow : Window
{
    public MainWindowViewModel ViewModel { get; } = new(new NavigationInputParser());

    public MainWindow()
    {
        InitializeComponent();
        SystemBackdrop = new MicaBackdrop();

        ViewModel.NavigationRequested += OnNavigationRequested;
        ViewModel.CommandRequested += OnCommandRequested;
        Activated += OnWindowActivated;
    }

    private async void OnWindowActivated(object sender, WindowActivatedEventArgs args)
    {
        Activated -= OnWindowActivated;

        try
        {
            await BrowserView.EnsureCoreWebView2Async();
            ConfigureWebView();
            UpdateViewModel();
        }
        catch (Exception exception)
        {
            ShowError(exception.Message);
        }
    }

    private void ConfigureWebView()
    {
        var core = BrowserView.CoreWebView2;
        core.Settings.AreHostObjectsAllowed = false;
        core.Settings.IsStatusBarEnabled = false;
        core.Settings.IsZoomControlEnabled = true;
        core.DocumentTitleChanged += OnDocumentTitleChanged;
        core.ProcessFailed += OnWebViewProcessFailed;
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

    private void OnNavigationRequested(object? sender, NavigationRequestedEventArgs args)
    {
        BrowserView.Source = args.Request.Target;
    }

    private void OnCommandRequested(object? sender, BrowserCommandRequestedEventArgs args)
    {
        switch (args.Command)
        {
            case BrowserCommand.GoBack when BrowserView.CanGoBack:
                BrowserView.GoBack();
                break;
            case BrowserCommand.GoForward when BrowserView.CanGoForward:
                BrowserView.GoForward();
                break;
            case BrowserCommand.Reload:
                BrowserView.Reload();
                break;
            case BrowserCommand.Stop:
                BrowserView.CoreWebView2?.Stop();
                break;
        }
    }

    private void OnNavigationStarting(WebView2 sender, CoreWebView2NavigationStartingEventArgs args)
    {
        StatusBar.IsOpen = false;
        UpdateViewModel(isLoading: true);
    }

    private void OnNavigationCompleted(WebView2 sender, CoreWebView2NavigationCompletedEventArgs args)
    {
        UpdateViewModel(isLoading: false);
        if (!args.IsSuccess)
        {
            ShowError($"Navigation failed: {args.WebErrorStatus}");
        }
    }

    private void OnSourceChanged(WebView2 sender, CoreWebView2SourceChangedEventArgs args) => UpdateViewModel();

    private void OnDocumentTitleChanged(CoreWebView2 sender, object args) => UpdateViewModel();

    private void OnWebViewProcessFailed(CoreWebView2 sender, CoreWebView2ProcessFailedEventArgs args)
    {
        ShowError($"Web content process failed: {args.ProcessFailedKind}");
    }

    private void OnAddTabButtonClick(TabView sender, object args)
    {
        ShowError("Multiple tabs arrive in Phase 1. The current build is the first vertical slice.");
    }

    private void UpdateViewModel(bool? isLoading = null)
    {
        var core = BrowserView.CoreWebView2;
        ViewModel.UpdateNavigationState(
            BrowserView.Source,
            core?.DocumentTitle,
            isLoading ?? ViewModel.IsLoading,
            BrowserView.CanGoBack,
            BrowserView.CanGoForward);

        ActiveTab.Header = ViewModel.PageTitle;
    }

    private void ShowError(string message)
    {
        StatusBar.Message = message;
        StatusBar.IsOpen = true;
    }
}
