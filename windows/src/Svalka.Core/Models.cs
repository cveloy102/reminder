namespace Svalka.Core;

/// <summary>The four lists of the app. Storage values match the Android version.</summary>
public enum TaskList { Tasks, Shopping, Links, Thoughts }

public enum RepeatRule { None, Daily, Weekly, Monthly }

public static class Storage
{
    public static string Value(this TaskList list) => list switch
    {
        TaskList.Shopping => "shopping",
        TaskList.Links => "links",
        TaskList.Thoughts => "thoughts",
        _ => "tasks"
    };

    public static TaskList ParseList(string? value) => value switch
    {
        "shopping" => TaskList.Shopping,
        "links" => TaskList.Links,
        "thoughts" => TaskList.Thoughts,
        _ => TaskList.Tasks
    };

    public static string Value(this RepeatRule rule) => rule switch
    {
        RepeatRule.Daily => "daily",
        RepeatRule.Weekly => "weekly",
        RepeatRule.Monthly => "monthly",
        _ => "none"
    };

    public static RepeatRule ParseRepeat(string? value) => value switch
    {
        "daily" => RepeatRule.Daily,
        "weekly" => RepeatRule.Weekly,
        "monthly" => RepeatRule.Monthly,
        _ => RepeatRule.None
    };

    public static string Label(this RepeatRule rule) => rule switch
    {
        RepeatRule.Daily => "КАЖДЫЙ ДЕНЬ",
        RepeatRule.Weekly => "КАЖДУЮ НЕДЕЛЮ",
        RepeatRule.Monthly => "КАЖДЫЙ МЕСЯЦ",
        _ => "НЕ ПОВТОРЯТЬ"
    };
}

/// <summary>
/// One record in any list. Times are Unix milliseconds, exactly like the Android app,
/// so data files and backups are interchangeable between the two.
/// </summary>
public sealed record ReminderTask(
    string Id,
    string Title,
    long CreatedAt,
    bool Completed = false,
    bool Pinned = false,
    long? ReminderAt = null,
    long? DeletedAt = null,
    TaskList List = TaskList.Tasks,
    string? Url = null,
    RepeatRule Repeat = RepeatRule.None,
    long? SnoozeUntil = null);
