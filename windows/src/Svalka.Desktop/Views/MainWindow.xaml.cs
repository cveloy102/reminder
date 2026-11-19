using System;
using System.ComponentModel;
using System.Diagnostics;
using System.Linq;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Threading;
using Svalka.Core;
using Svalka.Desktop.Services;
using Svalka.Desktop.Theme;
using Svalka.Desktop.ViewModels;

namespace Svalka.Desktop.Views;

public partial class MainWindow : Window
{
    private readonly MainViewModel _vm;
    private readonly AppSettings _settings;
    private readonly Notifier _notifier;
    private readonly DispatcherTimer _snackbarTimer = new() { Interval = TimeSpan.FromSeconds(2.6) };
    private Action? _onDialogClosed;
    private Point _dragStart;
    private TaskItemViewModel? _dragCandidate;

    private readonly SyncService _sync;

    public MainWindow(MainViewModel vm, AppSettings settings, Notifier notifier, SyncService sync)
    {
        InitializeComponent();
        _sync = sync;
        sync.Engine.StatusChanged += () => Dispatcher.BeginInvoke(UpdateSyncBadge);
        UpdateSyncBadge();
        _vm = vm;
        _settings = settings;
        _notifier = notifier;
        DataContext = vm;
        RestoreWindowBounds();
        BuildTabs();

        vm.Message += ShowMessage;
        vm.PropertyChanged += OnVmChanged;
        _snackbarTimer.Tick += (_, _) =>
        {
            _snackbarTimer.Stop();
            Snackbar.Visibility = Visibility.Collapsed;
        };
        SourceInitialized += (_, _) => ThemeManager.ApplyTitleBar(this);
        Loaded += (_, _) =>
        {
            Composer.Focus();
            if (vm.RecoveredFiles.Count > 0)
                ShowMessage("Файл данных был повреждён и отложен в сторону: " + string.Join(", ", vm.RecoveredFiles));
        };
        Activated += (_, _) => vm.PurgeTrash();
        PreviewKeyDown += OnWindowKeyDown;
        StateChanged += (_, _) => UpdateMaximizeState();
        Activated += (_, _) => TitleBar.Opacity = 1;
        Deactivated += (_, _) => TitleBar.Opacity = 0.7;
    }

    // ---- Own title bar ----

    private void Min_Click(object sender, RoutedEventArgs e) => WindowState = WindowState.Minimized;
    private void Max_Click(object sender, RoutedEventArgs e) =>
        WindowState = WindowState == WindowState.Maximized ? WindowState.Normal : WindowState.Maximized;
    private void Close_Click(object sender, RoutedEventArgs e) => Close();

    private void UpdateMaximizeState()
    {
        var maximized = WindowState == WindowState.Maximized;
        // A maximised chrome-less window spills over the screen edge by the resize border; pad it back.
        Root.Margin = maximized ? new Thickness(7) : new Thickness(0);
        MaxIcon.Data = Geometry.Parse(maximized ? "M2.5,0.5 H9.5 V7.5 M0.5,2.5 H7.5 V9.5 H0.5 Z" : "M0.5,0.5 H9.5 V9.5 H0.5 Z");
        MaxButton.ToolTip = maximized ? "Восстановить" : "Развернуть";
    }

    private void OnVmChanged(object? sender, PropertyChangedEventArgs e)
    {
        if (e.PropertyName == nameof(MainViewModel.Section))
        {
            BuildTabs();
            ListScroll.ScrollToTop();
        }
        if (e.PropertyName == nameof(MainViewModel.InTrash)) ListScroll.ScrollToTop();
    }

    // ---- Window size and position ----

    private void RestoreWindowBounds()
    {
        Width = Math.Max(MinWidth, _settings.WindowWidth);
        Height = Math.Max(MinHeight, _settings.WindowHeight);
        if (_settings.WindowLeft is { } left && _settings.WindowTop is { } top &&
            left >= SystemParameters.VirtualScreenLeft && top >= SystemParameters.VirtualScreenTop &&
            left + 100 <= SystemParameters.VirtualScreenLeft + SystemParameters.VirtualScreenWidth &&
            top + 100 <= SystemParameters.VirtualScreenTop + SystemParameters.VirtualScreenHeight)
        {
            WindowStartupLocation = WindowStartupLocation.Manual;
            Left = left;
            Top = top;
        }
        else WindowStartupLocation = WindowStartupLocation.CenterScreen;
    }

