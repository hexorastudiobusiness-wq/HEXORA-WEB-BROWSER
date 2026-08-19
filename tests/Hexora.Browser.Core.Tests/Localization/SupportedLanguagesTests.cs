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
}
