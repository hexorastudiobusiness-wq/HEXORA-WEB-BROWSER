using Hexora.Browser.WebView2;

namespace Hexora.Browser.Infrastructure.Tests.WebView;

public sealed class WebViewProfileManagerTests : IDisposable
{
    private readonly string _temporaryDirectory = Path.Combine(
        Path.GetTempPath(),
        $"hexora-tests-{Guid.NewGuid():N}");

    [Fact]
    public void DefaultProfileIsStableAndPersistent()
    {
        var manager = CreateManager();

        var first = manager.GetDefaultProfile();
        var second = manager.GetDefaultProfile();

        Assert.Equal(first, second);
        Assert.False(first.IsPrivate);
        Assert.False(first.DeleteDataOnClose);
        Assert.Equal("Default", first.ProfileName);
    }

    [Fact]
    public void PrivateProfilesAreUniqueAndDeletedOnClose()
    {
        var manager = CreateManager();
        var first = manager.CreatePrivateProfile();
        var second = manager.CreatePrivateProfile();
        Directory.CreateDirectory(first.UserDataFolder);
        File.WriteAllText(Path.Combine(first.UserDataFolder, "temporary-data"), "test");

        manager.DeleteProfileData(first);

        Assert.True(first.IsPrivate);
        Assert.True(first.DeleteDataOnClose);
        Assert.NotEqual(first.UserDataFolder, second.UserDataFolder);
        Assert.False(Directory.Exists(first.UserDataFolder));
    }

    [Fact]
    public void DeleteProfileDataNeverDeletesOutsidePrivateRoot()
    {
        var manager = CreateManager();
        var protectedDirectory = Path.Combine(_temporaryDirectory, "must-remain");
        Directory.CreateDirectory(protectedDirectory);
        var untrusted = new WebViewProfileOptions(
            protectedDirectory,
            "Private",
            IsPrivate: true,
            DeleteDataOnClose: true);

        manager.DeleteProfileData(untrusted);

        Assert.True(Directory.Exists(protectedDirectory));
    }

    public void Dispose()
    {
        if (Directory.Exists(_temporaryDirectory))
        {
            Directory.Delete(_temporaryDirectory, recursive: true);
        }

        GC.SuppressFinalize(this);
    }

    private WebViewProfileManager CreateManager() =>
        new(
            Path.Combine(_temporaryDirectory, "default"),
            Path.Combine(_temporaryDirectory, "private"));
}
