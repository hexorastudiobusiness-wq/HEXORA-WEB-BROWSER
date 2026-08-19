namespace Hexora.Browser.WebView2;

public sealed record WebViewProfileOptions(string UserDataFolder, bool IsPrivate)
{
    public string ProfileName { get; } = IsPrivate ? $"Private-{Guid.NewGuid():N}" : "Default";
}
