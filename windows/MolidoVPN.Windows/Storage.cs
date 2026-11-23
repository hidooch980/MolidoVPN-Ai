using System.Security.AccessControl;
using System.Security.Cryptography;
using System.Security.Principal;
using System.Text;

namespace MolidoVpn;

public static class Paths
{
    /// <summary>%ProgramData%\MolidoVPN, restricted to Administrators and SYSTEM.</summary>
    public static string DataDir
    {
        get
        {
            var dir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.CommonApplicationData), "MolidoVPN");
            if (!Directory.Exists(dir))
            {
                var security = new DirectorySecurity();
                security.SetAccessRuleProtection(isProtected: true, preserveInheritance: false);
                foreach (var sid in new[] { WellKnownSidType.BuiltinAdministratorsSid, WellKnownSidType.LocalSystemSid })
                    security.AddAccessRule(new FileSystemAccessRule(new SecurityIdentifier(sid, null),
                        FileSystemRights.FullControl,
                        InheritanceFlags.ContainerInherit | InheritanceFlags.ObjectInherit,
                        PropagationFlags.None, AccessControlType.Allow));
                new DirectoryInfo(dir).Create(security);
            }
            return dir;
        }
    }

    public static string ProfilesFile => Path.Combine(DataDir, "profiles.bin");
    public static string SettingsFile => Path.Combine(DataDir, "settings.txt");
}

/// <summary>Profiles encrypted with Windows DPAPI (current user). Text is sensitive: never log it.</summary>
public static class ProfileStore
{
    private static readonly byte[] Entropy = Encoding.UTF8.GetBytes("MolidoVPN.profiles.v1");
    public const int MaxBytes = 256 * 1024;

    public static string? Load()
    {
        if (!File.Exists(Paths.ProfilesFile)) return null;
        try
        {
            var plain = ProtectedData.Unprotect(File.ReadAllBytes(Paths.ProfilesFile), Entropy, DataProtectionScope.CurrentUser);
            try { return Encoding.UTF8.GetString(plain); }
            finally { Array.Clear(plain); }
        }
        catch (CryptographicException) { return null; }
    }

    public static void Save(string text)
    {
        var plain = Encoding.UTF8.GetBytes(text);
        try
        {
            if (plain.Length > MaxBytes) throw new InvalidOperationException("Profiles exceed size limit");
            File.WriteAllBytes(Paths.ProfilesFile, ProtectedData.Protect(plain, Entropy, DataProtectionScope.CurrentUser));
        }
        finally { Array.Clear(plain); }
    }

    public static void Delete()
    {
        try { File.Delete(Paths.ProfilesFile); } catch { /* best effort */ }
    }
}

/// <summary>Non-secret UI settings as key=value lines.</summary>
public static class Settings
{
    public static string? Get(string key) =>
        Read().TryGetValue(key, out var v) ? v : null;

    public static void Set(string key, string? value)
    {
        var map = Read();
        if (value == null) map.Remove(key); else map[key] = value;
        File.WriteAllLines(Paths.SettingsFile, map.Select(kv => $"{kv.Key}={kv.Value}"));
    }

    private static Dictionary<string, string> Read()
    {
        var map = new Dictionary<string, string>();
        if (!File.Exists(Paths.SettingsFile)) return map;
        foreach (var line in File.ReadAllLines(Paths.SettingsFile))
        {
            var i = line.IndexOf('=');
            if (i > 0) map[line[..i]] = line[(i + 1)..];
        }
        return map;
    }
}