    public void SaveBounds()
    {
        if (WindowState != WindowState.Normal) return;
        _settings.WindowLeft = Left;
        _settings.WindowTop = Top;
        _settings.WindowWidth = Width;
        _settings.WindowHeight = Height;
        _settings.Save();
    }

    protected override void OnClosing(CancelEventArgs e)
    {
        SaveBounds();
        base.OnClosing(e);
    }

    // ---- Tabs ----

    private void BuildTabs()
    {
        Tabs.Children.Clear();
        foreach (var list in Enum.GetValues<TaskList>())
        {
            var selected = list == _vm.Section;
            var line = new Border { Height = 2 }.Res(Border.BackgroundProperty, selected ? "B.Accent" : "B.Div");
            var label = new TextBlock
            {
                Text = MainViewModel.NameOf(list), FontSize = 12, FontWeight = FontWeights.ExtraBold, Margin = new Thickness(0, 9, 0, 6)
            }.Res(TextBlock.ForegroundProperty, selected ? "B.Fg" : "B.Sec");
            var tab = new StackPanel { Margin = new Thickness(0, 0, 10, 0), Background = Brushes.Transparent, Cursor = Cursors.Hand };
            tab.Children.Add(line);
            tab.Children.Add(label);
            tab.ToolTip = $"Ctrl+{(int)list + 1}";
            var target = list;
            tab.MouseLeftButtonUp += (_, _) => SelectSection(target);
            Tabs.Children.Add(tab);
        }
    }

    private void SelectSection(TaskList list)
    {
        _vm.Section = list;
        _vm.InTrash = false;
        Composer.Focus();
    }

    // ---- Keyboard ----

    private void OnWindowKeyDown(object sender, KeyEventArgs e)
    {
        if (e.Key == Key.Escape)
        {
            if (Overlay.Visibility == Visibility.Visible) CloseDialog();
            else if (_vm.InTrash) _vm.InTrash = false;
            e.Handled = true;
            return;
        }
        if (Overlay.Visibility == Visibility.Visible) return;
        if (Keyboard.Modifiers == ModifierKeys.Control)
        {
            var index = e.Key switch { Key.D1 => 0, Key.D2 => 1, Key.D3 => 2, Key.D4 => 3, _ => -1 };
            if (index >= 0)
            {
                SelectSection((TaskList)index);
                e.Handled = true;
            }
            else if (e.Key == Key.N)
            {
                Composer.Focus();
                e.Handled = true;
            }
        }
    }

    private void Composer_PreviewKeyDown(object sender, KeyEventArgs e)
    {
        if (e.Key != Key.Enter) return;
        var ctrl = Keyboard.Modifiers.HasFlag(ModifierKeys.Control);
        var shift = Keyboard.Modifiers.HasFlag(ModifierKeys.Shift);
        // Thoughts are often several lines long: Enter adds a line, Ctrl+Enter saves.
        if (_vm.IsThoughts ? ctrl : !shift)
        {
            Submit();
            e.Handled = true;
        }
    }

    private string _lastComposerText = "";

    private void Composer_TextChanged(object sender, TextChangedEventArgs e)
    {
        var text = Composer.Text;
        var pasted = _vm.Section == TaskList.Links && text.Length - _lastComposerText.Length > 1 &&
                     TaskRules.NormalizeLinkUrl(text) != null;
        _lastComposerText = text;
        if (pasted) Dispatcher.BeginInvoke(() => AskLinkName(TaskRules.NormalizeLinkUrl(text)!));
    }

    private void Add_Click(object sender, MouseButtonEventArgs e) => Submit();

    private void Submit()
    {
        var link = _vm.Submit();
        if (link != null) AskLinkName(link);
        else Composer.Focus();
    }

    // ---- Top bar and list header ----

