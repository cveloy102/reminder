using Svalka.Core;
using Xunit;

namespace Svalka.Core.Tests;

/// <summary>Same cases as RecurrenceTest.kt on Android, plus format compatibility checks.</summary>
public class CoreTests
{
    // Tests run in whatever zone the machine has; times are built in local time like the Android test with a fixed zone.
    private static long At(int year, int month, int day, int hour = 8, int minute = 0) =>
        Clock.FromLocal(new DateTime(year, month, day, hour, minute, 0));

    private static ReminderTask Task(long? reminderAt = null, RepeatRule repeat = RepeatRule.None, bool completed = false,
        long? snoozeUntil = null, long? deletedAt = null) =>
        new("id", "t", 0, Completed: completed, ReminderAt: reminderAt, Repeat: repeat, SnoozeUntil: snoozeUntil, DeletedAt: deletedAt);

    [Fact] public void DailyMovesToNextDayAfterNow() =>
        Assert.Equal(At(2026, 11, 2), TaskRules.NextOccurrence(At(2026, 11, 1), RepeatRule.Daily, At(2026, 11, 1, 8, 1)));

    [Fact] public void DailySkipsMissedDays() =>
        Assert.Equal(At(2026, 11, 11), TaskRules.NextOccurrence(At(2026, 11, 1), RepeatRule.Daily, At(2026, 11, 10, 9)));

    [Fact] public void WeeklyAddsSevenDays() =>
        Assert.Equal(At(2026, 11, 8), TaskRules.NextOccurrence(At(2026, 11, 1), RepeatRule.Weekly, At(2026, 11, 1, 9)));

    [Fact] public void MonthlyClampsToShortMonth() =>
        Assert.Equal(At(2026, 2, 28), TaskRules.NextOccurrence(At(2026, 1, 31), RepeatRule.Monthly, At(2026, 1, 31, 9)));

    [Fact] public void NoneHasNoNextOccurrence() =>
        Assert.Null(TaskRules.NextOccurrence(At(2026, 11, 1), RepeatRule.None, 0));

    [Fact] public void DeliveryAdvancesRepeatingTaskAndResetsDone()
    {
        var delivered = Task(At(2026, 11, 1), RepeatRule.Daily, completed: true).AfterDelivery(At(2026, 11, 1));
        Assert.Equal(At(2026, 11, 2), delivered.ReminderAt);
        Assert.False(delivered.Completed);
    }

    [Fact] public void DeliveryOfOneShotReminderChangesNothing()
    {
        var original = Task(At(2026, 11, 1));
        Assert.Equal(original, original.AfterDelivery(At(2026, 11, 1, 8, 1)));
    }

    [Fact] public void SnoozeWinsAndIsClearedAfterDelivery()
    {
        var snoozed = Task(At(2026, 11, 2), RepeatRule.Daily).SnoozedFor(At(2026, 11, 1), 15);
        Assert.Equal(At(2026, 11, 1) + 15 * 60_000L, snoozed.EffectiveAt());
        var after = snoozed.AfterDelivery(snoozed.SnoozeUntil!.Value);
        Assert.Null(after.SnoozeUntil);
        Assert.Equal(At(2026, 11, 2), after.ReminderAt);
    }

    [Fact] public void MarkDoneClearsSnoozeButKeepsRepeat()
    {
        var done = Task(At(2026, 11, 2), RepeatRule.Daily, snoozeUntil: 5L).MarkDone();
        Assert.True(done.Completed);
        Assert.Null(done.SnoozeUntil);
        Assert.Equal(RepeatRule.Daily, done.Repeat);
        Assert.True(done.Remindable());
    }

    [Fact] public void CompletedOneShotTaskIsNotRemindable()
    {
        Assert.False(Task(At(2026, 11, 2), completed: true).Remindable());
        Assert.False(Task(At(2026, 11, 2), deletedAt: 1L).Remindable());
        Assert.False(Task(null).Remindable());
    }

    [Fact] public void TrashOlderThanThirtyDaysIsPurged()
    {
        const long day = 24L * 60 * 60 * 1000;
        var now = 100 * day;
        var fresh = new ReminderTask("a", "a", 0, DeletedAt: now - 29 * day);
        var old = new ReminderTask("b", "b", 0, DeletedAt: now - 31 * day);
        var active = new ReminderTask("c", "c", 0);
        Assert.Equal(new[] { fresh, active }, TaskRules.PurgeExpiredTrash(new[] { fresh, old, active }, now));
    }

