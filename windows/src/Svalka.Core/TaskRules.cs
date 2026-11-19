using System.Globalization;
using System.Text.RegularExpressions;

namespace Svalka.Core;

/// <summary>Business rules ported one-to-one from the Android app (TaskStore.kt, Recurrence.kt, ShareIntake.kt).</summary>
public static class TaskRules
{
    public const long TrashRetentionMs = 30L * 24 * 60 * 60 * 1000;
    private static readonly CultureInfo Ru = CultureInfo.GetCultureInfo("ru-RU");
    private static readonly Regex UrlInText = new(@"https?://[^\s<>""']+", RegexOptions.IgnoreCase);
    private static readonly Regex SchemeLike = new("^[A-Za-z][A-Za-z0-9+.-]*$");

    /// <summary>Trims and capitalises the first letter.</summary>
    public static string NormalizeTitle(string title)
    {
        var clean = title.Trim();
        for (var i = 0; i < clean.Length; i++)
        {
            if (char.IsLetter(clean[i]))
                return clean[..i] + char.ToUpper(clean[i], Ru) + clean[(i + 1)..];
        }
        return clean;
    }

    /// <summary>Turns "example.com" or "https://example.com/x" into a valid http(s) address, or null.</summary>
    public static string? NormalizeLinkUrl(string value)
    {
        var clean = value.Trim();
        if (clean.Length == 0 || clean.Any(char.IsWhiteSpace)) return null;
        var colon = clean.IndexOf(':');
        var prefix = colon >= 0 ? clean[..colon] : clean;
        if (colon >= 0 && !clean.Contains("://") && !prefix.Contains('.') && SchemeLike.IsMatch(prefix)) return null;
        var candidate = clean.Contains("://") ? clean : "https://" + clean;
        if (!Uri.TryCreate(candidate, UriKind.Absolute, out var uri)) return null;
        if (uri.Scheme != "http" && uri.Scheme != "https") return null;
        return string.IsNullOrWhiteSpace(uri.Host) ? null : candidate;
    }

    public static string LinkDisplayName(string url)
    {
        if (!Uri.TryCreate(url, UriKind.Absolute, out var uri) || string.IsNullOrEmpty(uri.Host)) return url;
        return uri.Host.StartsWith("www.") ? uri.Host[4..] : uri.Host;
    }

    /// <summary>First web address inside a piece of text, without trailing punctuation.</summary>
    public static string? FindUrlInText(string text)
    {
        var match = UrlInText.Match(text);
        if (!match.Success) return null;
        var value = match.Value.TrimEnd('.', ',', ';', ':', '!', '?', ')', ']', '}');
        return value.Length > "https://".Length ? value : null;
    }

    public static ReminderTask Normalized(this ReminderTask task) => task with
    {
        Title = task.List == TaskList.Links && task.Url != null && task.Title == LinkDisplayName(task.Url)
            ? task.Title : NormalizeTitle(task.Title),
        Pinned = task.Pinned && !task.Completed
    };

    /// <summary>Active (not deleted) records: not done first, pinned on top, otherwise stored order.</summary>
    public static List<ReminderTask> OrderedActive(IEnumerable<ReminderTask> tasks) =>
        tasks.Where(t => t.DeletedAt == null)
            .OrderBy(t => t.Completed)
            .ThenByDescending(t => t.Pinned)
            .ToList();

    public static List<ReminderTask> PurgeExpiredTrash(IEnumerable<ReminderTask> tasks, long now) =>
        tasks.Where(t => !(t.DeletedAt != null && now - t.DeletedAt > TrashRetentionMs)).ToList();

    // ---- Reminders and repeats ----

    /// <summary>First occurrence of from + k*rule (k ≥ 1) strictly later than after.</summary>
    public static long? NextOccurrence(long from, RepeatRule rule, long after)
    {
        if (rule == RepeatRule.None) return null;
        var baseLocal = Clock.ToLocal(from);
        for (var step = 1; step <= 20_000; step++)
        {
            var candidate = rule switch
            {
                RepeatRule.Daily => baseLocal.AddDays(step),
                RepeatRule.Weekly => baseLocal.AddDays(7 * step),
                _ => baseLocal.AddMonths(step)
            };
            var ms = Clock.FromLocal(candidate);
            if (ms > after) return ms;
        }
        return null;
    }

    /// <summary>The moment the next notification is due: a snooze wins over the regular reminder.</summary>
    public static long? EffectiveAt(this ReminderTask task) => task.SnoozeUntil ?? task.ReminderAt;

    /// <summary>Repeating tasks keep reminding even when ticked off; they reset at the next occurrence.</summary>
    public static bool Remindable(this ReminderTask task) =>
        task.DeletedAt == null && task.EffectiveAt() != null && (!task.Completed || task.Repeat != RepeatRule.None);

    /// <summary>State of the task right after its notification has been shown.</summary>
    public static ReminderTask AfterDelivery(this ReminderTask task, long now)
    {
        if (task.SnoozeUntil != null) return task with { SnoozeUntil = null };
        if (task.ReminderAt is not { } at) return task;
        if (task.Repeat == RepeatRule.None) return task;
        return task with { ReminderAt = NextOccurrence(at, task.Repeat, now) ?? at, Completed = false };
    }

    public static ReminderTask MarkDone(this ReminderTask task) =>
        task with { Completed = true, Pinned = false, SnoozeUntil = null };

    public static ReminderTask SnoozedFor(this ReminderTask task, long now, int minutes) =>
        task with { SnoozeUntil = now + minutes * 60_000L };

    // ---- Russian wording ----

    public static string CountNoun(int count, string one, string few, string many)
    {
        if (count % 100 is >= 11 and <= 14) return many;
        if (count % 10 == 1) return one;
        if (count % 10 is >= 2 and <= 4) return few;
        return many;
    }

    public static string FormatStamp(long ms) =>
        Clock.ToLocal(ms).ToString("d MMM, HH:mm", Ru).ToUpper(Ru).Replace(".", "");

    public static string ReminderLabel(this ReminderTask task)
    {
        if (task.EffectiveAt() is not { } at) return "";
        var snoozed = task.SnoozeUntil != null ? " · ОТЛОЖЕНО" : "";
        var repeat = task.Repeat == RepeatRule.None ? "" : " · " + task.Repeat.Label();
        return FormatStamp(at) + snoozed + repeat;
    }

    private static readonly string[] Months =
        { "ЯНВ", "ФЕВ", "МАР", "АПР", "МАЯ", "ИЮН", "ИЮЛ", "АВГ", "СЕН", "ОКТ", "НОЯ", "ДЕК" };

    public static (string Day, string Month) DayAndMonth(long ms)
    {
        var local = Clock.ToLocal(ms);
        return (local.Day.ToString("00"), Months[local.Month - 1]);
    }
}
