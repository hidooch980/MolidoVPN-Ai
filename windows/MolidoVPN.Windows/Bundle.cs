using System.Text;

namespace MolidoVpn;

/// <summary>One WireGuard config. <see cref="Text"/> is sensitive: never log it.</summary>
public sealed record WgEntry(string Name, string Text)
{
    public override string ToString() => Name;
}

/// <summary>
/// Several WireGuard configs in one text blob. Each starts at an [Interface] line; comment
/// lines directly above it belong to it and the first comment is its display name.
/// Same rules as the Android WireGuardBundle.
/// </summary>
public static class Bundle
{
    public const int MaxEntries = 200;

    public static List<WgEntry> Split(string text)
    {
        var sections = new List<List<string>>();
        List<string>? current = null;
        var pending = new List<string>();
        foreach (var line in text.Replace("\r\n", "\n").Split('\n'))
        {
            var t = line.Trim();
            if (t.Equals("[Interface]", StringComparison.OrdinalIgnoreCase))
            {
                if (current != null) sections.Add(current);
                current = new List<string>(pending) { line };
                pending.Clear();
            }
            else if (t.StartsWith('#') || (t.Length == 0 && pending.Count > 0)) pending.Add(line);
            else if (current == null) pending.Clear();
            else
            {
                current.AddRange(pending);
                pending.Clear();
                current.Add(line);
            }
        }
        if (current != null) { current.AddRange(pending); sections.Add(current); }

        return sections.Take(MaxEntries).Select((lines, i) =>
        {
            var name = lines.FirstOrDefault(l => l.Trim().StartsWith('#'))?.Trim().TrimStart('#').Trim() ?? "";
            if (name.StartsWith("Name:", StringComparison.OrdinalIgnoreCase)) name = name[5..].Trim();
            if (name.Length == 0) name = $"WireGuard {i + 1}";
            return new WgEntry(name, string.Join("\n", lines).Trim());
        }).ToList();
    }

    /// <summary>A file without a leading comment gets its file name as its display name.</summary>
    public static string FromFile(string fileName, string text)
    {
        var body = text.Trim();
        return body.StartsWith('#') ? body : $"# {Path.GetFileNameWithoutExtension(fileName)}\n{body}";
    }

    /// <summary>Config text with comments removed, as wireguard.exe expects it.</summary>
    public static string ForWireGuard(WgEntry entry)
    {
        var sb = new StringBuilder();
        foreach (var line in entry.Text.Split('\n'))
            if (!line.TrimStart().StartsWith('#')) sb.Append(line.TrimEnd()).Append("\r\n");
        return sb.ToString();
    }
}
