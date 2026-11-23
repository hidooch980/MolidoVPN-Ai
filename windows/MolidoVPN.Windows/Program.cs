namespace MolidoVpn;

internal static class Program
{
    [STAThread]
    private static void Main()
    {
        // One instance only: two windows driving the same tunnel service would fight each other.
        using var mutex = new Mutex(initiallyOwned: true, "Global\\MolidoVPN.Windows", out var created);
        if (!created)
        {
            MessageBox.Show("MolidoVPN از قبل باز است.", "MolidoVPN", MessageBoxButtons.OK, MessageBoxIcon.Information,
                MessageBoxDefaultButton.Button1, MessageBoxOptions.RtlReading | MessageBoxOptions.RightAlign);
            return;
        }
        Application.SetHighDpiMode(HighDpiMode.PerMonitorV2);
        Application.EnableVisualStyles();
        Application.SetCompatibleTextRenderingDefault(false);
        Application.ThreadException += (_, _) =>
            MessageBox.Show("خطای غیرمنتظره رخ داد؛ برنامه را دوباره باز کنید.", "MolidoVPN");
        Application.Run(new MainForm());
    }
}
