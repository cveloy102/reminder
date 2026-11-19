using System;
using System.IO;
using System.Linq;

namespace Svalka.Desktop.Services;

/// <summary>
/// Finds the folder of the "Google Drive for desktop" app (usually G:\Мой диск or G:\My Drive),
/// so file dialogs open right there. Returns null if the app is not installed.
/// </summary>
public static class GoogleDriveFolder
{
    private static readonly string[] MyDriveNames = { "Мой диск", "My Drive" };

    public static string? Find()
    {
        try
        {
            foreach (var drive in DriveInfo.GetDrives().Where(d => d.IsReady))
            {
                var label = SafeLabel(drive);
                var looksLikeDrive = label.Contains("Google", StringComparison.OrdinalIgnoreCase);
                foreach (var name in MyDriveNames)
                {
                    var candidate = Path.Combine(drive.RootDirectory.FullName, name);
                    if ((looksLikeDrive || drive.DriveType == DriveType.Fixed && label.Length == 0) && Directory.Exists(candidate))
                        return candidate;
                }
            }
            var home = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
            foreach (var name in new[] { "Google Drive", "Google Диск", "GoogleDrive" })
            {
                var candidate = Path.Combine(home, name);
                if (!Directory.Exists(candidate)) continue;
                foreach (var my in MyDriveNames)
                    if (Directory.Exists(Path.Combine(candidate, my))) return Path.Combine(candidate, my);
                return candidate;
            }
        }
        catch (Exception) { /* fall through */ }
        return null;
    }

    public static bool IsInside(string path)
    {
        var root = Find();
        return root != null && Path.GetFullPath(path).StartsWith(Path.GetFullPath(root), StringComparison.OrdinalIgnoreCase);
    }

    private static string SafeLabel(DriveInfo drive)
    {
        try { return drive.VolumeLabel ?? ""; } catch { return ""; }
    }
}
