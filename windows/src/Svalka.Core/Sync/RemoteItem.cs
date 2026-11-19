using System.Text.Json.Nodes;

namespace Svalka.Core.Sync;

/// <summary>A row of the server's "items" collection.</summary>
public sealed record RemoteItem(string RecordId, string Updated, ReminderTask Task, bool Purged)
{
    public static RemoteItem FromJson(JsonObject o)
    {
        static long? Ms(JsonObject o, string key) =>
            o[key] is JsonValue v && v.TryGetValue<double>(out var d) && d > 0 ? (long)d : null;
        static string? Str(JsonObject o, string key) =>
            o[key] is JsonValue v && v.TryGetValue<string>(out var s) && s.Length > 0 ? s : null;
        static bool Bool(JsonObject o, string key) => o[key] is JsonValue v && v.TryGetValue<bool>(out var b) && b;

        var task = new ReminderTask(
            Id: Str(o, "itemId") ?? throw new InvalidDataException("Сервер вернул запись без ID"),
            Title: Str(o, "title") ?? "",
            CreatedAt: Ms(o, "createdAt") ?? 0,
            Completed: Bool(o, "completed"),
            Pinned: Bool(o, "pinned"),
            ReminderAt: Ms(o, "reminderAt"),
            DeletedAt: Ms(o, "deletedAt"),
            List: Storage.ParseList(Str(o, "list")),
            Url: Str(o, "url"),
            Repeat: Storage.ParseRepeat(Str(o, "repeat")),
            SnoozeUntil: Ms(o, "snoozeUntil"),
            UpdatedAt: Ms(o, "updatedAt") ?? 0);
        return new RemoteItem(Str(o, "id")!, Str(o, "updated") ?? "", task, Bool(o, "purged"));
    }

    public static JsonObject ToJson(ReminderTask t, string owner, bool purged = false) => new()
    {
        ["owner"] = owner,
        ["itemId"] = t.Id,
        ["title"] = t.Title,
        ["list"] = t.List.Value(),
        ["createdAt"] = t.CreatedAt,
        ["updatedAt"] = t.EditedAt,
        ["completed"] = t.Completed,
        ["pinned"] = t.Pinned,
        ["reminderAt"] = t.ReminderAt ?? 0,
        ["deletedAt"] = t.DeletedAt ?? 0,
        ["snoozeUntil"] = t.SnoozeUntil ?? 0,
        ["url"] = t.Url ?? "",
        ["repeat"] = t.Repeat.Value(),
        ["purged"] = purged
    };
}
