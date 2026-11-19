using System;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Text;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Input;
using Microsoft.Win32;
using Svalka.Core;
using Svalka.Desktop.Services;
using Svalka.Desktop.Theme;
using static Svalka.Desktop.Views.DialogKit;

namespace Svalka.Desktop.Views;

/// <summary>All dialogs of the main window: editor, reminder picker, link name, settings, data and backups.</summary>
public partial class MainWindow
{
    private static readonly CultureInfo Ru = CultureInfo.GetCultureInfo("ru-RU");

    // ---- Link name ----

    private void AskLinkName(string url)
    {
        if (Overlay.Visibility == Visibility.Visible) return;
        var name = Field("");
        var hint = Caption("НАЗВАНИЕ (НЕОБЯЗАТЕЛЬНО)");
        void Save()
        {
            _vm.AddLink(url, name.Text);
            CloseDialog();
        }
        name.KeyDown += (_, e) => { if (e.Key == Key.Enter) Save(); };
        var url_ = Body(url, "B.Rem", 12);
        url_.FontFamily = (System.Windows.Media.FontFamily)FindResource("Mono");
        ShowDialog(Card(Stack(
            Heading("КАК НАЗВАТЬ ССЫЛКУ?", 22),
            Gap(8), Body("Можно оставить без названия — покажем адрес сайта."),
            Gap(16), url_,
            Gap(16), hint, Gap(8), name,
            Gap(20), Accent("СОХРАНИТЬ ССЫЛКУ", Save),
            Gap(4), Link("ОТМЕНА", CloseDialog))));
    }

    // ---- Editor ----

