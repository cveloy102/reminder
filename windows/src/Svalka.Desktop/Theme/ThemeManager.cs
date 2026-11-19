using System;
using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Interop;
using System.Windows.Media;
using Microsoft.Win32;
using Svalka.Desktop.Services;

namespace Svalka.Desktop.Theme;

/// <summary>Terminal palette from the Android app (MarkerTheme.kt), light and dark.</summary>
public static class ThemeManager
{
    private static ThemeMode _mode = ThemeMode.System;

    public static bool IsDark { get; private set; }
    public static event Action? Changed;

    public static void Init(ThemeMode mode)
    {
        _mode = mode;
        SystemEvents.UserPreferenceChanged += (_, e) =>
        {
            if (e.Category == UserPreferenceCategory.General && _mode == ThemeMode.System)
                Application.Current?.Dispatcher.Invoke(Apply);
        };
        Apply();
    }

    public static void SetMode(ThemeMode mode)
    {
        _mode = mode;
        Apply();
    }

    private static void Apply()
    {
        IsDark = _mode switch
        {
            ThemeMode.Dark => true,
            ThemeMode.Light => false,
            _ => SystemUsesDark()
        };
        var r = Application.Current.Resources;
        if (IsDark)
        {
            Set(r, "B.Bg", 0x101010); Set(r, "B.Fg", 0xE8E4D9); Set(r, "B.Sec", 0xA9A49B); Set(r, "B.Div", 0x34312F);
            Set(r, "B.Accent", 0xF0443E); Set(r, "B.AccentText", 0x101010); Set(r, "B.Rem", 0xF26B65); Set(r, "B.Sheet", 0x1A1918);
            Set(r, "B.Hover", 0x1F1E1C); Set(r, "B.Scrim", 0x000000, 0xB0);
        }
        else
        {
            Set(r, "B.Bg", 0xF5F2EB); Set(r, "B.Fg", 0x181716); Set(r, "B.Sec", 0x665F59); Set(r, "B.Div", 0xD8D2C9);
            Set(r, "B.Accent", 0xCF332D); Set(r, "B.AccentText", 0xFFFFFF); Set(r, "B.Rem", 0xB92B26); Set(r, "B.Sheet", 0xFFFDF8);
            Set(r, "B.Hover", 0xECE7DE); Set(r, "B.Scrim", 0x181716, 0x80);
        }
        foreach (Window window in Application.Current.Windows) ApplyTitleBar(window);
        Changed?.Invoke();
    }

    private static void Set(ResourceDictionary r, string key, int rgb, byte alpha = 0xFF)
    {
        var brush = new SolidColorBrush(Color.FromArgb(alpha, (byte)(rgb >> 16), (byte)(rgb >> 8), (byte)rgb));
        brush.Freeze();
        r[key] = brush;
    }

    private static bool SystemUsesDark()
    {
        try
        {
            using var key = Registry.CurrentUser.OpenSubKey(@"Software\Microsoft\Windows\CurrentVersion\Themes\Personalize");
            return key?.GetValue("AppsUseLightTheme") is int light && light == 0;
        }
        catch (Exception) { return false; }
    }

    [DllImport("dwmapi.dll")]
    private static extern int DwmSetWindowAttribute(IntPtr hwnd, int attribute, ref int value, int size);

    /// <summary>Dark or light native title bar to match the theme (Windows 10 2004+ / 11).</summary>
    public static void ApplyTitleBar(Window window)
    {
        var handle = new WindowInteropHelper(window).Handle;
        if (handle == IntPtr.Zero) return;
        var dark = IsDark ? 1 : 0;
        if (DwmSetWindowAttribute(handle, 20, ref dark, sizeof(int)) != 0)
            DwmSetWindowAttribute(handle, 19, ref dark, sizeof(int));
        // Windows 11: caption colour = background colour.
        var caption = IsDark ? 0x101010 : 0xEBF2F5; // COLORREF is 0x00BBGGRR
        DwmSetWindowAttribute(handle, 35, ref caption, sizeof(int));
    }
}
