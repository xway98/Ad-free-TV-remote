// Pocket Remote PC agent: lets the phone app move the mouse, type and press keys on this laptop.
//
// Runs as a tray icon, starts with Windows, listens on TCP 47891 (TLS) and announces itself with a UDP broadcast on
// 47892 so the phone finds it as soon as the laptop is on the network. A phone has to be paired once with a 6-digit
// code shown on this screen; after that it authenticates with a random token.
//
// Written for the C# 5 compiler that ships with Windows (csc.exe), so it needs no SDK to build: see build.ps1.

using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Security;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using System.Security.Authentication;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using System.Threading;
using System.Windows.Forms;
using Microsoft.Win32;

namespace PocketRemotePc
{
    // ---- wire protocol ----------------------------------------------------------------------------------
    // Every message is [u16 big-endian length][payload]; payload[0] is the opcode. Numbers are big-endian.
    static class Op
    {
        // phone -> laptop
        public const byte Auth = 0x01;       // token(32)
        public const byte Pair = 0x02;       // hmac(32) name...   hmac = HMAC-SHA256(key=code, msg=certificate sha256)
        public const byte Move = 0x10;       // i16 dx, i16 dy
        public const byte Button = 0x11;     // u8 button (1 left, 2 right, 3 middle, 4 back, 5 forward), u8 down
        public const byte Scroll = 0x12;     // i16 vertical, i16 horizontal (120 = one notch, positive = up / right)
        public const byte Key = 0x20;        // u16 virtual key, u8 down
        public const byte Text = 0x21;       // utf-8 text typed as-is
        public const byte Chord = 0x22;      // u8 n, n * u16 virtual keys: pressed in order, released in reverse
        public const byte System = 0x30;     // u8: 1 lock, 2 sleep, 3 restart, 4 shut down, 5 screen off
        public const byte Launch = 0x31;     // utf-8 "app:<Start menu app id>" or "run:<path or link>[\u0001arguments]"
        public const byte ListApps = 0x32;   // asks for the Start menu's apps (optional u8: 1 = rescan); answered with Apps
        public const byte ListWindows = 0x33; // asks for the open windows; answered with Windows
        public const byte FocusWindow = 0x34; // i64 window handle: bring it to the front
        public const byte CloseWindow = 0x35; // i64 window handle: ask it to close
        public const byte ClipSet = 0x36;     // utf-8 text: put it on the laptop's clipboard
        public const byte ClipGet = 0x37;     // asks for the laptop's clipboard text; answered with Clip
        public const byte Ping = 0x40;
        public const byte ReleaseAll = 0x50;
        // laptop -> phone
        public const byte Pong = 0x41;
        public const byte AuthOk = 0x80;     // laptop name
        public const byte PairOk = 0x81;     // token(32) laptop name
        public const byte Apps = 0x82;       // lines of "app:<id>\t<name>"
        public const byte Windows = 0x83;    // lines of "handle\tforeground(0/1)\tprocess\ttitle", front-most first
        public const byte Clip = 0x84;       // utf-8 text
        public const byte Denied = 0x8E;     // reason
    }

    static class Log
    {
        static readonly object gate = new object();
        static string path;

        public static void Init(string dir)
        {
            path = Path.Combine(dir, "agent.log");
            try { if (File.Exists(path) && new FileInfo(path).Length > 200000) File.Delete(path); } catch (Exception) { }
        }

        public static void Write(string msg)
        {
            string line = DateTime.Now.ToString("HH:mm:ss.fff") + " " + msg;
            lock (gate)
            {
                try { if (path != null) File.AppendAllText(path, line + "\r\n"); } catch (Exception) { }
                try { Console.WriteLine(line); } catch (Exception) { }
            }
        }
    }

    // ---- injecting input ----------------------------------------------------------------------------------
    static class Input
    {
        public static bool Dry;

        [StructLayout(LayoutKind.Sequential)]
        struct MOUSEINPUT { public int dx, dy; public uint mouseData, dwFlags, time; public IntPtr dwExtraInfo; }
        [StructLayout(LayoutKind.Sequential)]
        struct KEYBDINPUT { public ushort wVk, wScan; public uint dwFlags, time; public IntPtr dwExtraInfo; }
        [StructLayout(LayoutKind.Sequential)]
        struct HARDWAREINPUT { public uint uMsg; public ushort wParamL, wParamH; }
        [StructLayout(LayoutKind.Explicit)]
        struct InputUnion
        {
            [FieldOffset(0)] public MOUSEINPUT mi;
            [FieldOffset(0)] public KEYBDINPUT ki;
            [FieldOffset(0)] public HARDWAREINPUT hi;
        }
        [StructLayout(LayoutKind.Sequential)]
        struct INPUT { public uint type; public InputUnion u; }

        [DllImport("user32.dll", SetLastError = true)] static extern uint SendInput(uint n, INPUT[] inputs, int size);
        [DllImport("user32.dll")] static extern uint MapVirtualKey(uint code, uint mapType);
        [DllImport("user32.dll")] static extern bool LockWorkStation();
        [DllImport("user32.dll")] static extern IntPtr SendMessage(IntPtr hWnd, uint msg, IntPtr wParam, IntPtr lParam);
        [DllImport("powrprof.dll")] static extern bool SetSuspendState(bool hibernate, bool force, bool disableWakeEvent);

        const uint MOUSE = 0, KEYBOARD = 1;
        const uint MOVE = 0x0001, LEFTDOWN = 0x0002, LEFTUP = 0x0004, RIGHTDOWN = 0x0008, RIGHTUP = 0x0010,
                   MIDDLEDOWN = 0x0020, MIDDLEUP = 0x0040, XDOWN = 0x0080, XUP = 0x0100, WHEEL = 0x0800, HWHEEL = 0x1000;
        const uint KEYUP = 0x0002, UNICODE = 0x0004, EXTENDED = 0x0001;

