using Hexora.Browser.Infrastructure.Diagnostics;

namespace Hexora.Browser.Infrastructure.Tests.Diagnostics;

public sealed class LocalDiagnosticLoggerTests : IDisposable
{
    private readonly string _temporaryDirectory = Path.Combine(
        Path.GetTempPath(),
        $"hexora-tests-{Guid.NewGuid():N}");

    [Fact]
    public void RecordExcludesExceptionMessageAndSensitivePageData()
    {
        var path = Path.Combine(_temporaryDirectory, "diagnostics.jsonl");
        var logger = new LocalDiagnosticLogger(path);
        var exception = new InvalidOperationException(
            "https://private.example/account?token=secret-password");

        logger.Record("webview.process_failed", DiagnosticLevel.Error, exception);

        var contents = File.ReadAllText(path);
        Assert.Contains("webview.process_failed", contents, StringComparison.Ordinal);
        Assert.Contains("InvalidOperationException", contents, StringComparison.Ordinal);
        Assert.DoesNotContain("private.example", contents, StringComparison.Ordinal);
        Assert.DoesNotContain("secret-password", contents, StringComparison.Ordinal);
    }

    [Theory]
    [InlineData("contains a space")]
    [InlineData("contains/slash")]
    [InlineData("")]
    public void RecordRejectsUnsafeEventNames(string eventName)
    {
        var path = Path.Combine(_temporaryDirectory, "diagnostics.jsonl");
        var logger = new LocalDiagnosticLogger(path);

        Assert.Throws<ArgumentException>(() => logger.Record(eventName));
        Assert.False(File.Exists(path));
    }

    public void Dispose()
    {
        if (Directory.Exists(_temporaryDirectory))
        {
            Directory.Delete(_temporaryDirectory, recursive: true);
        }

        GC.SuppressFinalize(this);
    }
}
