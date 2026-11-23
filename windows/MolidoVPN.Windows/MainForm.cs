using System.Diagnostics;
using System.Drawing;
using System.Text;

namespace MolidoVpn;

/// <summary>One clean window: status + one main button, then server choice and profile actions.</summary>
public sealed class MainForm : Form
{
    private static readonly Color Bg = Color.FromArgb(0x0B, 0x10, 0x20);
    private static readonly Color Surface = Color.FromArgb(0x14, 0x1A, 0x2E);
    private static readonly Color Surface2 = Color.FromArgb(0x1C, 0x24, 0x40);
    private static readonly Color TextC = Color.FromArgb(0xF1, 0xF5, 0xF9);
    private static readonly Color Muted = Color.FromArgb(0x94, 0xA3, 0xB8);
    private static readonly Color Accent = Color.FromArgb(0x63, 0x66, 0xF1);
    private static readonly Color Success = Color.FromArgb(0x10, 0xB9, 0x81);
    private static readonly Color Warn = Color.FromArgb(0xF5, 0x9E, 0x0B);
    private static readonly Color Danger = Color.FromArgb(0xEF, 0x44, 0x44);

    private const int MaxFailover = 8;
    private const int WarpVariants = 6;
    private const int HandshakeTimeoutMs = 8000;

    private readonly Label _status = new();
    private readonly Label _notice = new();
    private readonly Label _profiles = new();
    private readonly Button _main = new();
    private readonly ComboBox _server = new();
    private readonly Button _import = new(), _warp = new(), _clear = new();

    private List<WgEntry> _entries = new();
    private bool _busy, _connected, _confirmed;
    private CancellationTokenSource? _cts;

    public MainForm()
    {
        Text = "MolidoVPN";
        RightToLeft = RightToLeft.Yes;
        RightToLeftLayout = true;
        BackColor = Bg;
        ForeColor = TextC;
        Font = new Font("Segoe UI", 10f);
        FormBorderStyle = FormBorderStyle.FixedSingle;
        MaximizeBox = false;
        StartPosition = FormStartPosition.CenterScreen;
        ClientSize = new Size(420, 560);

        var root = new TableLayoutPanel { Dock = DockStyle.Fill, Padding = new Padding(20), ColumnCount = 1, BackColor = Bg };
        Controls.Add(root);

        root.Controls.Add(MakeLabel("MolidoVPN", 18f, TextC, bold: true));

        var hero = MakeCard();
        _status.Font = new Font(Font.FontFamily, 16f, FontStyle.Bold);
        foreach (var l in new[] { _status, _notice, _profiles }) { l.AutoSize = false; l.Dock = DockStyle.Top; l.TextAlign = ContentAlignment.MiddleCenter; }
        _status.Height = 40; _notice.Height = 40; _profiles.Height = 24;
        _notice.ForeColor = Warn; _profiles.ForeColor = Muted;
        StyleButton(_main, Accent, 15f);
        _main.Dock = DockStyle.Top; _main.Height = 52;
        _main.Click += async (_, _) => await OnMainAsync();
        hero.Controls.Add(_main);
        hero.Controls.Add(Spacer(12));
        hero.Controls.Add(_profiles);
        hero.Controls.Add(_notice);
        hero.Controls.Add(_status);
        hero.Height = 190;
        root.Controls.Add(hero);

        root.Controls.Add(MakeLabel("سرور", 10f, Muted, bold: true));
        var server = MakeCard();
        _server.DropDownStyle = ComboBoxStyle.DropDownList;
        _server.FlatStyle = FlatStyle.Flat;
        _server.BackColor = Surface2; _server.ForeColor = TextC;
        _server.Dock = DockStyle.Top;
        _server.SelectedIndexChanged += (_, _) => { if (_server.SelectedIndex >= 0) Settings.Set("server", _server.SelectedIndex.ToString()); };
        server.Controls.Add(_server);
        server.Height = 60;
        root.Controls.Add(server);

        root.Controls.Add(MakeLabel("پروفایلها", 10f, Muted, bold: true));
        var actions = MakeCard();
        foreach (var (b, text) in new[] { (_clear, "حذف همه پروفایلها"), (_warp, "دریافت کانفیگ رایگان WARP"), (_import, "وارد کردن فایل WireGuard") })
        {
            StyleButton(b, Surface2, 10f);
            b.Text = text; b.Dock = DockStyle.Top; b.Height = 40;
            actions.Controls.Add(b);
            actions.Controls.Add(Spacer(6));
        }
        _clear.ForeColor = Danger;
        _import.Click += (_, _) => ImportFiles();
        _warp.Click += async (_, _) => await AddWarpAsync();
        _clear.Click += (_, _) => ClearProfiles();
        actions.Height = 160;
        root.Controls.Add(actions);

        root.Controls.Add(MakeLabel($"نسخه {Application.ProductVersion} • دادهها فقط روی همین کامپیوتر", 8.5f, Muted, bold: false));

        Load += (_, _) => OnLoaded();
        FormClosing += OnClosing;
    }

