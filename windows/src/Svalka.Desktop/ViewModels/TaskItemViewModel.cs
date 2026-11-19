using CommunityToolkit.Mvvm.ComponentModel;
using Svalka.Core;

namespace Svalka.Desktop.ViewModels;

/// <summary>One row of the list, with everything the row template needs already computed.</summary>
public sealed partial class TaskItemViewModel : ObservableObject
{
    public TaskItemViewModel(ReminderTask task, bool inTrash, bool showDoneHeader, int doneCount)
    {
        Task = task;
        InTrash = inTrash;
        ShowDoneHeader = showDoneHeader;
        DoneHeaderText = $"ГОТОВО / {doneCount}";
        var (day, month) = TaskRules.DayAndMonth(task.CreatedAt);
        DayText = day;
        MonthText = task.Pinned && !inTrash ? month + " ●" : month;
        ReminderText = task.ReminderAt == null ? null : "↗ " + task.ReminderLabel();
    }

    public ReminderTask Task { get; }
    public string Id => Task.Id;
    public string Title => Task.Title;
    public string? Url => Task.Url;
    public bool InTrash { get; }
    public bool Completed => Task.Completed;
    public bool IsThought => Task.List == TaskList.Thoughts;
    public bool IsLink => Task.List == TaskList.Links;
    public bool IsCheckable => !IsThought && !IsLink;
    public bool ShowPinnedCaption => Task.Pinned && !InTrash && !IsThought;
    public bool HasUrl => IsLink && !string.IsNullOrEmpty(Task.Url);
    public string? ReminderText { get; }
    public bool HasReminder => ReminderText != null;
    public bool Muted => Completed || InTrash;
    public bool DateAccent => Task.Pinned && !InTrash;
    public string DayText { get; }
    public string MonthText { get; }
    public bool ShowDoneHeader { get; }
    public string DoneHeaderText { get; }
    public bool CanPin => !InTrash && !Completed;
    public string PinMenuText => Task.Pinned ? "Открепить" : "Закрепить";
    public bool CanRemind => !InTrash && IsCheckable;

    [ObservableProperty] private bool _isDropTarget;
}