    private void TrashToggle_Click(object sender, RoutedEventArgs e) => _vm.InTrash = !_vm.InTrash;
    private void ClearPurchased_Click(object sender, RoutedEventArgs e) => _vm.ClearCompleted(TaskList.Shopping);
    private void Undo_Click(object sender, RoutedEventArgs e) => _vm.Undo();
    private void Settings_Click(object sender, RoutedEventArgs e) => ShowSettings();
    private void Data_Click(object sender, RoutedEventArgs e) => ShowData();
    private void Sync_Click(object sender, RoutedEventArgs e) => ShowSync();

    private void UpdateSyncBadge()
    {
        var engine = _sync.Engine;
        var (text, brush) = !engine.SignedIn ? ("СИНХР", "B.Sec")
            : engine.Status == Svalka.Core.Sync.SyncStatus.Error ? ("СИНХР !", "B.Accent")
            : engine.Status == Svalka.Core.Sync.SyncStatus.Syncing ? ("СИНХР ⟳", "B.Fg")
            : ("СИНХР ✓", "B.Fg");
        SyncBadge.Text = text;
        SyncBadge.SetResourceReference(TextBlock.ForegroundProperty, brush);
        SyncButton.ToolTip = !engine.SignedIn ? "Синхронизация выключена"
            : engine.LastError ?? (engine.LiveConnected ? "Синхронизация работает" : "Синхронизация: нет постоянной связи с сервером");
        _syncDialogRefresh?.Invoke();
    }

    // ---- Rows ----

    private static TaskItemViewModel? ItemOf(object sender) => (sender as FrameworkElement)?.DataContext as TaskItemViewModel;

    private void Row_Toggle(object sender, MouseButtonEventArgs e)
    {
        if (ItemOf(sender) is { InTrash: false } item) _vm.Toggle(item.Task);
        e.Handled = true;
    }

    private void Row_Open(object sender, MouseButtonEventArgs e)
    {
        if (ItemOf(sender) is { } item) ShowEditor(item.Task, item.InTrash);
    }

    private void Row_Quick(object sender, RoutedEventArgs e)
    {
        if (ItemOf(sender) is not { } item) return;
        if (item.InTrash) _vm.Restore(item.Task);
        else _vm.MoveToTrash(item.Task);
    }

    private void Row_RightClick(object sender, MouseButtonEventArgs e)
    {
        if (ItemOf(sender) is not { } item) return;
        var menu = new ContextMenu();
        void Add(string header, Action action, bool enabled = true)
        {
            var mi = new MenuItem { Header = header, IsEnabled = enabled };
            mi.Click += (_, _) => action();
            menu.Items.Add(mi);
        }
        var task = item.Task;
        if (item.InTrash)
        {
            Add("Восстановить", () => _vm.Restore(task));
            Add("Удалить навсегда…", () => ConfirmDeleteForever(task));
        }
        else
        {
            Add("Открыть", () => ShowEditor(task, false));
            if (item.IsCheckable) Add(task.Completed ? "Вернуть в список" : "Отметить выполненным", () => _vm.Toggle(task));
            Add(item.PinMenuText, () => _vm.TogglePin(task), item.CanPin);
            if (item.CanRemind) Add(task.ReminderAt == null ? "Напомнить…" : "Изменить напоминание…", () => ShowReminderPicker(task));
            if (item.HasUrl) Add("Открыть ссылку в браузере", () => OpenUrl(task.Url!));
            Add("Скопировать текст", () => CopyText(item.HasUrl ? task.Url! : task.Title));
            menu.Items.Add(new Separator());
            Add("В корзину", () => _vm.MoveToTrash(task));
        }
        menu.PlacementTarget = (UIElement)sender;
        menu.IsOpen = true;
        e.Handled = true;
    }

    // Drag & drop to reorder, started from the "⋮" handle.
    private void Handle_MouseDown(object sender, MouseButtonEventArgs e)
    {
        _dragCandidate = ItemOf(sender) is { InTrash: false } item ? item : null;
        _dragStart = e.GetPosition(this);
    }

