using System;
using System.Media;
using System.Reflection;
using Microsoft.Toolkit.Uwp.Notifications;
using Svalka.Core;

namespace Svalka.Desktop.Services;

/// <summary>
/// Windows toast notifications with the same buttons as on the phone: "Готово", "Через 15 мин", "Через 1 час".
/// The toast stays on screen until answered (reminder scenario) and plays the app's own signal.
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

    /// <summary>Returns false when Windows refused to show the toast (we then retry on the next check).</summary>
    public bool ShowReminder(ReminderTask task)
    {
        try
        {
            new ToastContentBuilder()
                .AddArgument("action", ActionOpen)
                .AddArgument("id", task.Id)
                .SetToastScenario(ToastScenario.Reminder)
                .AddText("СВАЛКА · ПОРА")
                .AddText(task.Title)
                .AddButton(new ToastButton().SetContent("Готово")
                    .AddArgument("action", ActionDone).AddArgument("id", task.Id).SetBackgroundActivation())
                .AddButton(new ToastButton().SetContent("Через 15 мин")
                    .AddArgument("action", ActionSnooze).AddArgument("id", task.Id).AddArgument("minutes", 15).SetBackgroundActivation())
                .AddButton(new ToastButton().SetContent("Через 1 час")
                    .AddArgument("action", ActionSnooze).AddArgument("id", task.Id).AddArgument("minutes", 60).SetBackgroundActivation())
                .AddAudio(new ToastAudio { Silent = true })
                .Show(toast => toast.Tag = Tag(task.Id));
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
            new ToastContentBuilder()
                .AddArgument("action", ActionOpen)
                .AddText("СВАЛКА · ПРОВЕРКА")
                .AddText("Так будут выглядеть напоминания.")
                .AddAudio(new ToastAudio { Silent = true })
                .Show();
            PlaySound();
        }
        catch (Exception) { /* ignore */ }
    }

    public void Remove(string taskId)
    {
        try { ToastNotificationManagerCompat.History.Remove(Tag(taskId)); } catch (Exception) { /* ignore */ }
    }

    private void PlaySound()
    {
        try { _sound?.Play(); } catch (Exception) { /* ignore */ }
    }

    // Toast tags are limited to 64 characters; ids are GUIDs (36), but stay safe for imported data.
    private static string Tag(string id) => id.Length <= 64 ? id : id[..64];
}