    private void ShowEditor(ReminderTask selected, bool inTrash)
    {
        var isThought = selected.List == TaskList.Thoughts;
        var caption = inTrash ? "ИЗ КОРЗИНЫ"
            : isThought ? "ТВОЯ МЫСЛЬ · " + TaskRules.FormatStamp(selected.CreatedAt)
            : selected.List switch { TaskList.Links => "ТВОЯ ССЫЛКА", TaskList.Shopping => "ТВОЯ ПОКУПКА", _ => "ТВОЯ ЗАМЕТКА" };
        var heading = inTrash ? "Удалённая запись."
            : isThought ? "Редактировать мысль."
            : selected.List switch { TaskList.Links => "Редактировать ссылку.", TaskList.Shopping => "Редактировать покупку.", _ => "Редактировать дело." };
        var stack = Stack(Caption(caption, "B.Rem"), Heading(heading), Gap(20));

        if (inTrash)
        {
            stack.Children.Add(Body(selected.Title, "B.Fg", 17));
            stack.Children.Add(Gap(24));
            stack.Children.Add(Accent("ВОССТАНОВИТЬ", () => { _vm.Restore(selected, offerUndo: false); CloseDialog(); }));
            stack.Children.Add(Gap(8));
            stack.Children.Add(Outline("УДАЛИТЬ НАВСЕГДА", () => ConfirmDeleteForever(selected)));
            ShowDialog(Card(Scroll(stack)));
            return;
        }

        var current = _vm.Find(selected.Id) ?? selected;
        stack.Children.Add(Caption(isThought ? "ТЕКСТ МЫСЛИ" : selected.List switch
        {
            TaskList.Links => "НАЗВАНИЕ ССЫЛКИ", TaskList.Shopping => "ТЕКСТ ПОКУПКИ", _ => "ТЕКСТ ДЕЛА"
        }));
        stack.Children.Add(Gap(8));
        var title = Field(current.Title, multiline: selected.List != TaskList.Links, minHeight: isThought ? 150 : selected.List == TaskList.Links ? 0 : 90);
        if (!isThought) title.FontWeight = FontWeights.SemiBold;
        stack.Children.Add(title);
        stack.Children.Add(Gap(18));

        TextBox? urlBox = null;
        string CleanTitle() => TaskRules.NormalizeTitle(title.Text) is { Length: > 0 } t ? t : current.Title;

        if (isThought)
        {
            var row = new DockPanel();
            var copy = Link("СКОПИРОВАТЬ ⧉", () => CopyText(title.Text.Trim()), "B.Rem");
            DockPanel.SetDock(copy, Dock.Right);
            row.Children.Add(copy);
            var pin = Link(current.Pinned ? "● ЗАКРЕПЛЕНО · ОТКРЕПИТЬ" : "ЗАКРЕПИТЬ НАВЕРХУ", () =>
            {
                _vm.Save(current with { Title = CleanTitle(), Pinned = !current.Pinned });
                CloseDialog();
            }, current.Pinned ? "B.Rem" : "B.Sec");
            pin.HorizontalAlignment = HorizontalAlignment.Left;
            row.Children.Add(pin);
            stack.Children.Add(row);
        }
        else if (selected.List == TaskList.Links)
        {
            stack.Children.Add(Caption("АДРЕС ССЫЛКИ"));
            stack.Children.Add(Gap(8));
            urlBox = Field(current.Url ?? "", mono: true);
            stack.Children.Add(urlBox);
            var open = Link("ОТКРЫТЬ ССЫЛКУ ↗", () => OpenUrl(TaskRules.NormalizeLinkUrl(urlBox.Text) ?? current.Url ?? ""), "B.Rem", 12);
            open.HorizontalAlignment = HorizontalAlignment.Right;
            stack.Children.Add(open);
        }
        else
        {
            stack.Children.Add(Caption("НАПОМИНАНИЕ"));
            stack.Children.Add(Gap(8));
            var reminderRow = new Border
            {
                CornerRadius = new CornerRadius(10), BorderThickness = new Thickness(1), Padding = new Thickness(14),
                Cursor = Cursors.Hand
            }.Res(Border.BackgroundProperty, "B.Bg").Res(Border.BorderBrushProperty, "B.Div");
            var dock = new DockPanel();
            var choose = Caption("ВЫБРАТЬ", "B.Sec", 10);
            DockPanel.SetDock(choose, Dock.Right);
            choose.VerticalAlignment = VerticalAlignment.Center;
            dock.Children.Add(choose);
            var arrow = new TextBlock { Text = "↗  ", FontSize = 18, FontWeight = FontWeights.Bold }.Res(TextBlock.ForegroundProperty, "B.Rem");
            dock.Children.Add(arrow);
            dock.Children.Add(Body(current.ReminderAt == null ? "Назначить дату и время" : current.ReminderLabel(), "B.Fg", 15));
            reminderRow.Child = dock;
            reminderRow.MouseLeftButtonUp += (_, _) => ShowReminderPicker(current with { Title = CleanTitle() });
            stack.Children.Add(reminderRow);
            if (current.ReminderAt != null)
            {
                var remove = Link("УБРАТЬ НАПОМИНАНИЕ", () =>
                {
                    _vm.RemoveReminder(current with { Title = CleanTitle() });
                    CloseDialog();
                });
                remove.HorizontalAlignment = HorizontalAlignment.Right;
                stack.Children.Add(remove);
            }
        }

        void Save()
        {
            if (selected.List == TaskList.Links)
            {
                var normalized = TaskRules.NormalizeLinkUrl(urlBox!.Text);
                if (normalized == null)
                {
                    ShowMessage("Проверь адрес ссылки");
                    return;
                }
                var name = title.Text.Trim();
                _vm.Save(current with { Title = name.Length > 0 ? name : TaskRules.LinkDisplayName(normalized), Url = normalized });
            }
            else if (!string.IsNullOrWhiteSpace(title.Text)) _vm.Save(current with { Title = title.Text });
            CloseDialog();
        }

        title.PreviewKeyDown += (_, e) =>
        {
            var ctrl = Keyboard.Modifiers.HasFlag(ModifierKeys.Control);
            if (e.Key == Key.Enter && (ctrl || selected.List == TaskList.Links))
            {
                Save();
                e.Handled = true;
            }
        };

        stack.Children.Add(Divider());
        stack.Children.Add(Accent("СОХРАНИТЬ ИЗМЕНЕНИЯ", Save));
        var trash = Link("В КОРЗИНУ", () => { _vm.MoveToTrash(current); CloseDialog(); }, "B.Sec", 12);
        trash.HorizontalAlignment = HorizontalAlignment.Center;
        stack.Children.Add(Gap(4));
        stack.Children.Add(trash);
        ShowDialog(Card(Scroll(stack)));
    }

