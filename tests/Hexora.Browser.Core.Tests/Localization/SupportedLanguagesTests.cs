using Hexora.Browser.Core.Localization;

namespace Hexora.Browser.Core.Tests.Localization;

public sealed class SupportedLanguagesTests
{
    [Fact]
    public void All_ContainsInitialProductLanguages()
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
    public void GetOrDefault_RtlLanguage_IsRightToLeft(string tag)
    {
        Assert.True(SupportedLanguages.GetOrDefault(tag).IsRightToLeft);
    }

    [Fact]
    public void GetOrDefault_UnknownLanguage_FallsBackToEnglish()
    {
        Assert.Equal(SupportedLanguages.English, SupportedLanguages.GetOrDefault("de-DE"));
    }
}
