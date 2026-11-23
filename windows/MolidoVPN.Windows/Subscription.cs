using System.Net.Http;
using System.Text;
using System.Text.RegularExpressions;

namespace MolidoVpn;

/// <summary>Result of reading a subscription. <see cref="WireGuardText"/> is sensitive: never log it.</summary>
public sealed record SubscriptionResult(string WireGuardText, int WireGuardCount, int UnsupportedCount)
{
    public override string ToString() => $"SubscriptionResult(wg={WireGuardCount}, unsupported={UnsupportedCount})";
}

/// <summary>
/// Fetches a subscription link the user entered and keeps the WireGuard configs in it (.conf text
/// or wireguard:// / wg:// links, plain or base64). Other link types are counted, not imported.
/// Same rules as the Android SubscriptionImport.
/// </summary>
public static class Subscription
{
    public const int MaxResponseBytes = 512 * 1024;
    private static readonly string[] OtherSchemes =
        { "vless://", "vmess://", "trojan://", "ss://", "ssr://", "hysteria2://", "hy2://", "tuic://", "socks://", "http://", "https://" };

    public static async Task<SubscriptionResult> FetchAsync(string url, CancellationToken token)
    {
        if (!Uri.TryCreate(url.Trim(), UriKind.Absolute, out var uri) || uri.Scheme != Uri.UriSchemeHttps || string.IsNullOrEmpty(uri.Host))
            throw new ArgumentException("Only https links are allowed");
        using var http = new HttpClient { Timeout = TimeSpan.FromSeconds(15) };
        http.DefaultRequestHeaders.TryAddWithoutValidation("User-Agent", "MolidoVPN");
        using var response = await http.GetAsync(uri, HttpCompletionOption.ResponseHeadersRead, token);
        if (!response.IsSuccessStatusCode) throw new InvalidOperationException($"HTTP {(int)response.StatusCode}");
        await using var stream = await response.Content.ReadAsStreamAsync(token);
        using var buffer = new MemoryStream();
        var chunk = new byte[8192];
        int n;
        while ((n = await stream.ReadAsync(chunk, token)) > 0)
        {
            if (buffer.Length + n > MaxResponseBytes) throw new InvalidOperationException("Subscription too large");
            buffer.Write(chunk, 0, n);
        }
        return Parse(Encoding.UTF8.GetString(buffer.ToArray()));
    }

    public static SubscriptionResult Parse(string body)
    {
        var text = DecodeIfBase64(body.Trim()).Replace("\r\n", "\n");
        var configs = new List<string>();
        if (text.Contains("[Interface]", StringComparison.OrdinalIgnoreCase))
            configs.AddRange(Bundle.Split(text).Select(e => e.Text));
        var unsupported = 0;
        foreach (var raw in text.Split('\n'))
        {
            var line = raw.Trim();
            if (line.StartsWith("wireguard://", StringComparison.OrdinalIgnoreCase) || line.StartsWith("wg://", StringComparison.OrdinalIgnoreCase))
            {
                var conf = FromUri(line);
                if (conf != null) configs.Add(conf); else unsupported++;
            }
            else if (OtherSchemes.Any(s => line.StartsWith(s, StringComparison.OrdinalIgnoreCase))) unsupported++;
        }
        var distinct = configs.Distinct().Take(Bundle.MaxEntries).ToList();
        return new SubscriptionResult(string.Join("\n\n", distinct), distinct.Count, unsupported);
    }

    /// <summary>wireguard://&lt;private key&gt;@host:port?publickey=..&amp;address=..#name → .conf text.</summary>
    public static string? FromUri(string link)
    {
        try
        {
            var rest = link[(link.IndexOf("://", StringComparison.Ordinal) + 3)..];
            var hash = rest.IndexOf('#');
            var name = hash >= 0 ? Decode(rest[(hash + 1)..]) : "";
            var main = hash >= 0 ? rest[..hash] : rest;
            var q = main.IndexOf('?');
            var query = q >= 0 ? main[(q + 1)..] : "";
            var authority = (q >= 0 ? main[..q] : main).TrimEnd('/');
            var at = authority.LastIndexOf('@');
            if (at <= 0) return null;
            var privateKey = Decode(authority[..at]);
            var endpoint = authority[(at + 1)..];
            var p = query.Split('&').Where(s => s.Contains('='))
                .Select(s => s.Split('=', 2))
                .GroupBy(kv => Decode(kv[0]).ToLowerInvariant())
                .ToDictionary(g => g.Key, g => Decode(g.First()[1]));
            var publicKey = p.GetValueOrDefault("publickey") ?? p.GetValueOrDefault("public_key") ?? p.GetValueOrDefault("peer_public_key");
            var address = p.GetValueOrDefault("address") ?? p.GetValueOrDefault("ip") ?? p.GetValueOrDefault("local_address");
            if (privateKey.Length != 44 || publicKey?.Length != 44 || string.IsNullOrWhiteSpace(address) || !endpoint.Contains(':')) return null;
            var mtu = int.TryParse(p.GetValueOrDefault("mtu"), out var m) ? m : 1280;
            var lines = new List<string>
            {
                $"# {(name.Length > 0 ? name : endpoint)}",
                "[Interface]",
                $"PrivateKey = {privateKey}",
                $"Address = {string.Join(", ", address.Split(',').Select(a => a.Trim()))}",
                $"DNS = {p.GetValueOrDefault("dns") ?? "1.1.1.1, 1.0.0.1"}",
                $"MTU = {mtu}",
                "",
                "[Peer]",
                $"PublicKey = {publicKey}",
            };
            if (p.TryGetValue("presharedkey", out var psk)) lines.Add($"PresharedKey = {psk}");
            lines.Add("AllowedIPs = 0.0.0.0/0, ::/0");
            lines.Add($"Endpoint = {endpoint}");
            return string.Join("\n", lines);
        }
        catch { return null; }
    }

    private static string Decode(string value) => Uri.UnescapeDataString(value);

    private static string DecodeIfBase64(string text)
    {
        if (text.Contains("://") || text.Contains("[Interface]", StringComparison.OrdinalIgnoreCase)) return text;
        var compact = Regex.Replace(text, "\\s", "").Replace('-', '+').Replace('_', '/');
        compact += new string('=', (4 - compact.Length % 4) % 4);
        try { return Encoding.UTF8.GetString(Convert.FromBase64String(compact)); }
        catch (FormatException) { return text; }
    }
}