    private void ConfirmDeleteForever(ReminderTask task) => Confirm("Удалить навсегда?", "Эту запись уже нельзя будет восстановить.",
        "УДАЛИТЬ", () => _vm.DeletePermanently(task));

    private void Confirm(string title, string text, string confirm, Action action)
    {
        var buttons = new UniformGrid { Rows = 1 };
        var cancel = Outline("ОТМЕНА", CloseDialog);
        cancel.Margin = new Thickness(0, 0, 6, 0);
        var ok = Accent(confirm, () => { CloseDialog(); action(); });
        ok.Margin = new Thickness(6, 0, 0, 0);
        buttons.Children.Add(cancel);
        buttons.Children.Add(ok);
        ShowDialog(Card(Stack(Heading(title, 22), Gap(10), Body(text), Gap(22), buttons)));
    }

    // ---- Reminder picker (calendar, 24h time, repeat) ----

    private void ShowReminderPicker(ReminderTask task)
    {
        var initial = task.ReminderAt is { } at ? Clock.ToLocal(at) : DateTime.Now.AddHours(1);
        var chosenDay = initial.Date;
        var shownMonth = new DateTime(initial.Year, initial.Month, 1);
        var repeat = task.Repeat;

        var monthLabel = new TextBlock { FontSize = 16, FontWeight = FontWeights.Black, VerticalAlignment = VerticalAlignment.Center }
            .Res(TextBlock.ForegroundProperty, "B.Fg");
        var grid = new UniformGrid { Columns = 7 };
        var error = Body("", "B.Rem", 12);
        error.FontWeight = FontWeights.Bold;
        error.Visibility = Visibility.Collapsed;

        Border Arrow(string symbol, string tip, int delta)
        {
            var b = new Border
            {
                Width = 36, Height = 36, CornerRadius = new CornerRadius(7), BorderThickness = new Thickness(1),
                Cursor = Cursors.Hand, ToolTip = tip, Margin = new Thickness(8, 0, 0, 0),
                Child = new TextBlock { Text = symbol, FontSize = 22, HorizontalAlignment = HorizontalAlignment.Center, Margin = new Thickness(0, -4, 0, 0) }
                    .Res(TextBlock.ForegroundProperty, "B.Fg")
            }.Res(Border.BackgroundProperty, "B.Bg").Res(Border.BorderBrushProperty, "B.Div");
            b.MouseLeftButtonUp += (_, _) => { shownMonth = shownMonth.AddMonths(delta); Render(); };
            return b;
        }

        void Render()
        {
            monthLabel.Text = Ru.DateTimeFormat.GetMonthName(shownMonth.Month).ToUpper(Ru) + " " + shownMonth.Year;
            grid.Children.Clear();
            foreach (var name in new[] { "ПН", "ВТ", "СР", "ЧТ", "ПТ", "СБ", "ВС" })
                grid.Children.Add(new TextBlock { Text = name, FontSize = 10, FontWeight = FontWeights.Bold, HorizontalAlignment = HorizontalAlignment.Center, Margin = new Thickness(0, 6, 0, 6) }
                    .Res(TextBlock.ForegroundProperty, "B.Sec"));
            var offset = ((int)shownMonth.DayOfWeek + 6) % 7;
            var days = DateTime.DaysInMonth(shownMonth.Year, shownMonth.Month);
            for (var i = 0; i < offset; i++) grid.Children.Add(new Border());
            for (var d = 1; d <= days; d++)
            {
                var date = new DateTime(shownMonth.Year, shownMonth.Month, d);
                var selected = date == chosenDay;
                var past = date < DateTime.Today;
                var cell = new Border
                {
                    Height = 38, Margin = new Thickness(2), CornerRadius = new CornerRadius(7), Cursor = Cursors.Hand,
                    Child = new TextBlock
                    {
                        Text = d.ToString(), FontSize = 14, HorizontalAlignment = HorizontalAlignment.Center,
                        VerticalAlignment = VerticalAlignment.Center,
                        FontWeight = selected ? FontWeights.Black : FontWeights.Medium, Opacity = past && !selected ? 0.45 : 1
                    }.Res(TextBlock.ForegroundProperty, selected ? "B.AccentText" : "B.Fg")
                }.Res(Border.BackgroundProperty, selected ? "B.Accent" : "B.Sheet");
                cell.MouseLeftButtonUp += (_, _) => { chosenDay = date; error.Visibility = Visibility.Collapsed; Render(); };
                grid.Children.Add(cell);
            }
        }

        var header = new DockPanel();
        var next = Arrow("›", "Следующий месяц", 1);
        var prev = Arrow("‹", "Предыдущий месяц", -1);
        DockPanel.SetDock(next, Dock.Right);
        DockPanel.SetDock(prev, Dock.Right);
        header.Children.Add(next);
        header.Children.Add(prev);
        header.Children.Add(monthLabel);

        TextBox TimeBox(int value)
        {
            var box = Field(value.ToString("00"), mono: true);
            box.FontSize = 24;
            box.FontWeight = FontWeights.Black;
            box.TextAlignment = TextAlignment.Center;
            box.MaxLength = 2;
            box.PreviewTextInput += (_, e) => e.Handled = !e.Text.All(char.IsDigit);
            box.GotKeyboardFocus += (_, _) => box.SelectAll();
            box.TextChanged += (_, _) => error.Visibility = Visibility.Collapsed;
            return box;
        }
        var hour = TimeBox(initial.Hour);
        var minute = TimeBox(initial.Minute);
        var time = new Grid();
        time.ColumnDefinitions.Add(new ColumnDefinition());
        time.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        time.ColumnDefinitions.Add(new ColumnDefinition());
        var colon = new TextBlock { Text = ":", FontSize = 26, FontWeight = FontWeights.Black, Margin = new Thickness(10, 0, 10, 0), VerticalAlignment = VerticalAlignment.Center }
            .Res(TextBlock.ForegroundProperty, "B.Fg");
        Grid.SetColumn(colon, 1);
        Grid.SetColumn(minute, 2);
        time.Children.Add(hour);
        time.Children.Add(colon);
        time.Children.Add(minute);

        var rules = new[] { RepeatRule.None, RepeatRule.Daily, RepeatRule.Weekly, RepeatRule.Monthly };
        var repeatControl = Segmented(new[] { "НЕТ", "ДЕНЬ", "НЕДЕЛЯ", "МЕСЯЦ" }, Array.IndexOf(rules, repeat), i => repeat = rules[i]);

        void Confirm()
        {
            if (!int.TryParse(hour.Text, out var h) || h is < 0 or > 23 || !int.TryParse(minute.Text, out var m) || m is < 0 or > 59)
            {
                error.Text = "Укажи время от 00:00 до 23:59";
                error.Visibility = Visibility.Visible;
                return;
            }
            var at = Clock.FromLocal(chosenDay.AddHours(h).AddMinutes(m));
            if (!_vm.SetReminder(task, at, repeat))
            {
                error.Text = "Выбери дату и время в будущем";
                error.Visibility = Visibility.Visible;
                return;
            }
            CloseDialog();
        }
        hour.KeyDown += (_, e) => { if (e.Key == Key.Enter) Confirm(); };
        minute.KeyDown += (_, e) => { if (e.Key == Key.Enter) Confirm(); };

        Render();
        ShowDialog(Card(Scroll(Stack(
            Caption("НАПОМИНАНИЕ", "B.Rem"),
            Heading("Когда напомнить?", 26),
            Gap(18), header, Gap(8), grid,
            Gap(16), Caption("ВРЕМЯ · 24 ЧАСА"), Gap(8), time,
            Gap(16), Caption("ПОВТОР"), Gap(8), repeatControl,
            Gap(8), error,
            Gap(18), Accent("ПОСТАВИТЬ НАПОМИНАНИЕ", Confirm),
            Gap(4), Link("ОТМЕНА", CloseDialog)))));
    }