    [Fact] public void FindsUrlInText()
    {
        Assert.Equal("https://example.com/a?b=1", TaskRules.FindUrlInText("Смотри: https://example.com/a?b=1."));
        Assert.Equal("https://example.com", TaskRules.FindUrlInText("(https://example.com)"));
        Assert.Null(TaskRules.FindUrlInText("просто текст без ссылки"));
    }

    [Fact] public void NormalizesLinks()
    {
        Assert.Equal("https://example.com", TaskRules.NormalizeLinkUrl(" example.com "));
        Assert.Equal("http://a.ru/x", TaskRules.NormalizeLinkUrl("http://a.ru/x"));
        Assert.Null(TaskRules.NormalizeLinkUrl("mailto:me"));
        Assert.Null(TaskRules.NormalizeLinkUrl("два слова"));
        Assert.Equal("example.com", TaskRules.LinkDisplayName("https://www.example.com/page"));
    }

    [Fact] public void CapitalisesFirstLetter()
    {
        Assert.Equal("Купить молоко", TaskRules.NormalizeTitle("  купить молоко "));
        Assert.Equal("2 Яйца", TaskRules.NormalizeTitle("2 яйца"));
    }

    [Fact] public void RussianPlurals()
    {
        Assert.Equal("дело", TaskRules.CountNoun(1, "дело", "дела", "дел"));
        Assert.Equal("дела", TaskRules.CountNoun(3, "дело", "дела", "дел"));
        Assert.Equal("дел", TaskRules.CountNoun(11, "дело", "дела", "дел"));
        Assert.Equal("дело", TaskRules.CountNoun(21, "дело", "дела", "дел"));
    }

    [Fact] public void OrderPutsPinnedFirstAndDoneLast()
    {
        var a = new ReminderTask("a", "a", 0);
        var b = new ReminderTask("b", "b", 0, Completed: true);
        var c = new ReminderTask("c", "c", 0, Pinned: true);
        var d = new ReminderTask("d", "d", 0, DeletedAt: 1);
        Assert.Equal(new[] { "c", "a", "b" }, TaskRules.OrderedActive(new[] { a, b, c, d }).Select(t => t.Id));
    }

    [Fact] public void BackupRoundTripKeepsRepeatAndSnooze()
    {
        var original = new[] { Task(At(2026, 11, 2), RepeatRule.Weekly, snoozeUntil: At(2026, 11, 1, 9)) };
        Assert.Equal(original, TaskBackupCodec.Decode(TaskBackupCodec.Encode(original, 1L)).Tasks);
    }

    [Fact] public void OldBackupWithoutRepeatStillLoads()
    {
        const string json = """{"app":"com.svalka.reminder","version":3,"exportedAt":1,"tasks":[{"id":"x","title":"Молоко","createdAt":1,"completed":false,"list":"shopping"}]}""";
        var task = Assert.Single(TaskBackupCodec.Decode(json).Tasks);
        Assert.Equal(RepeatRule.None, task.Repeat);
        Assert.Equal(TaskList.Shopping, task.List);
    }

    [Fact] public void ReadsAndroidBackupWithNulls()
    {
        const string json = """
        {"app":"com.svalka.reminder","version":4,"exportedAt":1763550000000,"tasks":[
          {"id":"1","title":"Позвонить","createdAt":1763550000000,"completed":false,"pinned":true,"reminderAt":1763560000000,"deletedAt":null,"list":"tasks","url":null,"repeat":"daily","snoozeUntil":null},
          {"id":"2","title":"example.com","createdAt":1,"completed":false,"pinned":false,"reminderAt":null,"deletedAt":null,"list":"links","url":"https://example.com","repeat":"none","snoozeUntil":null},
          {"id":"3","title":"Мысль","createdAt":2,"completed":false,"pinned":false,"reminderAt":null,"deletedAt":5,"list":"thoughts","url":null,"repeat":"none","snoozeUntil":null}]}
        """;
        var tasks = TaskBackupCodec.Decode(json).Tasks;
        Assert.Equal(3, tasks.Count);
        Assert.Equal(RepeatRule.Daily, tasks[0].Repeat);
        Assert.True(tasks[0].Pinned);
        Assert.Equal("https://example.com", tasks[1].Url);
        Assert.Equal(5, tasks[2].DeletedAt);
        Assert.Equal(TaskList.Thoughts, tasks[2].List);
    }

