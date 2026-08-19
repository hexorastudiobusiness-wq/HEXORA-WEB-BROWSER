using Hexora.Browser.Core.Browsing;

namespace Hexora.Browser.Core.Tests.Browsing;

public sealed class TabSleepPolicyTests
{
    [Fact]
    public void CanSuspendReturnsTrueForIdleInitializedBackgroundTab()
    {
        var state = CreateEligibleState();

        Assert.True(TabSleepPolicy.CanSuspend(state));
    }

    [Theory]
    [InlineData(true, false, false, false, false, false)]
    [InlineData(false, true, false, false, false, false)]
    [InlineData(false, false, true, false, false, false)]
    [InlineData(false, false, false, true, false, false)]
    [InlineData(false, false, false, false, true, false)]
    [InlineData(false, false, false, false, false, true)]
    public void CanSuspendReturnsFalseForProtectedActivity(
        bool isActive,
        bool isUninitialized,
        bool isPlayingAudio,
        bool isDownloading,
        bool hasSensitiveActivity,
        bool isSecurityPromptOpen)
    {
        var state = CreateEligibleState() with
        {
            IsActive = isActive,
            IsInitialized = !isUninitialized,
            IsPlayingAudio = isPlayingAudio,
            IsDownloading = isDownloading,
            HasSensitiveActivity = hasSensitiveActivity,
            IsSecurityPromptOpen = isSecurityPromptOpen,
        };

        Assert.False(TabSleepPolicy.CanSuspend(state));
    }

    private static TabActivityState CreateEligibleState() =>
        new(
            IsActive: false,
            IsInitialized: true,
            IsPlayingAudio: false,
            IsDownloading: false,
            HasSensitiveActivity: false,
            IsSecurityPromptOpen: false);
}
