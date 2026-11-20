using System;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Input;
using System.Windows.Media;

namespace Svalka.Desktop.Views;

/// <summary>Small helpers that build dialog content in the app's terminal style.</summary>
public static class DialogKit
{
    public static T Res<T>(this T element, DependencyProperty property, string key) where T : FrameworkElement
    {
        element.SetResourceReference(property, key);
        return element;
    }

    public static Border Card(UIElement content)
    {
        var card = new Border { CornerRadius = new CornerRadius(14), Padding = new Thickness(24), Child = content };
        card.Res(Border.BackgroundProperty, "B.Sheet").Res(Border.BorderBrushProperty, "B.Div");
        card.BorderThickness = new Thickness(1);
        return card;
    }

    public static ScrollViewer Scroll(UIElement content) => new()
    {
        Content = content, VerticalScrollBarVisibility = ScrollBarVisibility.Auto, Focusable = false
    };

    public static TextBlock Caption(string text, string brush = "B.Sec", double size = 11)
    {
        var t = new TextBlock { Text = text, FontSize = size, TextWrapping = TextWrapping.Wrap };
        t.Style = (Style)Application.Current.FindResource("Caption");
        t.TextTrimming = TextTrimming.None;
        return t.Res(TextBlock.ForegroundProperty, brush);
    }

    public static TextBlock Heading(string text, double size = 26)
    {
        var t = new TextBlock
        {
            Text = text, FontSize = size, FontWeight = FontWeights.Black, TextWrapping = TextWrapping.Wrap,
            Margin = new Thickness(0, 6, 0, 0)
        };
        return t.Res(TextBlock.ForegroundProperty, "B.Fg");
    }

    public static TextBlock Body(string text, string brush = "B.Sec", double size = 14)
    {
        var t = new TextBlock { Text = text, FontSize = size, TextWrapping = TextWrapping.Wrap, LineHeight = size * 1.45 };
        return t.Res(TextBlock.ForegroundProperty, brush);
    }

    public static TextBox Field(string text, bool multiline = false, double minHeight = 0, bool mono = false)
    {
        var box = new TextBox
        {
            Text = text,
            Style = (Style)Application.Current.FindResource("Field"),
            AcceptsReturn = multiline,
            TextWrapping = multiline ? TextWrapping.Wrap : TextWrapping.NoWrap,
            MinHeight = minHeight,
            MaxHeight = multiline ? 320 : double.PositiveInfinity
        };
        if (mono)
        {
            box.FontFamily = (FontFamily)Application.Current.FindResource("Mono");
            box.FontSize = 13;
        }
        return box;
    }

    public static PasswordBox Password() => new() { Style = (Style)Application.Current.FindResource("PasswordField") };

    public static Button Accent(string text, Action? onClick = null) => MakeButton("AccentButton", text, onClick);
    public static Button Outline(string text, Action? onClick = null) => MakeButton("OutlineButton", text, onClick);

    private static Button MakeButton(string style, string text, Action? onClick)
    {
        var b = new Button { Content = text, Style = (Style)Application.Current.FindResource(style) };
        if (onClick != null) b.Click += (_, _) => onClick();
        return b;
    }

    public static Button Link(string text, Action onClick, string brush = "B.Sec", double size = 11)
    {
        var b = new Button
        {
            Style = (Style)Application.Current.FindResource("FlatButton"),
            Content = Caption(text, brush, size)
        };
        b.Click += (_, _) => onClick();
        return b;
    }

    /// <summary>Segmented control: one row of options, the selected one filled with the accent colour.</summary>
    public static Border Segmented(string[] labels, int selected, Action<int> onSelect)
    {
        var grid = new UniformGrid { Rows = 1 };
        var cells = new Border[labels.Length];
        void Paint(int chosen)
        {
            for (var i = 0; i < cells.Length; i++)
            {
                cells[i].Res(Border.BackgroundProperty, i == chosen ? "B.Accent" : "B.Sheet");
                ((TextBlock)cells[i].Child).Res(TextBlock.ForegroundProperty, i == chosen ? "B.AccentText" : "B.Fg");
            }
        }
        for (var i = 0; i < labels.Length; i++)
        {
            var index = i;
            var label = new TextBlock
            {
                Text = labels[i], FontSize = 11, FontWeight = FontWeights.Black, HorizontalAlignment = HorizontalAlignment.Center,
                VerticalAlignment = VerticalAlignment.Center,
                FontFamily = (FontFamily)Application.Current.FindResource("Mono")
            };
            var cell = new Border { Height = 42, Child = label, Cursor = Cursors.Hand };
            cell.MouseLeftButtonUp += (_, _) =>
            {
                Paint(index);
                onSelect(index);
            };
            cells[i] = cell;
            grid.Children.Add(cell);
        }
        Paint(selected);
        var frame = new Border { BorderThickness = new Thickness(1), CornerRadius = new CornerRadius(8), Child = grid, ClipToBounds = true };
        return frame.Res(Border.BorderBrushProperty, "B.Div");
    }

    public static Border Divider() =>
        new Border { Height = 1, Margin = new Thickness(0, 18, 0, 18) }.Res(Border.BackgroundProperty, "B.Div");

    public static UIElement Gap(double height) => new Border { Height = height };

    public static StackPanel Stack(params UIElement[] children)
    {
        var stack = new StackPanel();
        foreach (var child in children) stack.Children.Add(child);
        return stack;
    }
}