    // ---------- State ----------

    private void OnLoaded()
    {
        if (!WireGuard.IsInstalled)
        {
            var go = MessageBox.Show(this, "برای اتصال، WireGuard رسمی ویندوز باید نصب باشد. صفحه دانلود باز شود؟",
                "WireGuard نصب نیست", MessageBoxButtons.YesNo, MessageBoxIcon.Information,
                MessageBoxDefaultButton.Button1, MessageBoxOptions.RtlReading | MessageBoxOptions.RightAlign);
            if (go == DialogResult.Yes) Process.Start(new ProcessStartInfo(WireGuard.DownloadUrl) { UseShellExecute = true });
        }
        SetEntries(ProfileStore.Load() ?? "", save: false);
        _connected = WireGuard.IsInstalled && WireGuard.IsRunning();
        _confirmed = _connected && WireGuard.HasHandshake(); // read from the engine, so it survives restarts
        Render();
    }

    private void SetEntries(string text, bool save)
    {
        _entries = Bundle.Split(text);
        if (save)
        {
            if (_entries.Count == 0) ProfileStore.Delete();
            else ProfileStore.Save(string.Join("\n\n", _entries.Select(e => e.Text)));
        }
        _server.Items.Clear();
        _server.Items.Add(_entries.Count > 0 ? $"🌐 خودکار ({_entries.Count})" : "🌐 خودکار");
        foreach (var e in _entries) _server.Items.Add(e.Name);
        var saved = int.TryParse(Settings.Get("server"), out var i) ? i : 0;
        _server.SelectedIndex = saved >= 0 && saved < _server.Items.Count ? saved : 0;
    }

    private void Say(string? message) { _notice.Text = message ?? ""; Render(); }

    private void Render()
    {
        (_status.Text, _status.ForeColor) = (_connected, _confirmed, _busy) switch
        {
            (true, true, _) => ("متصل", Success),
            (true, false, _) => ("متصل • در انتظار پاسخ سرور", Accent),
            (false, _, true) => ("در حال اتصال…", Accent),
            _ => ("قطع", Muted),
        };
        _profiles.Text = _entries.Count == 0 ? "هنوز پروفایلی وارد نشده" : $"{_entries.Count} پروفایل آماده";
        _main.Text = _connected || _busy ? "قطع اتصال" : "اتصال";
        _main.BackColor = _connected || _busy ? Danger : Accent;
        var editable = !_busy && !_connected;
        foreach (Control c in new Control[] { _server, _import, _warp, _clear }) c.Enabled = editable;
    }

    // ---------- Actions ----------

    private async Task OnMainAsync()
    {
        if (_busy) { _cts?.Cancel(); return; }
        if (_connected) { await RunAsync(() => WireGuard.Down()); _connected = _confirmed = false; Say(null); return; }
        await ConnectAsync();
    }

    private async Task ConnectAsync()
    {
        if (!WireGuard.IsInstalled) { Say("ابتدا WireGuard رسمی ویندوز را نصب کنید."); return; }
        if (_entries.Count == 0) { Say("ابتدا یک پروفایل وارد کنید یا WARP بگیرید."); return; }
        var index = _server.SelectedIndex;
        var candidates = (index > 0 && index <= _entries.Count ? new List<WgEntry> { _entries[index - 1] } : _entries)
            .Take(MaxFailover).ToList();

        _busy = true;
        _cts = new CancellationTokenSource();
        var token = _cts.Token;
        string? result = null;
        try
        {
            for (var i = 0; i < candidates.Count && !token.IsCancellationRequested; i++)
            {
                var entry = candidates[i];
                Say(candidates.Count > 1 ? $"در حال امتحان سرور ({i + 1} از {candidates.Count})…" : "در حال اتصال…");
                try
                {
                    await Task.Run(() => WireGuard.Up(Bundle.ForWireGuard(entry)), token);
                }
                catch (OperationCanceledException) { break; }
                catch (Exception) { result = "شروع WireGuard ناموفق بود؛ فایل پروفایل را بررسی کنید."; continue; }

                var answered = await Task.Run(() => WireGuard.AwaitHandshake(HandshakeTimeoutMs, token));
                if (answered) { _connected = _confirmed = true; result = null; return; }
                if (i == candidates.Count - 1 && !token.IsCancellationRequested)
                {
                    _connected = true; _confirmed = false;
                    result = "هیچ سروری پاسخ نداد؛ اتصال را قطع کنید یا پروفایل دیگری امتحان کنید.";
                    return;
                }
                await Task.Run(() => WireGuard.Down());
            }
            if (token.IsCancellationRequested)
            {
                await Task.Run(() => WireGuard.Down());
                result = "اتصال لغو شد.";
            }
        }
        finally
        {
            _busy = false;
            _connected = WireGuard.IsRunning();
            if (!_connected) _confirmed = false;
            _cts.Dispose(); _cts = null;
            Say(result);
        }
    }

