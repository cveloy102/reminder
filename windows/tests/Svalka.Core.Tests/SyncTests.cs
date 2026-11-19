using Svalka.Core;
using Svalka.Core.Sync;
using Xunit;

namespace Svalka.Core.Tests;

public class SyncMergeTests
{
    private static RemoteItem Remote(ReminderTask t, bool purged = false) => new("r-" + t.Id, "2026-01-01 00:00:00.000Z", t, purged);

    [Fact] public void NewerRemoteEditWinsAndClearsDirty()
    {
        var local = new List<ReminderTask> { new("a", "Старое", 1, UpdatedAt: 10) };
        var dirty = new HashSet<string> { "a" };
        var result = SyncMerge.ApplyRemote(local, new[] { Remote(new("a", "Новое", 1, UpdatedAt: 20)) }, dirty, new HashSet<string>());
        Assert.Equal("Новое", result.Single().Title);
        Assert.Empty(dirty);
    }

    [Fact] public void NewerLocalEditStays()
    {
        var local = new List<ReminderTask> { new("a", "Моё", 1, UpdatedAt: 30) };
        var dirty = new HashSet<string> { "a" };
        var result = SyncMerge.ApplyRemote(local, new[] { Remote(new("a", "Чужое", 1, UpdatedAt: 20)) }, dirty, new HashSet<string>());
        Assert.Equal("Моё", result.Single().Title);
        Assert.Contains("a", dirty);
    }

    [Fact] public void NewRecordsGoOnTopAndPurgeRemoves()
    {
        var local = new List<ReminderTask> { new("a", "A", 1), new("b", "B", 2) };
        var result = SyncMerge.ApplyRemote(local,
            new[] { Remote(new("c", "C", 5, UpdatedAt: 5)), Remote(new("b", "", 0), purged: true) },
            new HashSet<string>(), new HashSet<string>());
        Assert.Equal(new[] { "c", "a" }, result.Select(t => t.Id));
    }

    [Fact] public void LocallyPurgedRecordIsNotResurrected()
    {
        var result = SyncMerge.ApplyRemote(new List<ReminderTask>(), new[] { Remote(new("x", "X", 1, UpdatedAt: 99)) },
            new HashSet<string>(), new HashSet<string> { "x" });
        Assert.Empty(result);
    }

    [Fact] public void OldDataWithoutUpdatedAtUsesCreatedAt()
    {
        Assert.Equal(7, new ReminderTask("a", "a", 7).EditedAt);
        Assert.Equal(9, new ReminderTask("a", "a", 7, UpdatedAt: 9).EditedAt);
    }

    [Fact] public void ServerUrlIsNormalized()
    {
        Assert.Equal("https://sync.example.com", SyncClient.NormalizeUrl(" sync.example.com/ "));
        Assert.Equal("http://127.0.0.1:8090", SyncClient.NormalizeUrl("http://127.0.0.1:8090"));
        Assert.Null(SyncClient.NormalizeUrl("ftp://x"));
        Assert.Null(SyncClient.NormalizeUrl("два слова"));
    }
}

/// <summary>
/// End-to-end test against a real server. Runs only when SVALKA_TEST_SERVER is set
/// (for example http://127.0.0.1:8090 with sign-up open); otherwise it passes without doing anything.
/// </summary>
public class SyncServerTests
{
    private sealed class Host : ISyncHost
    {
        public List<ReminderTask> Tasks = new();
        public Task<List<ReminderTask>> SnapshotAsync() { lock (this) return Task.FromResult(Tasks.ToList()); }
        public Task MutateAsync(Func<List<ReminderTask>, List<ReminderTask>> change) { lock (this) Tasks = change(Tasks); return Task.CompletedTask; }
        public void SaveState(SyncState state) { }
        public void Edit(SyncEngine engine, ReminderTask task)
        {
            lock (this)
            {
                var i = Tasks.FindIndex(t => t.Id == task.Id);
                if (i >= 0) Tasks[i] = task; else Tasks.Insert(0, task);
            }
            engine.MarkDirty(task.Id);
        }
        public void Purge(SyncEngine engine, string id)
        {
            lock (this) Tasks.RemoveAll(t => t.Id == id);
            engine.MarkPurged(id);
        }
    }

    private static async Task Eventually(Func<bool> condition, int seconds = 15)
    {
        for (var i = 0; i < seconds * 10 && !condition(); i++) await Task.Delay(100);
        Assert.True(condition());
    }

