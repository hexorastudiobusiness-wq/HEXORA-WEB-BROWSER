using System.Security;
using System.Text.Json;

namespace Hexora.Browser.Infrastructure.Diagnostics;

public sealed class RunStateTracker
{
    private readonly string _markerPath;
    private bool _runStarted;

    public RunStateTracker(string markerPath)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(markerPath);
        _markerPath = markerPath;
    }

    public static RunStateTracker CreateDefault()
    {
        var localData = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
        var markerPath = Path.Combine(localData, "Hexora", "Browser", "State", "running.json");
        return new RunStateTracker(markerPath);
    }

    public bool BeginRun()
    {
        var previousRunEndedUnexpectedly = File.Exists(_markerPath);
        var directory = Path.GetDirectoryName(_markerPath);

        try
        {
            if (!string.IsNullOrEmpty(directory))
            {
                Directory.CreateDirectory(directory);
            }

            var marker = new RunMarker(DateTimeOffset.UtcNow, Environment.ProcessId);
            var temporaryPath = $"{_markerPath}.{Guid.NewGuid():N}.tmp";
            try
            {
                File.WriteAllText(temporaryPath, JsonSerializer.Serialize(marker));
                File.Move(temporaryPath, _markerPath, true);
                _runStarted = true;
            }
            finally
            {
                File.Delete(temporaryPath);
            }
        }
        catch (IOException)
        {
            _runStarted = false;
        }
        catch (UnauthorizedAccessException)
        {
            _runStarted = false;
        }
        catch (SecurityException)
        {
            _runStarted = false;
        }

        return previousRunEndedUnexpectedly;
    }

    public void CompleteRun()
    {
        if (!_runStarted)
        {
            return;
        }

        try
        {
            File.Delete(_markerPath);
            _runStarted = false;
        }
        catch (IOException)
        {
            // A stale marker is handled as an unexpected exit on the next launch.
        }
        catch (UnauthorizedAccessException)
        {
            // A stale marker is handled as an unexpected exit on the next launch.
        }
        catch (SecurityException)
        {
            // A stale marker is handled as an unexpected exit on the next launch.
        }
    }

    private sealed record RunMarker(DateTimeOffset StartedAtUtc, int ProcessId);
}
