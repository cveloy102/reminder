using System;
using System.IO;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Security.Cryptography;
using System.Text.Json;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;

namespace Svalka.Desktop.Services;

internal sealed record WindowsRelease(Version Version, long AssetId, long Size, byte[] Sha256);

internal static class ReleaseUpdates
{
    private const string Api = "https://api.github.com/repos/cveloy102/reminder/releases";
    private const int PageSize = 100;
    private static readonly Regex SetupName = new(@"\ASvalka-Windows-Setup-([0-9]+\.[0-9]+\.[0-9]+)\.exe\z");
    private static readonly HttpClient Http = CreateClient();

    internal static async Task<WindowsRelease?> CheckAsync(CancellationToken cancellationToken)
    {
        WindowsRelease? best = null;
        for (var page = 1; ; page++)
        {
            using var response = await Http.GetAsync($"{Api}?per_page={PageSize}&page={page}", cancellationToken)
                .ConfigureAwait(false);
            response.EnsureSuccessStatusCode();
            await using var content = await response.Content.ReadAsStreamAsync(cancellationToken).ConfigureAwait(false);
            using var json = await JsonDocument.ParseAsync(content, cancellationToken: cancellationToken).ConfigureAwait(false);
            foreach (var release in json.RootElement.EnumerateArray())
            {
                if (release.GetProperty("draft").GetBoolean() || release.GetProperty("prerelease").GetBoolean())
                    continue;

                foreach (var asset in release.GetProperty("assets").EnumerateArray())
                {
                    var match = SetupName.Match(asset.GetProperty("name").GetString() ?? "");
                    if (!match.Success || !Version.TryParse(match.Groups[1].Value + ".0", out var version) ||
                        version <= (best?.Version ?? Installation.Version) ||
                        asset.GetProperty("state").GetString() != "uploaded")
                        continue;

                    var size = asset.GetProperty("size").GetInt64();
                    var digest = asset.TryGetProperty("digest", out var value) ? value.GetString() : null;
                    if (size <= 0 || digest == null || !Regex.IsMatch(digest, @"\Asha256:[0-9a-fA-F]{64}\z"))
                        throw new InvalidDataException("В релизе нет контрольной суммы установщика.");
                    best = new WindowsRelease(version, asset.GetProperty("id").GetInt64(), size,
                        Convert.FromHexString(digest[7..]));
                }
            }

            if (json.RootElement.GetArrayLength() < PageSize) return best;
        }
    }

    internal static async Task<string> DownloadAsync(WindowsRelease release, IProgress<int> progress,
        CancellationToken cancellationToken)
    {
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        timeout.CancelAfter(TimeSpan.FromMinutes(10));
        var token = timeout.Token;
        Directory.CreateDirectory(Installation.DownloadsPath);
        var path = Path.Combine(Installation.DownloadsPath, "Svalka-Update.exe");
        var pending = Path.Combine(Installation.DownloadsPath, Guid.NewGuid().ToString("N") + ".part");

        try
        {
            using var request = new HttpRequestMessage(HttpMethod.Get, $"{Api}/assets/{release.AssetId}");
            request.Headers.Accept.Add(new MediaTypeWithQualityHeaderValue("application/octet-stream"));
            using var response = await Http.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, token)
                .ConfigureAwait(false);
            response.EnsureSuccessStatusCode();

            using var hash = IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
            await using var input = await response.Content.ReadAsStreamAsync(token).ConfigureAwait(false);
            await using (var output = File.Create(pending))
            {
                var buffer = new byte[128 * 1024];
                long downloaded = 0;
                var lastPercent = -1;
                int count;
                while ((count = await input.ReadAsync(buffer, token).ConfigureAwait(false)) > 0)
                {
                    downloaded += count;
                    if (downloaded > release.Size) throw InvalidInstaller();
                    await output.WriteAsync(buffer.AsMemory(0, count), token).ConfigureAwait(false);
                    hash.AppendData(buffer, 0, count);
                    var percent = (int)(downloaded * 100 / release.Size);
                    if (percent == lastPercent) continue;
                    progress.Report(percent);
                    lastPercent = percent;
                }

                if (downloaded != release.Size || !CryptographicOperations.FixedTimeEquals(hash.GetHashAndReset(), release.Sha256))
                    throw InvalidInstaller();
            }

            cancellationToken.ThrowIfCancellationRequested();
            if (Installation.ReadVersion(pending) != release.Version)
                throw new InvalidDataException("Версия установщика не совпадает с версией в релизе.");
            File.Move(pending, path, overwrite: true);
            return path;
        }
        finally { File.Delete(pending); }
    }

    private static InvalidDataException InvalidInstaller() => new("Скачанный установщик не прошёл проверку целостности.");

    private static HttpClient CreateClient()
    {
        var client = new HttpClient { Timeout = TimeSpan.FromSeconds(45) };
        client.DefaultRequestHeaders.UserAgent.ParseAdd("Svalka-Windows/" + Installation.Version.ToString(3));
        client.DefaultRequestHeaders.Add("X-GitHub-Api-Version", "2022-11-28");
        return client;
    }
}
