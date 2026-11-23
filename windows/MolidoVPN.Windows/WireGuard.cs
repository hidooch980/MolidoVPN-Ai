using System.Diagnostics;
using System.ServiceProcess;
using System.Text;

namespace MolidoVpn;

/// <summary>
/// Thin wrapper over the official WireGuard for Windows tools (wireguard.exe / wg.exe).
/// The tunnel runs as the service "WireGuardTunnel$molido" managed by WireGuard itself.
/// </summary>
public static class WireGuard
{
    public const string TunnelName = "molido";
    public const string DownloadUrl = "https://www.wireguard.com/install/";
    private static string ServiceName => "WireGuardTunnel$" + TunnelName;

    private static readonly string Dir = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles), "WireGuard");

    public static string WireGuardExe => Path.Combine(Dir, "wireguard.exe");
    public static string WgExe => Path.Combine(Dir, "wg.exe");
    public static bool IsInstalled => File.Exists(WireGuardExe) && File.Exists(WgExe);

    /// <summary>Config is written here only while the tunnel is up; the folder is admin-only.</summary>
    private static string ConfigPath => Path.Combine(Paths.DataDir, TunnelName + ".conf");

    public static bool IsRunning()
    {
        try
        {
            using var sc = new ServiceController(ServiceName);
            return sc.Status is ServiceControllerStatus.Running or ServiceControllerStatus.StartPending;
        }
        catch (InvalidOperationException) { return false; } // service does not exist
    }

    public static void Up(string configText)
    {
        if (IsRunning()) Down();
        File.WriteAllText(ConfigPath, configText, new UTF8Encoding(false));
        var (code, output) = Run(WireGuardExe, $"/installtunnelservice \"{ConfigPath}\"");
        if (code != 0) throw new InvalidOperationException("wireguard.exe: " + output.Trim());
    }

    public static void Down()
    {
        Run(WireGuardExe, $"/uninstalltunnelservice {TunnelName}");
        for (var i = 0; i < 20 && IsRunning(); i++) Thread.Sleep(250);
        try { File.Delete(ConfigPath); } catch { /* best effort */ }
    }

    /// <summary>True once the peer has answered a handshake, within <paramref name="timeoutMs"/>.</summary>
    public static bool AwaitHandshake(int timeoutMs, CancellationToken token)
    {
        var deadline = Environment.TickCount64 + timeoutMs;
        while (Environment.TickCount64 < deadline && !token.IsCancellationRequested)
        {
            if (HasHandshake()) return true;
            Thread.Sleep(500);
        }
        return HasHandshake();
    }

    public static bool HasHandshake()
    {
        var (code, output) = Run(WgExe, $"show {TunnelName} latest-handshakes");
        if (code != 0) return false;
        // Each line: "<peer public key>\t<unix seconds>"; 0 means never.
        return output.Split('\n').Any(l => l.Split('\t') is [_, var ts] && long.TryParse(ts.Trim(), out var s) && s > 0);
    }

    /// <summary>New X25519 key pair from wg.exe. The private key stays on this PC.</summary>
    public static (string PrivateKey, string PublicKey) GenerateKeys()
    {
        var (c1, priv) = Run(WgExe, "genkey");
        if (c1 != 0 || priv.Trim().Length != 44) throw new InvalidOperationException("wg genkey failed");
        var (c2, pub) = Run(WgExe, "pubkey", priv.Trim());
        if (c2 != 0 || pub.Trim().Length != 44) throw new InvalidOperationException("wg pubkey failed");
        return (priv.Trim(), pub.Trim());
    }

    private static (int Code, string Output) Run(string exe, string args, string? stdin = null)
    {
        var psi = new ProcessStartInfo(exe, args)
        {
            UseShellExecute = false,
            CreateNoWindow = true,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            RedirectStandardInput = stdin != null,
        };
        using var p = Process.Start(psi) ?? throw new InvalidOperationException("Could not start " + exe);
        if (stdin != null) { p.StandardInput.Write(stdin + "\n"); p.StandardInput.Close(); }
        var stdout = p.StandardOutput.ReadToEndAsync();
        var stderr = p.StandardError.ReadToEndAsync();
        if (!p.WaitForExit(15_000)) { try { p.Kill(); } catch { } return (-1, "timeout"); }
        return (p.ExitCode, stdout.Result + stderr.Result);
    }
}
