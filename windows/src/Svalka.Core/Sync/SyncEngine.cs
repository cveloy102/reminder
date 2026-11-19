using System.Net;

namespace Svalka.Core.Sync;

/// <summary>Bridge to the app: gives the sync engine the records and lets it change them on the app's own thread.</summary>
public interface ISyncHost
{
    Task<List<ReminderTask>> SnapshotAsync();
    /// <summary>Replaces the records with the result of <paramref name="change"/>, saves them, refreshes the screen.</summary>
    Task MutateAsync(Func<List<ReminderTask>, List<ReminderTask>> change);
    void SaveState(SyncState state);
}

public enum SyncStatus { Off, Idle, Syncing, Error }

/// <summary>
/// Keeps local records and the server in step: pull what changed elsewhere, merge (newest edit wins),
/// push local edits. Runs after every local change (with a short pause), on realtime events, and every few minutes.
/// </summary>
public sealed class SyncEngine : IDisposable
{
    private readonly ISyncHost _host;
    private readonly SyncState _state;
    private readonly SemaphoreSlim _gate = new(1, 1);
    private readonly object _lock = new();
    private readonly Timer _debounce;
    private readonly Timer _periodic;
    private SyncClient? _client;
    private RealtimeListener? _listener;
    private bool _again;

    public SyncEngine(ISyncHost host, SyncState state)
    {
        _host = host;
        _state = state;
        _debounce = new Timer(_ => _ = SyncNowAsync(), null, Timeout.Infinite, Timeout.Infinite);
        _periodic = new Timer(_ => { if (SignedIn) _ = SyncNowAsync(); }, null, Timeout.Infinite, Timeout.Infinite);
    }

    public event Action? StatusChanged;

    public SyncState State => _state;
    public bool SignedIn => _state.SignedIn;
    public SyncStatus Status { get; private set; } = SyncStatus.Off;
    public string? LastError { get; private set; }
    public bool LiveConnected { get; private set; }

    public static async Task<ServerInfo> CheckServerAsync(string url, CancellationToken ct = default)
    {
        using var client = new SyncClient(url);
        return await client.GetInfoAsync(ct);
    }

    /// <summary>Signs in (or creates the account first). Local records are uploaded and merged with the account.</summary>
    public async Task SignInAsync(string url, string email, string password, bool register)
    {
        var client = new SyncClient(url);
        try
        {
            await client.GetInfoAsync();
            if (register) await client.RegisterAsync(email, password);
            var auth = await client.LoginAsync(email, password);
            Stop();
            lock (_lock)
            {
                var sameAccount = _state.UserId == auth.UserId && _state.ServerUrl == client.BaseUrl;
                if (!sameAccount) _state.ResetAccountData();
                _state.ServerUrl = client.BaseUrl;
                _state.Email = auth.Email;
                _state.UserId = auth.UserId;
                _state.Token = auth.Token;
            }
            // Everything on this device takes part in the first merge.
            var local = await _host.SnapshotAsync();
            lock (_lock) foreach (var t in local) _state.Dirty.Add(t.Id);
            _host.SaveState(_state);
            _client = client;
            StartBackground();
        }
        catch
        {
            client.Dispose();
            throw;
        }
        await SyncNowAsync();
    }

    /// <summary>Continues a saved session after the app starts.</summary>
    public void Resume()
    {
        if (!SignedIn) return;
        _client = new SyncClient(_state.ServerUrl!, _state.Token);
        StartBackground();
        _ = Task.Run(async () =>
        {
            try
            {
                var auth = await _client.RefreshAsync();
                lock (_lock) _state.Token = auth.Token;
                _host.SaveState(_state);
            }
            catch (Exception) { /* offline or expired: SyncNowAsync reports it */ }
            await SyncNowAsync();
        });
    }

    /// <summary>After sleep or a network change: reopen the realtime stream and sync right away.</summary>
    public void Reconnect()
    {
        if (!SignedIn || _client == null) return;
        _listener?.Dispose();
        _listener = null;
        LiveConnected = false;
        StartBackground();
        _debounce.Change(1000, Timeout.Infinite);
    }

    public void SignOut()
    {
        Stop();
        lock (_lock) _state.ResetAccountData();
        _host.SaveState(_state);
        SetStatus(SyncStatus.Off, null);
    }