    private void Handle_MouseMove(object sender, MouseEventArgs e)
    {
        if (_dragCandidate == null || e.LeftButton != MouseButtonState.Pressed) return;
        var delta = e.GetPosition(this) - _dragStart;
        if (Math.Abs(delta.Y) < SystemParameters.MinimumVerticalDragDistance) return;
        var source = _dragCandidate;
        _dragCandidate = null;
        DragDrop.DoDragDrop((DependencyObject)sender, new DataObject("svalka-task", source.Id), DragDropEffects.Move);
        foreach (var row in _vm.Items) row.IsDropTarget = false;
    }

    private bool CanDropOn(DragEventArgs e, out string fromId, out TaskItemViewModel? target)
    {
        fromId = e.Data.GetData("svalka-task") as string ?? "";
        target = ItemOf(e.Source) ?? (e.OriginalSource as FrameworkElement)?.DataContext as TaskItemViewModel;
        if (target == null || target.InTrash || fromId.Length == 0 || fromId == target.Id) return false;
        var source = _vm.Find(fromId);
        return source != null && source.List == target.Task.List && source.Completed == target.Completed;
    }

    private void Row_DragOver(object sender, DragEventArgs e)
    {
        var ok = CanDropOn(e, out _, out var target);
        e.Effects = ok ? DragDropEffects.Move : DragDropEffects.None;
        foreach (var row in _vm.Items) row.IsDropTarget = ok && row == target;
        AutoScroll(e);
        e.Handled = true;
    }

    private void Row_DragLeave(object sender, DragEventArgs e)
    {
        if (ItemOf(sender) is { } item) item.IsDropTarget = false;
    }

    private void Row_Drop(object sender, DragEventArgs e)
    {
        if (CanDropOn(e, out var fromId, out var target)) _vm.Reorder(fromId, target!.Id);
        e.Handled = true;
    }

    private void AutoScroll(DragEventArgs e)
    {
        var y = e.GetPosition(ListScroll).Y;
        if (y < 30) ListScroll.ScrollToVerticalOffset(ListScroll.VerticalOffset - 12);
        else if (y > ListScroll.ActualHeight - 30) ListScroll.ScrollToVerticalOffset(ListScroll.VerticalOffset + 12);
    }

    // ---- Helpers ----

    public void ShowMessage(string text)
    {
        SnackbarText.Text = text;
        Snackbar.Visibility = Visibility.Visible;
        _snackbarTimer.Stop();
        _snackbarTimer.Start();
    }

    private void OpenUrl(string url)
    {
        try { Process.Start(new ProcessStartInfo(url) { UseShellExecute = true }); }
        catch (Exception) { ShowMessage("Не удалось открыть ссылку"); }
    }

    private void CopyText(string text)
    {
        try
        {
            Clipboard.SetText(text);
            ShowMessage("Скопировано");
        }
        catch (Exception) { ShowMessage("Не удалось скопировать"); }
    }

    // ---- Dialog host ----

    public void ShowDialog(FrameworkElement content, Action? onClosed = null)
    {
        _onDialogClosed?.Invoke();
        _onDialogClosed = onClosed;
        DialogHost.Content = content;
        Overlay.Visibility = Visibility.Visible;
        content.MaxHeight = Math.Max(300, ActualHeight - 80);
        Dispatcher.BeginInvoke(DispatcherPriority.Input, () =>
        {
            var first = FindFirst<TextBox>(content);
            if (first != null)
            {
                first.Focus();
                first.CaretIndex = first.Text.Length;
            }
            else content.MoveFocus(new TraversalRequest(FocusNavigationDirection.First));
        });
    }

    public void CloseDialog()
    {
        Overlay.Visibility = Visibility.Collapsed;
        DialogHost.Content = null;
        var closed = _onDialogClosed;
        _onDialogClosed = null;
        closed?.Invoke();
        Composer.Focus();
    }

    private void Scrim_MouseDown(object sender, MouseButtonEventArgs e) => CloseDialog();

    private static T? FindFirst<T>(DependencyObject root) where T : DependencyObject
    {
        if (root is T match) return match;
        foreach (var child in LogicalTreeHelper.GetChildren(root).OfType<DependencyObject>())
        {
            var found = FindFirst<T>(child);
            if (found != null) return found;
        }
        return null;
    }
}
