using System.Text.Json.Nodes;

namespace Svalka.Core;

/// <summary>JSON shape of one record, identical to ReminderTask.toJson() on Android.</summary>
public static class TaskJson
{
    public static JsonObject ToJson(ReminderTask t) => new()
    {
        ["id"] = t.Id,
        ["title"] = t.Title,
        ["createdAt"] = t.CreatedAt,
        ["completed"] = t.Completed,
        ["pinned"] = t.Pinned,
        ["reminderAt"] = t.ReminderAt,
        ["deletedAt"] = t.DeletedAt,
        ["list"] = t.List.Value(),
        ["url"] = t.Url,
        ["repeat"] = t.Repeat.Value(),
        ["snoozeUntil"] = t.SnoozeUntil,
        ["updatedAt"] = t.UpdatedAt
    };

    /// <summary>Lenient parse used for the app's own data files.</summary>
    public static ReminderTask FromStoreJson(JsonObject o, TaskList list) => new(
        Id: o["id"]!.GetValue<string>(),
        Title: o["title"]!.GetValue<string>(),
        CreatedAt: o["createdAt"]!.GetValue<long>(),
        Completed: OptBool(o, "completed"),
        Pinned: OptBool(o, "pinned"),
        ReminderAt: OptLong(o, "reminderAt"),
        DeletedAt: OptLong(o, "deletedAt"),
        List: list,
        Url: OptNonBlank(o, "url"),
        Repeat: Storage.ParseRepeat(OptString(o, "repeat")),
        SnoozeUntil: OptLong(o, "snoozeUntil"),
        UpdatedAt: OptLong(o, "updatedAt") ?? 0);

    internal static bool OptBool(JsonObject o, string key) =>
        o[key] is JsonValue v && v.TryGetValue<bool>(out var b) && b;

    internal static long? OptLong(JsonObject o, string key)
    {
        if (o[key] is not JsonValue v) return null;
        if (v.TryGetValue<long>(out var l)) return l;
        if (v.TryGetValue<double>(out var d) && d == Math.Floor(d)) return (long)d;
        throw new FormatException($"Повреждено поле {key}");
    }

    internal static string? OptString(JsonObject o, string key) =>
        o[key] is JsonValue v && v.TryGetValue<string>(out var s) ? s : null;

    internal static string? OptNonBlank(JsonObject o, string key)
    {
        var s = OptString(o, key);
        return string.IsNullOrWhiteSpace(s) ? null : s;
    }
}
