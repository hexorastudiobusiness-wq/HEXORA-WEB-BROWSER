namespace Hexora.Browser.Core.Browsing;

public readonly record struct BrowserTabId(Guid Value)
{
    public static BrowserTabId New() => new(Guid.NewGuid());

    public override string ToString() => Value.ToString("N");
}
