using Hexora.Browser.Core.Localization;

namespace Hexora.Browser.App;

public sealed class PrivateWindowRequestedEventArgs(SupportedLanguage language) : EventArgs
{
    public SupportedLanguage Language { get; } = language;
}