        static readonly object gate = new object();
        static readonly HashSet<int> heldButtons = new HashSet<int>();
        static readonly HashSet<int> heldKeys = new HashSet<int>();

        static void Send(INPUT[] inputs)
        {
            if (inputs.Length == 0) return;
            uint sent = SendInput((uint)inputs.Length, inputs, Marshal.SizeOf(typeof(INPUT)));
            if (sent != inputs.Length) Log.Write("SendInput sent " + sent + " of " + inputs.Length + " (error " + Marshal.GetLastWin32Error() + ")");
        }

        static INPUT Mouse(uint flags, int dx, int dy, uint data)
        {
            INPUT i = new INPUT();
            i.type = MOUSE;
            i.u.mi.dx = dx;
            i.u.mi.dy = dy;
            i.u.mi.dwFlags = flags;
            i.u.mi.mouseData = data;
            return i;
        }

        static INPUT Keyboard(int vk, bool down)
        {
            INPUT i = new INPUT();
            i.type = KEYBOARD;
            i.u.ki.wVk = (ushort)vk;
            i.u.ki.wScan = (ushort)MapVirtualKey((uint)vk, 0);
            uint f = down ? 0u : KEYUP;
            if (IsExtended(vk)) f |= EXTENDED;
            i.u.ki.dwFlags = f;
            return i;
        }

        static INPUT Unicode(char c, bool down)
        {
            INPUT i = new INPUT();
            i.type = KEYBOARD;
            i.u.ki.wScan = c;
            i.u.ki.dwFlags = UNICODE | (down ? 0u : KEYUP);
            return i;
        }

        static bool IsExtended(int vk)
        {
            return (vk >= 0x21 && vk <= 0x28) || vk == 0x2C || vk == 0x2D || vk == 0x2E || vk == 0x5B || vk == 0x5C
                || vk == 0x5D || vk == 0xA3 || vk == 0xA5 || vk == 0x6F || vk == 0x90;
        }

        public static void Move(int dx, int dy)
        {
            if (Dry) { Log.Write("move " + dx + "," + dy); return; }
            Send(new INPUT[] { Mouse(MOVE, dx, dy, 0) });
        }

        public static void Button(int button, bool down)
        {
            if (Dry) { Log.Write("button " + button + (down ? " down" : " up")); }
            lock (gate) { if (down) heldButtons.Add(button); else heldButtons.Remove(button); }
            if (Dry) return;
            uint flags; uint data = 0;
            switch (button)
            {
                case 1: flags = down ? LEFTDOWN : LEFTUP; break;
                case 2: flags = down ? RIGHTDOWN : RIGHTUP; break;
                case 3: flags = down ? MIDDLEDOWN : MIDDLEUP; break;
                case 4: flags = down ? XDOWN : XUP; data = 1; break;
                case 5: flags = down ? XDOWN : XUP; data = 2; break;
                default: return;
            }
            Send(new INPUT[] { Mouse(flags, 0, 0, data) });
        }

        public static void Scroll(int vertical, int horizontal)
        {
            if (Dry) { Log.Write("scroll " + vertical + "," + horizontal); return; }
            List<INPUT> l = new List<INPUT>();
            if (vertical != 0) l.Add(Mouse(WHEEL, 0, 0, unchecked((uint)vertical)));
            if (horizontal != 0) l.Add(Mouse(HWHEEL, 0, 0, unchecked((uint)horizontal)));
            Send(l.ToArray());
        }

        public static void Key(int vk, bool down)
        {
            if (Dry) { Log.Write("key " + vk + (down ? " down" : " up")); }
            lock (gate) { if (down) heldKeys.Add(vk); else heldKeys.Remove(vk); }
            if (Dry) return;
            Send(new INPUT[] { Keyboard(vk, down) });
        }

        public static void Chord(int[] vks)
        {
            if (Dry) { Log.Write("chord " + string.Join("+", Array.ConvertAll(vks, delegate(int v) { return v.ToString(); }))); return; }
            List<INPUT> l = new List<INPUT>();
            foreach (int vk in vks) l.Add(Keyboard(vk, true));
            for (int i = vks.Length - 1; i >= 0; i--) l.Add(Keyboard(vks[i], false));
            Send(l.ToArray());
        }

        public static void Text(string text)
        {
            if (Dry) { Log.Write("text \"" + text + "\""); return; }
            List<INPUT> l = new List<INPUT>();
            foreach (char c in text)
            {
                if (c == '\r') continue;
                if (c == '\n') { l.Add(Keyboard(0x0D, true)); l.Add(Keyboard(0x0D, false)); }
                else if (c == '\t') { l.Add(Keyboard(0x09, true)); l.Add(Keyboard(0x09, false)); }
                else { l.Add(Unicode(c, true)); l.Add(Unicode(c, false)); }
            }
            Send(l.ToArray());
        }

        /** Lets go of everything the phone still holds, so a dropped connection never leaves a key or button stuck. */
        public static void ReleaseAll()
        {
            int[] buttons, keys;
            lock (gate)
            {
                buttons = new int[heldButtons.Count]; heldButtons.CopyTo(buttons);
                keys = new int[heldKeys.Count]; heldKeys.CopyTo(keys);
            }
            foreach (int b in buttons) Button(b, false);
            foreach (int k in keys) Key(k, false);
        }

        public static void SystemAction(int action)
        {
            Log.Write("system " + action);
            if (Dry) return;
            switch (action)
            {
                case 1: LockWorkStation(); break;
                case 2: SetSuspendState(false, true, false); break;
                case 3: Process.Start(new ProcessStartInfo("shutdown", "/r /t 0") { CreateNoWindow = true, UseShellExecute = false }); break;
                case 4: Process.Start(new ProcessStartInfo("shutdown", "/s /t 0") { CreateNoWindow = true, UseShellExecute = false }); break;
                case 5: SendMessage((IntPtr)0xFFFF, 0x0112, (IntPtr)0xF170, (IntPtr)2); break;
            }
        }
    }

