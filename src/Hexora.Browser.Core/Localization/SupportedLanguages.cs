using System.Collections.ObjectModel;

namespace Hexora.Browser.Core.Localization;

public static class SupportedLanguages
{
    public static readonly SupportedLanguage English = new("en-US", "English", false);
    public static readonly SupportedLanguage Persian = new("fa-IR", "فارسی", true);
    public static readonly SupportedLanguage Arabic = new("ar-SA", "العربية", true);

    public static ReadOnlyCollection<SupportedLanguage> All { get; } =
        Array.AsReadOnly([English, Persian, Arabic]);

    public static SupportedLanguage GetOrDefault(string? languageTag) =>
        All.FirstOrDefault(language =>
            string.Equals(language.Tag, languageTag, StringComparison.OrdinalIgnoreCase)) ?? English;
}
