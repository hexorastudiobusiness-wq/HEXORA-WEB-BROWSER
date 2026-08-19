namespace Hexora.Browser.Core.Browsing;

public static class TabSleepPolicy
{
    public static bool CanSuspend(TabActivityState state) =>
        !state.IsActive &&
        state.IsInitialized &&
        !state.IsPlayingAudio &&
        !state.IsDownloading &&
        !state.HasSensitiveActivity &&
        !state.IsSecurityPromptOpen;
}

public readonly record struct TabActivityState(
    bool IsActive,
    bool IsInitialized,
    bool IsPlayingAudio,
    bool IsDownloading,
    bool HasSensitiveActivity,
    bool IsSecurityPromptOpen);
