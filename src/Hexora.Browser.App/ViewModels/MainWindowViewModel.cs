using Hexora.Browser.App.Presentation;
using Hexora.Browser.Core.Navigation;

namespace Hexora.Browser.App.ViewModels;

public sealed class MainWindowViewModel : ObservableObject
{
    private readonly INavigationInputParser _navigationInputParser;
    private string _address = string.Empty;
    private string _pageTitle = "New tab";
    private bool _isLoading;
    private bool _canGoBack;
    private bool _canGoForward;

    public MainWindowViewModel(INavigationInputParser navigationInputParser)
    {
        _navigationInputParser = navigationInputParser;
        BackCommand = new RelayCommand(() => RequestCommand(BrowserCommand.GoBack), () => CanGoBack);
        ForwardCommand = new RelayCommand(() => RequestCommand(BrowserCommand.GoForward), () => CanGoForward);
        ReloadCommand = new RelayCommand(
            () => RequestCommand(IsLoading ? BrowserCommand.Stop : BrowserCommand.Reload));
    }

    public event EventHandler<NavigationRequestedEventArgs>? NavigationRequested;

    public event EventHandler<BrowserCommandRequestedEventArgs>? CommandRequested;

    public RelayCommand BackCommand { get; }

    public RelayCommand ForwardCommand { get; }

    public RelayCommand ReloadCommand { get; }

    public string Address
    {
        get => _address;
        set => SetProperty(ref _address, value);
    }

    public string PageTitle
    {
        get => _pageTitle;
        private set => SetProperty(ref _pageTitle, value);
    }

    public bool IsLoading
    {
        get => _isLoading;
        private set => SetProperty(ref _isLoading, value);
    }

    public bool CanGoBack
    {
        get => _canGoBack;
        private set
        {
            if (SetProperty(ref _canGoBack, value))
            {
                BackCommand.NotifyCanExecuteChanged();
            }
        }
    }

    public bool CanGoForward
    {
        get => _canGoForward;
        private set
        {
            if (SetProperty(ref _canGoForward, value))
            {
                ForwardCommand.NotifyCanExecuteChanged();
            }
        }
    }

    public void NavigateFromInput(string input)
    {
        var request = _navigationInputParser.Parse(input);
        NavigationRequested?.Invoke(this, new NavigationRequestedEventArgs(request));
    }

    public void UpdateNavigationState(Uri? source, string? title, bool isLoading, bool canGoBack, bool canGoForward)
    {
        if (source is not null && source.Scheme is "http" or "https")
        {
            Address = source.AbsoluteUri;
        }

        PageTitle = string.IsNullOrWhiteSpace(title) ? "New tab" : title;
        IsLoading = isLoading;
        CanGoBack = canGoBack;
        CanGoForward = canGoForward;
    }

    private void RequestCommand(BrowserCommand command) =>
        CommandRequested?.Invoke(this, new BrowserCommandRequestedEventArgs(command));
}
