using System;
using System.Collections.Generic;
using System.Linq;
using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Threading;

namespace Svalka.Desktop.Views;

/// <summary>
/// The app's own reminder card in the bottom-right corner, drawn in the СВАЛКА palette instead of the
/// grey Windows toast: a red stripe on the left, "[ ПОРА ]" in the mono font, the record's text and the
/// same three buttons as on the phone. It never takes focus away from what the person is doing.
/// Several cards stack upwards. A reminder stays until answered; a test card closes by itself.
/// The window is an ordinary opaque one (no AllowsTransparency): a transparent layered WPF window loses ClearType
/// and is composed as a bitmap, which made the text look soft. The shadow comes from the system (DWM).
/// </summary>
public sealed class ReminderToast : Window
{
    private const double CardWidth = 400;
    private const double Gap = 10;
    private const double Edge = 12;
    private static readonly List<ReminderToast> Open = new();

    private readonly DispatcherTimer? _autoClose;
    private bool _closed;

    private void CloseOnce() { if (!_closed) Close(); }

    public string? TaskId { get; }

    /// <summary>Opens a card. <paramref name="onAction"/> gets (action, minutes); null buttons hide the row.</summary>
    public static ReminderToast Present(string caption, string text, string? taskId, Action<string, int>? onAction,
        Action? onOpen, TimeSpan? autoClose = null)
    {
        if (taskId != null) CloseFor(taskId);
        var toast = new ReminderToast(caption, text, taskId, onAction, onOpen, autoClose);
        Open.Add(toast);
        toast.Show();
        Arrange();
        return toast;
    }

    public static void CloseFor(string taskId)
    {
        var ui = Application.Current?.Dispatcher;
        if (ui == null) return;
        if (!ui.CheckAccess()) { ui.InvokeAsync(() => CloseFor(taskId)); return; }
        foreach (var toast in Open.Where(t => t.TaskId == taskId).ToList()) toast.CloseOnce();
    }

    private ReminderToast(string caption, string text, string? taskId, Action<string, int>? onAction, Action? onOpen,
        TimeSpan? autoClose)
    {
        TaskId = taskId;
        var res = Application.Current.Resources;
        Brush B(string key) => (Brush)res[key];

        WindowStyle = WindowStyle.None;
        ResizeMode = ResizeMode.NoResize;
        Background = B("B.Sheet");
        ShowInTaskbar = false;
        ShowActivated = false;
        Topmost = true;
        Width = CardWidth;
        SizeToContent = SizeToContent.Height;
        // No caption or frame, but DWM still draws the window's shadow (it needs a sliver of extended frame).
        System.Windows.Shell.WindowChrome.SetWindowChrome(this, new System.Windows.Shell.WindowChrome
        {
            CaptionHeight = 0,
            ResizeBorderThickness = new Thickness(0),
            GlassFrameThickness = new Thickness(0, 0, 0, 1),
            CornerRadius = new CornerRadius(0),
            UseAeroCaptionButtons = false
        });

        var title = new TextBlock
        {
            Text = caption,
            FontFamily = (FontFamily)res["Mono"],
            FontWeight = FontWeights.ExtraBold,
            FontSize = 11,
            Foreground = B("B.Accent"),
            VerticalAlignment = VerticalAlignment.Center
        };
        var close = new Button
        {
            Content = new TextBlock { Text = "✕", FontSize = 12 },
            Style = (Style)res["FlatButton"],
            Foreground = B("B.Sec"),
            Padding = new Thickness(8, 4, 8, 4),
            ToolTip = "Скрыть",
            HorizontalAlignment = HorizontalAlignment.Right
        };
        close.Click += (_, _) => Close();
        var header = new DockPanel { LastChildFill = true };
        DockPanel.SetDock(close, Dock.Right);
        header.Children.Add(close);
        header.Children.Add(title);

        var body = new TextBlock
        {
            Text = text,
            FontFamily = (FontFamily)res["Inter"],
            FontWeight = FontWeights.SemiBold,
            FontSize = 15,
            LineHeight = 21,
            Foreground = B("B.Fg"),
            TextWrapping = TextWrapping.Wrap,
            TextTrimming = TextTrimming.CharacterEllipsis,
            MaxHeight = 21 * 4,
            Margin = new Thickness(0, 6, 0, 0)
        };

        var content = new StackPanel { Margin = new Thickness(18, 10, 8, 16) };
        content.Children.Add(header);
        content.Children.Add(body);

        if (onAction != null)
        {
            var row = new UniformGrid3(new Thickness(0, 14, 10, 0));
            row.Add(MakeButton("ГОТОВО", "AccentButton", () => onAction(Services.Notifier.ActionDone, 0)));
            row.Add(MakeButton("15 МИН", "OutlineButton", () => onAction(Services.Notifier.ActionSnooze, 15)));
            row.Add(MakeButton("1 ЧАС", "OutlineButton", () => onAction(Services.Notifier.ActionSnooze, 60)));
            content.Children.Add(row.Panel);
        }

        var stripe = new Border { Width = 3, Background = B("B.Accent"), HorizontalAlignment = HorizontalAlignment.Left };
        var grid = new Grid();
        grid.Children.Add(content);
        grid.Children.Add(stripe);

        var card = new Border
        {
            Background = B("B.Sheet"),
            BorderBrush = B("B.Div"),
            BorderThickness = new Thickness(1),
            Child = grid,
            Cursor = onOpen != null ? Cursors.Hand : null,
            Opacity = 0
        };
        if (onOpen != null)
        {
            card.MouseLeftButtonUp += (_, e) =>
            {
                if (e.OriginalSource is DependencyObject d && FindParent<Button>(d) != null) return;
                onOpen();
                CloseOnce();
            };
        }
        Content = card;
        UseLayoutRounding = true;
        SnapsToDevicePixels = true;
        TextOptions.SetTextFormattingMode(this, TextFormattingMode.Display);
        TextOptions.SetTextRenderingMode(this, TextRenderingMode.ClearType);

        if (autoClose is { } after)
        {
            _autoClose = new DispatcherTimer { Interval = after };
            _autoClose.Tick += (_, _) => CloseOnce();
            MouseEnter += (_, _) => _autoClose.Stop();
            MouseLeave += (_, _) => _autoClose.Start();
        }

        SourceInitialized += (_, _) =>
        {
            // Never steal focus and stay out of Alt+Tab.
            var hwnd = new WindowInteropHelper(this).Handle;
            var style = GetWindowLong(hwnd, GwlExStyle);
            SetWindowLong(hwnd, GwlExStyle, style | WsExNoActivate | WsExToolWindow);
            // Windows 11 would round the corners; the app's look is square. Older Windows ignores this.
            var square = DwmCornerDoNotRound;
            DwmSetWindowAttribute(hwnd, DwmWindowCornerPreference, ref square, sizeof(int));
        };
        Loaded += (_, _) =>
        {
            Arrange();
            // Fade the content, not the window. Text is drawn without ClearType only while the fade runs;
            // afterwards the animation is removed so nothing semi-transparent stays above the text.
            var fade = new DoubleAnimation(0, 1, TimeSpan.FromMilliseconds(160));
            fade.Completed += (_, _) => { card.BeginAnimation(OpacityProperty, null); card.Opacity = 1; };
            card.BeginAnimation(OpacityProperty, fade);
            _autoClose?.Start();
        };
        Closed += (_, _) =>
        {
            _closed = true;
            _autoClose?.Stop();
            Open.Remove(this);
            Arrange();
        };

        Button MakeButton(string label, string style, Action click)
        {
            var b = new Button { Content = label, Style = (Style)res[style], Height = 38, FontSize = 12 };
            b.Click += (_, _) => { click(); CloseOnce(); };
            return b;
        }
    }

