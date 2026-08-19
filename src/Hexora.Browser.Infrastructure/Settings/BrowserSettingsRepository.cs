using System.Text.Json;
using Hexora.Browser.Core.Settings;

namespace Hexora.Browser.Infrastructure.Settings;

public sealed class BrowserSettingsRepository
{
    private readonly JsonSettingsStore _store;

    public BrowserSettingsRepository(string filePath)
    {
        _store = new JsonSettingsStore(filePath);
    }

    public static BrowserSettingsRepository CreateDefault()
    {
        var localData = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
        var filePath = Path.Combine(localData, "Hexora", "Browser", "settings.json");
        return new BrowserSettingsRepository(filePath);
    }

    public async Task<BrowserSettings> LoadAsync(CancellationToken cancellationToken = default)
    {
        try
        {
            return await _store.LoadAsync(BrowserSettings.Default, cancellationToken);
        }
        catch (IOException)
        {
            return BrowserSettings.Default;
        }
        catch (UnauthorizedAccessException)
        {
            return BrowserSettings.Default;
        }
        catch (JsonException)
        {
            return BrowserSettings.Default;
        }
    }

    public Task SaveAsync(BrowserSettings settings, CancellationToken cancellationToken = default) =>
        _store.SaveAsync(settings, cancellationToken);
}
