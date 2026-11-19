using System;
using System.Collections.Generic;
using System.IO;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using Svalka.Core;

namespace Svalka.Desktop.Services;

/// <summary>
/// Automatic copy into a chosen file (normally inside the Google Drive folder, which the Google Drive app uploads).
/// Like on Android: saves 3 seconds after the last change, and never replaces a copy with an empty list automatically.
/// </summary>
public sealed class CloudBackup
{
    private readonly AppSettings _settings;
    private readonly SemaphoreSlim _writeLock = new(1, 1);
    private CancellationTokenSource? _pending;

    public CloudBackup(AppSettings settings) => _settings = settings;

    public event Action? StateChanged;

    public string? FilePath => _settings.CloudFile;
    public bool Connected => !string.IsNullOrEmpty(_settings.CloudFile);
    public bool Saving { get; private set; }
    public string? Error { get; private set; }
    public long LastSaveAt => _settings.LastCloudSave;

    public void Connect(string path)
    {
        _settings.CloudFile = path;
        _settings.LastCloudSave = 0;
        Error = null;
        _settings.Save();
        StateChanged?.Invoke();
    }

    public void Disconnect()
    {
        _pending?.Cancel();
        _settings.CloudFile = null;
        _settings.LastCloudSave = 0;
        Error = null;
        _settings.Save();
        StateChanged?.Invoke();
    }

    /// <summary>Returns null on success or the error text.</summary>
    public async Task<string?> SaveAsync(IReadOnlyCollection<ReminderTask> snapshot, bool manual)
    {
        var target = _settings.CloudFile;
        if (target == null) return "Файл не выбран";
        _pending?.Cancel();
        if (!manual && snapshot.Count == 0) return null;
        var cts = new CancellationTokenSource();
        if (!manual) _pending = cts;
        try
        {
            if (!manual) await Task.Delay(3000, cts.Token);
            await _writeLock.WaitAsync(cts.Token);
        }
        catch (OperationCanceledException) { return null; }
        try
        {
            Saving = true;
            StateChanged?.Invoke();
            var data = TaskBackupCodec.Encode(snapshot);
            if (Encoding.UTF8.GetByteCount(data) > TaskBackupCodec.MaxBytes) throw new IOException("Список слишком большой для копии");
            await Task.Run(() =>
            {
                Directory.CreateDirectory(Path.GetDirectoryName(target)!);
                AtomicFile.WriteAllText(target, data);
            });
            _settings.LastCloudSave = Clock.NowMs();
            Error = null;
            _settings.Save();
            return null;
        }
        catch (Exception error)
        {
            Error = error.Message;
            return error.Message;
        }
        finally
        {
            Saving = false;
            _writeLock.Release();
            StateChanged?.Invoke();
        }
    }
}