    // ---- opening apps -------------------------------------------------------------------------------------
    static class Apps
    {
        static readonly object gate = new object();
        static string cached;
        static DateTime cachedAt;

        /** Opens a Start menu app ("app:<id>") or a path, folder or link ("run:<target>[\u0001arguments]"). */
        public static void Launch(string id)
        {
            Log.Write("launch " + id.Replace('\u0001', ' '));
            if (Input.Dry) return;
            try
            {
                if (id.StartsWith("app:"))
                {
                    Process.Start(new ProcessStartInfo("explorer.exe", "\"shell:AppsFolder\\" + id.Substring(4) + "\"") { UseShellExecute = false });
                }
                else if (id.StartsWith("run:"))
                {
                    string[] p = id.Substring(4).Split('\u0001');
                    ProcessStartInfo psi = new ProcessStartInfo(p[0]);
                    if (p.Length > 1 && p[1].Length > 0) psi.Arguments = p[1];
                    psi.UseShellExecute = true;
                    Process.Start(psi);
                }
            }
            catch (Exception e) { Log.Write("launch failed: " + e.Message); }
        }

        /**
         * The Start menu's apps (desktop and Store apps alike) as lines of "app:<id>\t<name>". An answer is reused for
         * half a minute so opening the list twice is instant; rescan asks Windows again whatever is remembered.
         */
        public static string List(bool rescan)
        {
            lock (gate)
            {
                if (!rescan && cached != null && DateTime.UtcNow - cachedAt < TimeSpan.FromSeconds(30)) return cached;
            }
            StringBuilder sb = new StringBuilder();
            try
            {
                ProcessStartInfo psi = new ProcessStartInfo("powershell.exe",
                    "-NoProfile -NonInteractive -Command \"[Console]::OutputEncoding=[Text.Encoding]::UTF8; " +
                    "Get-StartApps | Sort-Object Name | ForEach-Object { $_.AppID + [char]9 + $_.Name }\"");
                psi.UseShellExecute = false;
                psi.CreateNoWindow = true;
                psi.RedirectStandardOutput = true;
                psi.StandardOutputEncoding = Encoding.UTF8;
                using (Process p = Process.Start(psi))
                {
                    string all = p.StandardOutput.ReadToEnd();
                    if (!p.WaitForExit(20000)) { try { p.Kill(); } catch (Exception) { } }
                    HashSet<string> seen = new HashSet<string>();
                    foreach (string raw in all.Split('\n'))
                    {
                        string line = raw.Trim('\r', ' ');
                        int tab = line.IndexOf('\t');
                        if (tab <= 0 || tab == line.Length - 1) continue;
                        string name = line.Substring(tab + 1), id = line.Substring(0, tab);
                        if (!seen.Add(id)) continue;
                        string entry = "app:" + id + "\t" + name + "\n";
                        if (Encoding.UTF8.GetByteCount(sb.ToString()) + Encoding.UTF8.GetByteCount(entry) > 60000) break;
                        sb.Append(entry);
                    }
                }
            }
            catch (Exception e) { Log.Write("listing apps failed: " + e.Message); }
            string result = sb.ToString();
            if (result.Length > 0) lock (gate) { cached = result; cachedAt = DateTime.UtcNow; }
            return result;
        }
    }

    // ---- windows and the clipboard ------------------------------------------------------------------------
    static class Windows
    {
        delegate bool EnumProc(IntPtr h, IntPtr l);

        [DllImport("user32.dll")] static extern bool EnumWindows(EnumProc p, IntPtr l);
        [DllImport("user32.dll")] static extern bool IsWindow(IntPtr h);
        [DllImport("user32.dll")] static extern bool IsWindowVisible(IntPtr h);
        [DllImport("user32.dll")] static extern bool IsIconic(IntPtr h);
        [DllImport("user32.dll")] static extern bool ShowWindow(IntPtr h, int cmd);
        [DllImport("user32.dll")] static extern int GetWindowTextLength(IntPtr h);
        [DllImport("user32.dll", CharSet = CharSet.Unicode)] static extern int GetWindowText(IntPtr h, StringBuilder s, int max);
        [DllImport("user32.dll")] static extern IntPtr GetWindow(IntPtr h, uint cmd);
        [DllImport("user32.dll")] static extern int GetWindowLong(IntPtr h, int index);
        [DllImport("user32.dll")] static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);
        [DllImport("user32.dll")] static extern IntPtr GetForegroundWindow();
        [DllImport("user32.dll")] static extern bool SetForegroundWindow(IntPtr h);
        [DllImport("user32.dll")] static extern bool BringWindowToTop(IntPtr h);
        [DllImport("user32.dll")] static extern bool AttachThreadInput(uint a, uint b, bool attach);
        [DllImport("user32.dll")] static extern bool PostMessage(IntPtr h, uint msg, IntPtr w, IntPtr l);
        [DllImport("kernel32.dll")] static extern uint GetCurrentThreadId();
        [DllImport("dwmapi.dll")] static extern int DwmGetWindowAttribute(IntPtr h, int attr, out int value, int size);

        /** The windows you would see in Alt+Tab, front-most first: "handle\tforeground\tprocess\ttitle" per line. */
        public static string List()
        {
            StringBuilder sb = new StringBuilder();
            IntPtr front = GetForegroundWindow();
            uint me = (uint)Process.GetCurrentProcess().Id;
            EnumWindows(delegate(IntPtr h, IntPtr l)
            {
                try
                {
                    if (!IsWindowVisible(h) || GetWindowTextLength(h) == 0) return true;
                    int ex = GetWindowLong(h, -20);
                    bool toolWindow = (ex & 0x80) != 0, appWindow = (ex & 0x40000) != 0;
                    if (!appWindow && (toolWindow || GetWindow(h, 4) != IntPtr.Zero)) return true;     // owned dialogs, tool windows
                    int cloaked;
                    if (DwmGetWindowAttribute(h, 14, out cloaked, 4) == 0 && cloaked != 0) return true;  // Store apps parked on other desktops
                    uint pid;
                    GetWindowThreadProcessId(h, out pid);
                    if (pid == me) return true;
                    StringBuilder title = new StringBuilder(GetWindowTextLength(h) + 1);
                    GetWindowText(h, title, title.Capacity);
                    string t = title.ToString().Replace('\t', ' ').Replace('\n', ' ').Replace('\r', ' ');
                    if (t == "Program Manager") return true;
                    string proc = "";
                    try { proc = Process.GetProcessById((int)pid).ProcessName; } catch (Exception) { }
                    string line = h.ToInt64() + "\t" + (h == front ? "1" : "0") + "\t" + proc + "\t" + t + "\n";
                    if (Encoding.UTF8.GetByteCount(sb.ToString()) + Encoding.UTF8.GetByteCount(line) < 60000) sb.Append(line);
                }
                catch (Exception) { }
                return true;
            }, IntPtr.Zero);
            return sb.ToString();
        }

