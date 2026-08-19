using Hexora.Browser.Core.Navigation;

namespace Hexora.Browser.Core.Tests.Navigation;

public sealed class NavigationInputParserTests
{
    private readonly NavigationInputParser _sut = new("https://search.example/?q={0}");

    [Theory]
    [InlineData("https://example.com/path", "https://example.com/path")]
    [InlineData("http://localhost:8080", "http://localhost:8080/")]
    public void Parse_AbsoluteWebAddress_ReturnsAddress(string input, string expected)
    {
        var result = _sut.Parse(input);

        Assert.Equal(NavigationKind.WebAddress, result.Kind);
        Assert.Equal(expected, result.Target.AbsoluteUri);
    }

    [Theory]
    [InlineData("example.com", "https://example.com/")]
    [InlineData("localhost:5000", "https://localhost:5000/")]
    public void Parse_HostWithoutScheme_AddsHttps(string input, string expected)
    {
        var result = _sut.Parse(input);

        Assert.Equal(NavigationKind.WebAddress, result.Kind);
        Assert.Equal(expected, result.Target.AbsoluteUri);
    }

    [Theory]
    [InlineData("مرورگر سبک")]
    [InlineData("modern browser")]
    [InlineData("javascript:alert(1)")]
    [InlineData("file:///C:/secret.txt")]
    public void Parse_SearchOrUnsafeScheme_ReturnsEncodedSearch(string input)
    {
        var result = _sut.Parse(input);

        Assert.Equal(NavigationKind.Search, result.Kind);
        Assert.StartsWith("https://search.example/?q=", result.Target.AbsoluteUri, StringComparison.Ordinal);
        Assert.DoesNotContain(" ", result.Target.AbsoluteUri, StringComparison.Ordinal);
    }

    [Fact]
    public void Parse_Whitespace_Throws()
    {
        Assert.Throws<ArgumentException>(() => _sut.Parse("   "));
    }
}