    private void StartBackground()
    {
        _listener = new RealtimeListener(_client!);
        _listener.Changed += () => _debounce.Change(400, Timeout.Infinite);
        _listener.ConnectionChanged += live => { LiveConnected = live; StatusChanged?.Invoke(); };
        _listener.Start();
        _periodic.Change(TimeSpan.FromMinutes(1), TimeSpan.FromMinutes(1));
        SetStatus(SyncStatus.Idle, null);
    }

    private void Stop()
    {
        _periodic.Change(Timeout.Infinite, Timeout.Infinite);
        _debounce.Change(Timeout.Infinite, Timeout.Infinite);
        _listener?.Dispose();
        _listener = null;
        _client?.Dispose();
        _client = null;
        LiveConnected = false;
    }

    /// <summary>Call after a local edit of a record.</summary>
    public void MarkDirty(string id)
    {
        lock (_lock)
        {
            _state.Dirty.Add(id);
            _state.Purged.Remove(id);
        }
        _host.SaveState(_state);
        if (SignedIn) _debounce.Change(1500, Timeout.Infinite);
    }

    /// <summary>Call after a record was deleted for good on this device.</summary>
    public void MarkPurged(string id)
    {
        lock (_lock)
        {
            _state.Dirty.Remove(id);
            _state.Purged.Add(id);
        }
        _host.SaveState(_state);
        if (SignedIn) _debounce.Change(1500, Timeout.Infinite);
    }

    public async Task SyncNowAsync()
    {
        var client = _client;
        if (client == null || !SignedIn) return;
        if (!await _gate.WaitAsync(0))
        {
            _again = true; // a sync is running: run once more after it
            return;
        }
        try
        {
            do
            {
                _again = false;
                SetStatus(SyncStatus.Syncing, null);
                await RunOnceAsync(client);
                SetStatus(SyncStatus.Idle, null);
            } while (_again);
        }
        catch (SyncException e)
        {
            SetStatus(SyncStatus.Error, e.Status == HttpStatusCode.Unauthorized ? "Нужно войти снова" : e.Message);
        }
        catch (Exception e)
        {
            SetStatus(SyncStatus.Error, e.Message);
        }
        finally
        {
            _gate.Release();
        }
    }

    private async Task RunOnceAsync(SyncClient client)
    {
        // 0. Restored from a backup since the last visit? The server may miss edits newer than that backup,
        //    so compare everything once: pull all records again and upload every local one.
        var info = await client.GetInfoAsync();
        if (info.Instance.Length > 0 && info.Instance != _state.Instance)
        {
            var firstVisit = _state.Instance == null;
            if (!firstVisit)
            {
                var all = await _host.SnapshotAsync();
                lock (_lock)
                {
                    _state.Cursor = "";
                    foreach (var t in all) _state.Dirty.Add(t.Id);
                }
            }
            lock (_lock) _state.Instance = info.Instance;
            _host.SaveState(_state);
        }

        // 1. Pull what changed on the server since last time and merge it in.
        var pulled = await client.PullAsync(_state.Cursor);
        if (pulled.Count > 0)
        {
            await _host.MutateAsync(local =>
            {
                lock (_lock) return SyncMerge.ApplyRemote(local, pulled, _state.Dirty, _state.Purged);
            });
            lock (_lock)
            {
                foreach (var r in pulled)
                {
                    if (r.Purged) _state.RecordIds.Remove(r.Task.Id);
                    else _state.RecordIds[r.Task.Id] = r.RecordId;
                }
                var newest = pulled.Max(r => r.Updated) ?? "";
                if (string.CompareOrdinal(newest, _state.Cursor) > 0) _state.Cursor = newest;
            }
        }

        // 2. Push local edits.
        string[] dirty, purged;
        lock (_lock)
        {
            dirty = _state.Dirty.ToArray();
            purged = _state.Purged.ToArray();
        }
        string? rejected = null;
        if (dirty.Length > 0)
        {
            var local = (await _host.SnapshotAsync()).ToDictionary(t => t.Id);
            foreach (var id in dirty)
            {
                lock (_lock) _state.Dirty.Remove(id);
                if (!local.TryGetValue(id, out var task)) continue;
                try { await UploadAsync(client, task, purged: false); }
                catch (SyncException e) when (e.Status == HttpStatusCode.BadRequest)
                {
                    // The server refused this one record (for example, text over its size limit). Keep it for a later
                    // try and go on with the rest: one bad record must not stop every other change from syncing.
                    lock (_lock) _state.Dirty.Add(id);
                    rejected ??= RejectedMessage(task, e);
                }
                catch
                {
                    lock (_lock) _state.Dirty.Add(id);
                    throw;
                }
            }
        }
        foreach (var id in purged)
        {
            var tombstone = new ReminderTask(id, "", 0, UpdatedAt: Clock.NowMs());
            await UploadAsync(client, tombstone, purged: true);
            lock (_lock)
            {
                _state.Purged.Remove(id);
                _state.RecordIds.Remove(id);
            }
        }
        lock (_lock) _state.LastSyncAt = Clock.NowMs();
        _host.SaveState(_state);
        if (rejected != null) throw new SyncException(rejected, HttpStatusCode.BadRequest);
    }