        /** Brings a window to the front, restoring it first when it is minimised. */
        public static void Focus(long handle)
        {
            Log.Write("focus window");
            if (Input.Dry) return;
            IntPtr h = new IntPtr(handle);
            if (!IsWindow(h)) return;
            if (IsIconic(h)) ShowWindow(h, 9);
            // Windows only lets the program in front hand the front to someone else, so borrow its input thread for a moment
            uint pid, mine = GetCurrentThreadId();
            IntPtr front = GetForegroundWindow();
            uint theirs = front == IntPtr.Zero ? 0 : GetWindowThreadProcessId(front, out pid);
            bool attached = theirs != 0 && theirs != mine && AttachThreadInput(mine, theirs, true);
            BringWindowToTop(h);
            SetForegroundWindow(h);
            if (attached) AttachThreadInput(mine, theirs, false);
        }

        /** Asks a window to close the way its own X button would (it may still ask about unsaved work). */
        public static void Close(long handle)
        {
            Log.Write("close window");
            if (Input.Dry) return;
            PostMessage(new IntPtr(handle), 0x0010, IntPtr.Zero, IntPtr.Zero);
        }
    }

    static class Clip
    {
        public static void Set(string text)
        {
            Log.Write("clipboard set, " + text.Length + " characters");
            if (Input.Dry) return;
            RunSta(delegate { Clipboard.SetText(text, TextDataFormat.UnicodeText); });
        }

        public static string Get()
        {
            string r = "";
            RunSta(delegate { if (Clipboard.ContainsText()) r = Clipboard.GetText(); });
            // one reply holds about 60 KB: cut longer text on a whole character
            while (Encoding.UTF8.GetByteCount(r) > 60000) r = r.Substring(0, r.Length - Math.Max(1, r.Length / 20));
            Log.Write("clipboard read, " + r.Length + " characters");
            return r;
        }

        /** The clipboard only works on a thread of its own kind (STA), and another program may hold it for a moment. */
        static void RunSta(ThreadStart work)
        {
            Thread t = new Thread(delegate()
            {
                for (int i = 0; i < 5; i++)
                {
                    try { work(); return; }
                    catch (Exception) { Thread.Sleep(80); }
                }
                Log.Write("the clipboard was busy");
            });
            t.SetApartmentState(ApartmentState.STA);
            t.IsBackground = true;
            t.Start();
            t.Join(4000);
        }
    }

    // ---- certificate and paired phones --------------------------------------------------------------------
    sealed class Store
    {
        public readonly string Dir;
        public X509Certificate2 Cert;
        public byte[] Fingerprint;
        readonly object gate = new object();
        readonly List<string[]> phones = new List<string[]>();   // [sha256(token) hex, phone name]

        public Store(string dir)
        {
            Dir = dir;
            Directory.CreateDirectory(dir);
            LoadCert();
            LoadPhones();
        }

        void LoadCert()
        {
            string f = Path.Combine(Dir, "cert.bin");
            if (File.Exists(f))
            {
                try
                {
                    byte[] pfx = ProtectedData.Unprotect(File.ReadAllBytes(f), null, DataProtectionScope.CurrentUser);
                    Cert = new X509Certificate2(pfx, "", X509KeyStorageFlags.Exportable | X509KeyStorageFlags.PersistKeySet);
                }
                catch (Exception e) { Log.Write("certificate unreadable, making a new one: " + e.Message); Cert = null; }
            }
            if (Cert == null)
            {
                using (RSACryptoServiceProvider rsa = new RSACryptoServiceProvider(2048))
                {
                    CertificateRequest req = new CertificateRequest("CN=Pocket Remote PC agent", rsa, HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1);
                    byte[] pfx;
                    using (X509Certificate2 tmp = req.CreateSelfSigned(DateTimeOffset.UtcNow.AddDays(-1), DateTimeOffset.UtcNow.AddYears(30)))
                        pfx = tmp.Export(X509ContentType.Pfx, "");
                    File.WriteAllBytes(f, ProtectedData.Protect(pfx, null, DataProtectionScope.CurrentUser));
                    Cert = new X509Certificate2(pfx, "", X509KeyStorageFlags.Exportable | X509KeyStorageFlags.PersistKeySet);
                }
                Log.Write("made a new certificate");
            }
            using (SHA256 sha = SHA256.Create()) Fingerprint = sha.ComputeHash(Cert.RawData);
        }

        string PhonesFile { get { return Path.Combine(Dir, "phones.txt"); } }

        void LoadPhones()
        {
            if (!File.Exists(PhonesFile)) return;
            foreach (string line in File.ReadAllLines(PhonesFile))
            {
                string[] p = line.Split(new char[] { '\t' }, 2);
                if (p.Length == 2) phones.Add(p);
            }
        }

        public int Count { get { lock (gate) return phones.Count; } }

        static string HashOf(byte[] token)
        {
            using (SHA256 sha = SHA256.Create()) return Convert.ToBase64String(sha.ComputeHash(token));
        }

        public void Add(byte[] token, string name)
        {
            lock (gate)
            {
                phones.Add(new string[] { HashOf(token), name.Replace('\t', ' ').Replace('\n', ' ') });
                Save();
            }
        }

        /** The phone's name if this token was handed out by a pairing, otherwise null. */
        public string Check(byte[] token)
        {
            string h = HashOf(token);
            lock (gate)
            {
                foreach (string[] p in phones) if (p[0] == h) return p[1];
            }
            return null;
        }

        public void Forget()
        {
            lock (gate) { phones.Clear(); Save(); }
        }

        void Save()
        {
            List<string> lines = new List<string>();
            foreach (string[] p in phones) lines.Add(p[0] + "\t" + p[1]);
            File.WriteAllLines(PhonesFile, lines.ToArray());
        }
    }

    // ---- the network side ---------------------------------------------------------------------------------
    sealed class Server
    {
        public const int DefaultPort = 47891, BeaconPort = 47892;

        /** 2 added opening apps, 3 rescanning them on request, 4 the window list and clipboard; the phone reads this to tell an old agent from a broken one. */
        public const int Protocol = 4;

        public event Action<string> Paired;

        readonly Store store;
        readonly int port;
        readonly object gate = new object();
        readonly ManualResetEvent networkChanged = new ManualResetEvent(false);
        TcpListener listener;
        Session current;
        string pairCode;
        DateTime pairUntil;
        int pairFails;

        sealed class Session
        {
            public TcpClient Client;
            public void Close() { try { Client.Close(); } catch (Exception) { } }
        }

        public Server(Store store, int port)
        {
            this.store = store;
            this.port = port;
        }

        public Store Store { get { return store; } }
        public int Port { get { return port; } }

        public void Start()
        {
            listener = new TcpListener(IPAddress.Any, port);
            listener.Start(8);
            new Thread(AcceptLoop) { IsBackground = true, Name = "accept" }.Start();
            new Thread(BeaconLoop) { IsBackground = true, Name = "beacon" }.Start();
            NetworkChange.NetworkAddressChanged += delegate { networkChanged.Set(); };
            Log.Write("listening on " + port + ", certificate " + Hex(store.Fingerprint).Substring(0, 16));
        }

        public void Stop()
        {
            try { listener.Stop(); } catch (Exception) { }
            Session s = current;
            if (s != null) s.Close();
        }

        /** Opens a two-minute pairing window and returns the code to show on screen. */
        public string OpenPairing()
        {
            byte[] b = new byte[4];
            using (RandomNumberGenerator rng = RandomNumberGenerator.Create()) rng.GetBytes(b);
            lock (gate)
            {
                pairCode = (BitConverter.ToUInt32(b, 0) % 1000000).ToString("D6");
                pairUntil = DateTime.UtcNow.AddMinutes(2);
                pairFails = 0;
                Log.Write("pairing opened");
                return pairCode;
            }
        }

        public void ClosePairing()
        {
            lock (gate) { pairCode = null; }
        }

        public static string Hex(byte[] b)
        {
            StringBuilder sb = new StringBuilder();
            foreach (byte x in b) sb.Append(x.ToString("x2"));
            return sb.ToString();
        }

        void AcceptLoop()
        {
            while (true)
            {
                TcpClient c;
                try { c = listener.AcceptTcpClient(); }
                catch (Exception) { return; }
                TcpClient client = c;
                new Thread(delegate() { Handle(client); }) { IsBackground = true, Name = "session" }.Start();
            }
        }

        static byte[] ReadExact(Stream s, int n)
        {
            byte[] b = new byte[n];
            int got = 0;
            while (got < n)
            {
                int r = s.Read(b, got, n - got);
                if (r <= 0) throw new EndOfStreamException();
                got += r;
            }
            return b;
        }

        static byte[] ReadFrame(Stream s)
        {
            byte[] h = ReadExact(s, 2);
            int n = (h[0] << 8) | h[1];
            return n == 0 ? new byte[0] : ReadExact(s, n);
        }

        static void WriteFrame(Stream s, byte op, byte[] a, byte[] b)
        {
            int n = 1 + (a == null ? 0 : a.Length) + (b == null ? 0 : b.Length);
            byte[] buf = new byte[2 + n];
            buf[0] = (byte)(n >> 8);
            buf[1] = (byte)n;
            buf[2] = op;
            int o = 3;
            if (a != null) { Buffer.BlockCopy(a, 0, buf, o, a.Length); o += a.Length; }
            if (b != null) Buffer.BlockCopy(b, 0, buf, o, b.Length);
            lock (s)      // the session thread and an app-list answer can both write
            {
                s.Write(buf, 0, buf.Length);
                s.Flush();
            }
        }

        static bool SameBytes(byte[] a, byte[] b)
        {
            if (a.Length != b.Length) return false;
            int d = 0;
            for (int i = 0; i < a.Length; i++) d |= a[i] ^ b[i];
            return d == 0;
        }

        void Handle(TcpClient client)
        {
            Session me = new Session();
            me.Client = client;
            bool authed = false;
            string who = "?";
            try
            {
                client.NoDelay = true;
                client.Client.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.KeepAlive, true);
                client.ReceiveTimeout = 10000;
                using (SslStream ssl = new SslStream(client.GetStream(), false))
                {
                    ssl.AuthenticateAsServer(store.Cert, false, SslProtocols.Tls12, false);
                    byte[] first = ReadFrame(ssl);
                    string name = Authenticate(ssl, first);
                    if (name == null) return;
                    who = name;
                    authed = true;
                    Session old;
                    lock (gate) { old = current; current = me; }
                    if (old != null) old.Close();      // a reconnecting phone replaces its own stale connection
                    Log.Write("connected: " + who);
                    client.ReceiveTimeout = 15000;     // the phone pings every 2 seconds

                    while (true)
                    {
                        byte[] p = ReadFrame(ssl);
                        if (p.Length == 0) continue;
                        if (!Dispatch(ssl, p)) break;
                    }
                }
            }
            catch (Exception e)
            {
                if (authed) Log.Write("lost " + who + ": " + e.GetType().Name);
            }
            finally
            {
                bool wasCurrent;
                lock (gate) { wasCurrent = (current == me); if (wasCurrent) current = null; }
                if (wasCurrent) Input.ReleaseAll();
                me.Close();
                if (authed) Log.Write("disconnected: " + who);
            }
        }

        /** Returns the phone's name when the first message is a valid token or a valid pairing, otherwise null. */
        string Authenticate(Stream ssl, byte[] p)
        {
            if (p.Length == 33 && p[0] == Op.Auth)
            {
                byte[] token = new byte[32];
                Buffer.BlockCopy(p, 1, token, 0, 32);
                string name = store.Check(token);
                if (name == null)
                {
                    Thread.Sleep(1000);
                    WriteFrame(ssl, Op.Denied, Encoding.UTF8.GetBytes("not paired"), null);
                    return null;
                }
                WriteFrame(ssl, Op.AuthOk, Encoding.UTF8.GetBytes(Environment.MachineName + "\n" + Protocol), null);
                return name;
            }
            if (p.Length > 33 && p[0] == Op.Pair)
            {
                byte[] mac = new byte[32];
                Buffer.BlockCopy(p, 1, mac, 0, 32);
                string phone = Encoding.UTF8.GetString(p, 33, p.Length - 33);
                string code;
                lock (gate)
                {
                    if (pairCode == null || DateTime.UtcNow > pairUntil) code = null;
                    else if (pairFails >= 5) { pairCode = null; code = null; }
                    else code = pairCode;
                }
                if (code == null)
                {
                    WriteFrame(ssl, Op.Denied, Encoding.UTF8.GetBytes("pairing is not open on the laptop"), null);
                    return null;
                }
                byte[] expect;
                using (HMACSHA256 h = new HMACSHA256(Encoding.ASCII.GetBytes(code))) expect = h.ComputeHash(store.Fingerprint);
                if (!SameBytes(mac, expect))
                {
                    lock (gate) pairFails++;
                    Thread.Sleep(500);
                    WriteFrame(ssl, Op.Denied, Encoding.UTF8.GetBytes("wrong code"), null);
                    return null;
                }
                byte[] token = new byte[32];
                using (RandomNumberGenerator rng = RandomNumberGenerator.Create()) rng.GetBytes(token);
                store.Add(token, phone);
                ClosePairing();
                WriteFrame(ssl, Op.PairOk, token, Encoding.UTF8.GetBytes(Environment.MachineName));
                Log.Write("paired with " + phone);
                Action<string> cb = Paired;
                if (cb != null) cb(phone);
                return phone;
            }
            return null;
        }

        static long I64(byte[] p, int o)
        {
            long v = 0;
            for (int i = 0; i < 8; i++) v = (v << 8) | p[o + i];
            return v;
        }

        static int I16(byte[] p, int o)
        {
            return (short)((p[o] << 8) | p[o + 1]);
        }

        /** Runs one message from the phone. False ends the session. */
        bool Dispatch(Stream ssl, byte[] p)
        {
            switch (p[0])
            {
                case Op.Move:
                    if (p.Length >= 5) Input.Move(I16(p, 1), I16(p, 3));
                    break;
                case Op.Button:
                    if (p.Length >= 3) Input.Button(p[1], p[2] != 0);
                    break;
                case Op.Scroll:
                    if (p.Length >= 5) Input.Scroll(I16(p, 1), I16(p, 3));
                    break;
                case Op.Key:
                    if (p.Length >= 4) Input.Key((p[1] << 8) | p[2], p[3] != 0);
                    break;
                case Op.Text:
                    if (p.Length > 1) Input.Text(Encoding.UTF8.GetString(p, 1, p.Length - 1));
                    break;
                case Op.Chord:
                    if (p.Length >= 2)
                    {
                        int n = Math.Min(p[1], (p.Length - 2) / 2);
                        int[] vks = new int[n];
                        for (int i = 0; i < n; i++) vks[i] = (p[2 + 2 * i] << 8) | p[3 + 2 * i];
                        Input.Chord(vks);
                    }
                    break;
                case Op.System:
                    if (p.Length >= 2) Input.SystemAction(p[1]);
                    break;
                case Op.Launch:
                    if (p.Length > 1) Apps.Launch(Encoding.UTF8.GetString(p, 1, p.Length - 1));
                    break;
                case Op.ListApps:
                    bool rescan = p.Length >= 2 && p[1] != 0;
                    ThreadPool.QueueUserWorkItem(delegate
                    {
                        try
                        {
                            string apps = Apps.List(rescan);
                            Log.Write("sending " + apps.Split('\n').Length + " apps");
                            WriteFrame(ssl, Op.Apps, Encoding.UTF8.GetBytes(apps), null);
                        }
                        catch (Exception) { }      // the phone went away while we were asking Windows
                    });
                    break;
                case Op.ListWindows:
                    ThreadPool.QueueUserWorkItem(delegate
                    {
                        try { WriteFrame(ssl, Op.Windows, Encoding.UTF8.GetBytes(Windows.List()), null); }
                        catch (Exception) { }
                    });
                    break;
                case Op.FocusWindow:
                    if (p.Length >= 9) Windows.Focus(I64(p, 1));
                    break;
                case Op.CloseWindow:
                    if (p.Length >= 9) Windows.Close(I64(p, 1));
                    break;
                case Op.ClipSet:
                    if (p.Length > 1) Clip.Set(Encoding.UTF8.GetString(p, 1, p.Length - 1));
                    break;
                case Op.ClipGet:
                    ThreadPool.QueueUserWorkItem(delegate
                    {
                        try { WriteFrame(ssl, Op.Clip, Encoding.UTF8.GetBytes(Clip.Get()), null); }
                        catch (Exception) { }
                    });
                    break;
                case Op.ReleaseAll:
                    Input.ReleaseAll();
                    break;
                case Op.Ping:
                    WriteFrame(ssl, Op.Pong, null, null);
                    break;
            }
            return true;
        }

        // ---- announcing ourselves ----

        public static List<IPAddress> LocalAddresses(List<IPAddress> broadcasts)
        {
            List<IPAddress> ips = new List<IPAddress>();
            try
            {
                foreach (NetworkInterface ni in NetworkInterface.GetAllNetworkInterfaces())
                {
                    if (ni.OperationalStatus != OperationalStatus.Up || ni.NetworkInterfaceType == NetworkInterfaceType.Loopback) continue;
                    foreach (UnicastIPAddressInformation u in ni.GetIPProperties().UnicastAddresses)
                    {
                        if (u.Address.AddressFamily != AddressFamily.InterNetwork || u.IPv4Mask == null) continue;
                        byte[] a = u.Address.GetAddressBytes(), m = u.IPv4Mask.GetAddressBytes();
                        if (a[0] == 169 && a[1] == 254) continue;
                        ips.Add(u.Address);
                        if (broadcasts != null)
                        {
                            byte[] b = new byte[4];
                            for (int i = 0; i < 4; i++) b[i] = (byte)(a[i] | ~m[i]);
                            broadcasts.Add(new IPAddress(b));
                        }
                    }
                }
            }
            catch (Exception) { }
            return ips;
        }

        void BeaconLoop()
        {
            string fp = Hex(store.Fingerprint).Substring(0, 16);
            using (UdpClient udp = new UdpClient())
            {
                udp.EnableBroadcast = true;
                while (true)
                {
                    byte[] msg = Encoding.UTF8.GetBytes("TVRPC1|" + Environment.MachineName + "|" + port + "|" + fp);
                    List<IPAddress> targets = new List<IPAddress>();
                    LocalAddresses(targets);
                    targets.Add(IPAddress.Broadcast);
                    foreach (IPAddress t in targets)
                    {
                        try { udp.Send(msg, msg.Length, new IPEndPoint(t, BeaconPort)); } catch (Exception) { }
                    }
                    // a network change (Wi-Fi back after wake) announces again at once instead of waiting
                    if (networkChanged.WaitOne(2000))
                    {
                        networkChanged.Reset();
                        Thread.Sleep(400);
                    }
                }
            }
        }
    }

    // ---- tray icon and pairing window ---------------------------------------------------------------------
    sealed class Tray : ApplicationContext
    {
        readonly Server server;
        readonly NotifyIcon icon;
        readonly ToolStripMenuItem startup, count;
        Form pairForm;

        const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run", RunName = "PocketRemotePc";

        public Tray(Server server)
        {
            this.server = server;
            ContextMenuStrip menu = new ContextMenuStrip();
            ToolStripMenuItem title = new ToolStripMenuItem("Pocket Remote PC agent") { Enabled = false };
            menu.Items.Add(title);
            count = new ToolStripMenuItem("") { Enabled = false };
            menu.Items.Add(count);
            menu.Items.Add(new ToolStripSeparator());
            menu.Items.Add("Pair new phone...", null, delegate { ShowPairing(); });
            menu.Items.Add("Forget all phones", null, delegate
            {
                if (MessageBox.Show("Forget every paired phone? Each one will have to be paired again.", "Pocket Remote PC agent",
                        MessageBoxButtons.YesNo, MessageBoxIcon.Question) == DialogResult.Yes)
                {
                    server.Store.Forget();
                    RefreshCount();
                }
            });
            startup = new ToolStripMenuItem("Start with Windows");
            startup.Click += delegate { SetStartup(!startup.Checked); };
            menu.Items.Add(startup);
            menu.Items.Add(new ToolStripSeparator());
            menu.Items.Add("Exit", null, delegate { icon.Visible = false; server.Stop(); Application.Exit(); });
            menu.Opening += delegate { RefreshCount(); startup.Checked = IsStartup(); };

            icon = new NotifyIcon();
            icon.Icon = MakeIcon();
            icon.Text = "Pocket Remote PC agent";
            icon.ContextMenuStrip = menu;
            icon.Visible = true;
            icon.DoubleClick += delegate { ShowPairing(); };
            server.Paired += delegate(string phone) { Notify("Paired with " + phone); };

            RefreshCount();
            if (server.Store.Count == 0) ShowPairing();
        }

        void RefreshCount()
        {
            int n = server.Store.Count;
            count.Text = n == 0 ? "No phones paired yet" : n + (n == 1 ? " phone paired" : " phones paired");
        }

        static Icon MakeIcon()
        {
            using (Bitmap bmp = new Bitmap(32, 32))
            {
                using (Graphics g = Graphics.FromImage(bmp))
                {
                    g.SmoothingMode = System.Drawing.Drawing2D.SmoothingMode.AntiAlias;
                    using (Brush b = new SolidBrush(Color.FromArgb(255, 53, 210))) g.FillEllipse(b, 1, 1, 30, 30);
                    using (Pen p = new Pen(Color.White, 3f))
                    {
                        g.DrawEllipse(p, 9, 7, 14, 18);
                        g.DrawLine(p, 16, 7, 16, 14);
                    }
                }
                return Icon.FromHandle(bmp.GetHicon());
            }
        }

        void Notify(string text)
        {
            icon.ShowBalloonTip(4000, "Pocket Remote PC agent", text, ToolTipIcon.Info);
            RefreshCount();
            if (pairForm != null && !pairForm.IsDisposed) pairForm.BeginInvoke(new Action(delegate { pairForm.Close(); }));
        }

        static bool IsStartup()
        {
            using (RegistryKey k = Registry.CurrentUser.OpenSubKey(RunKey)) return k != null && k.GetValue(RunName) != null;
        }

        static void SetStartup(bool on)
        {
            using (RegistryKey k = Registry.CurrentUser.OpenSubKey(RunKey, true))
            {
                if (k == null) return;
                if (on) k.SetValue(RunName, "\"" + Application.ExecutablePath + "\"");
                else k.DeleteValue(RunName, false);
            }
        }

        void ShowPairing()
        {
            if (pairForm != null && !pairForm.IsDisposed) { pairForm.Activate(); return; }
            string code = server.OpenPairing();
            Form f = new Form();
            f.Text = "Pair a phone";
            f.FormBorderStyle = FormBorderStyle.FixedDialog;
            f.MaximizeBox = false;
            f.MinimizeBox = false;
            f.TopMost = true;
            f.StartPosition = FormStartPosition.CenterScreen;
            f.ClientSize = new Size(380, 230);
            f.BackColor = Color.FromArgb(11, 7, 22);
            f.ForeColor = Color.White;

            Label head = new Label { Text = "On the phone: Laptop, then enter this code", AutoSize = false, TextAlign = ContentAlignment.MiddleCenter, Left = 10, Top = 14, Width = 360, Height = 24, Font = new Font("Segoe UI", 10.5f) };
            Label big = new Label { Text = code.Substring(0, 3) + " " + code.Substring(3), AutoSize = false, TextAlign = ContentAlignment.MiddleCenter, Left = 10, Top = 44, Width = 360, Height = 70, Font = new Font("Consolas", 40f, FontStyle.Bold), ForeColor = Color.FromArgb(255, 53, 210) };
            List<string> ips = new List<string>();
            foreach (IPAddress a in Server.LocalAddresses(null)) ips.Add(a.ToString());
            Label addr = new Label { Text = "This laptop: " + (ips.Count == 0 ? "no network" : string.Join(", ", ips.ToArray())) + "\nThe code works for 2 minutes.", AutoSize = false, TextAlign = ContentAlignment.MiddleCenter, Left = 10, Top = 124, Width = 360, Height = 52, Font = new Font("Segoe UI", 10f), ForeColor = Color.FromArgb(200, 255, 255, 255) };
            Button close = new Button { Text = "Cancel", Left = 140, Top = 184, Width = 100, Height = 30, FlatStyle = FlatStyle.System };
            close.Click += delegate { f.Close(); };
            f.Controls.Add(head);
            f.Controls.Add(big);
            f.Controls.Add(addr);
            f.Controls.Add(close);
            f.FormClosed += delegate { server.ClosePairing(); };
            pairForm = f;
            f.Show();
            f.Activate();
        }
    }

    static class Program
    {
        static string Arg(string[] a, string name)
        {
            for (int i = 0; i < a.Length - 1; i++) if (a[i] == name) return a[i + 1];
            return null;
        }

        static bool Has(string[] a, string name)
        {
            return Array.IndexOf(a, name) >= 0;
        }

        [STAThread]
        static int Main(string[] args)
        {
            Application.EnableVisualStyles();
            SetupOptions so = new SetupOptions();
            so.Dir = Arg(args, "--install-dir");
            so.Data = Arg(args, "--data");
            so.Silent = Has(args, "--silent");
            so.Sandbox = Has(args, "--sandbox");
            so.KeepData = Has(args, "--keep-data");
            if (Has(args, "--no-startup")) so.Startup = false;
            if (Has(args, "--no-shortcut")) so.Shortcut = false;
            if (Has(args, "--no-firewall")) so.Firewall = false;
            if (Has(args, "--public")) so.Public = true;

            // small jobs the installer starts (some with the Windows admin prompt)
            string fw = Arg(args, "--firewall-add");
            if (fw != null) return Setup.FirewallAdd(fw, Array.IndexOf(args, fw) + 1 < args.Length && args[Array.IndexOf(args, fw) + 1] == "1");
            if (Has(args, "--firewall-remove")) return Setup.FirewallRemove();
            string finalDir = Arg(args, "--uninstall-final");
            if (finalDir != null) { so.Dir = finalDir; return Setup.UninstallFinal(so); }
            if (Has(args, "--uninstall")) return Setup.Uninstall(so);

            bool dry = Has(args, "--dry"), noGui = Has(args, "--nogui"), pair = Has(args, "--pair");
            bool runAgent = dry || noGui || pair || Has(args, "--agent") || Setup.RunsFromInstall();

            // opened from the downloaded file (or with --silent): this is the installer
            if (!runAgent)
            {
                if (so.Silent)
                {
                    if (so.Dir == null) so.Dir = Setup.InstalledDir() ?? Setup.DefaultDir();
                    Directory.CreateDirectory(so.Dir);
                    Log.Init(so.Data ?? Setup.DefaultData());
                    Setup.Install(so, delegate(string s) { Console.WriteLine(s); });
                    Console.WriteLine("INSTALLED " + so.Dir);
                    return 0;
                }
                return Setup.Wizard(so);
            }

            int port = Server.DefaultPort;
            string p = Arg(args, "--port");
            if (p != null) port = int.Parse(p);
            string data = so.Data ?? Setup.DefaultData();

            bool created;
            Mutex single = new Mutex(true, "PocketRemotePcAgent" + (dry ? "-dry" + port : ""), out created);
            if (!created) return 0;

            Directory.CreateDirectory(data);
            Log.Init(data);
            Input.Dry = dry;
            Log.Write("starting Pocket Remote PC agent " + Setup.Version + ", protocol " + Server.Protocol + (dry ? " (dry run: nothing is injected)" : ""));

            Store store = new Store(data);
            Server server = new Server(store, port);
            try { server.Start(); }
            catch (Exception e)
            {
                Log.Write("cannot listen on " + port + ": " + e.Message);
                if (!noGui) MessageBox.Show("Pocket Remote PC agent cannot listen on port " + port + ".\n" + e.Message, "Pocket Remote PC agent");
                return 1;
            }

            if (noGui)
            {
                if (pair) Console.WriteLine("PAIRING_CODE " + server.OpenPairing());
                Console.WriteLine("READY");
                Thread.Sleep(Timeout.Infinite);
                return 0;
            }

            Application.Run(new Tray(server));
            GC.KeepAlive(single);
            return 0;
        }
    }
}

