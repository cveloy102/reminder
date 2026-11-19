using System.Text;
using System.Text.Json.Nodes;

namespace Svalka.Core;

/// <summary>
/// Stores each list in its own JSON file (tasks.json, shopping.json, links.json, thoughts.json),
/// the same layout as the Android app. Writes are atomic: a temp file replaces the old one.
/// </summary>
public sealed class TaskStore
{
    private static readonly (TaskList List, string File)[] Files =
    {
        (TaskList.Tasks, "tasks.json"), (TaskList.Shopping, "shopping.json"),
        (TaskList.Links, "links.json"), (TaskList.Thoughts, "thoughts.json")
    };

    private readonly string _directory;

    public TaskStore(string directory)
    {
        _directory = directory;
        Directory.CreateDirectory(directory);
    }

    /// <summary>Names of data files that could not be read and were set aside as *.corrupt-*.</summary>
    public IReadOnlyList<string> RecoveredFiles { get; private set; } = Array.Empty<string>();

    public List<ReminderTask> Load()
    {
        var problems = new List<string>();
        var result = new List<ReminderTask>();
        foreach (var (list, name) in Files) result.AddRange(LoadFrom(name, list, problems));
        if (problems.Count > 0) RecoveredFiles = problems;
        return result;
    }

    private IEnumerable<ReminderTask> LoadFrom(string name, TaskList list, List<string> problems)
    {
        var path = Path.Combine(_directory, name);
        if (!File.Exists(path)) return Array.Empty<ReminderTask>();
        try
        {
            var array = JsonNode.Parse(File.ReadAllText(path, Encoding.UTF8))!.AsArray();
            return array.Select(item => TaskJson.FromStoreJson(item!.AsObject(), list)).ToList();
        }
        catch (Exception)
        {
            // A damaged file must not crash the app on every launch: keep it aside for manual recovery.
            var aside = Path.Combine(_directory, $"{name}.corrupt-{Clock.NowMs()}");
            try { File.Move(path, aside); } catch { /* ignore */ }
            problems.Add(name);
            return Array.Empty<ReminderTask>();
        }
    }

    public void Save(IReadOnlyCollection<ReminderTask> tasks)
    {
        foreach (var (list, name) in Files)
        {
            var array = new JsonArray(tasks.Where(t => t.List == list).Select(t => (JsonNode)TaskJson.ToJson(t)).ToArray());
            AtomicFile.WriteAllText(Path.Combine(_directory, name), array.ToJsonString());
        }
    }
}

public static class AtomicFile
{
    public static void WriteAllText(string path, string content)
    {
        var temp = path + ".tmp";
        File.WriteAllText(temp, content, new UTF8Encoding(false));
        if (File.Exists(path)) File.Replace(temp, path, null);
        else File.Move(temp, path);
    }
}
