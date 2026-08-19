using System.Security;
using System.Text.Json;

namespace Hexora.Browser.Infrastructure.Diagnostics;

public sealed class LocalDiagnosticLogger
{
    private const long MaximumFileBytes = 1_048_576;
    private const int RetainedArchiveCount = 4;
    private static readonly JsonSerializerOptions SerializerOptions = new(JsonSerializerDefaults.Web);

    private readonly object _gate = new();
    private readonly string _filePath;
    private readonly string _sessionId = Guid.NewGuid().ToString("N");

    public LocalDiagnosticLogger(string filePath)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(filePath);
        _filePath = filePath;
    }

    public static LocalDiagnosticLogger CreateDefault()
    {
        var localData = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
        var filePath = Path.Combine(localData, "Hexora", "Browser", "Logs", "diagnostics.jsonl");
        return new LocalDiagnosticLogger(filePath);
    }

    public void Record(
        string eventName,
        DiagnosticLevel level = DiagnosticLevel.Information,
        Exception? exception = null,
        long? durationMilliseconds = null,
        long? workingSetMegabytes = null)
    {
        ValidateEventName(eventName);

        var entry = new DiagnosticEntry(
            DateTimeOffset.UtcNow,
            level,
            eventName,
            _sessionId,
            exception?.GetType().FullName,
            NormalizeMetric(durationMilliseconds),
            NormalizeMetric(workingSetMegabytes));

        lock (_gate)
        {
            try
            {
                var directory = Path.GetDirectoryName(_filePath);
                if (!string.IsNullOrEmpty(directory))
                {
                    Directory.CreateDirectory(directory);
                }

                RotateIfNeeded();
                File.AppendAllText(_filePath, JsonSerializer.Serialize(entry, SerializerOptions) + Environment.NewLine);
            }
            catch (IOException)
            {
                // Diagnostics must never prevent the browser from running.
            }
            catch (UnauthorizedAccessException)
            {
                // Diagnostics must never prevent the browser from running.
            }
            catch (SecurityException)
            {
                // Diagnostics must never prevent the browser from running.
            }
        }
    }

    private static long? NormalizeMetric(long? value) => value is >= 0 ? value : null;

    private static void ValidateEventName(string eventName)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(eventName);
        if (eventName.Length > 64 || eventName.Any(character =>
                !(char.IsAsciiLetterOrDigit(character) || character is '.' or '-' or '_')))
        {
            throw new ArgumentException("Diagnostic event names must use only ASCII letters, digits, dots, dashes, or underscores.", nameof(eventName));
        }
    }

    private void RotateIfNeeded()
    {
        if (!File.Exists(_filePath) || new FileInfo(_filePath).Length < MaximumFileBytes)
        {
            return;
        }

        File.Delete(GetArchivePath(RetainedArchiveCount));
        for (var index = RetainedArchiveCount - 1; index >= 1; index--)
        {
            var source = GetArchivePath(index);
            if (File.Exists(source))
            {
                File.Move(source, GetArchivePath(index + 1));
            }
        }

        File.Move(_filePath, GetArchivePath(1));
    }

    private string GetArchivePath(int index) => $"{_filePath}.{index}";

    private sealed record DiagnosticEntry(
        DateTimeOffset TimestampUtc,
        DiagnosticLevel Level,
        string EventName,
        string SessionId,
        string? ExceptionType,
        long? DurationMilliseconds,
        long? WorkingSetMegabytes);
}
