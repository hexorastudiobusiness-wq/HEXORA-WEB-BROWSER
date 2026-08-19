namespace Hexora.Browser.Core.Navigation;

public sealed class NavigationInputParser : INavigationInputParser
{
    private static readonly char[] HostIndicators = ['.', ':'];
    private readonly string _searchUrlTemplate;

    public NavigationInputParser(string searchUrlTemplate = "https://duckduckgo.com/?q={0}")
    {
        if (string.IsNullOrWhiteSpace(searchUrlTemplate) || !searchUrlTemplate.Contains("{0}", StringComparison.Ordinal))
        {
            throw new ArgumentException("The search URL template must contain the {0} placeholder.", nameof(searchUrlTemplate));
        }

        _searchUrlTemplate = searchUrlTemplate;
    }

    public NavigationRequest Parse(string input)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(input);

        var normalized = input.Trim();
        if (TryCreateHttpUri(normalized, out var absoluteUri))
        {
            return new NavigationRequest(absoluteUri, NavigationKind.WebAddress, normalized);
        }

        if (LooksLikeHost(normalized) &&
            TryCreateHttpUri($"https://{normalized}", out var inferredUri))
        {
            return new NavigationRequest(inferredUri, NavigationKind.WebAddress, normalized);
        }

        var escapedQuery = Uri.EscapeDataString(normalized);
        var searchUri = new Uri(string.Format(System.Globalization.CultureInfo.InvariantCulture, _searchUrlTemplate, escapedQuery));
        return new NavigationRequest(searchUri, NavigationKind.Search, normalized);
    }

    private static bool LooksLikeHost(string value) =>
        !value.Any(char.IsWhiteSpace) &&
        !value.Contains("://", StringComparison.Ordinal) &&
        (value.Equals("localhost", StringComparison.OrdinalIgnoreCase) || value.IndexOfAny(HostIndicators) >= 0);

    private static bool TryCreateHttpUri(string value, out Uri uri)
    {
        if (Uri.TryCreate(value, UriKind.Absolute, out var candidate) &&
            (candidate.Scheme == Uri.UriSchemeHttps || candidate.Scheme == Uri.UriSchemeHttp) &&
            !string.IsNullOrWhiteSpace(candidate.Host))
        {
            uri = candidate;
            return true;
        }

        uri = null!;
        return false;
    }
}
