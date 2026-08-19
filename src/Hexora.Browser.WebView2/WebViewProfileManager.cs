using System.Security;

namespace Hexora.Browser.WebView2;

public sealed class WebViewProfileManager
{
    private readonly string _profileRoot;
    private readonly string _privateRoot;

    public WebViewProfileManager(string profileRoot, string privateRoot)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(profileRoot);
        ArgumentException.ThrowIfNullOrWhiteSpace(privateRoot);
        _profileRoot = Path.GetFullPath(profileRoot);
        _privateRoot = Path.GetFullPath(privateRoot);
    }

    public static WebViewProfileManager CreateDefault()
    {
        var localData = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
        var applicationRoot = Path.Combine(localData, "Hexora", "Browser");
        return new WebViewProfileManager(
            Path.Combine(applicationRoot, "WebView2"),
            Path.Combine(applicationRoot, "Private"));
    }

    public WebViewProfileOptions GetDefaultProfile() =>
        new(_profileRoot, "Default", IsPrivate: false, DeleteDataOnClose: false);

    public WebViewProfileOptions CreatePrivateProfile() =>
        new(
            Path.Combine(_privateRoot, Guid.NewGuid().ToString("N")),
            "Private",
            IsPrivate: true,
            DeleteDataOnClose: true);

    public void PurgeAbandonedPrivateProfiles()
    {
        if (!Directory.Exists(_privateRoot))
        {
            return;
        }

        try
        {
            var cutoffUtc = DateTime.UtcNow.AddDays(-7);
            foreach (var directory in Directory.EnumerateDirectories(_privateRoot))
            {
                if (Directory.GetLastWriteTimeUtc(directory) < cutoffUtc)
                {
                    TryDeleteDirectory(directory);
                }
            }
        }
        catch (IOException)
        {
            // Cleanup is best effort and never blocks browser startup.
        }
        catch (UnauthorizedAccessException)
        {
            // Cleanup is best effort and never blocks browser startup.
        }
        catch (SecurityException)
        {
            // Cleanup is best effort and never blocks browser startup.
        }
    }

    public void DeleteProfileData(WebViewProfileOptions profile)
    {
        ArgumentNullException.ThrowIfNull(profile);
        if (!profile.DeleteDataOnClose || !IsUnderPrivateRoot(profile.UserDataFolder))
        {
            return;
        }

        TryDeleteDirectory(profile.UserDataFolder);
    }

    private bool IsUnderPrivateRoot(string candidatePath)
    {
        var relativePath = Path.GetRelativePath(_privateRoot, Path.GetFullPath(candidatePath));
        return relativePath.Length > 0 &&
               relativePath != "." &&
               !relativePath.StartsWith("..", StringComparison.Ordinal) &&
               !Path.IsPathRooted(relativePath);
    }

    private static void TryDeleteDirectory(string path)
    {
        try
        {
            if (Directory.Exists(path))
            {
                Directory.Delete(path, recursive: true);
            }
        }
        catch (IOException)
        {
            // Locked profile data is retried on the next application launch.
        }
        catch (UnauthorizedAccessException)
        {
            // Locked profile data is retried on the next application launch.
        }
        catch (SecurityException)
        {
            // Locked profile data is retried on the next application launch.
        }
    }
}
