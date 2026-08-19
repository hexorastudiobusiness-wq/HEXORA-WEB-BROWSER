using System.Collections.ObjectModel;

namespace Hexora.Browser.Core.Browsing;

public sealed class BrowserSession
{
    private readonly List<BrowserTabState> _tabs = [];
    private ReadOnlyCollection<BrowserTabState>? _readOnlyTabs;

    public BrowserSession()
    {
        _tabs.Add(BrowserTabState.New(isActive: true));
    }

    public IReadOnlyList<BrowserTabState> Tabs => _readOnlyTabs ??= _tabs.AsReadOnly();

    public BrowserTabState ActiveTab => _tabs.Single(tab => tab.IsActive);

    public BrowserTabState OpenTab(Uri? source = null, bool activate = true)
    {
        if (activate)
        {
            DeactivateAll();
        }

        var tab = BrowserTabState.New(isActive: activate) with { Source = source };
        _tabs.Add(tab);
        return tab;
    }

    public bool Activate(BrowserTabId id)
    {
        var targetIndex = FindIndex(id);
        if (targetIndex < 0)
        {
            return false;
        }

        for (var index = 0; index < _tabs.Count; index++)
        {
            _tabs[index] = _tabs[index] with { IsActive = index == targetIndex };
        }

        return true;
    }

    public bool Close(BrowserTabId id)
    {
        var closingIndex = FindIndex(id);
        if (closingIndex < 0)
        {
            return false;
        }

        var wasActive = _tabs[closingIndex].IsActive;
        _tabs.RemoveAt(closingIndex);

        if (_tabs.Count == 0)
        {
            _tabs.Add(BrowserTabState.New(isActive: true));
            return true;
        }

        if (wasActive)
        {
            var nextActiveIndex = Math.Min(closingIndex, _tabs.Count - 1);
            _tabs[nextActiveIndex] = _tabs[nextActiveIndex] with { IsActive = true };
        }

        return true;
    }

    public bool UpdateNavigation(BrowserTabId id, Uri? source, string? title)
    {
        var index = FindIndex(id);
        if (index < 0)
        {
            return false;
        }

        _tabs[index] = _tabs[index] with
        {
            Source = source,
            Title = title?.Trim() ?? string.Empty,
        };
        return true;
    }

    public bool SetPinned(BrowserTabId id, bool isPinned)
    {
        var index = FindIndex(id);
        if (index < 0)
        {
            return false;
        }

        _tabs[index] = _tabs[index] with { IsPinned = isPinned };
        return true;
    }

    private int FindIndex(BrowserTabId id) => _tabs.FindIndex(tab => tab.Id == id);

    private void DeactivateAll()
    {
        for (var index = 0; index < _tabs.Count; index++)
        {
            _tabs[index] = _tabs[index] with { IsActive = false };
        }
    }
}
