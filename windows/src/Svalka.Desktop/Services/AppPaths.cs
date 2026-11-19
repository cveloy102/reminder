using System;
using System.IO;

namespace Svalka.Desktop.Services;

public static class AppPaths
{
    /// <summary>%APPDATA%\Svalka — settings and the data folder live here.</summary>
    public static string Root { get; } = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "Svalka");

    public static string Data => Path.Combine(Root, "data");
    public static string Settings => Path.Combine(Root, "settings.json");
}
