using System;
using System.Diagnostics;
using Microsoft.Win32;

namespace Svalka.Desktop.Services;

/// <summary>"Start with Windows" through the per-user Run key; the app then starts hidden in the tray.</summary>
public static class Autostart
{
    private const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";
    private const string Name = "Svalka";

    private static string Command => $"\"{Environment.ProcessPath ?? Process.GetCurrentProcess().MainModule!.FileName}\" --tray";

    public static bool IsEnabled
    {
        get
        {
            using var key = Registry.CurrentUser.OpenSubKey(RunKey);
            return key?.GetValue(Name) is string value && value.Length > 0;
        }
    }

    public static void Set(bool enabled)
    {
        using var key = Registry.CurrentUser.CreateSubKey(RunKey);
        if (enabled) key.SetValue(Name, Command);
        else key.DeleteValue(Name, false);
    }

    /// <summary>Keeps the stored path right after the exe was moved or updated.</summary>
    public static void Refresh()
    {
        if (IsEnabled) Set(true);
    }
}