    // ---- Settings ----

    private void ShowSettings()
    {
        var modes = new[] { ThemeMode.System, ThemeMode.Light, ThemeMode.Dark };
        var theme = Segmented(new[] { "СИСТЕМА", "СВЕТЛАЯ", "ТЁМНАЯ" }, Array.IndexOf(modes, _settings.Theme), i =>
        {
            _settings.Theme = modes[i];
            _settings.Save();
            ThemeManager.SetMode(modes[i]);
            BuildTabs();
        });

        var autostart = Autostart.IsEnabled;
        var autostartControl = Segmented(new[] { "НЕТ", "ДА, В ТРЕЙ" }, autostart ? 1 : 0, i =>
        {
            try { Autostart.Set(i == 1); }
            catch (Exception error) { ShowMessage("Не удалось изменить автозапуск: " + error.Message); }
        });

        var version = typeof(MainWindow).Assembly.GetName().Version;
        var title = new StackPanel { Orientation = Orientation.Horizontal };
        title.Children.Add(Heading("Настройки", 28));
        var underscore = Heading("_", 28).Res(TextBlock.ForegroundProperty, "B.Accent");
        title.Children.Add(underscore);

        ShowDialog(Card(Scroll(Stack(
            Caption("[ НАСТРОЙКИ / 01 ]", "B.Rem"),
            title,
            Gap(18), Caption("ТЕМА"), Gap(8), theme,
            Gap(18), Caption("ЗАПУСКАТЬ ВМЕСТЕ С WINDOWS"), Gap(8), autostartControl,
            Gap(6), Body("Окно закрывается в трей, напоминания приходят и без открытого окна.", "B.Sec", 12),
            Gap(18), Caption("УВЕДОМЛЕНИЯ"), Gap(8),
            Outline("ПОКАЗАТЬ ПРОБНОЕ УВЕДОМЛЕНИЕ", () => _notifier.ShowTest()),
            Gap(18), Body($"СВАЛКА для Windows · версия {version?.ToString(3)}\nДанные: {AppPaths.Data}", "B.Sec", 11),
            Gap(8), Link("НАЗАД К СПИСКУ ←", CloseDialog)))));
    }

