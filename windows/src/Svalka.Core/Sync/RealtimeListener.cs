using System.Net.Http.Headers;
using System.Text;
using System.Text.Json.Nodes;

namespace Svalka.Core.Sync;

/// <summary>
/// Keeps an event stream open to the server and raises <see cref="Changed"/> whenever any of the user's
/// records changes on another device. Reconnects by itself with a growing pause.
/// </summary>
public sealed class RealtimeListener : IDisposable
{
    private readonly SyncClient _client;
    private readonly CancellationTokenSource _cts = new();

    public RealtimeListener(SyncClient client) => _client = client;

    public event Action? Changed;
    public event Action<bool>? ConnectionChanged;

    public void Start() => _ = Task.Run(LoopAsync);

    private async Task LoopAsync()
    {
        var delay = TimeSpan.FromSeconds(2);
        while (!_cts.IsCancellationRequested)
        {
            try
            {
                await ListenOnceAsync(_cts.Token);
                delay = TimeSpan.FromSeconds(2);
            }
            catch (OperationCanceledException) when (_cts.IsCancellationRequested) { return; }
            catch (Exception) { /* network trouble: retry below */ }
            ConnectionChanged?.Invoke(false);
            try { await Task.Delay(delay, _cts.Token); } catch (OperationCanceledException) { return; }
            delay = TimeSpan.FromSeconds(Math.Min(60, delay.TotalSeconds * 2));
        }
    }

    private async Task ListenOnceAsync(CancellationToken ct)
    {
        using var http = new HttpClient { Timeout = Timeout.InfiniteTimeSpan };
        using var request = new HttpRequestMessage(HttpMethod.Get, _client.BaseUrl + "/api/realtime");
        request.Headers.Accept.Add(new MediaTypeWithQualityHeaderValue("text/event-stream"));
        using var response = await http.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, ct);
        response.EnsureSuccessStatusCode();
        using var stream = await response.Content.ReadAsStreamAsync(ct);
        using var reader = new StreamReader(stream, Encoding.UTF8);
        string? eventName = null;
        var data = new StringBuilder();
        while (!ct.IsCancellationRequested)
        {
            var line = await reader.ReadLineAsync(ct);
            if (line == null) return; // server closed the stream
            if (line.Length == 0)
            {
                await HandleAsync(eventName, data.ToString(), ct);
                eventName = null;
                data.Clear();
            }
            else if (line.StartsWith("event:")) eventName = line[6..].Trim();
            else if (line.StartsWith("data:")) data.Append(line[5..].Trim());
        }
    }

    private async Task HandleAsync(string? eventName, string data, CancellationToken ct)
    {
        if (eventName == "PB_CONNECT")
        {
            var clientId = (JsonNode.Parse(data) as JsonObject)?["clientId"]?.GetValue<string>();
            if (clientId == null) return;
            await _client.SendAsync(HttpMethod.Post, "/api/realtime",
                new JsonObject { ["clientId"] = clientId, ["subscriptions"] = new JsonArray("items/*") }, ct);
            ConnectionChanged?.Invoke(true);
            Changed?.Invoke(); // catch up on anything missed while disconnected
        }
        else if (eventName != null && eventName.StartsWith("items")) Changed?.Invoke();
    }

    public void Dispose()
    {
        _cts.Cancel();
        _cts.Dispose();
    }
}
