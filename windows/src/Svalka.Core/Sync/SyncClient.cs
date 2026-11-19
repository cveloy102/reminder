using System.Net;
using System.Net.Http.Headers;
using System.Text;
using System.Text.Json.Nodes;

namespace Svalka.Core.Sync;

public sealed class SyncException : Exception
{
    public SyncException(string message, HttpStatusCode? status = null, Exception? inner = null) : base(message, inner) => Status = status;
    public HttpStatusCode? Status { get; }
    public bool Unauthorized => Status is HttpStatusCode.Unauthorized or HttpStatusCode.Forbidden;
}

public sealed record ServerInfo(bool Signup, int Version);
public sealed record AuthResult(string Token, string UserId, string Email);

/// <summary>Talks to the СВАЛКА sync server (PocketBase REST API).</summary>
public sealed class SyncClient : IDisposable
{
    private readonly HttpClient _http;
    public string BaseUrl { get; }
    public string? Token { get; set; }

    public SyncClient(string baseUrl, string? token = null, HttpMessageHandler? handler = null)
    {
        BaseUrl = NormalizeUrl(baseUrl) ?? throw new SyncException("Проверь адрес сервера");
        Token = token;
        _http = handler == null ? new HttpClient() : new HttpClient(handler);
        _http.Timeout = TimeSpan.FromSeconds(30);
    }

    /// <summary>"sync.example.com" → "https://sync.example.com"; keeps http:// only if typed explicitly.</summary>
    public static string? NormalizeUrl(string? value)
    {
        var clean = (value ?? "").Trim().TrimEnd('/');
        if (clean.Length == 0 || clean.Any(char.IsWhiteSpace)) return null;
        if (!clean.Contains("://")) clean = "https://" + clean;
        if (!Uri.TryCreate(clean, UriKind.Absolute, out var uri) || (uri.Scheme != "https" && uri.Scheme != "http") ||
            string.IsNullOrEmpty(uri.Host)) return null;
        return clean;
    }

    public async Task<ServerInfo> GetInfoAsync(CancellationToken ct = default)
    {
        JsonObject o;
        try { o = await SendAsync(HttpMethod.Get, "/api/svalka/info", null, ct, auth: false); }
        catch (SyncException e) when (e.Status == HttpStatusCode.NotFound)
        {
            throw new SyncException("По этому адресу нет сервера СВАЛКИ", e.Status);
        }
        if (o["app"]?.GetValue<string>() != "svalka-sync") throw new SyncException("По этому адресу нет сервера СВАЛКИ");
        return new ServerInfo(o["signup"]?.GetValue<bool>() == true, o["version"]?.GetValue<int>() ?? 1);
    }

    public async Task<AuthResult> LoginAsync(string email, string password, CancellationToken ct = default)
    {
        try
        {
            var o = await SendAsync(HttpMethod.Post, "/api/collections/users/auth-with-password",
                new JsonObject { ["identity"] = email.Trim(), ["password"] = password }, ct, auth: false);
            return ReadAuth(o);
        }
        catch (SyncException e) when (e.Status == HttpStatusCode.BadRequest)
        {
            throw new SyncException("Неверная почта или пароль", e.Status);
        }
    }

    public async Task RegisterAsync(string email, string password, CancellationToken ct = default)
    {
        await SendAsync(HttpMethod.Post, "/api/collections/users/records",
            new JsonObject { ["email"] = email.Trim(), ["password"] = password, ["passwordConfirm"] = password }, ct, auth: false);
    }

    /// <summary>Exchanges the current token for a fresh one (tokens expire after some days of inactivity).</summary>
    public async Task<AuthResult> RefreshAsync(CancellationToken ct = default) =>
        ReadAuth(await SendAsync(HttpMethod.Post, "/api/collections/users/auth-refresh", null, ct));

    private AuthResult ReadAuth(JsonObject o)
    {
        var token = o["token"]!.GetValue<string>();
        var record = o["record"]!.AsObject();
        Token = token;
        return new AuthResult(token, record["id"]!.GetValue<string>(), record["email"]?.GetValue<string>() ?? "");
    }

    /// <summary>Rows changed at or after the cursor, oldest first.</summary>
    public async Task<List<RemoteItem>> PullAsync(string cursor, CancellationToken ct = default)
    {
        var result = new List<RemoteItem>();
        var filter = cursor.Length == 0 ? "" : $"&filter={Uri.EscapeDataString($"updated >= \"{cursor}\"")}";
        for (var page = 1; ; page++)
        {
            var o = await SendAsync(HttpMethod.Get,
                $"/api/collections/items/records?page={page}&perPage=200&sort=updated,id&skipTotal=1{filter}", null, ct);
            var items = o["items"]!.AsArray();
            result.AddRange(items.Select(i => RemoteItem.FromJson(i!.AsObject())));
            if (items.Count < 200) break;
        }
        return result;
    }

    public async Task<RemoteItem> CreateAsync(JsonObject body, CancellationToken ct = default) =>
        RemoteItem.FromJson(await SendAsync(HttpMethod.Post, "/api/collections/items/records", body, ct));

    public async Task<RemoteItem> UpdateAsync(string recordId, JsonObject body, CancellationToken ct = default) =>
        RemoteItem.FromJson(await SendAsync(HttpMethod.Patch, $"/api/collections/items/records/{recordId}", body, ct));

    public async Task<RemoteItem?> FindAsync(string itemId, CancellationToken ct = default)
    {
        var filter = Uri.EscapeDataString($"itemId = \"{itemId.Replace("\"", "")}\"");
        var o = await SendAsync(HttpMethod.Get, $"/api/collections/items/records?perPage=1&skipTotal=1&filter={filter}", null, ct);
        var first = o["items"]!.AsArray().FirstOrDefault();
        return first == null ? null : RemoteItem.FromJson(first.AsObject());
    }

    internal HttpClient Http => _http;

    internal async Task<JsonObject> SendAsync(HttpMethod method, string path, JsonObject? body, CancellationToken ct, bool auth = true)
    {
        using var request = new HttpRequestMessage(method, BaseUrl + path);
        if (auth && Token != null) request.Headers.Authorization = new AuthenticationHeaderValue(Token);
        if (body != null) request.Content = new StringContent(body.ToJsonString(), Encoding.UTF8, "application/json");
        HttpResponseMessage response;
        try { response = await _http.SendAsync(request, ct); }
        catch (OperationCanceledException) when (!ct.IsCancellationRequested) { throw new SyncException("Сервер не отвечает"); }
        catch (HttpRequestException e) { throw new SyncException("Нет связи с сервером", null, e); }
        using (response)
        {
            var text = await response.Content.ReadAsStringAsync(ct);
            JsonObject? json = null;
            try { json = text.Length > 0 ? JsonNode.Parse(text) as JsonObject : null; } catch { /* not JSON */ }
            if (!response.IsSuccessStatusCode)
            {
                var message = json?["message"]?.GetValue<string>();
                if (response.StatusCode == HttpStatusCode.Unauthorized) message = "Нужно войти снова";
                var details = json?["data"] is JsonObject data && data.Count > 0
                    ? string.Join("; ", data.Select(kv => $"{kv.Key}: {kv.Value?["message"]}")) : null;
                throw new SyncException(details != null ? $"{message} ({details})" : message ?? $"Ошибка сервера {(int)response.StatusCode}",
                    response.StatusCode);
            }
            return json ?? new JsonObject();
        }
    }

    public void Dispose() => _http.Dispose();
}
