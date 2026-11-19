namespace Svalka.Core.Sync;

/// <summary>What the app remembers about the sync account between runs.</summary>
public sealed class SyncState
{
    public string? ServerUrl { get; set; }
    public string? Email { get; set; }
    public string? UserId { get; set; }
    public string? Token { get; set; }
    /// <summary>Server "updated" time of the newest record already pulled.</summary>
    public string Cursor { get; set; } = "";
    public long LastSyncAt { get; set; }
    /// <summary>Ids of local records changed since the last successful upload.</summary>
    public HashSet<string> Dirty { get; set; } = new();
    /// <summary>Ids of records deleted for good that other devices still need to learn about.</summary>
    public HashSet<string> Purged { get; set; } = new();
    /// <summary>Record id → server row id.</summary>
    public Dictionary<string, string> RecordIds { get; set; } = new();

    /// <summary>Server instance id seen last time; a new one means the server was restored from a backup.</summary>
    public string? Instance { get; set; }

    public bool SignedIn => !string.IsNullOrEmpty(Token) && !string.IsNullOrEmpty(ServerUrl);

    public void ResetAccountData()
    {
        Token = null;
        UserId = null;
        Cursor = "";
        Instance = null;
        LastSyncAt = 0;
        Dirty.Clear();
        Purged.Clear();
        RecordIds.Clear();
    }
}