    private void ImportFiles()
    {
        using var dialog = new OpenFileDialog
        {
            Title = "انتخاب فایلهای WireGuard",
            Filter = "WireGuard (*.conf)|*.conf|همه فایلها (*.*)|*.*",
            Multiselect = true,
        };
        if (dialog.ShowDialog(this) != DialogResult.OK) return;
        try
        {
            var sb = new StringBuilder(string.Join("\n\n", _entries.Select(e => e.Text)));
            var added = 0;
            foreach (var file in dialog.FileNames)
            {
                if (new FileInfo(file).Length > 64 * 1024) continue;
                var text = File.ReadAllText(file);
                if (!text.Contains("[Interface]", StringComparison.OrdinalIgnoreCase)) continue;
                if (Warp.IsWarp(text)) text = Warp.WithEndpoints(Bundle.FromFile(file, text), WarpVariants);
                sb.Append("\n\n").Append(Bundle.FromFile(file, text));
                added++;
            }
            if (added == 0) { Say("فایل WireGuard معتبری انتخاب نشد."); return; }
            SetEntries(sb.ToString(), save: true);
            Say($"{added} فایل اضافه شد.");
        }
        catch (Exception) { Say("خواندن یا ذخیره فایلها ناموفق بود."); }
    }

    private async Task AddWarpAsync()
    {
        if (!WireGuard.IsInstalled) { Say("ابتدا WireGuard رسمی ویندوز را نصب کنید."); return; }
        _busy = true;
        Say("در حال دریافت کانفیگ WARP…");
        string? message;
        try
        {
            var config = await Warp.RegisterAsync(CancellationToken.None);
            var all = string.Join("\n\n", _entries.Select(e => e.Text).Append(Warp.WithEndpoints(config, WarpVariants)));
            SetEntries(all, save: true);
            message = "کانفیگ WARP اضافه شد.";
        }
        catch (Exception) { message = "دریافت WARP ناموفق بود؛ شاید در شبکه شما مسدود باشد."; }
        _busy = false;
        Say(message);
    }

    private void ClearProfiles()
    {
        if (MessageBox.Show(this, "همه پروفایلهای ذخیرهشده حذف شوند؟", "حذف پروفایلها", MessageBoxButtons.YesNo,
                MessageBoxIcon.Warning, MessageBoxDefaultButton.Button2, MessageBoxOptions.RtlReading | MessageBoxOptions.RightAlign) != DialogResult.Yes) return;
        SetEntries("", save: true);
        Say("پروفایلها حذف شدند.");
    }

    private async Task RunAsync(Action action)
    {
        _busy = true; Render();
        try { await Task.Run(action); } catch { /* reported via Render state */ }
        _busy = false;
    }

    private void OnClosing(object? sender, FormClosingEventArgs e)
    {
        if (!_connected && !_busy) return;
        var answer = MessageBox.Show(this, "اتصال VPN قطع شود؟ اگر «خیر» را بزنید، تونل پس از بستن برنامه هم فعال میماند.",
            "بستن MolidoVPN", MessageBoxButtons.YesNoCancel, MessageBoxIcon.Question,
            MessageBoxDefaultButton.Button1, MessageBoxOptions.RtlReading | MessageBoxOptions.RightAlign);
        if (answer == DialogResult.Cancel) { e.Cancel = true; return; }
        _cts?.Cancel();
        if (answer == DialogResult.Yes) WireGuard.Down();
    }

    // ---------- Widgets ----------

    private static Label MakeLabel(string text, float size, Color color, bool bold) => new()
    {
        Text = text, AutoSize = true, ForeColor = color,
        Font = new Font("Segoe UI", size, bold ? FontStyle.Bold : FontStyle.Regular),
        Margin = new Padding(2, 12, 2, 6),
    };

    private static Panel MakeCard() => new()
    {
        BackColor = Surface, Padding = new Padding(14), Dock = DockStyle.Top, Width = 380,
        Margin = new Padding(0, 0, 0, 4),
    };

    private static Control Spacer(int height) => new Panel { Height = height, Dock = DockStyle.Top, BackColor = Color.Transparent };

    private static void StyleButton(Button b, Color fill, float size)
    {
        b.FlatStyle = FlatStyle.Flat;
        b.FlatAppearance.BorderSize = 0;
        b.BackColor = fill;
        b.ForeColor = TextC;
        b.Font = new Font("Segoe UI", size, FontStyle.Bold);
        b.Cursor = Cursors.Hand;
    }
}
