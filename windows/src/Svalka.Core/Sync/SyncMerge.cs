namespace Svalka.Core.Sync;

/// <summary>Pure merge rules: the newest edit of each record wins; a purge removes the record everywhere.</summary>
public static class SyncMerge
{
    /// <summary>Applies pulled rows to the local list. Returns the new list; updates dirty/purged sets in place.</summary>
    public static List<ReminderTask> ApplyRemote(IReadOnlyList<ReminderTask> local, IEnumerable<RemoteItem> remote,
        ISet<string> dirty, ISet<string> purged)
    {
        var result = local.ToList();
        var added = new List<ReminderTask>();
        foreach (var item in remote)
        {
            var id = item.Task.Id;
            var index = result.FindIndex(t => t.Id == id);
            if (item.Purged)
            {
                if (index >= 0) result.RemoveAt(index);
                dirty.Remove(id);
                purged.Remove(id);
                continue;
            }
            if (purged.Contains(id)) continue; // deleted here for good, the purge will be uploaded
            if (index < 0)
            {
                var pending = added.FindIndex(t => t.Id == id);
                if (pending < 0) added.Add(item.Task);
                else if (item.Task.EditedAt >= added[pending].EditedAt) added[pending] = item.Task;
                continue;
            }
            if (item.Task.EditedAt > result[index].EditedAt)
            {
                result[index] = item.Task;
                dirty.Remove(id);
            }
            else if (item.Task.EditedAt < result[index].EditedAt)
            {
                dirty.Add(id); // the server has an older version: upload ours again
            }
        }
        // Records new to this device go on top, newest first, like freshly added ones.
        result.InsertRange(0, added.OrderByDescending(t => t.CreatedAt));
        return result;
    }
}
