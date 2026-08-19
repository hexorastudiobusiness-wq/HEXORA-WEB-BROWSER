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
        Find(languageTag) ?? English;

    public static SupportedLanguage GetBestMatch(IEnumerable<string> preferredLanguageTags)
    {
        ArgumentNullException.ThrowIfNull(preferredLanguageTags);

        foreach (var languageTag in preferredLanguageTags)
        {
            var language = Find(languageTag);
            if (language is not null)
            {
                return language;
            }
        }

        return English;
    }

    public static SupportedLanguage? Find(string? languageTag)
    {
        if (string.IsNullOrWhiteSpace(languageTag))
        {
            return null;
        }

        var exact = All.FirstOrDefault(language =>
            string.Equals(language.Tag, languageTag, StringComparison.OrdinalIgnoreCase));
        if (exact is not null)
        {
            return exact;
        }

        var separatorIndex = languageTag.IndexOf('-');
        var neutralTag = separatorIndex < 0 ? languageTag : languageTag[..separatorIndex];
        return All.FirstOrDefault(language =>
            language.Tag.StartsWith($"{neutralTag}-", StringComparison.OrdinalIgnoreCase));
    }
}
