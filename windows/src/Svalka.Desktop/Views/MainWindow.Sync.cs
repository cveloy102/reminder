using System;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using Svalka.Core;
using Svalka.Core.Sync;
using static Svalka.Desktop.Views.DialogKit;

namespace Svalka.Desktop.Views;

/// <summary>The sync dialog: sign in to your own server, see the status, sign out.</summary>
public partial class MainWindow
{
    private Action? _syncDialogRefresh;

    private void ShowSync()
    {
        if (_sync.Engine.SignedIn) ShowSyncStatus();
        else ShowSyncLogin();
    }

    private void ShowSyncLogin()
    {
        var state = _sync.Engine.State;
        var server = Field(state.ServerUrl ?? "");
        var email = Field(state.Email ?? "");
        var password = Password();
        var error = Body("", "B.Rem", 12);
        error.FontWeight = FontWeights.Bold;
        error.Visibility = Visibility.Collapsed;
        Button? login = null, register = null;

        async void Go(bool create)
        {
            var url = SyncClient.NormalizeUrl(server.Text);
            if (url == null) { Fail("Укажи адрес сервера, например sync.example.com"); return; }
            if (!email.Text.Contains('@')) { Fail("Укажи почту"); return; }
            if (password.Password.Length < 8) { Fail("Пароль — не короче 8 символов"); return; }
            login!.IsEnabled = register!.IsEnabled = false;
            error.Visibility = Visibility.Collapsed;
            login.Content = create ? "ВОЙТИ" : "ПОДКЛЮЧАЮСЬ…";
            register.Content = create ? "СОЗДАЮ АККАУНТ…" : "СОЗДАТЬ АККАУНТ";
            try
            {
                await _sync.Engine.SignInAsync(url, email.Text.Trim(), password.Password, create);
                CloseDialog();
                ShowMessage("Синхронизация включена");
            }
            catch (Exception e)
            {
                Fail(e.Message);
                login.Content = "ВОЙТИ";
                register.Content = "СОЗДАТЬ АККАУНТ";
                login.IsEnabled = register.IsEnabled = true;
            }
        }

        void Fail(string text)
        {
            error.Text = text;
            error.Visibility = Visibility.Visible;
        }

        login = Accent("ВОЙТИ", () => Go(false));
        register = Outline("СОЗДАТЬ АККАУНТ", () => Go(true));
        password.KeyDown += (_, e) => { if (e.Key == Key.Enter) Go(false); };

        var title = new StackPanel { Orientation = Orientation.Horizontal };
        title.Children.Add(Heading("Синхронизация", 26));
        title.Children.Add(Heading("_", 26).Res(TextBlock.ForegroundProperty, "B.Accent"));

        ShowDialog(Card(Scroll(Stack(
            Caption("[ СВОЙ СЕРВЕР ]", "B.Rem"),
            title,
            Gap(8), Body("Записи будут одинаковыми на компьютере и телефоне. Нужен сервер СВАЛКИ — свой или того, кто дал тебе адрес."),
            Gap(18), Caption("АДРЕС СЕРВЕРА"), Gap(6), server,
            Gap(14), Caption("ПОЧТА"), Gap(6), email,
            Gap(14), Caption("ПАРОЛЬ"), Gap(6), password,
            Gap(8), error,
            Gap(18), login,
            Gap(8), register,
            Gap(10), Body("Записи с этого компьютера объединятся с записями аккаунта, ничего не удалится.", "B.Sec", 11),
            Gap(4), Link("ОТМЕНА", CloseDialog)))));
    }

    private void ShowSyncStatus()
    {
        var engine = _sync.Engine;
        var status = Body("", "B.Fg", 14);
        var details = Body("", "B.Sec", 12);
        var syncNow = Accent("СИНХРОНИЗИРОВАТЬ СЕЙЧАС", () => _ = engine.SyncNowAsync());

        void Refresh()
        {
            var state = engine.State;
            status.Text = engine.Status switch
            {
                SyncStatus.Syncing => "Синхронизирую…",
                SyncStatus.Error => "Ошибка: " + engine.LastError,
                _ => state.LastSyncAt > 0 ? "Всё синхронизировано · " + TaskRules.FormatStamp(state.LastSyncAt).ToLowerInvariant() : "Подключено"
            };
            status.SetResourceReference(TextBlock.ForegroundProperty, engine.Status == SyncStatus.Error ? "B.Rem" : "B.Fg");
            details.Text = $"Аккаунт: {state.Email}\nСервер: {state.ServerUrl}\n" +
                           (engine.LiveConnected ? "Изменения приходят сразу." : "Постоянной связи нет — проверю ещё раз через пару минут.");
            syncNow.IsEnabled = engine.Status != SyncStatus.Syncing;
        }
        Refresh();
        _syncDialogRefresh = Refresh;

        var actions = new StackPanel();
        if (engine.LastError == "Нужно войти снова")
            actions.Children.Add(Outline("ВОЙТИ СНОВА", () => { engine.SignOut(); ShowSyncLogin(); }));

        ShowDialog(Card(Scroll(Stack(
            Caption("[ СИНХРОНИЗАЦИЯ ]", "B.Rem"),
            Heading("Свой сервер", 26),
            Gap(14), status,
            Gap(8), details,
            Gap(18), syncNow,
            Gap(8), actions,
            Divider(),
            Link("ВЫЙТИ ИЗ АККАУНТА", () => Confirm("Выйти из аккаунта?",
                "Синхронизация выключится. Записи останутся на этом компьютере и на сервере.", "ВЫЙТИ", () =>
                {
                    engine.SignOut();
                    ShowMessage("Синхронизация выключена");
                }), "B.Sec"),
            Link("НАЗАД К СПИСКУ ←", CloseDialog)))), () => _syncDialogRefresh = null);
    }
}