    // ---- Data: Google Drive autocopy, export, import ----

    private void ShowData()
    {
        var cloud = _vm.Cloud;
        var status = Body("", "B.Sec", 13);
        var stack = Stack(
            Heading("ТВОИ ДАННЫЕ", 24),
            Gap(8), Body("Копия сохраняет дела, покупки, ссылки и мысли. Формат тот же, что у приложения на телефоне."),
            Gap(18), Caption("GOOGLE ДИСК · АВТОКОПИЯ", "B.Fg", 12), Gap(6), status, Gap(10));

        void UpdateStatus()
        {
            status.Text = cloud.Error != null ? "Ошибка: " + cloud.Error
                : cloud.Saving ? "Сохраняю копию…"
                : cloud.Connected && cloud.LastSaveAt > 0
                    ? $"Автокопия включена · сохранено {Clock.ToLocal(cloud.LastSaveAt).ToString("d MMM, HH:mm", Ru)}\n{cloud.FilePath}"
                : cloud.Connected ? "Автокопия включена. Изменения сохраняются после редактирования.\n" + cloud.FilePath
                : "Выбери файл в папке Google Диска на компьютере. Потом копия будет обновляться сама, а Google Диск загрузит её в облако.";
        }
        UpdateStatus();
        Action onState = () => Dispatcher.Invoke(UpdateStatus);
        cloud.StateChanged += onState;

        if (!cloud.Connected)
        {
            stack.Children.Add(Accent("ВЫБРАТЬ ФАЙЛ НА GOOGLE ДИСКЕ", () => ChooseCloudFile()));
        }
        else
        {
            stack.Children.Add(Accent("СОХРАНИТЬ СЕЙЧАС", _vm.SaveCloudNow));
            var row = new DockPanel { Margin = new Thickness(0, 4, 0, 0) };
            var off = Link("ОТКЛЮЧИТЬ", () => { cloud.Disconnect(); CloseDialog(); ShowMessage("Автокопия отключена"); });
            DockPanel.SetDock(off, Dock.Right);
            row.Children.Add(off);
            var change = Link("СМЕНИТЬ ФАЙЛ", () => ChooseCloudFile());
            change.HorizontalAlignment = HorizontalAlignment.Left;
            row.Children.Add(change);
            stack.Children.Add(row);
        }
        stack.Children.Add(Gap(6));
        stack.Children.Add(Body("Не выбирай файл, в который пишет телефон: две программы будут затирать копии друг друга. " +
                                "Для переноса с телефона есть кнопка ниже.", "B.Sec", 11));

        stack.Children.Add(Divider());
        stack.Children.Add(Caption("С ТЕЛЕФОНА", "B.Fg", 12));
        stack.Children.Add(Gap(6));
        stack.Children.Add(Body("Открой файл автокопии, который сохраняет СВАЛКА на телефоне, и добавь записи сюда.", "B.Sec", 13));
        stack.Children.Add(Gap(10));
        stack.Children.Add(Outline("ВЗЯТЬ КОПИЮ С ТЕЛЕФОНА", () => ChooseImport(GoogleDriveFolder.Find())));

        stack.Children.Add(Divider());
        stack.Children.Add(Caption("ОТДЕЛЬНЫЙ ФАЙЛ", "B.Fg", 12));
        stack.Children.Add(Gap(10));
        stack.Children.Add(Accent("СОХРАНИТЬ КОПИЮ", Export));
        stack.Children.Add(Gap(8));
        stack.Children.Add(Outline("ВОССТАНОВИТЬ ИЗ ФАЙЛА", () => ChooseImport(null)));
        stack.Children.Add(Gap(8));
        var folder = Link("ОТКРЫТЬ ПАПКУ С ДАННЫМИ", () => OpenUrl(AppPaths.Data));
        folder.HorizontalAlignment = HorizontalAlignment.Center;
        stack.Children.Add(folder);

        ShowDialog(Card(Scroll(stack)), () => cloud.StateChanged -= onState);
    }