    [Fact] public void RejectsForeignFiles()
    {
        Assert.Throws<InvalidDataException>(() => TaskBackupCodec.Decode("""{"app":"other","version":1}"""));
        Assert.Throws<InvalidDataException>(() => TaskBackupCodec.Decode("not json"));
        Assert.Throws<InvalidDataException>(() => TaskBackupCodec.Decode(
            """{"app":"com.svalka.reminder","version":4,"exportedAt":1,"tasks":[{"id":"a","title":"x","createdAt":1,"completed":false},{"id":"a","title":"y","createdAt":1,"completed":false}]}"""));
    }

    [Fact] public void MergeAddsOnlyNewIds()
    {
        var current = new[] { new ReminderTask("a", "a", 0) };
        var merged = TaskBackupCodec.Merge(current, new[] { new ReminderTask("a", "changed", 0), new ReminderTask("b", "b", 0) });
        Assert.Equal(new[] { "a", "b" }, merged.Select(t => t.Id));
        Assert.Equal("a", merged[0].Title);
    }

    [Fact] public void StoreRoundTripAndCorruptFileIsSetAside()
    {
        var dir = Path.Combine(Path.GetTempPath(), "svalka-test-" + Guid.NewGuid());
        try
        {
            var store = new TaskStore(dir);
            var tasks = new List<ReminderTask>
            {
                new("1", "Дело", 1, ReminderAt: 5, Repeat: RepeatRule.Monthly),
                new("2", "Хлеб", 2, List: TaskList.Shopping, Completed: true),
                new("3", "site.ru", 3, List: TaskList.Links, Url: "https://site.ru"),
                new("4", "Мысль", 4, List: TaskList.Thoughts, Pinned: true)
            };
            store.Save(tasks);
            Assert.Equal(tasks, new TaskStore(dir).Load());

            File.WriteAllText(Path.Combine(dir, "shopping.json"), "{broken");
            var reloaded = new TaskStore(dir);
            Assert.Equal(3, reloaded.Load().Count);
            Assert.Contains("shopping.json", reloaded.RecoveredFiles);
            Assert.Contains(Directory.GetFiles(dir), f => f.Contains("shopping.json.corrupt-"));
        }
        finally { Directory.Delete(dir, true); }
    }
}

public class ReminderEngineTests
{
    private static long At(int day, int hour = 8, int minute = 0) => Clock.FromLocal(new DateTime(2026, 11, day, hour, minute, 0));

    [Fact] public void ShowsDueReminderOnceAndAdvancesRepeat()
    {
        var delivered = new Dictionary<string, long>();
        var shown = new List<string>();
        var tasks = new List<ReminderTask>
        {
            new("a", "Daily", 0, ReminderAt: At(1), Repeat: RepeatRule.Daily),
            new("b", "Future", 0, ReminderAt: At(5)),
            new("c", "One-shot", 0, ReminderAt: At(1, 7))
        };
        var after = ReminderEngine.ProcessDue(tasks, At(1, 8, 1), delivered, t => { shown.Add(t.Id); return true; });
        Assert.Equal(new[] { "a", "c" }, shown);
        Assert.Equal(At(2), after[0].ReminderAt);
        Assert.Equal(At(5), after[1].ReminderAt);

        shown.Clear();
        ReminderEngine.ProcessDue(after, At(1, 8, 2), delivered, t => { shown.Add(t.Id); return true; });
        Assert.Empty(shown); // the one-shot reminder is not repeated
    }

    [Fact] public void SkipsRemindersMissedForMoreThanADay()
    {
        var shown = 0;
        var tasks = new List<ReminderTask> { new("a", "Old", 0, ReminderAt: At(1), Repeat: RepeatRule.Weekly) };
        var after = ReminderEngine.ProcessDue(tasks, At(3), new Dictionary<string, long>(), _ => { shown++; return true; });
        Assert.Equal(0, shown);
        Assert.Equal(At(8), after[0].ReminderAt);
    }

    [Fact] public void KeepsTaskWhenNotificationFailed()
    {
        var tasks = new List<ReminderTask> { new("a", "x", 0, ReminderAt: At(1), Repeat: RepeatRule.Daily) };
        var after = ReminderEngine.ProcessDue(tasks, At(1, 9), new Dictionary<string, long>(), _ => false);
        Assert.Equal(tasks, after);
    }

    [Fact] public void ForgetsDeliveryLogOfDeletedTasks()
    {
        var delivered = new Dictionary<string, long> { ["gone"] = 1 };
        ReminderEngine.ProcessDue(new List<ReminderTask>(), 0, delivered, _ => true);
        Assert.Empty(delivered);
    }
}
