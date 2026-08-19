using Hexora.Browser.Infrastructure.Diagnostics;

namespace Hexora.Browser.Infrastructure.Tests.Diagnostics;

public sealed class RunStateTrackerTests : IDisposable
{
    private readonly string _temporaryDirectory = Path.Combine(
        Path.GetTempPath(),
        $"hexora-tests-{Guid.NewGuid():N}");

    [Fact]
    public void BeginRunDetectsStaleMarkerAndCompleteRunClearsIt()
    {
        var markerPath = Path.Combine(_temporaryDirectory, "running.json");
        var firstRun = new RunStateTracker(markerPath);
        var recoveredRun = new RunStateTracker(markerPath);

        Assert.False(firstRun.BeginRun());
        Assert.True(recoveredRun.BeginRun());

        recoveredRun.CompleteRun();

        var cleanRun = new RunStateTracker(markerPath);
        Assert.False(cleanRun.BeginRun());
        cleanRun.CompleteRun();
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