    /// <summary>Stacks the open cards upwards from the bottom-right corner of the work area.</summary>
    private static void Arrange()
    {
        var area = SystemParameters.WorkArea;
        var bottom = area.Bottom - Edge;
        foreach (var toast in Open.AsEnumerable().Reverse())
        {
            if (!toast.IsLoaded) continue;
            var height = toast.ActualHeight;
            // Whole physical pixels: a window at a fractional position renders its text blurred.
            var scale = VisualTreeHelper.GetDpi(toast).DpiScaleX;
            toast.Left = Math.Round((area.Right - toast.Width - Edge) * scale) / scale;
            toast.Top = Math.Round((bottom - height) * scale) / scale;
            bottom -= height + Gap;
        }
    }

    private static T? FindParent<T>(DependencyObject child) where T : DependencyObject
    {
        for (var d = child; d != null; d = VisualTreeHelper.GetParent(d) ?? LogicalTreeHelper.GetParent(d))
            if (d is T found) return found;
        return null;
    }

    /// <summary>Three equal columns with a gap, without the plain UniformGrid's lack of spacing.</summary>
    private sealed class UniformGrid3
    {
        private int _count;
        public Grid Panel { get; }

        public UniformGrid3(Thickness margin)
        {
            Panel = new Grid { Margin = margin };
            for (var i = 0; i < 5; i++)
                Panel.ColumnDefinitions.Add(new ColumnDefinition
                    { Width = i % 2 == 0 ? new GridLength(1, GridUnitType.Star) : new GridLength(8) });
        }

        public void Add(UIElement element)
        {
            Grid.SetColumn(element, _count * 2);
            Panel.Children.Add(element);
            _count++;
        }
    }

    private const int GwlExStyle = -20;
    private const int WsExNoActivate = 0x08000000;
    private const int WsExToolWindow = 0x00000080;

    [DllImport("user32.dll")]
    private static extern int GetWindowLong(IntPtr hwnd, int index);

    [DllImport("user32.dll")]
    private static extern int SetWindowLong(IntPtr hwnd, int index, int value);

    private const int DwmWindowCornerPreference = 33;
    private const int DwmCornerDoNotRound = 1;

    [DllImport("dwmapi.dll")]
    private static extern int DwmSetWindowAttribute(IntPtr hwnd, int attribute, ref int value, int size);
}
