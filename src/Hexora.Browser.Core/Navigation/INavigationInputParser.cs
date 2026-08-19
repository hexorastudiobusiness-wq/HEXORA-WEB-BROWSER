namespace Hexora.Browser.Core.Navigation;

public interface INavigationInputParser
{
    NavigationRequest Parse(string input);
}
