namespace Hexora.Browser.Core.Browsing;

public sealed record BrowserTabState(
    BrowserTabId Id,
    Uri? Source,
    string Title,
    bool IsPinned,
    bool IsActive)
{
    public static BrowserTabState New(bool isActive = false) =>
        new(BrowserTabId.New(), null, string.Empty, false, isActive);
}