    private void ChooseCloudFile()
    {
        var drive = GoogleDriveFolder.Find();
        var dialog = new SaveFileDialog
        {
            Title = "Файл автокопии на Google Диске",
            FileName = "svalka-windows.json",
            Filter = "Копия СВАЛКИ (*.json)|*.json",
            InitialDirectory = drive ?? Environment.GetFolderPath(Environment.SpecialFolder.MyDocuments),
            OverwritePrompt = false
        };
        if (dialog.ShowDialog(this) != true) return;
        var path = dialog.FileName;
        if (File.Exists(path) && !IsOwnOrEmptyCopy(path))
        {
            ShowMessage("В этом файле уже есть другие данные. Выбери новый файл.");
            return;
        }
        _vm.Cloud.Connect(path);
        CloseDialog();
        _vm.SaveCloudNow();
        if (drive == null || !GoogleDriveFolder.IsInside(path))
            ShowMessage("Файл не в папке Google Диска: копия останется только на компьютере");
    }

    /// <summary>True if the file is empty or an existing СВАЛКА copy (so it is safe to overwrite).</summary>
    private static bool IsOwnOrEmptyCopy(string path)
    {
        try
        {
            var text = File.ReadAllText(path, Encoding.UTF8);
            if (string.IsNullOrWhiteSpace(text)) return true;
            TaskBackupCodec.Decode(text);
            return true;
        }
        catch (Exception) { return false; }
    }

