using Hexora.Browser.Core.Browsing;

namespace Hexora.Browser.Core.Tests.Browsing;

public sealed class BrowserSessionTests
{
    [Fact]
    public void ConstructorCreatesOneActiveNewTab()
    {
        var session = new BrowserSession();

        var tab = Assert.Single(session.Tabs);
        Assert.True(tab.IsActive);
        Assert.Null(tab.Source);
        Assert.Equal(tab, session.ActiveTab);
    }

    [Fact]
    public void OpenTabActivatesNewTabAndKeepsExistingState()
    {
        var session = new BrowserSession();
        var original = session.ActiveTab;
        var target = new Uri("https://example.com/");

        var opened = session.OpenTab(target);

        Assert.Equal(2, session.Tabs.Count);
        Assert.Equal(opened.Id, session.ActiveTab.Id);
        Assert.Equal(target, opened.Source);
        Assert.False(session.Tabs.Single(tab => tab.Id == original.Id).IsActive);
    }

    [Fact]
    public void CloseActiveTabActivatesNearestRemainingTab()
    {
        var session = new BrowserSession();
        var first = session.ActiveTab;
        var second = session.OpenTab(new Uri("https://example.com/"));
        var third = session.OpenTab(new Uri("https://example.org/"));
        session.Activate(second.Id);

        var closed = session.Close(second.Id);

        Assert.True(closed);
        Assert.Equal(third.Id, session.ActiveTab.Id);
        Assert.Contains(session.Tabs, tab => tab.Id == first.Id);
    }

    [Fact]
    public void CloseLastTabReplacesItWithFreshActiveTab()
    {
        var session = new BrowserSession();
        var closingId = session.ActiveTab.Id;

        session.Close(closingId);

        var replacement = Assert.Single(session.Tabs);
        Assert.NotEqual(closingId, replacement.Id);
        Assert.True(replacement.IsActive);
    }

    [Fact]
    public void UpdateNavigationUpdatesOnlyTargetTab()
    {
        var session = new BrowserSession();
        var target = session.ActiveTab;
        var source = new Uri("https://hexora.example/");

        var updated = session.UpdateNavigation(target.Id, source, "  HEXORA  ");

        Assert.True(updated);
        Assert.Equal(source, session.ActiveTab.Source);
        Assert.Equal("HEXORA", session.ActiveTab.Title);
    }

    [Fact]
    public void OperationsOnUnknownTabReturnFalse()
    {
        var session = new BrowserSession();
        var unknown = BrowserTabId.New();

        Assert.False(session.Activate(unknown));
        Assert.False(session.Close(unknown));
        Assert.False(session.SetPinned(unknown, true));
        Assert.False(session.UpdateNavigation(unknown, null, null));
    }
}
