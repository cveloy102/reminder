using System;
using System.Threading;

namespace Svalka.Desktop.Services;

/// <summary>Only one copy runs; starting it again brings the existing window forward.</summary>
public sealed class SingleInstance : IDisposable
{
    internal const string MutexName = @"Local\Svalka.Windows.SingleInstance";
    private const string EventName = @"Local\Svalka.Windows.Show";
    private readonly Mutex _mutex;
    private readonly EventWaitHandle _showEvent;
    private readonly bool _owned;
    private RegisteredWaitHandle? _wait;

    public SingleInstance()
    {
        _mutex = new Mutex(false, MutexName);
        _owned = TryAcquire(_mutex);
        _showEvent = new EventWaitHandle(false, EventResetMode.AutoReset, EventName);
    }

    public bool IsFirst => _owned;

    internal static bool TryAcquire(Mutex mutex)
    {
        try { return mutex.WaitOne(0); }
        catch (AbandonedMutexException) { return true; }
    }

    public void SignalFirst() => _showEvent.Set();

    public void ListenForShow(Action onShow) =>
        _wait = ThreadPool.RegisterWaitForSingleObject(_showEvent, (_, _) => onShow(), null, Timeout.Infinite, false);

    public void Dispose()
    {
        _wait?.Unregister(null);
        if (_owned) _mutex.ReleaseMutex();
        _mutex.Dispose();
        _showEvent.Dispose();
    }
}
