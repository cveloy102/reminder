using System;
using System.Collections.Generic;
using System.IO;
using System.Text.Json;
using Svalka.Core;

namespace Svalka.Desktop.Services;

public enum ThemeMode { System, Light, Dark }

/// <summary>Small settings file (%APPDATA%\Svalka\settings.json). Never holds the records themselves.</summary>
public sealed class AppSettings
{
    public ThemeMode Theme { get; set; } = ThemeMode.System;
    public string? CloudFile { get; set; }
    public long LastCloudSave { get; set; }
    public Dictionary<string, long> DeliveredReminders { get; set; } = new();
    public bool TrayHintShown { get; set; }
    public double? WindowLeft { get; set; }
    public double? WindowTop { get; set; }
    public double WindowWidth { get; set; } = 460;
    public double WindowHeight { get; set; } = 820;

    private static readonly JsonSerializerOptions Options = new() { WriteIndented = true };

    public static AppSettings Load()
    {
        try
        {
            if (File.Exists(AppPaths.Settings))
                return JsonSerializer.Deserialize<AppSettings>(File.ReadAllText(AppPaths.Settings)) ?? new AppSettings();
        }
        catch (Exception) { /* a broken settings file just resets the settings */ }
        return new AppSettings();
    }

    public void Save()
    {
        try
        {
            Directory.CreateDirectory(AppPaths.Root);
            AtomicFile.WriteAllText(AppPaths.Settings, JsonSerializer.Serialize(this, Options));
        }
        catch (Exception) { /* settings are not critical */ }
    }
}