    private void Export()
    {
        var dialog = new SaveFileDialog
        {
            Title = "Сохранить копию",
            FileName = $"svalka-{DateTime.Now:yyyy-MM-dd_HH-mm}.json",
            Filter = "Копия СВАЛКИ (*.json)|*.json"
        };
        if (dialog.ShowDialog(this) != true) return;
        try
        {
            var data = TaskBackupCodec.Encode(_vm.Tasks);
            if (Encoding.UTF8.GetByteCount(data) > TaskBackupCodec.MaxBytes) throw new IOException("Список слишком большой для копии");
            AtomicFile.WriteAllText(dialog.FileName, data);
            CloseDialog();
            ShowMessage("Копия сохранена");
        }
        catch (Exception error) { ShowMessage("Не удалось сохранить: " + error.Message); }
    }

    private void ChooseImport(string? initialDirectory)
    {
        var dialog = new OpenFileDialog
        {
            Title = "Выбери копию СВАЛКИ",
            Filter = "Копия СВАЛКИ (*.json)|*.json|Все файлы|*.*",
            InitialDirectory = initialDirectory ?? Environment.GetFolderPath(Environment.SpecialFolder.MyDocuments)
        };
        if (dialog.ShowDialog(this) != true) return;
        try
        {
            var info = new FileInfo(dialog.FileName);
            if (info.Length > TaskBackupCodec.MaxBytes) throw new InvalidDataException("Файл слишком большой");
            var backup = TaskBackupCodec.Decode(File.ReadAllText(dialog.FileName, Encoding.UTF8));
            ShowImportPreview(backup);
        }
        catch (Exception error) { ShowMessage("Не удалось прочитать копию: " + error.Message); }
    }

    private void ShowImportPreview(TaskBackup backup)
    {
        var currentIds = _vm.Tasks.Select(t => t.Id).ToHashSet();
        var newCount = backup.Tasks.Count(t => !currentIds.Contains(t.Id));
        var add = Accent($"ДОБАВИТЬ НОВЫЕ · {newCount}", () => { CloseDialog(); _vm.RestoreBackup(backup, replace: false); });
        add.IsEnabled = newCount > 0;
        ShowDialog(Card(Scroll(Stack(
            Heading("ВОССТАНОВИТЬ КОПИЮ?", 22),
            Gap(10), Caption("КОПИЯ ОТ " + Clock.ToLocal(backup.ExportedAt).ToString("d MMM yyyy, HH:mm", Ru).ToUpper(Ru)),
            Gap(8), Body($"В файле: {backup.Tasks.Count} записей, из них {backup.Tasks.Count(t => t.List == TaskList.Shopping)} покупок, " +
                         $"{backup.Tasks.Count(t => t.List == TaskList.Links)} ссылок и {backup.Tasks.Count(t => t.List == TaskList.Thoughts)} мыслей.\n" +
                         $"Сейчас: {_vm.Tasks.Count} записей. Новых в файле: {newCount}."),
            Gap(20), add,
            Gap(8), Outline("ЗАМЕНИТЬ ВЕСЬ СПИСОК", () => Confirm("Заменить все записи?",
                "Все списки и корзина будут заменены содержимым файла. Сохрани копию текущих данных, если они нужны.",
                "ЗАМЕНИТЬ", () => _vm.RestoreBackup(backup, replace: true))),
            Gap(4), Link("ОТМЕНА", CloseDialog)))));
    }
}
