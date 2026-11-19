namespace Svalka.Core;

/// <summary>
/// Decides which reminders are due, the same way ReminderScheduler.rescheduleAll() does on Android:
/// a reminder missed by less than 24 hours (computer asleep or off) is still shown, older ones are skipped,
/// and repeating tasks move on to their next date.
/// </summary>
public static class ReminderEngine
{
    public const long MissedWindowMs = 24L * 60 * 60 * 1000;

    /// <param name="delivered">Task id → reminder time that was already shown (survives restarts).</param>
    /// <param name="post">Shows a notification; returns false if it could not be shown.</param>
    public static List<ReminderTask> ProcessDue(IReadOnlyList<ReminderTask> tasks, long now,
        IDictionary<string, long> delivered, Func<ReminderTask, bool> post)
    {
        var known = tasks.Select(t => t.Id).ToHashSet();
        foreach (var stale in delivered.Keys.Where(k => !known.Contains(k)).ToList()) delivered.Remove(stale);

        var result = new List<ReminderTask>(tasks.Count);
        foreach (var task in tasks)
        {
            if (task.EffectiveAt() is not { } at || !task.Remindable() || at > now)
            {
                result.Add(task);
                continue;
            }
            bool handled;
            if (now - at > MissedWindowMs) handled = true;
            else if (delivered.TryGetValue(task.Id, out var shown) && shown == at) handled = true;
            else if (post(task))
            {
                delivered[task.Id] = at;
                handled = true;
            }
            else handled = false;
            result.Add(handled ? task.AfterDelivery(now) : task);
        }
        return result;
    }
}
