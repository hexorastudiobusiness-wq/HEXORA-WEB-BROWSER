using Microsoft.Windows.ApplicationModel.Resources;

namespace Hexora.Browser.App.Localization;

public sealed class AppLocalizer
{
    private readonly ResourceManager _resourceManager = new();
    private readonly ResourceMap _resourceMap;
    private ResourceContext _context;

    public AppLocalizer(string languageTag)
    {
        _resourceMap = _resourceManager.MainResourceMap.GetSubtree("Resources");
        _context = CreateContext(languageTag);
    }

    public string LanguageTag { get; private set; } = string.Empty;

    public string GetString(string resourceName) =>
        _resourceMap.GetValue(resourceName, _context).ValueAsString;

    public string Format(string resourceName, object? value) =>
        string.Format(System.Globalization.CultureInfo.CurrentCulture, GetString(resourceName), value);

    public void SetLanguage(string languageTag)
    {
        _context = CreateContext(languageTag);
    }

    private ResourceContext CreateContext(string languageTag)
    {
        LanguageTag = languageTag;
        var context = _resourceManager.CreateResourceContext();
        context.QualifierValues["Language"] = languageTag;
        return context;
    }
}
