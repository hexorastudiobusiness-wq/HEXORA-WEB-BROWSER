using Hexora.Browser.WebView2;
using Microsoft.Web.WebView2.Core;
using WebViewControl = Microsoft.UI.Xaml.Controls.WebView2;

namespace Hexora.Browser.App.WebView;

public sealed class WebViewEnvironmentProvider
{
    private readonly Lazy<Task<CoreWebView2Environment>> _environment;

    public WebViewEnvironmentProvider(WebViewProfileOptions profile)
    {
        Profile = profile ?? throw new ArgumentNullException(nameof(profile));
        _environment = new Lazy<Task<CoreWebView2Environment>>(
            () => CoreWebView2Environment.CreateWithOptionsAsync(
                null,
                profile.UserDataFolder,
                new CoreWebView2EnvironmentOptions()));
    }

    public WebViewProfileOptions Profile { get; }

    public async Task InitializeAsync(WebViewControl view)
    {
        ArgumentNullException.ThrowIfNull(view);

        var environment = await _environment.Value;
        var controllerOptions = environment.CreateCoreWebView2ControllerOptions();
        controllerOptions.ProfileName = Profile.ProfileName;
        controllerOptions.IsInPrivateModeEnabled = Profile.IsPrivate;
        await view.EnsureCoreWebView2Async(environment, controllerOptions);
    }
}
