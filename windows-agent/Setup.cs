// The installer half of PocketRemotePc.exe.
//
// Double-clicking the downloaded exe shows a small setup window; the same file, once installed, is the background agent
// (it starts the agent when it runs from its install folder). Installs for the current user only, with no admin rights
// needed except one optional Windows Firewall rule (a single UAC prompt). Uninstall is in Settings > Apps.

using System;
using System.ComponentModel;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Reflection;
using System.Threading;
using System.Windows.Forms;
using Microsoft.Win32;

[assembly: AssemblyTitle("Pocket Remote for PC")]
[assembly: AssemblyProduct("Pocket Remote for PC")]
[assembly: AssemblyCompany("Pocket Remote")]
[assembly: AssemblyVersion("1.0.0.0")]
[assembly: AssemblyFileVersion("1.0.0.0")]

namespace PocketRemotePc
{
    sealed class SetupOptions
    {
        public string Dir, Data;
        public bool Startup = true, Firewall = true, Public, Shortcut = true;
        public bool Silent, Sandbox, KeepData;
    }

    static class Setup
    {
        public const string Version = "1.0.0", AppName = "Pocket Remote for PC", RuleName = "Pocket Remote PC agent", ExeName = "PocketRemotePc.exe";
        const string UninstallKey = @"Software\Microsoft\Windows\CurrentVersion\Uninstall\PocketRemotePc";
        const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run", RunName = "PocketRemotePc";

