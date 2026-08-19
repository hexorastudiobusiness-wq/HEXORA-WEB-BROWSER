namespace Hexora.Browser.Core.Settings;

public sealed record BrowserSettings(string? LanguageTag)
{
    public static BrowserSettings Default { get; } = new((string?)null);
}
