using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Threading.Tasks;
using System.Windows.Threading;
using Svalka.Core;
using Svalka.Core.Sync;
using Svalka.Desktop.ViewModels;

namespace Svalka.Desktop.Services;

/// <summary>
/// Connects the window's records with the sync engine. The account state lives in %APPDATA%\Svalka\sync.json;
/// the login token in it is encrypted for the current Windows user (DPAPI).
/// </summary>
public sealed class SyncService : ISyncHost, IDisposable
{
    private static readonly string StatePath = Path.Combine(AppPaths.Root, "sync.json");
    private static readonly JsonSerializerOptions Options = new() { WriteIndented = true };
    private readonly MainViewModel _vm;
    private readonly Dispatcher _ui;
    private readonly object _saveLock = new();

    public SyncService(MainViewModel vm, Dispatcher ui)
    {
        _vm = vm;
        _ui = ui;
        Engine = new SyncEngine(this, LoadState());
        vm.RecordChanged += Engine.MarkDirty;
        vm.RecordPurged += Engine.MarkPurged;
        // After sleep or a network switch the old realtime connection is usually dead: open a new one at once.
        Microsoft.Win32.SystemEvents.PowerModeChanged += OnPowerModeChanged;
        System.Net.NetworkInformation.NetworkChange.NetworkAvailabilityChanged += OnNetworkChanged;
    }

    private void OnPowerModeChanged(object? sender, Microsoft.Win32.PowerModeChangedEventArgs e)
    {
        if (e.Mode == Microsoft.Win32.PowerModes.Resume) Engine.Reconnect();
    }

    private void OnNetworkChanged(object? sender, System.Net.NetworkInformation.NetworkAvailabilityEventArgs e)
    {
        if (e.IsAvailable) Engine.Reconnect();
    }

    public SyncEngine Engine { get; }

    public void Start() => Engine.Resume();

    public Task<List<ReminderTask>> SnapshotAsync() => _ui.InvokeAsync(() => _vm.Tasks.ToList()).Task;

    public Task MutateAsync(Func<List<ReminderTask>, List<ReminderTask>> change) =>
        _ui.InvokeAsync(() => _vm.ApplyFromSync(change)).Task;

    public void SaveState(SyncState state)
    {
        lock (_saveLock)
        {
            try
            {
                Directory.CreateDirectory(AppPaths.Root);
                var stored = new StoredState
                {
                    ServerUrl = state.ServerUrl, Email = state.Email, UserId = state.UserId,
                    Token = state.Token == null ? null : Protect(state.Token),
                    Cursor = state.Cursor, LastSyncAt = state.LastSyncAt,
                    Dirty = state.Dirty.ToList(), Purged = state.Purged.ToList(), RecordIds = new(state.RecordIds)
                };
                AtomicFile.WriteAllText(StatePath, JsonSerializer.Serialize(stored, Options));
            }
            catch (Exception) { /* will be saved on the next change */ }
        }
    }

    private static SyncState LoadState()
    {
        try
        {
            if (!File.Exists(StatePath)) return new SyncState();
            var s = JsonSerializer.Deserialize<StoredState>(File.ReadAllText(StatePath)) ?? new StoredState();
            return new SyncState
            {
                ServerUrl = s.ServerUrl, Email = s.Email, UserId = s.UserId,
                Token = s.Token == null ? null : Unprotect(s.Token),
                Cursor = s.Cursor ?? "", LastSyncAt = s.LastSyncAt,
                Dirty = new HashSet<string>(s.Dirty ?? new()), Purged = new HashSet<string>(s.Purged ?? new()),
                RecordIds = s.RecordIds ?? new()
            };
        }
        catch (Exception) { return new SyncState(); }
    }

    private static string Protect(string value) =>
        Convert.ToBase64String(ProtectedData.Protect(Encoding.UTF8.GetBytes(value), null, DataProtectionScope.CurrentUser));

    private static string? Unprotect(string value)
    {
        try { return Encoding.UTF8.GetString(ProtectedData.Unprotect(Convert.FromBase64String(value), null, DataProtectionScope.CurrentUser)); }
        catch (Exception) { return null; }
    }

    public void Dispose()
    {
        Microsoft.Win32.SystemEvents.PowerModeChanged -= OnPowerModeChanged;
        System.Net.NetworkInformation.NetworkChange.NetworkAvailabilityChanged -= OnNetworkChanged;
        Engine.Dispose();
    }

    private sealed class StoredState
    {
        public string? ServerUrl { get; set; }
        public string? Email { get; set; }
        public string? UserId { get; set; }
        public string? Token { get; set; }
        public string? Cursor { get; set; }
        public long LastSyncAt { get; set; }
        public List<string>? Dirty { get; set; }
        public List<string>? Purged { get; set; }
        public Dictionary<string, string>? RecordIds { get; set; }
    }
}