    internal static string RejectedMessage(ReminderTask task, SyncException e)
    {
        var title = task.Title.Length > 40 ? task.Title[..40] + "…" : task.Title;
        return $"Сервер не принял запись «{title}»: {e.Message}";
    }

    private async Task UploadAsync(SyncClient client, ReminderTask task, bool purged)
    {
        var body = RemoteItem.ToJson(task, _state.UserId!, purged);
        string? recordId;
        lock (_lock) _state.RecordIds.TryGetValue(task.Id, out recordId);
        if (recordId != null)
        {
            try
            {
                await client.UpdateAsync(recordId, body);
                return;
            }
            catch (SyncException e) when (e.Status == HttpStatusCode.NotFound) { /* removed on the server: create again */ }
            catch (SyncException e) when (e.Status == HttpStatusCode.Conflict)
            {
                if (purged) { await ForcePurgeAsync(client, task); return; }
                await TakeServerVersionAsync(client, task.Id);
                return;
            }
        }
        RemoteItem saved;
        try { saved = await client.CreateAsync(body); }
        catch (SyncException e) when (e.Status == HttpStatusCode.BadRequest)
        {
            // Already on the server (created by another device): update that row instead.
            var existing = await client.FindAsync(task.Id) ?? throw e;
            try { saved = await client.UpdateAsync(existing.RecordId, body); }
            catch (SyncException conflict) when (conflict.Status == HttpStatusCode.Conflict)
            {
                if (purged) { await ForcePurgeAsync(client, task); return; }
                await TakeServerVersionAsync(client, task.Id);
                return;
            }
        }
        lock (_lock) _state.RecordIds[task.Id] = saved.RecordId;
    }

    /// <summary>
    /// A delete for good must win even over a newer edit (or a device whose clock runs ahead): send it again
    /// with an edit time just after the server's, so the server accepts it and every device removes the record.
    /// </summary>
    private async Task ForcePurgeAsync(SyncClient client, ReminderTask tombstone)
    {
        var remote = await client.FindAsync(tombstone.Id);
        if (remote == null || remote.Purged) return;
        var stamp = Math.Max(remote.Task.EditedAt + 1, Clock.NowMs());
        await client.UpdateAsync(remote.RecordId, RemoteItem.ToJson(tombstone with { UpdatedAt = stamp }, _state.UserId!, purged: true));
    }

    /// <summary>The server refused an older edit: bring its newer version here.</summary>
    private async Task TakeServerVersionAsync(SyncClient client, string itemId)
    {
        var remote = await client.FindAsync(itemId);
        if (remote == null) return;
        await _host.MutateAsync(local =>
        {
            lock (_lock) return SyncMerge.ApplyRemote(local, new[] { remote }, _state.Dirty, _state.Purged);
        });
        lock (_lock) _state.RecordIds[itemId] = remote.RecordId;
    }

    private void SetStatus(SyncStatus status, string? error)
    {
        Status = status;
        LastError = error;
        StatusChanged?.Invoke();
    }

    public void Dispose()
    {
        Stop();
        _debounce.Dispose();
        _periodic.Dispose();
    }
}
