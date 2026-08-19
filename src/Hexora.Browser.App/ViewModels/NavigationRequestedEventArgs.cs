using Hexora.Browser.Core.Navigation;

namespace Hexora.Browser.App.ViewModels;

public sealed class NavigationRequestedEventArgs(NavigationRequest request) : EventArgs
{
    public NavigationRequest Request { get; } = request;
}