        public static string DefaultDir()
        {
            return Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), @"Programs\Pocket Remote PC");
        }

        public static string DefaultData()
        {
            return Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "PocketRemotePc");
        }

        /** Where it is installed, according to Windows' list of installed apps; null when it isn't. */
        public static string InstalledDir()
        {
            using (RegistryKey k = Registry.CurrentUser.OpenSubKey(UninstallKey))
                return k == null ? null : k.GetValue("InstallLocation") as string;
        }

        static string Norm(string p)
        {
            return Path.GetFullPath(p).TrimEnd('\\');
        }

        /** True when this exe is the installed copy: then it is the agent, not the installer. */
        public static bool RunsFromInstall()
        {
            string d = InstalledDir();
            if (d == null) return false;
            return string.Equals(Norm(d), Norm(Path.GetDirectoryName(Application.ExecutablePath)), StringComparison.OrdinalIgnoreCase);
        }

        static string StartMenuLink()
        {
            return Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.Programs), AppName + ".lnk");
        }

        // ---- install ----

        public static void Install(SetupOptions o, Action<string> report)
        {
            string target = Path.Combine(o.Dir, ExeName);
            report("Stopping the running agent...");
            StopAgents(o.Sandbox ? o.Dir : null);

            report("Copying files...");
            Directory.CreateDirectory(o.Dir);
            if (!string.Equals(Norm(Application.ExecutablePath), Norm(target), StringComparison.OrdinalIgnoreCase))
                CopyWithRetry(Application.ExecutablePath, target);
            if (o.Sandbox) return;

            report("Adding it to your installed apps...");
            using (RegistryKey k = Registry.CurrentUser.CreateSubKey(UninstallKey))
            {
                k.SetValue("DisplayName", AppName);
                k.SetValue("DisplayVersion", Version);
                k.SetValue("Publisher", "Pocket Remote");
                k.SetValue("InstallLocation", o.Dir);
                k.SetValue("DisplayIcon", target);
                k.SetValue("UninstallString", "\"" + target + "\" --uninstall");
                k.SetValue("NoModify", 1, RegistryValueKind.DWord);
                k.SetValue("NoRepair", 1, RegistryValueKind.DWord);
                k.SetValue("EstimatedSize", (int)(new FileInfo(target).Length / 1024), RegistryValueKind.DWord);
            }

            using (RegistryKey k = Registry.CurrentUser.OpenSubKey(RunKey, true))
            {
                if (k != null)
                {
                    if (o.Startup) k.SetValue(RunName, "\"" + target + "\"");
                    else k.DeleteValue(RunName, false);
                }
            }

            if (o.Shortcut)
            {
                report("Making the Start menu shortcut...");
                try { MakeShortcut(StartMenuLink(), target); } catch (Exception e) { Log.Write("shortcut failed: " + e.Message); }
            }

            if (o.Firewall)
            {
                report("Allowing phone connections through the firewall (Windows will ask once)...");
                if (!RunElevated("--firewall-add \"" + target + "\" " + (o.Public ? "1" : "0")))
                    report("The firewall rule was not added. Windows will ask when the agent first starts: choose Allow.");
            }
        }

        static void CopyWithRetry(string from, string to)
        {
            for (int i = 0; ; i++)
            {
                try { File.Copy(from, to, true); return; }
                catch (IOException) { if (i >= 12) throw; Thread.Sleep(300); }
                catch (UnauthorizedAccessException) { if (i >= 12) throw; Thread.Sleep(300); }
            }
        }

        static void MakeShortcut(string path, string target)
        {
            Type t = Type.GetTypeFromProgID("WScript.Shell");
            dynamic shell = Activator.CreateInstance(t);
            dynamic link = shell.CreateShortcut(path);
            link.TargetPath = target;
            link.WorkingDirectory = Path.GetDirectoryName(target);
            link.Description = AppName;
            link.Save();
        }

        /** Stops running copies of the agent. With a folder, only the ones running from inside it (used by tests). */
        static void StopAgents(string onlyUnder)
        {
            foreach (Process p in Process.GetProcessesByName("PocketRemotePc"))
            {
                try
                {
                    if (p.Id == Process.GetCurrentProcess().Id) continue;
                    if (onlyUnder != null)
                    {
                        string f = p.MainModule.FileName;
                        if (!Norm(f).StartsWith(Norm(onlyUnder), StringComparison.OrdinalIgnoreCase)) continue;
                    }
                    p.Kill();
                    p.WaitForExit(4000);
                }
                catch (Exception) { }
            }
        }

        /** Runs this exe again with Windows' admin prompt. False when the prompt was declined or it failed. */
        static bool RunElevated(string args)
        {
            try
            {
                ProcessStartInfo psi = new ProcessStartInfo(Application.ExecutablePath, args);
                psi.Verb = "runas";
                psi.UseShellExecute = true;
                psi.WindowStyle = ProcessWindowStyle.Hidden;
                using (Process p = Process.Start(psi))
                {
                    p.WaitForExit();
                    return p.ExitCode == 0;
                }
            }
            catch (Win32Exception) { return false; }      // the user said no to the admin prompt
        }

        static int Netsh(string args)
        {
            ProcessStartInfo psi = new ProcessStartInfo("netsh.exe", args);
            psi.UseShellExecute = false;
            psi.CreateNoWindow = true;
            using (Process p = Process.Start(psi))
            {
                p.WaitForExit();
                return p.ExitCode;
            }
        }

        /** The elevated helper: opens the agent's port for the installed program. Exit code 0 means it worked. */
        public static int FirewallAdd(string exe, bool publicToo)
        {
            Netsh("advfirewall firewall delete rule name=\"" + RuleName + "\"");
            return Netsh("advfirewall firewall add rule name=\"" + RuleName + "\" dir=in action=allow protocol=TCP localport=" +
                Server.DefaultPort + " program=\"" + exe + "\" profile=" + (publicToo ? "private,domain,public" : "private,domain") + " enable=yes");
        }

        public static int FirewallRemove()
        {
            return Netsh("advfirewall firewall delete rule name=\"" + RuleName + "\"");
        }

        /** Asks Windows whether the Wi-Fi you are on is marked Public, which hides the laptop from the phone by default. */
        public static bool DetectPublicNetwork()
        {
            try
            {
                ProcessStartInfo psi = new ProcessStartInfo("powershell.exe",
                    "-NoProfile -NonInteractive -Command \"Get-NetConnectionProfile | Where-Object { $_.IPv4Connectivity -ne 'Disconnected' } | ForEach-Object { $_.NetworkCategory }\"");
                psi.UseShellExecute = false;
                psi.CreateNoWindow = true;
                psi.RedirectStandardOutput = true;
                using (Process p = Process.Start(psi))
                {
                    string o = p.StandardOutput.ReadToEnd();
                    p.WaitForExit(10000);
                    return o.IndexOf("Public", StringComparison.OrdinalIgnoreCase) >= 0;
                }
            }
            catch (Exception) { return false; }
        }

        // ---- uninstall ----

        /** Step one, from the installed copy: ask, then hand over to a temporary copy, because an exe can't delete its own folder. */
        public static int Uninstall(SetupOptions o)
        {
            string dir = o.Dir ?? InstalledDir() ?? DefaultDir();
            if (!o.Silent)
            {
                if (MessageBox.Show("Remove " + AppName + " from this laptop?", AppName, MessageBoxButtons.YesNo, MessageBoxIcon.Question) != DialogResult.Yes)
                    return 0;
                o.KeepData = MessageBox.Show("Also forget the paired phones?\n\nYes: remove everything.\nNo: keep them, so a future install needs no new pairing.",
                    AppName, MessageBoxButtons.YesNo, MessageBoxIcon.Question) != DialogResult.Yes;
            }
            string tmp = Path.Combine(Path.GetTempPath(), "PocketRemotePc-uninstall-" + Guid.NewGuid().ToString("N").Substring(0, 8) + ".exe");
            File.Copy(Application.ExecutablePath, tmp, true);
            string args = "--uninstall-final \"" + dir + "\"" + (o.KeepData ? " --keep-data" : "") + (o.Sandbox ? " --sandbox" : "")
                + (o.Silent ? " --silent" : "") + (o.Data != null ? " --data \"" + o.Data + "\"" : "");
            Process p = Process.Start(new ProcessStartInfo(tmp, args) { UseShellExecute = false });
            if (o.Silent)
            {
                p.WaitForExit();
                return p.ExitCode;
            }
            return 0;
        }

        /** Step two, from the temporary copy: take everything away. */
        public static int UninstallFinal(SetupOptions o)
        {
            string dir = o.Dir;
            Thread.Sleep(700);                       // the copy that started us is still exiting
            StopAgents(o.Sandbox ? dir : null);
            if (!o.Sandbox)
            {
                using (RegistryKey k = Registry.CurrentUser.OpenSubKey(RunKey, true)) { if (k != null) k.DeleteValue(RunName, false); }
                try { File.Delete(StartMenuLink()); } catch (Exception) { }
                try { Registry.CurrentUser.DeleteSubKeyTree(UninstallKey, false); } catch (Exception) { }
                RunElevated("--firewall-remove");
            }
            DeleteFolder(dir);
            if (!o.KeepData && (!o.Sandbox || o.Data != null)) DeleteFolder(o.Data ?? DefaultData());
            if (!o.Silent) MessageBox.Show(AppName + " was removed.", AppName, MessageBoxButtons.OK, MessageBoxIcon.Information);
            // this temporary copy deletes itself once it has exited
            Process.Start(new ProcessStartInfo("cmd.exe", "/c ping -n 3 127.0.0.1 >nul & del \"" + Application.ExecutablePath + "\"")
                { CreateNoWindow = true, UseShellExecute = false });
            return 0;
        }

        static void DeleteFolder(string dir)
        {
            for (int i = 0; i < 10 && Directory.Exists(dir); i++)
            {
                try { Directory.Delete(dir, true); }
                catch (Exception) { Thread.Sleep(400); }
            }
        }

        // ---- the setup window ----

        public static int Wizard(SetupOptions o)
        {
            Application.EnableVisualStyles();
            Application.Run(new SetupForm(o));
            return 0;
        }
    }

    sealed class SetupForm : Form
    {
        readonly SetupOptions o;
        readonly string installedDir;
        readonly Label title, body, status, where;
        readonly CheckBox cbStartup, cbShortcut, cbFirewall, cbPublic;
        readonly Button main, remove, cancel;
        string installedExe;
        bool finished;

        public SetupForm(SetupOptions options)
        {
            o = options;
            installedDir = Setup.InstalledDir();
            if (o.Dir == null) o.Dir = installedDir ?? Setup.DefaultDir();

            Text = Setup.AppName + " setup";
            FormBorderStyle = FormBorderStyle.FixedDialog;
            MaximizeBox = false;
            MinimizeBox = false;
            StartPosition = FormStartPosition.CenterScreen;
            ClientSize = new Size(540, 450);
            BackColor = Color.FromArgb(11, 7, 22);
            ForeColor = Color.White;
            Font = new Font("Segoe UI", 9.5f);
            try { Icon = Icon.ExtractAssociatedIcon(Application.ExecutablePath); } catch (Exception) { }

            title = new Label { Left = 28, Top = 22, Width = 484, Height = 40, Font = new Font("Segoe UI", 20f, FontStyle.Bold) };
            body = new Label { Left = 28, Top = 70, Width = 484, Height = 80, ForeColor = Color.FromArgb(210, 255, 255, 255) };
            cbStartup = Check("Start with Windows (recommended: the phone connects as soon as this laptop is on)", 160, true);
            cbShortcut = Check("Add a Start menu shortcut", 204, true);
            cbFirewall = Check("Let the phone connect through the Windows Firewall (Windows asks for permission once)", 238, true);
            cbPublic = Check("My Wi-Fi is marked Public in Windows (turn this on if the phone can't find the laptop)", 282, false);
            where = new Label { Left = 28, Top = 332, Width = 484, Height = 20, ForeColor = Color.FromArgb(150, 255, 255, 255), Text = "Installs to " + o.Dir + " (this user only)" };
            status = new Label { Left = 28, Top = 360, Width = 484, Height = 40, ForeColor = Color.FromArgb(255, 53, 210) };
            main = new Button { Left = 306, Top = 404, Width = 120, Height = 32, FlatStyle = FlatStyle.System };
            cancel = new Button { Left = 436, Top = 404, Width = 80, Height = 32, Text = "Cancel", FlatStyle = FlatStyle.System };
            remove = new Button { Left = 28, Top = 404, Width = 110, Height = 32, Text = "Uninstall", FlatStyle = FlatStyle.System, Visible = installedDir != null };
            Controls.AddRange(new Control[] { title, body, cbStartup, cbShortcut, cbFirewall, cbPublic, where, status, main, cancel, remove });

            title.Text = installedDir == null ? Setup.AppName : "Update " + Setup.AppName;
            body.Text = "Control this laptop from your phone: mouse, air mouse, keyboard, shortcuts and apps. "
                + "This puts a small program in your tray icons that the phone app connects to. "
                + "No account, no ads, and nothing leaves your Wi-Fi.";
            main.Text = installedDir == null ? "Install" : "Update";
            cancel.Click += delegate { Close(); };
            main.Click += delegate { if (finished) Finish(); else StartInstall(); };
            remove.Click += delegate
            {
                Hide();
                Setup.Uninstall(new SetupOptions { Dir = installedDir });
                Close();
            };

            // Windows' own Public/Private label decides whether the firewall rule applies, so ask what it is
            ThreadPool.QueueUserWorkItem(delegate
            {
                bool pub = Setup.DetectPublicNetwork();
                if (pub && IsHandleCreated) BeginInvoke(new Action(delegate { cbPublic.Checked = true; }));
            });
        }

        CheckBox Check(string text, int top, bool on)
        {
            return new CheckBox { Text = text, Left = 28, Top = top, Width = 484, Height = 40, Checked = on, FlatStyle = FlatStyle.Standard, ForeColor = Color.White };
        }

        void StartInstall()
        {
            o.Startup = cbStartup.Checked;
            o.Shortcut = cbShortcut.Checked;
            o.Firewall = cbFirewall.Checked;
            o.Public = cbPublic.Checked;
            main.Enabled = cancel.Enabled = remove.Enabled = false;
            foreach (CheckBox c in new CheckBox[] { cbStartup, cbShortcut, cbFirewall, cbPublic }) c.Enabled = false;
            installedExe = Path.Combine(o.Dir, Setup.ExeName);
            new Thread(delegate()
            {
                try
                {
                    Setup.Install(o, delegate(string s) { BeginInvoke(new Action(delegate { status.Text = s; })); });
                    BeginInvoke(new Action(Done));
                }
                catch (Exception e)
                {
                    Log.Write("install failed: " + e);
                    BeginInvoke(new Action(delegate
                    {
                        status.Text = "Setup could not finish: " + e.Message;
                        cancel.Enabled = true;
                        cancel.Text = "Close";
                    }));
                }
            }) { IsBackground = true }.Start();
        }

        void Done()
        {
            title.Text = "You're all set";
            body.Text = "Next, on your phone: open Pocket Remote, switch to Laptop, and enter the 6-digit code that appears here when you click Finish.";
            foreach (Control c in new Control[] { cbStartup, cbShortcut, cbFirewall, cbPublic, where, remove }) c.Visible = false;
            status.Top = 170;
            status.Text = "The agent starts with Windows, and its tray icon (bottom right) has Pair new phone and Exit. To uninstall: Settings > Apps.";
            status.ForeColor = Color.FromArgb(210, 255, 255, 255);
            main.Text = "Finish";
            main.Enabled = true;
            finished = true;
        }

        void Finish()
        {
            try { Process.Start(new ProcessStartInfo(installedExe) { UseShellExecute = false }); } catch (Exception) { }
            Close();
        }
    }
}
