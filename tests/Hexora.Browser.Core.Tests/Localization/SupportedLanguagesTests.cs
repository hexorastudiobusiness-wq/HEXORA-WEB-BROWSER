using Hexora.Browser.Core.Localization;

namespace Hexora.Browser.Core.Tests.Localization;

public sealed class SupportedLanguagesTests
{
    [Fact]
    public void AllContainsInitialProductLanguages()
    {
        Assert.Collection(
            SupportedLanguages.All,
            language => Assert.Equal("en-US", language.Tag),
            language => Assert.Equal("fa-IR", language.Tag),
            language => Assert.Equal("ar-SA", language.Tag));
    }

    [Theory]
    [InlineData("fa-IR")]
    [InlineData("ar-SA")]
    public void GetOrDefaultRtlLanguageIsRightToLeft(string tag)
    {
        Assert.True(SupportedLanguages.GetOrDefault(tag).IsRightToLeft);
    }

    [Fact]
    public void GetOrDefaultUnknownLanguageFallsBackToEnglish()
    {
        Assert.Equal(SupportedLanguages.English, SupportedLanguages.GetOrDefault("de-DE"));
    }

    [Theory]
    [InlineData("fa", "fa-IR")]
    [InlineData("fa-AF", "fa-IR")]
    [InlineData("ar-EG", "ar-SA")]
    [InlineData("en-GB", "en-US")]
    public void FindMatchesSupportedLanguageFamily(string requestedTag, string expectedTag)
    {
        Assert.Equal(expectedTag, SupportedLanguages.Find(requestedTag)?.Tag);
    }

    [Fact]
    public void GetBestMatchUsesPreferenceOrderAndFallsBackToEnglish()
    {
        Assert.Equal(
            SupportedLanguages.Arabic,
            SupportedLanguages.GetBestMatch(["de-DE", "ar-EG", "fa-IR"]));
        Assert.Equal(
            SupportedLanguages.English,
            SupportedLanguages.GetBestMatch(["de-DE", "nl-NL"]));
    }
}
