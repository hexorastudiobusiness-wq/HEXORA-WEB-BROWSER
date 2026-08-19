namespace Hexora.Browser.Core.Navigation;

public sealed record NavigationRequest(Uri Target, NavigationKind Kind, string OriginalInput);
