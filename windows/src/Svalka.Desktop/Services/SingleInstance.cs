using System;
using System.Threading;

namespace Svalka.Desktop.Services;

/// <summary>Only one copy runs; starting it again brings the existing window forward.</summary>
public sealed class SingleInstance : IDisposable
{
    private const string MutexName = @"Local\Svalka.Windows.SingleInstance";
    private const string EventName = @"Local\Svalka.Windows.Show";
    private readonly Mutex _mutex;
    private readonly EventWaitHandle _showEvent;
    private readonly bool _owned;
    private RegisteredWaitHandle? _wait;

    public SingleInstance()
    {
        _mutex = new Mutex(true, MutexName, out _owned);
        _showEvent = new EventWaitHandle(false, EventResetMode.AutoReset, EventName);
    }

    public bool IsFirst => _owned;

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