    [Fact] public async Task TwoDevicesStayInStep()
    {
        var server = Environment.GetEnvironmentVariable("SVALKA_TEST_SERVER");
        if (string.IsNullOrEmpty(server)) return;
        var email = $"t{Guid.NewGuid():N}@test.dev";
        const string password = "secret-12345";

        var pc = new Host();
        pc.Tasks.Add(new ReminderTask("p1", "С компьютера", 100, UpdatedAt: 100));
        var phone = new Host();
        phone.Tasks.Add(new ReminderTask("m1", "С телефона", 200, List: TaskList.Shopping, UpdatedAt: 200));

        using var pcSync = new SyncEngine(pc, new SyncState());
        using var phoneSync = new SyncEngine(phone, new SyncState());
        await pcSync.SignInAsync(server, email, password, register: true);
        await phoneSync.SignInAsync(server, email, password, register: false);
        Assert.Equal(SyncStatus.Idle, phoneSync.Status);
        await pcSync.SyncNowAsync();

        // Both devices now have both records.
        Assert.Equal(new[] { "m1", "p1" }, pc.Tasks.Select(t => t.Id).OrderBy(x => x));
        Assert.Equal(new[] { "m1", "p1" }, phone.Tasks.Select(t => t.Id).OrderBy(x => x));

        // An edit on one device reaches the other through the realtime stream, without calling sync by hand.
        pc.Edit(pcSync, pc.Tasks.First(t => t.Id == "m1") with { Completed = true, UpdatedAt = Clock.NowMs() });
        await Eventually(() => phone.Tasks.Any(t => t.Id == "m1" && t.Completed));

        // Newest edit wins when both changed the same record.
        var old = phone.Tasks.First(t => t.Id == "p1");
        phone.Edit(phoneSync, old with { Title = "Правка телефона", UpdatedAt = Clock.NowMs() - 5000 });
        pc.Edit(pcSync, old with { Title = "Правка компьютера", UpdatedAt = Clock.NowMs() });
        await Eventually(() => phone.Tasks.Any(t => t.Id == "p1" && t.Title == "Правка компьютера")
                               && pc.Tasks.Any(t => t.Id == "p1" && t.Title == "Правка компьютера"));

        // Deleting for good removes the record everywhere.
        phone.Purge(phoneSync, "m1");
        await Eventually(() => pc.Tasks.All(t => t.Id != "m1"));

        // Another account does not see these records.
        var stranger = new Host();
        using var strangerSync = new SyncEngine(stranger, new SyncState());
        await strangerSync.SignInAsync(server, $"s{Guid.NewGuid():N}@test.dev", password, register: true);
        Assert.Empty(stranger.Tasks);
    }

    private static string? Server() => Environment.GetEnvironmentVariable("SVALKA_TEST_SERVER") is { Length: > 0 } s ? s : null;
    private static string NewEmail() => $"t{Guid.NewGuid():N}@test.dev";

    [Fact] public async Task DeleteForGoodWinsOverNewerEditElsewhere()
    {
        if (Server() is not { } server) return;
        var email = NewEmail();
        var pc = new Host();
        pc.Tasks.Add(new ReminderTask("d1", "Удалю навсегда", 100, UpdatedAt: 100));
        var phone = new Host();
        using var pcSync = new SyncEngine(pc, new SyncState());
        using var phoneSync = new SyncEngine(phone, new SyncState());
        await pcSync.SignInAsync(server, email, "secret-12345", register: true);
        await phoneSync.SignInAsync(server, email, "secret-12345", register: false);
        Assert.Contains(phone.Tasks, t => t.Id == "d1");

        // The PC's clock runs ten minutes ahead: its edit carries a later time than the phone's delete.
        pc.Edit(pcSync, pc.Tasks[0] with { Title = "Правка с ПК", UpdatedAt = Clock.NowMs() + 600_000 });
        await pcSync.SyncNowAsync();
        await phoneSync.SyncNowAsync();
        phone.Purge(phoneSync, "d1");
        await phoneSync.SyncNowAsync();
        Assert.Equal(SyncStatus.Idle, phoneSync.Status);
        await pcSync.SyncNowAsync();
        Assert.DoesNotContain(pc.Tasks, t => t.Id == "d1");
    }

    [Fact] public async Task OneRejectedRecordDoesNotStopTheRest()
    {
        if (Server() is not { } server) return;
        var email = NewEmail();
        var phone = new Host();
        phone.Tasks.Add(new ReminderTask("big", new string('а', 10_001), 100, List: TaskList.Thoughts, UpdatedAt: 100));
        phone.Tasks.Add(new ReminderTask("ok", "Обычная запись", 200, UpdatedAt: 200));
        using var phoneSync = new SyncEngine(phone, new SyncState());
        await phoneSync.SignInAsync(server, email, "secret-12345", register: true);
        Assert.Equal(SyncStatus.Error, phoneSync.Status);
        Assert.Contains("не принял", phoneSync.LastError);
        Assert.Contains("big", phoneSync.State.Dirty);

        var pc = new Host();
        using var pcSync = new SyncEngine(pc, new SyncState());
        await pcSync.SignInAsync(server, email, "secret-12345", register: false);
        Assert.Contains(pc.Tasks, t => t.Id == "ok");
        Assert.DoesNotContain(pc.Tasks, t => t.Id == "big");
    }

    [Fact] public async Task PullGoesAcrossPages()
    {
        if (Server() is not { } server) return;
        var email = NewEmail();
        var pc = new Host();
        for (var i = 0; i < SyncClient.PageSize * 2 + 50; i++) pc.Tasks.Add(new ReminderTask($"r{i}", $"Запись {i}", 1000 + i, UpdatedAt: 1000 + i));
        using var pcSync = new SyncEngine(pc, new SyncState());
        await pcSync.SignInAsync(server, email, "secret-12345", register: true);
        Assert.Equal(SyncStatus.Idle, pcSync.Status);

        var phone = new Host();
        using var phoneSync = new SyncEngine(phone, new SyncState());
        await phoneSync.SignInAsync(server, email, "secret-12345", register: false);
        Assert.Equal(pc.Tasks.Select(t => t.Id).OrderBy(x => x), phone.Tasks.Select(t => t.Id).OrderBy(x => x));
    }

    [Fact] public async Task WrongPasswordIsReported()
    {
        var server = Environment.GetEnvironmentVariable("SVALKA_TEST_SERVER");
        if (string.IsNullOrEmpty(server)) return;
        using var engine = new SyncEngine(new Host(), new SyncState());
        var error = await Assert.ThrowsAsync<SyncException>(() => engine.SignInAsync(server, "nobody@test.dev", "wrong-pass", false));
        Assert.Equal("Неверная почта или пароль", error.Message);
    }
}
