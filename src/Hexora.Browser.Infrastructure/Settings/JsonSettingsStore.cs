using System.Text.Json;

namespace Hexora.Browser.Infrastructure.Settings;

public sealed class JsonSettingsStore
{
    private static readonly JsonSerializerOptions SerializerOptions = new(JsonSerializerDefaults.Web)
    {
        WriteIndented = true,
    };

    private readonly string _filePath;

    public JsonSettingsStore(string filePath)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(filePath);
        _filePath = filePath;
    }

    public async Task<T> LoadAsync<T>(T fallback, CancellationToken cancellationToken = default)
    {
        if (!File.Exists(_filePath))
        {
            return fallback;
        }

        await using var stream = File.OpenRead(_filePath);
        return await JsonSerializer.DeserializeAsync<T>(stream, SerializerOptions, cancellationToken) ?? fallback;
    }

    public async Task SaveAsync<T>(T value, CancellationToken cancellationToken = default)
    {
        var directory = Path.GetDirectoryName(_filePath);
        if (!string.IsNullOrEmpty(directory))
        {
            Directory.CreateDirectory(directory);
        }

        var temporaryPath = $"{_filePath}.{Guid.NewGuid():N}.tmp";
        try
        {
            await using (var stream = new FileStream(temporaryPath, FileMode.CreateNew, FileAccess.Write, FileShare.None))
            {
                await JsonSerializer.SerializeAsync(stream, value, SerializerOptions, cancellationToken);
                await stream.FlushAsync(cancellationToken);
            }

            File.Move(temporaryPath, _filePath, true);
        }
        finally
        {
            File.Delete(temporaryPath);
        }
    }
}
