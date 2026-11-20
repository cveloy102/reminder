using System;
using System.Threading;
using System.Threading.Tasks;
using Svalka.Desktop.Services;
using static Svalka.Desktop.Views.DialogKit;

namespace Svalka.Desktop.Views;

public partial class MainWindow
{
    private WindowsRelease? _availableUpdate;

    internal async Task CheckForUpdatesOnStartup()
    {
        try
        {
            _availableUpdate = await ReleaseUpdates.CheckAsync(CancellationToken.None);
            if (_availableUpdate != null && IsVisible)
                ShowMessage($"Доступна СВАЛКА {_availableUpdate.Version.ToString(3)}. Откройте «Настройки → Обновления».");
        }
        catch (Exception error)
        {
            System.Diagnostics.Debug.WriteLine(error);
        }
    }

    private void ShowUpdates()
    {
        var cancellation = new CancellationTokenSource();
        var token = cancellation.Token;
        var status = Body("");
        var download = Accent("СКАЧАТЬ И УСТАНОВИТЬ");
        var check = Outline("ПРОВЕРИТЬ СЕЙЧАС");
        download.Click += async (_, _) => await InstallUpdate();
        check.Click += async (_, _) => await Check();

        void ShowResult()
        {
            download.IsEnabled = _availableUpdate != null;
            status.Text = _availableUpdate == null
                ? "Установлена актуальная версия."
                : $"Доступна версия {_availableUpdate.Version.ToString(3)}. СВАЛКА закроется после загрузки, чтобы применить обновление.";
        }

        async Task Check()
        {
            check.IsEnabled = download.IsEnabled = false;
            status.Text = "Проверяем релизы GitHub…";
            try
            {
                var result = await ReleaseUpdates.CheckAsync(token);
                if (token.IsCancellationRequested) return;
                _availableUpdate = result;
                ShowResult();
            }
            catch (Exception)
            {
                if (!token.IsCancellationRequested)
                    status.Text = "Не удалось проверить обновления. Проверьте интернет и повторите.";
            }
            finally { if (!token.IsCancellationRequested) check.IsEnabled = true; }
        }

        async Task InstallUpdate()
        {
            if (_availableUpdate is not { } release) return;
            check.IsEnabled = download.IsEnabled = false;
            status.Text = "Загружаем установщик…";
            var downloading = true;
            var progress = new Progress<int>(percent =>
            {
                if (downloading && !token.IsCancellationRequested)
                    status.Text = $"Загружаем установщик… {percent}%";
            });
            try
            {
                var path = await ReleaseUpdates.DownloadAsync(release, progress, token);
                if (token.IsCancellationRequested) return;
                Installation.Start(path, "--apply-update", Environment.ProcessId.ToString());
                ((App)System.Windows.Application.Current).ExitApp();
            }
            catch (Exception error)
            {
                if (!token.IsCancellationRequested)
                {
                    status.Text = "Не удалось установить обновление. " + error.Message;
                    check.IsEnabled = download.IsEnabled = true;
                }
            }
            finally { downloading = false; }
        }

        ShowDialog(Card(Scroll(Stack(
            Heading("Обновления"),
            Gap(8), Caption($"СВАЛКА ДЛЯ WINDOWS / {Installation.Version.ToString(3)}"),
            Gap(18), status,
            Gap(18), download,
            Gap(8), check,
            Gap(8), Link("НАЗАД В НАСТРОЙКИ ←", ShowSettings)))),
            () => { cancellation.Cancel(); cancellation.Dispose(); });
        if (_availableUpdate == null) _ = Check();
        else ShowResult();
    }
}
