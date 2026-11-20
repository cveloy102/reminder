using System;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Reflection;
using System.Runtime.InteropServices;
using Microsoft.Win32;

namespace Svalka.Desktop.Services;

internal static class Installation
{
    private const string UninstallKey = @"Software\Microsoft\Windows\CurrentVersion\Uninstall\Svalka";
    private static readonly string DirectoryPath = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Programs", "Svalka");
    private static readonly string ShortcutPath = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.Programs), "СВАЛКА.lnk");

    internal static string ExePath { get; } = Path.Combine(DirectoryPath, "Svalka.exe");
    internal static string DownloadsPath { get; } = Path.Combine(
        Path.GetTempPath(), "Svalka");
    internal static Version Version { get; } = typeof(Installation).Assembly.GetName().Version!;
    internal static bool IsInstalled => File.Exists(ExePath);
    internal static bool RunningFromInstall => string.Equals(Environment.ProcessPath, ExePath, StringComparison.OrdinalIgnoreCase);

    internal static bool IsInstallerBuild => typeof(Installation).Assembly.GetCustomAttributes<AssemblyMetadataAttribute>()
        .Any(attribute => attribute.Key == "SvalkaInstaller" && attribute.Value == "true");

    internal static void Install()
    {
        if (!IsInstallerBuild || RunningFromInstall)
            throw new InvalidOperationException("Запустите установщик СВАЛКИ из папки загрузок.");
        if (IsInstalled && ReadVersion(ExePath) > Version)
            throw new InvalidOperationException("Уже установлена более новая версия СВАЛКИ.");

        Directory.CreateDirectory(DirectoryPath);
        var pending = ExePath + ".new";
        try
        {
            using (var file = new FileStream(pending, FileMode.Create, FileAccess.Write, FileShare.None))
            {
                using var source = File.OpenRead(Environment.ProcessPath!);
                source.CopyTo(file);
                file.Flush(flushToDisk: true);
            }
            File.Move(pending, ExePath, overwrite: true);
        }
        finally { File.Delete(pending); }

        CreateShortcut();
        using var key = Registry.CurrentUser.CreateSubKey(UninstallKey);
        key.SetValue("DisplayName", "СВАЛКА");
        key.SetValue("DisplayVersion", Version.ToString(3));
        key.SetValue("DisplayIcon", ExePath);
        key.SetValue("InstallLocation", DirectoryPath);
        key.SetValue("UninstallString", $"\"{ExePath}\" --uninstall");
        key.SetValue("NoModify", 1, RegistryValueKind.DWord);
        key.SetValue("NoRepair", 1, RegistryValueKind.DWord);
        Autostart.Refresh();
    }

    internal static void Remove()
    {
        File.Delete(ExePath);
        File.Delete(ShortcutPath);
        Registry.CurrentUser.DeleteSubKeyTree(UninstallKey, throwOnMissingSubKey: false);
        Autostart.Set(false);
        if (Directory.Exists(DirectoryPath) && !Directory.EnumerateFileSystemEntries(DirectoryPath).Any())
            Directory.Delete(DirectoryPath);
    }

    internal static void StartUninstaller()
    {
        Directory.CreateDirectory(DownloadsPath);
        var path = Path.Combine(DownloadsPath, "Svalka-Uninstall.exe");
        File.Copy(ExePath, path, overwrite: true);
        Start(path, "--remove-installed", Environment.ProcessId.ToString());
    }

    internal static void Start(string path, params string[] arguments)
    {
        var start = new ProcessStartInfo(path) { UseShellExecute = false, WorkingDirectory = Path.GetDirectoryName(path)! };
        foreach (var argument in arguments) start.ArgumentList.Add(argument);
        using var process = Process.Start(start) ?? throw new InvalidOperationException("Не удалось запустить СВАЛКУ.");
    }

    internal static Version ReadVersion(string file)
    {
        var info = FileVersionInfo.GetVersionInfo(file);
        return new Version(info.FileMajorPart, info.FileMinorPart, info.FileBuildPart, info.FilePrivatePart);
    }

    private static void CreateShortcut()
    {
        var shellType = Type.GetTypeFromProgID("WScript.Shell", throwOnError: true)!;
        dynamic shell = Activator.CreateInstance(shellType)!;
        try
        {
            dynamic shortcut = shell.CreateShortcut(ShortcutPath);
            try
            {
                shortcut.TargetPath = ExePath;
                shortcut.WorkingDirectory = DirectoryPath;
                shortcut.IconLocation = ExePath + ",0";
                shortcut.Description = "СВАЛКА — дела, покупки, ссылки и мысли";
                shortcut.Save();
            }
            finally { Marshal.FinalReleaseComObject(shortcut); }
        }
        finally { Marshal.FinalReleaseComObject(shell); }
    }
}
