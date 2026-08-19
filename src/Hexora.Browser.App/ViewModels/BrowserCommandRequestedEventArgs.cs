namespace Hexora.Browser.App.ViewModels;

public sealed class BrowserCommandRequestedEventArgs(BrowserCommand command) : EventArgs
{
    public BrowserCommand Command { get; } = command;
}
