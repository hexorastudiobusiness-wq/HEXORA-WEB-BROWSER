using System.Globalization;
using Hexora.Browser.Core.Localization;
using Hexora.Browser.Infrastructure.Settings;
using Microsoft.UI.Xaml;
using Windows.Globalization;

namespace Hexora.Browser.App;

public partial class App : Application
{
    private Window? _window;

    public App()
    {
        InitializeComponent();
    }

    protected override async void OnLaunched(LaunchActivatedEventArgs args)
    {
        var settingsRepository = BrowserSettingsRepository.CreateDefault();
        var settings = await settingsRepository.LoadAsync();
        var language = settings.LanguageTag is null
            ? SupportedLanguages.GetBestMatch(ApplicationLanguages.Languages)
            : SupportedLanguages.GetOrDefault(settings.LanguageTag);

        ApplicationLanguages.PrimaryLanguageOverride = language.Tag;
        ApplyCulture(language.Tag);

        _window = new MainWindow(settingsRepository, settings, language);
        _window.Activate();
    }

    internal static void ApplyCulture(string languageTag)
    {
        var culture = CultureInfo.GetCultureInfo(languageTag);
        CultureInfo.CurrentCulture = culture;
        CultureInfo.CurrentUICulture = culture;
    }
}
