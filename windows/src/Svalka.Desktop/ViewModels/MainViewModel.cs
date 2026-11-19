using System;
using System.Collections.Generic;
using System.Collections.ObjectModel;
using System.Linq;
using System.Windows.Threading;
using CommunityToolkit.Mvvm.ComponentModel;
using Svalka.Core;
using Svalka.Desktop.Services;

namespace Svalka.Desktop.ViewModels;

public sealed record PendingUndo(ReminderTask Original, bool Restoring);

/// <summary>
/// Holds all records and does what MainActivity does on Android: add, edit, tick off, reorder,
/// trash with undo, reminders, backups. Every change is saved to disk right away.
/// </summary>
public sealed partial class MainViewModel : ObservableObject
{
    private readonly TaskStore _store;
    private readonly AppSettings _settings;
    private readonly Notifier _notifier;
    private readonly DispatcherTimer _reminderTimer;
    private readonly DispatcherTimer _undoTimer;
    private readonly Dictionary<TaskList, string> _inputs = new()
    {
        [TaskList.Tasks] = "", [TaskList.Shopping] = "", [TaskList.Links] = "", [TaskList.Thoughts] = ""
    };
    private List<ReminderTask> _tasks;

    public MainViewModel(TaskStore store, AppSettings settings, Notifier notifier, CloudBackup cloud)
    {
        _store = store;
        _settings = settings;
        _notifier = notifier;
        Cloud = cloud;
        var loaded = store.Load();
        _tasks = loaded.Select(t => t.Normalized()).ToList();
        if (!_tasks.SequenceEqual(loaded)) store.Save(_tasks);
        RecoveredFiles = store.RecoveredFiles;
        PurgeTrash();

        _reminderTimer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(10) };
        _reminderTimer.Tick += (_, _) => CheckReminders();
        _undoTimer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(1) };
        _undoTimer.Tick += (_, _) => UndoTick();
        Rebuild();
    }

    public event Action<string>? Message;
    /// <summary>A record was edited here (sync uploads it).</summary>
    public event Action<string>? RecordChanged;
    /// <summary>A record was deleted for good here (sync removes it on other devices).</summary>
    public event Action<string>? RecordPurged;

    public CloudBackup Cloud { get; }
    public IReadOnlyList<string> RecoveredFiles { get; }
    public IReadOnlyList<ReminderTask> Tasks => _tasks;
    public ObservableCollection<TaskItemViewModel> Items { get; } = new();

    [ObservableProperty] private TaskList _section = TaskList.Tasks;
    [ObservableProperty] private bool _inTrash;
    [ObservableProperty] private PendingUndo? _pendingUndo;
    [ObservableProperty] private int _undoSecondsLeft;

    public string Input
    {
        get => _inputs[Section];
        set
        {
            if (_inputs[Section] == value) return;
            _inputs[Section] = value;
            OnPropertyChanged();
            OnPropertyChanged(nameof(InputEmpty));
        }
    }

    public bool InputEmpty => Input.Length == 0;
    public bool IsThoughts => Section == TaskList.Thoughts;
    public bool NotInTrash => !InTrash;
    public string SectionName => NameOf(Section);
    public string SectionCaption => $"[ {SectionName} ]";
    public string HeaderCaption => InTrash ? "[ КОРЗИНА ]" : "▦ SVLK / PERSONAL";
    public string BigTitle => InTrash ? "КОРЗИНА" : "СВАЛКА";
    public string ListCaption => InTrash ? "УДАЛЁННЫЕ" : IsThoughts ? "ЗАПИСИ" : "СПИСОК";
    public string TrashButtonText => InTrash ? "НАЗАД ←" : $"КОРЗИНА {DeletedCount}";
    public int DeletedCount => _tasks.Count(t => t.List == Section && t.DeletedAt != null);
    public int PurchasedCount => _tasks.Count(t => t.List == TaskList.Shopping && t.DeletedAt == null && t.Completed);
    public bool ShowClearPurchased => !InTrash && Section == TaskList.Shopping && PurchasedCount > 0;
    public string ClearPurchasedText => $"ОЧИСТИТЬ КУПЛЕННОЕ · {PurchasedCount}";
    public bool IsEmpty => Items.Count == 0;
    public bool HasUndo => PendingUndo != null;
    public string UndoText => PendingUndo?.Restoring == true ? "ВОССТАНОВЛЕНО" : "В КОРЗИНЕ";
    public string UndoCountdown => $"{UndoSecondsLeft} С";

    public int Count
    {
        get
        {
            if (InTrash) return DeletedCount;
            var active = _tasks.Where(t => t.List == Section && t.DeletedAt == null);
            return IsThoughts ? active.Count() : active.Count(t => !t.Completed);
        }
    }

    public string CountText
    {
        get
        {
            var c = Count;
            if (InTrash) return "  можно вернуть · хранится 30 дней";
            return Section switch
            {
                TaskList.Shopping => $"  {TaskRules.CountNoun(c, "покупка", "покупки", "покупок")} в списке",
                TaskList.Links => $"  {TaskRules.CountNoun(c, "ссылка", "ссылки", "ссылок")} в списке",
                TaskList.Thoughts => $"  {TaskRules.CountNoun(c, "мысль записана", "мысли записаны", "мыслей записано")}",
                _ => $"  {TaskRules.CountNoun(c, "дело", "дела", "дел")} на очереди"
            };
        }
    }

    public string Placeholder => Section switch
    {
        TaskList.Shopping => "ДОБАВИТЬ ПОКУПКУ",
        TaskList.Links => "ВСТАВИТЬ ССЫЛКУ",
        TaskList.Thoughts => "ЗАПИСАТЬ МЫСЛЬ · CTRL+ENTER — СОХРАНИТЬ",
        _ => "ДОБАВИТЬ ДЕЛО"
    };

    public string EmptyText => InTrash ? "Здесь пока пусто" : Section switch
    {
        TaskList.Shopping => "Здесь пока пусто.\nДобавь первую покупку ↓",
        TaskList.Links => "Здесь пока пусто.\nВставь первую ссылку ↓",
        TaskList.Thoughts => "Здесь пока пусто.\nЗапиши первую мысль ↓",
        _ => "Здесь пока пусто.\nЗапиши первое дело ↓"
    };

    public static string NameOf(TaskList list) => list switch
    {
        TaskList.Shopping => "ПОКУПКИ",
        TaskList.Links => "ССЫЛКИ",
        TaskList.Thoughts => "МЫСЛИ",
        _ => "ДЕЛА"
    };

    partial void OnSectionChanged(TaskList value)
    {
        InTrash = false;
        OnPropertyChanged(nameof(Input));
        OnPropertyChanged(nameof(InputEmpty));
        Rebuild();
    }

    partial void OnInTrashChanged(bool value) => Rebuild();
    partial void OnPendingUndoChanged(PendingUndo? value)
    {
        OnPropertyChanged(nameof(HasUndo));
        OnPropertyChanged(nameof(UndoText));
    }
    partial void OnUndoSecondsLeftChanged(int value) => OnPropertyChanged(nameof(UndoCountdown));

    public void Start()
    {
        CheckReminders();
        _reminderTimer.Start();
    }

    /// <summary>Rebuilds the visible rows and refreshes every derived caption.</summary>
    private void Rebuild()
    {
        List<ReminderTask> visible;
        if (InTrash)
            visible = _tasks.Where(t => t.List == Section && t.DeletedAt != null).OrderByDescending(t => t.DeletedAt).ToList();
        else
            visible = TaskRules.OrderedActive(_tasks.Where(t => t.List == Section));
        var doneCount = visible.Count(t => t.Completed);
        Items.Clear();
        for (var i = 0; i < visible.Count; i++)
        {
            var header = !InTrash && visible[i].Completed && (i == 0 || !visible[i - 1].Completed);
            Items.Add(new TaskItemViewModel(visible[i], InTrash, header, doneCount));
        }
        foreach (var name in new[]
                 {
                     nameof(IsThoughts), nameof(NotInTrash), nameof(SectionName), nameof(SectionCaption), nameof(HeaderCaption),
                     nameof(BigTitle), nameof(ListCaption), nameof(TrashButtonText), nameof(DeletedCount), nameof(PurchasedCount),
                     nameof(ShowClearPurchased), nameof(ClearPurchasedText), nameof(IsEmpty), nameof(Count), nameof(CountText),
                     nameof(Placeholder), nameof(EmptyText)
                 })
            OnPropertyChanged(name);
    }

    private void Commit(bool backup = true)
    {
        try { _store.Save(_tasks); }
        catch (Exception error) { Message?.Invoke("Не удалось сохранить: " + error.Message); }
        if (backup && Cloud.Connected) _ = Cloud.SaveAsync(_tasks.ToList(), manual: false);
        Rebuild();
    }

    public ReminderTask? Find(string id) => _tasks.FirstOrDefault(t => t.Id == id);

    /// <summary>Insert or replace a record (new records go on top), then save and recheck reminders.</summary>
    public void Save(ReminderTask task)
    {
        var normalized = task.Normalized() with { UpdatedAt = Clock.NowMs() };
        var index = _tasks.FindIndex(t => t.Id == normalized.Id);
        if (index >= 0) _tasks[index] = normalized;
        else _tasks.Insert(0, normalized);
        _notifier.Remove(normalized.Id);
        Commit();
        RecordChanged?.Invoke(normalized.Id);
    }

    /// <summary>Adds what is typed in the composer. Returns a link that still needs a name, if any.</summary>
    public string? Submit()
    {
        var text = Input;
        if (string.IsNullOrWhiteSpace(text)) return null;
        if (Section == TaskList.Links)
        {
            var url = TaskRules.NormalizeLinkUrl(text);
            if (url == null)
            {
                Message?.Invoke("Вставь адрес сайта или ссылку");
                return null;
            }
            return url;
        }
        AddTask(text, Section);
        Input = "";
        return null;
    }

    public void AddTask(string title, TaskList list)
    {
        var clean = title.Trim();
        if (clean.Length > 0) Save(new ReminderTask(Guid.NewGuid().ToString(), clean, Clock.NowMs(), List: list));
    }

    public void AddLink(string url, string title)
    {
        var normalized = TaskRules.NormalizeLinkUrl(url);
        if (normalized == null) return;
        if (_tasks.Any(t => t.List == TaskList.Links && t.DeletedAt == null && t.Url == normalized))
        {
            Message?.Invoke("Эта ссылка уже сохранена");
            if (Section == TaskList.Links) Input = "";
            return;
        }
        var name = title.Trim();
        if (name.Length == 0) name = TaskRules.LinkDisplayName(normalized);
        Save(new ReminderTask(Guid.NewGuid().ToString(), name, Clock.NowMs(), List: TaskList.Links, Url: normalized));
        if (Section == TaskList.Links) Input = "";
    }

    public void Toggle(ReminderTask task) =>
        Save(task with { Completed = !task.Completed, Pinned = false, SnoozeUntil = null });

    public void TogglePin(ReminderTask task)
    {
        Save(task with { Pinned = !task.Pinned });
        Message?.Invoke(task.Pinned ? "Откреплено" : "Закреплено");
    }

    public void ClearCompleted(TaskList list)
    {
        var now = Clock.NowMs();
        var ids = _tasks.Where(t => t.List == list && t.Completed && t.DeletedAt == null).Select(t => t.Id).ToHashSet();
        if (ids.Count == 0) return;
        _tasks = _tasks.Select(t => ids.Contains(t.Id) ? t with { DeletedAt = now, UpdatedAt = now } : t).ToList();
        foreach (var id in ids) _notifier.Remove(id);
        Commit();
        foreach (var id in ids) RecordChanged?.Invoke(id);
        Message?.Invoke($"В корзине: {ids.Count}");
    }

    /// <summary>Same rules as on Android: only within one list and within the same done/not done group.</summary>
    public void Reorder(string fromId, string toId)
    {
        if (fromId == toId) return;
        var source = Find(fromId);
        if (source == null || Find(toId)?.List != source.List) return;
        var active = TaskRules.OrderedActive(_tasks.Where(t => t.List == source.List));
        var from = active.FindIndex(t => t.Id == fromId);
        var to = active.FindIndex(t => t.Id == toId);
        if (from < 0 || to < 0 || active[from].Completed != active[to].Completed) return;
        var reordered = active.ToList();
        var moved = reordered[from] with { Pinned = !active[from].Completed && active[to].Pinned, UpdatedAt = Clock.NowMs() };
        reordered.RemoveAt(from);
        var target = reordered.FindIndex(t => t.Id == toId);
        reordered.Insert(target + (from < to ? 1 : 0), moved);
        _tasks = reordered.Concat(_tasks.Where(t => t.List != source.List || t.DeletedAt != null)).ToList();
        Commit();
        RecordChanged?.Invoke(fromId);
    }

    public void MoveToTrash(ReminderTask task, bool offerUndo = true)
    {
        Save(task with { DeletedAt = Clock.NowMs() });
        if (offerUndo) StartUndo(new PendingUndo(task, Restoring: false));
    }

    public void Restore(ReminderTask task, bool offerUndo = true)
    {
        Save(task with { DeletedAt = null });
        if (offerUndo) StartUndo(new PendingUndo(task, Restoring: true));
    }

    public void DeletePermanently(ReminderTask task)
    {
        _tasks.RemoveAll(t => t.Id == task.Id);
        _notifier.Remove(task.Id);
        Commit();
        RecordPurged?.Invoke(task.Id);
    }

    private void StartUndo(PendingUndo undo)
    {
        PendingUndo = undo;
        UndoSecondsLeft = 10;
        _undoTimer.Stop();
        _undoTimer.Start();
    }

    private void UndoTick()
    {
        UndoSecondsLeft--;
        if (UndoSecondsLeft > 0) return;
        _undoTimer.Stop();
        PendingUndo = null;
    }

    public void Undo()
    {
        if (PendingUndo is not { } undo) return;
        _undoTimer.Stop();
        PendingUndo = null;
        Save(undo.Original);
    }

    public bool SetReminder(ReminderTask task, long at, RepeatRule repeat)
    {
        if (at <= Clock.NowMs()) return false;
        Save(task with { ReminderAt = at, Repeat = repeat, SnoozeUntil = null });
        return true;
    }

    public void RemoveReminder(ReminderTask task) =>
        Save(task with { ReminderAt = null, Repeat = RepeatRule.None, SnoozeUntil = null });

    /// <summary>Handles a button pressed on a notification.</summary>
    public void HandleNotificationAction(string action, string id, int minutes)
    {
        var task = Find(id);
        if (task == null) return;
        var now = Clock.NowMs();
        var updated = action switch
        {
            Notifier.ActionDone => task.MarkDone(),
            Notifier.ActionSnooze => task.SnoozedFor(now, minutes > 0 ? minutes : 15),
            _ => null
        };
        if (updated != null) Save(updated);
    }

    public void ShowList(string id)
    {
        var task = Find(id);
        if (task == null) return;
        Section = task.List;
        InTrash = task.DeletedAt != null;
    }

    private void CheckReminders()
    {
        var before = _settings.DeliveredReminders.ToList();
        var updated = ReminderEngine.ProcessDue(_tasks, Clock.NowMs(), _settings.DeliveredReminders, _notifier.ShowReminder);
        if (!before.SequenceEqual(_settings.DeliveredReminders)) _settings.Save();
        if (updated.SequenceEqual(_tasks)) return;
        var now = Clock.NowMs();
        var changed = new List<string>();
        for (var i = 0; i < updated.Count; i++)
        {
            if (updated[i] == _tasks[i]) continue;
            updated[i] = updated[i] with { UpdatedAt = now };
            changed.Add(updated[i].Id);
        }
        _tasks = updated;
        Commit();
        foreach (var id in changed) RecordChanged?.Invoke(id);
    }

    /// <summary>Trash older than 30 days is removed for good.</summary>
    public void PurgeTrash()
    {
        var kept = TaskRules.PurgeExpiredTrash(_tasks, Clock.NowMs());
        if (kept.Count == _tasks.Count) return;
        var keptIds = kept.Select(t => t.Id).ToHashSet();
        var purged = _tasks.Where(t => !keptIds.Contains(t.Id)).Select(t => t.Id).ToList();
        _tasks = kept;
        _store.Save(_tasks);
        Rebuild();
        foreach (var id in purged) RecordPurged?.Invoke(id);
    }

    // ---- Backups ----

    public void RestoreBackup(TaskBackup backup, bool replace)
    {
        var result = (replace ? backup.Tasks : TaskBackupCodec.Merge(_tasks, backup.Tasks)).Select(t => t.Normalized()).ToList();
        try
        {
            _store.Save(result);
        }
        catch (Exception error)
        {
            Message?.Invoke("Не удалось восстановить: " + error.Message);
            return;
        }
        var before = _tasks.ToDictionary(t => t.Id);
        _tasks = result;
        PurgeTrash();
        Commit();
        foreach (var t in _tasks.Where(t => !before.TryGetValue(t.Id, out var old) || old != t)) RecordChanged?.Invoke(t.Id);
        var now = _tasks.Select(t => t.Id).ToHashSet();
        foreach (var id in before.Keys.Where(id => !now.Contains(id))) RecordPurged?.Invoke(id);
        CheckReminders();
        Message?.Invoke("Записи восстановлены");
    }

    /// <summary>Applies a change that came from the sync server (not an edit made here, so nothing is uploaded back).</summary>
    public void ApplyFromSync(Func<List<ReminderTask>, List<ReminderTask>> change)
    {
        var before = _tasks.Select(t => t.Id).ToHashSet();
        _tasks = change(_tasks.ToList()).Select(t => t.Normalized()).ToList();
        foreach (var gone in before.Where(id => _tasks.All(t => t.Id != id))) _notifier.Remove(gone);
        Commit();
    }

    public async void SaveCloudNow()
    {
        var error = await Cloud.SaveAsync(_tasks.ToList(), manual: true);
        Message?.Invoke(error == null ? "Копия сохранена в файл автокопии" : "Не удалось сохранить: " + error);
    }
}
