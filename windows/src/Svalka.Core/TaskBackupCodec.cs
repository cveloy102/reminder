using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace Svalka.Core;

public sealed record TaskBackup(List<ReminderTask> Tasks, long ExportedAt);

/// <summary>Backup file format shared with the Android app ("app": "com.svalka.reminder", version 4).</summary>
public static class TaskBackupCodec
{
    public const int MaxBytes = 5 * 1024 * 1024;
    private static readonly JsonSerializerOptions Indented = new() { WriteIndented = true };

    public static string Encode(IEnumerable<ReminderTask> tasks, long? exportedAt = null)
    {
        var root = new JsonObject
        {
            ["app"] = "com.svalka.reminder",
            ["version"] = 4,
            ["exportedAt"] = exportedAt ?? Clock.NowMs(),
            ["tasks"] = new JsonArray(tasks.Select(t => (JsonNode)TaskJson.ToJson(t)).ToArray())
        };
        return root.ToJsonString(Indented);
    }

    public static TaskBackup Decode(string json)
    {
        Require(Encoding.UTF8.GetByteCount(json) <= MaxBytes, "Файл слишком большой");
        JsonObject root;
        try { root = JsonNode.Parse(json)!.AsObject(); }
        catch (Exception) { throw new InvalidDataException("Это не копия СВАЛКИ или её версия не поддерживается"); }
        var version = root["version"] is JsonValue v && v.TryGetValue<int>(out var ver) ? ver : -1;
        Require(TaskJson.OptString(root, "app") == "com.svalka.reminder" && version is >= 1 and <= 4,
            "Это не копия СВАЛКИ или её версия не поддерживается");
        var exportedAt = RequiredLong(root, "exportedAt");
        var array = root["tasks"] as JsonArray ?? throw new InvalidDataException("В копии нет списка дел");
        Require(array.Count <= 10_000, "В копии слишком много дел");
        var ids = new HashSet<string>();
        var tasks = new List<ReminderTask>();
        foreach (var node in array)
        {
            var item = node as JsonObject ?? throw new InvalidDataException("Повреждена запись");
            var id = TaskJson.OptString(item, "id") ?? throw new InvalidDataException("Повреждён ID дела");
            var title = TaskJson.OptString(item, "title") ?? throw new InvalidDataException("Повреждён текст дела");
            Require(!string.IsNullOrWhiteSpace(id) && ids.Add(id), "В копии повторяются дела");
            Require(!string.IsNullOrWhiteSpace(title) && title.Length <= 10_000, "Повреждён текст дела");
            if (item["completed"] is not JsonValue cv || !cv.TryGetValue<bool>(out var completed))
                throw new InvalidDataException("Повреждён статус дела");
            tasks.Add(new ReminderTask(
                Id: id,
                Title: title,
                CreatedAt: RequiredLong(item, "createdAt"),
                Completed: completed,
                Pinned: TaskJson.OptBool(item, "pinned"),
                ReminderAt: OptionalLong(item, "reminderAt"),
                DeletedAt: OptionalLong(item, "deletedAt"),
                List: Storage.ParseList(TaskJson.OptString(item, "list") ?? "tasks"),
                Url: TaskJson.OptNonBlank(item, "url"),
                Repeat: Storage.ParseRepeat(TaskJson.OptString(item, "repeat") ?? "none"),
                SnoozeUntil: OptionalLong(item, "snoozeUntil"),
                UpdatedAt: OptionalLong(item, "updatedAt") ?? 0));
        }
        return new TaskBackup(tasks, exportedAt);
    }

    /// <summary>Adds only records whose id is not present yet.</summary>
    public static List<ReminderTask> Merge(IReadOnlyCollection<ReminderTask> current, IEnumerable<ReminderTask> imported)
    {
        var existing = current.Select(t => t.Id).ToHashSet();
        return current.Concat(imported.Where(t => !existing.Contains(t.Id))).ToList();
    }

    private static long RequiredLong(JsonObject o, string key)
    {
        try { return TaskJson.OptLong(o, key) ?? throw new InvalidDataException($"Повреждено поле {key}"); }
        catch (FormatException) { throw new InvalidDataException($"Повреждено поле {key}"); }
    }

    private static long? OptionalLong(JsonObject o, string key) =>
        o[key] is null ? null : RequiredLong(o, key);

    private static void Require(bool condition, string message)
    {
        if (!condition) throw new InvalidDataException(message);
    }
}
