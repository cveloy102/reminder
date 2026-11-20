using System;
using System.ComponentModel;
using System.Diagnostics;
using System.IO;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Input;
using Svalka.Desktop.Services;
using Svalka.Desktop.Theme;

namespace Svalka.Desktop.Views;

public partial class SetupWindow : Window
{
    private readonly bool _remove;
    private readonly bool _update;
    private readonly int _waitFor;
    private bool _busy;
    private bool _done;

    private SetupWindow(bool remove, bool update, int waitFor, string? error)
    {
        _remove = remove;
        _update = update || Installation.IsInstalled;
        _waitFor = waitFor;
        InitializeComponent();
        VersionLabel.Text = $"WINDOWS / {Installation.Version.ToString(3)}";
        HeadingLabel.Text = remove ? "Удалить СВАЛКУ?" : _update ? "Обновить СВАЛКУ" : "Установить СВАЛКУ";
        Description.Text = error ?? (remove ? "Записи и настройки останутся на компьютере."
            : _update ? "Записи, настройки и синхронизация сохранятся."
            : "Дела, покупки, ссылки и мысли. Ярлык появится в меню «Пуск».");
        PlaceLabel.Text = @"%LOCALAPPDATA%\Programs\Svalka";
        ActionButton.Content = remove ? "УДАЛИТЬ" : _update ? "ОБНОВИТЬ" : "УСТАНОВИТЬ";
        ActionButton.IsEnabled = error == null;
        if (update) Loaded += async (_, _) => await Apply();
        Closed += (_, _) => Application.Current.Shutdown();
        PreviewKeyDown += (_, e) =>
        {
            if (e.Key == Key.Escape) Close();
        };
    }

    internal static bool TryStart(Application app, string[] args)
    {
        var remove = args is ["--remove-installed", _];
        var installer = Installation.IsInstallerBuild && !Installation.RunningFromInstall;
        if (!installer && !remove)
        {
            if (args is not ["--uninstall"] || !Installation.RunningFromInstall) return false;
            try
            {
                Installation.StartUninstaller();
                app.Shutdown();
            }
            catch (Exception error)
            {
                ShowSetup(remove: true, update: false, waitFor: 0, error.Message);
            }
            return true;
        }

        var update = installer && args is ["--apply-update", _];
        var waitFor = (remove || update) && int.TryParse(args[1], out var pid) && pid > 0 ? pid : 0;
        ShowSetup(remove, update, waitFor);
        return true;
    }

    private static void ShowSetup(bool remove, bool update, int waitFor, string? error = null)
    {
        ThemeManager.Init(AppSettings.Load().Theme);
        new SetupWindow(remove, update, waitFor, error).Show();
    }

    private async void ActionClick(object sender, RoutedEventArgs e)
    {
        if (!_done)
        {
            await Apply();
            return;
        }

        try
        {
            Installation.Start(Installation.ExePath);
            Close();
        }
        catch (Exception error) { Description.Text = "Не удалось открыть СВАЛКУ. " + error.Message; }
    }

    private async Task Apply()
    {
        if (_busy) return;
        SetBusy(true);
        Description.Text = _remove ? "Удаляем приложение…" : "Копируем файлы приложения…";
        try
        {
            if (_waitFor != 0) await WaitForPreviousProcess();
            using var mutex = new Mutex(false, SingleInstance.MutexName);
            if (!SingleInstance.TryAcquire(mutex))
                throw new InvalidOperationException("Выйдите из СВАЛКИ через её значок в трее и повторите.");
            try { await Task.Run(_remove ? Installation.Remove : Installation.Install); }
            finally { mutex.ReleaseMutex(); }

            _done = true;
            HeadingLabel.Text = _remove ? "СВАЛКА удалена" : _update ? "СВАЛКА обновлена" : "СВАЛКА установлена";
            Description.Text = _remove ? "Записи и настройки сохранены." : "Можно открыть приложение.";
            ActionButton.Content = "ОТКРЫТЬ СВАЛКУ";
            if (_remove) ActionButton.Visibility = Visibility.Collapsed;
            CancelButton.Content = "ЗАКРЫТЬ";
        }
        catch (Exception error) { Description.Text = error.Message; }
        finally { SetBusy(false); }
    }

    private async Task WaitForPreviousProcess()
    {
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(20));
        try
        {
            using var process = Process.GetProcessById(_waitFor);
            await process.WaitForExitAsync(timeout.Token);
        }
        catch (ArgumentException) { }
        catch (OperationCanceledException)
        {
            throw new InvalidOperationException("СВАЛКА ещё работает. Выйдите из приложения через трей и повторите.");
        }
    }

    private void SetBusy(bool value)
    {
        _busy = value;
        ActionButton.IsEnabled = !value;
        CancelButton.IsEnabled = !value;
    }

    protected override void OnClosing(CancelEventArgs e)
    {
        if (_busy) e.Cancel = true;
        base.OnClosing(e);
    }

    private void DragHeader(object sender, MouseButtonEventArgs e)
    {
        if (e.ButtonState == MouseButtonState.Pressed) DragMove();
    }

    private void CloseClick(object sender, RoutedEventArgs e) => Close();
}
