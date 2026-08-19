namespace Hexora.Browser.WebView2;

public sealed record WebViewProfileOptions(
    string UserDataFolder,
    string ProfileName,
    bool IsPrivate,
    bool DeleteDataOnClose);
