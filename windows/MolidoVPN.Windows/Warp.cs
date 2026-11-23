using System.Net.Http;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace MolidoVpn;

/// <summary>
/// Registers a free Cloudflare WARP device and builds its WireGuard config. The private key is
/// generated locally by wg.exe; only the public key is sent. Uses the same unofficial client API
/// as wgcf (and the Android app), so Cloudflare may change or block it. Never log the result.
/// </summary>
public static class Warp
{
    private const string Endpoint = "https://api.cloudflareclient.com/v0a2158/reg";
    private const int MaxResponseBytes = 64 * 1024;

    public static readonly string[] EndpointRanges =
    {
        "8.6.112", "8.34.70", "8.34.146", "8.35.211", "8.39.125", "8.39.204", "8.39.214", "8.47.69",
        "162.159.192", "162.159.193", "162.159.195", "188.114.96", "188.114.97", "188.114.98", "188.114.99",
    };

    public static readonly int[] EndpointPorts =
    {
        2408, 500, 854, 859, 864, 878, 880, 890, 891, 894, 903, 908, 928, 934, 939, 942, 943, 945, 946,
        955, 968, 987, 988, 1002, 1010, 1014, 1018, 1070, 1074, 1180, 1387, 1701, 1843, 2371, 2506,
        3138, 3476, 3581, 3854, 4177, 4198, 4233, 4500, 5279, 5956, 7103, 7152, 7156, 7281, 7559,
        8319, 8742, 8854, 8886,
    };

    public static async Task<string> RegisterAsync(CancellationToken token)
    {
        var (privateKey, publicKey) = WireGuard.GenerateKeys();
        var body = new JsonObject
        {
            ["key"] = publicKey,
            ["install_id"] = "",
            ["fcm_token"] = "",
            ["tos"] = DateTime.UtcNow.ToString("yyyy-MM-dd'T'HH:mm:ss.fff'Z'"),
            ["type"] = "Android",
            ["locale"] = "en_US",
        }.ToJsonString();

        using var http = new HttpClient { Timeout = TimeSpan.FromSeconds(15) };
        using var request = new HttpRequestMessage(HttpMethod.Post, Endpoint)
        {
            Content = new StringContent(body, Encoding.UTF8, "application/json"),
        };
        request.Headers.TryAddWithoutValidation("User-Agent", "okhttp/3.12.1");
        request.Headers.TryAddWithoutValidation("CF-Client-Version", "a-6.30-3596");
        using var response = await http.SendAsync(request, token);
        if (!response.IsSuccessStatusCode) throw new InvalidOperationException($"WARP HTTP {(int)response.StatusCode}");
        var bytes = await response.Content.ReadAsByteArrayAsync(token);
        if (bytes.Length > MaxResponseBytes) throw new InvalidOperationException("WARP response too large");
        return BuildConfig(privateKey, JsonNode.Parse(bytes)!);
    }

    public static string BuildConfig(string privateKey, JsonNode response)
    {
        var config = response["config"]!;
        var peer = config["peers"]![0]!;
        var addresses = config["interface"]!["addresses"]!;
        var parts = new List<string>();
        var v4 = addresses["v4"]?.GetValue<string>();
        var v6 = addresses["v6"]?.GetValue<string>();
        if (!string.IsNullOrWhiteSpace(v4)) parts.Add(v4 + "/32");
        if (!string.IsNullOrWhiteSpace(v6)) parts.Add(v6 + "/128");
        if (parts.Count == 0) throw new InvalidOperationException("WARP response has no address");
        var host = peer["endpoint"]?["host"]?.GetValue<string>();
        if (string.IsNullOrWhiteSpace(host)) host = "engage.cloudflareclient.com:2408";
        return string.Join("\n",
            "# Cloudflare WARP",
            "[Interface]",
            $"PrivateKey = {privateKey}",
            $"Address = {string.Join(", ", parts)}",
            "DNS = 1.1.1.1, 1.0.0.1",
            "MTU = 1280",
            "",
            "[Peer]",
            $"PublicKey = {peer["public_key"]!.GetValue<string>()}",
            "AllowedIPs = 0.0.0.0/0, ::/0",
            $"Endpoint = {host}");
    }

    /// <summary>True when the config's peer is Cloudflare WARP (by its well-known public key or host).</summary>
    public static bool IsWarp(string configText) =>
        configText.Contains("bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=", StringComparison.Ordinal)
        || configText.Contains("cloudflareclient.com", StringComparison.OrdinalIgnoreCase);

    /// <summary>
    /// The same WARP account repeated with <paramref name="count"/> endpoints; the first keeps the
    /// original, about half of the rest use the 8.x ranges. Only one copy is ever active at a time.
    /// </summary>
    public static string WithEndpoints(string config, int count, Random? random = null)
    {
        random ??= Random.Shared;
        var lines = config.Replace("\r\n", "\n").Split('\n');
        var original = lines.FirstOrDefault(l => l.Trim().StartsWith("Endpoint", StringComparison.OrdinalIgnoreCase))
            ?.Split('=', 2)[1].Trim();
        if (original == null) return config;
        var eight = EndpointRanges.Where(r => r.StartsWith("8.")).ToArray();
        var endpoints = new List<string> { original };
        for (var guard = 0; endpoints.Count < count && guard < count * 20; guard++)
        {
            var ranges = endpoints.Count % 2 == 1 ? eight : EndpointRanges;
            var ep = $"{ranges[random.Next(ranges.Length)]}.{random.Next(1, 255)}:{EndpointPorts[random.Next(EndpointPorts.Length)]}";
            if (!endpoints.Contains(ep)) endpoints.Add(ep);
        }
        return string.Join("\n\n", endpoints.Select(ep => string.Join("\n", lines.Select(line =>
        {
            var t = line.Trim();
            if (t.StartsWith('#') && t.Contains("WARP", StringComparison.OrdinalIgnoreCase)) return $"# Cloudflare WARP {ep}";
            if (t.StartsWith("Endpoint", StringComparison.OrdinalIgnoreCase)) return $"Endpoint = {ep}";
            return line;
        }))));
    }
}
