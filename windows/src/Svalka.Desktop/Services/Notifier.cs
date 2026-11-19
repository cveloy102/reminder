using System;
using System.Media;
using System.Reflection;
using Microsoft.Toolkit.Uwp.Notifications;
using Svalka.Core;

namespace Svalka.Desktop.Services;

/// <summary>
/// Reminder cards with the same buttons as on the phone: "Готово", "Через 15 мин", "Через 1 час".
/// Drawn by the app itself in its own style; clicks on old Windows toasts are still handled.
/// </summary>
public sealed class Notifier
{
    public const string ActionOpen = "open";
    public const string ActionDone = "done";
    public const string ActionSnooze = "snooze";

    private readonly SoundPlayer? _sound;

    /// <summary>action, task id (may be empty), minutes. Raised on a background thread.</summary>
    public event Action<string, string, int>? Activated;

    public Notifier()
    {
        try
        {
            var stream = Assembly.GetExecutingAssembly().GetManifestResourceStream("reminder_signal.wav");
            if (stream != null)
            {
                _sound = new SoundPlayer(stream);
                _sound.Load();
            }
        }
        catch (Exception) { _sound = null; }

        ToastNotificationManagerCompat.OnActivated += e =>
        {
            var args = ToastArguments.Parse(e.Argument);
            args.TryGetValue("action", out string? action);
            args.TryGetValue("id", out string? id);
            var minutes = args.TryGetValue("minutes", out string? m) && int.TryParse(m, out var parsed) ? parsed : 0;
            Activated?.Invoke(action ?? ActionOpen, id ?? "", minutes);
        };
    }

    public static bool StartedFromToast => ToastNotificationManagerCompat.WasCurrentProcessToastActivated();

    /// <summary>
    /// Shows the app's own reminder card (see <see cref="Views.ReminderToast"/>): it stays until answered and
    /// plays the app's signal. Must be called on the UI thread. Returns false if the card could not be shown.
    /// </summary>
    public bool ShowReminder(ReminderTask task)
    {
        try
        {
            Views.ReminderToast.Present("[ СВАЛКА · ПОРА ]", task.Title, task.Id,
                (action, minutes) => Activated?.Invoke(action, task.Id, minutes),
                () => Activated?.Invoke(ActionOpen, task.Id, 0));
            PlaySound();
            return true;
        }
        catch (Exception)
        {
            return false;
        }
    }

    public void ShowTest()
    {
        try
        {
            Views.ReminderToast.Present("[ СВАЛКА · ПРОВЕРКА ]", "Так будут выглядеть напоминания.", null,
                null, () => Activated?.Invoke(ActionOpen, "", 0), TimeSpan.FromSeconds(6));
            PlaySound();
        }
        catch (Exception) { /* ignore */ }
    }

    public void Remove(string taskId)
    {
        try { Views.ReminderToast.CloseFor(taskId); } catch (Exception) { /* ignore */ }
        // Cards from older versions may still sit in the Windows notification centre.
        try { ToastNotificationManagerCompat.History.Remove(Tag(taskId)); } catch (Exception) { /* ignore */ }
    }

    private void PlaySound()
    {
        try { _sound?.Play(); } catch (Exception) { /* ignore */ }
    }

    // Toast tags are limited to 64 characters; ids are GUIDs (36), but stay safe for imported data.
    private static string Tag(string id) => id.Length <= 64 ? id : id[..64];
}
