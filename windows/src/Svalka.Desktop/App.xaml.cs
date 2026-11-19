using System;
using System.Linq;
using System.Windows;
using System.Windows.Threading;
using Svalka.Core;
using Svalka.Desktop.Services;
using Svalka.Desktop.Theme;
using Svalka.Desktop.ViewModels;
using Svalka.Desktop.Views;
using Forms = System.Windows.Forms;

namespace Svalka.Desktop;

public partial class App : Application
{
    private SingleInstance? _instance;
    private Forms.NotifyIcon? _tray;
    private MainWindow? _window;
    private AppSettings _settings = new();
    private SyncService? _sync;
    private bool _exiting;

    protected override void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);
        _instance = new SingleInstance();
        if (!_instance.IsFirst)
        {
            _instance.SignalFirst();
            Shutdown();
            return;
        }

        DispatcherUnhandledException += (_, args) =>
        {
            MessageBox.Show("Что-то пошло не так: " + args.Exception.Message, "СВАЛКА", MessageBoxButton.OK, MessageBoxImage.Warning);
            args.Handled = true;
        };

        _settings = AppSettings.Load();
        ThemeManager.Init(_settings.Theme);
        Autostart.Refresh();

        var notifier = new Notifier();
        var cloud = new CloudBackup(_settings);
        var vm = new MainViewModel(new TaskStore(AppPaths.Data), _settings, notifier, cloud);
        _sync = new SyncService(vm, Dispatcher);
        _window = new MainWindow(vm, _settings, notifier, _sync);
        _window.Closing += (_, args) =>
        {
            if (_exiting) return;
            args.Cancel = true;
            HideToTray();
        };

        notifier.Activated += (action, id, minutes) => Dispatcher.Invoke(() =>
        {
            if (action == Notifier.ActionOpen)
            {
                if (id.Length > 0) vm.ShowList(id);
                ShowWindow();
            }
            else vm.HandleNotificationAction(action, id, minutes);
        });

        _instance.ListenForShow(() => Dispatcher.Invoke(ShowWindow));
        CreateTray();
        vm.Start();
        _sync.Start();

        var startHidden = e.Args.Contains("--tray") || Notifier.StartedFromToast;
        if (!startHidden) ShowWindow();
    }

    private void CreateTray()
    {
        var menu = new Forms.ContextMenuStrip();
        menu.Items.Add("Открыть СВАЛКУ", null, (_, _) => ShowWindow());
        menu.Items.Add(new Forms.ToolStripSeparator());
        menu.Items.Add("Выйти", null, (_, _) => ExitApp());
        _tray = new Forms.NotifyIcon
        {
            Text = "СВАЛКА",
            Icon = LoadIcon(),
            ContextMenuStrip = menu,
            Visible = true
        };
        _tray.MouseClick += (_, args) =>
        {
            if (args.Button == Forms.MouseButtons.Left) ShowWindow();
        };
    }

    private static System.Drawing.Icon LoadIcon()
    {
        var stream = GetResourceStream(new Uri("pack://application:,,,/Assets/svalka.ico"))!.Stream;
        return new System.Drawing.Icon(stream, Forms.SystemInformation.SmallIconSize);
    }

    public void ShowWindow()
    {
        if (_window == null) return;
        if (!_window.IsVisible) _window.Show();
        if (_window.WindowState == WindowState.Minimized) _window.WindowState = WindowState.Normal;
        _window.Activate();
        _window.Topmost = true;
        _window.Topmost = false;
        _window.Focus();
    }

    private void HideToTray()
    {
        _window?.Hide();
        if (_settings.TrayHintShown || _tray == null) return;
        _settings.TrayHintShown = true;
        _settings.Save();
        _tray.ShowBalloonTip(4000, "СВАЛКА работает в трее",
            "Напоминания будут приходить и при закрытом окне. Выйти можно через значок в трее.", Forms.ToolTipIcon.None);
    }

    public void ExitApp()
    {
        _exiting = true;
        _window?.SaveBounds();
        _window?.Close();
        Shutdown();
    }

    protected override void OnExit(ExitEventArgs e)
    {
        if (_tray != null)
        {
            _tray.Visible = false;
            _tray.Dispose();
        }
        _settings.Save();
        _sync?.Dispose();
        _instance?.Dispose();
        base.OnExit(e);
    }
}
