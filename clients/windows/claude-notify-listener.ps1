<#
  claude-notify-listener.ps1
  Runs on the LOCAL Windows machine. A WebSocket client of the claude-remote
  daemon: it registers as a notification client, receives permission requests
  and notifications, shows them as native Windows toasts, and sends the user's
  Approve/Deny decision back over the same socket.

  This replaces the older HTTP-over-SSH-reverse-tunnel design (see ./legacy/):
  the daemon is now the single hook backend and fans each request out to ALL
  connected clients (this listener AND the Android app). The first client to
  answer wins; the daemon then broadcasts `permission_resolved` so the others
  dismiss the toast.

  How a button click gets back to the daemon
  ------------------------------------------
  WinRT toast action buttons activate a separate process (via the private
  `claudenotify:` URI scheme + a hidden VBScript shim), so they cannot touch
  this process's WebSocket directly. We therefore keep a tiny loopback
  HttpListener on 127.0.0.1:<CallbackPort>: the button shim POSTs the decision
  to /decide, and THIS process forwards it over the WebSocket as a
  `permission_response`. The main loop multiplexes the WebSocket receive and the
  HttpListener accept on a single thread (Task.WaitAny), so all socket sends
  happen from one thread (ClientWebSocket forbids concurrent sends).

  Connectivity
  ------------
  The listener dials OUT to the daemon, so reach it by either:
    - SSH LocalForward:  add `LocalForward 8770 127.0.0.1:8770` to the Windows
      ~/.ssh/config host block, then use -DaemonUrl ws://127.0.0.1:8770
    - LAN / WireGuard:   -DaemonUrl ws://<daemon-host-or-wg-ip>:8770

  Usage
  -----
    powershell -ExecutionPolicy Bypass -File claude-notify-listener.ps1 `
        -DaemonUrl ws://127.0.0.1:8770 -Token <paired-device-token>

  -FocusProcess lets you change which window types are eligible for focus; the
  most recently active one (highest in Z-order) wins. -Decide is used internally
  by the toast buttons and should not be passed manually.
#>
param(
    [string]$DaemonUrl = $(if ($env:CLAUDE_REMOTE_DAEMON_URL) { $env:CLAUDE_REMOTE_DAEMON_URL } else { 'ws://127.0.0.1:8770' }),
    [string]$Token = $(if ($env:CLAUDE_REMOTE_TOKEN) { $env:CLAUDE_REMOTE_TOKEN } else { 'windows-toast' }),
    [int]$CallbackPort = 58737,
    [string[]]$FocusProcess = @('Code', 'WindowsTerminal', 'OpenConsole', 'conhost', 'WindowsTerminalPreview', 'putty', 'mintty'),
    [string]$Decide
)

$AppId = '{1AC14E77-02E7-4E5D-B744-2EB1AE5198B7}\WindowsPowerShell\v1.0\powershell.exe'
$Scheme = 'claudenotify'

# --- Win32 helper: focus the most-recently-active session window ---------------
# Enumerates top-level windows front-to-back (Z-order) and activates the first one
# owned by a process in $FocusProcess. The AttachThreadInput dance is the standard
# workaround for Windows' foreground-lock restriction; it is best-effort.
Add-Type @"
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Runtime.InteropServices;

public class SessionFocus {
    delegate bool EnumProc(IntPtr h, IntPtr l);
    [DllImport("user32.dll")] static extern bool EnumWindows(EnumProc cb, IntPtr l);
    [DllImport("user32.dll")] static extern bool IsWindowVisible(IntPtr h);
    [DllImport("user32.dll")] static extern bool IsIconic(IntPtr h);
    [DllImport("user32.dll")] static extern IntPtr GetWindow(IntPtr h, uint cmd);
    [DllImport("user32.dll")] static extern int GetWindowTextLength(IntPtr h);
    [DllImport("user32.dll")] static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);
    [DllImport("user32.dll")] static extern int GetWindowLong(IntPtr h, int idx);
    [DllImport("user32.dll")] static extern bool SetForegroundWindow(IntPtr h);
    [DllImport("user32.dll")] static extern bool ShowWindow(IntPtr h, int n);
    [DllImport("user32.dll")] static extern bool BringWindowToTop(IntPtr h);
    [DllImport("user32.dll")] static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] static extern bool AttachThreadInput(uint a, uint b, bool f);
    [DllImport("kernel32.dll")] static extern uint GetCurrentThreadId();

    const uint GW_OWNER = 4;
    const int GWL_EXSTYLE = -20;
    const int WS_EX_TOOLWINDOW = 0x80;
    const int SW_RESTORE = 9;

    static bool IsAltTab(IntPtr h) {
        if (!IsWindowVisible(h)) return false;
        if (GetWindow(h, GW_OWNER) != IntPtr.Zero) return false;
        if (GetWindowTextLength(h) == 0) return false;
        int ex = GetWindowLong(h, GWL_EXSTYLE);
        if ((ex & WS_EX_TOOLWINDOW) != 0) return false;
        return true;
    }

    public static bool Activate(string[] names) {
        var set = new HashSet<string>(names, StringComparer.OrdinalIgnoreCase);
        IntPtr found = IntPtr.Zero;
        EnumWindows((h, l) => {
            if (!IsAltTab(h)) return true;          // keep scanning
            uint pid; GetWindowThreadProcessId(h, out pid);
            try {
                var p = Process.GetProcessById((int)pid);
                if (set.Contains(p.ProcessName)) { found = h; return false; }  // first = topmost in Z-order
            } catch { }
            return true;
        }, IntPtr.Zero);

        if (found == IntPtr.Zero) return false;

        IntPtr fg = GetForegroundWindow();
        uint fgPid;
        uint fgThread = GetWindowThreadProcessId(fg, out fgPid);
        uint thisThread = GetCurrentThreadId();
        AttachThreadInput(fgThread, thisThread, true);
        if (IsIconic(found)) ShowWindow(found, SW_RESTORE);
        BringWindowToTop(found);
        SetForegroundWindow(found);
        AttachThreadInput(fgThread, thisThread, false);
        return true;
    }
}
"@ | Out-Null

# --- Decide mode: invoked by the toast button via the claudenotify: scheme -----
# Records the decision through the local listener (/decide), then raises the
# session window. THIS process then forwards it to the daemon over the socket.
if ($Decide) {
    try {
        $id = if ($Decide -match 'id=([^&]+)')      { $matches[1] } else { '' }
        $v  = if ($Decide -match 'v=(allow|deny)')  { $matches[1] } else { '' }
        $p  = if ($Decide -match 'port=(\d+)')      { $matches[1] } else { $CallbackPort }
        if ($id -and $v) {
            try { Invoke-RestMethod -Uri "http://127.0.0.1:$p/decide?id=$id&v=$v" -TimeoutSec 3 | Out-Null } catch { }
        }
        [SessionFocus]::Activate($FocusProcess) | Out-Null
    } catch { }
    return
}

# --- One-time registration of the private URI scheme (HKCU, no admin) ----------
function Register-Scheme {
    param([int]$Port, [string[]]$FocusProcess)
    $script = $PSCommandPath
    $vbs    = Join-Path $PSScriptRoot 'claude-decide.vbs'
    $focus  = ($FocusProcess -join ',')

    # Hidden launcher: window style 0 = no console flash, no taskbar blink.
    $vbsBody = @'
Set sh = CreateObject("WScript.Shell")
If WScript.Arguments.Count > 0 Then
  sh.Run "powershell -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File ""<SCRIPT>"" -CallbackPort <PORT> -FocusProcess <FOCUS> -Decide """ & WScript.Arguments(0) & """", 0, False
End If
'@
    $vbsBody = $vbsBody -replace '<SCRIPT>', $script -replace '<PORT>', $Port -replace '<FOCUS>', $focus
    Set-Content -Path $vbs -Value $vbsBody -Encoding ASCII

    $base = "HKCU:\Software\Classes\$Scheme"
    New-Item -Path "$base\shell\open\command" -Force | Out-Null
    Set-ItemProperty -Path $base -Name '(default)'    -Value "URL:Claude Notify Protocol"
    Set-ItemProperty -Path $base -Name 'URL Protocol' -Value ''
    Set-ItemProperty -Path "$base\shell\open\command" -Name '(default)' -Value ("wscript.exe `"$vbs`" `"%1`"")
}
try { Register-Scheme -Port $CallbackPort -FocusProcess $FocusProcess }
catch { Write-Host "WARN: could not register '$Scheme' scheme: $($_.Exception.Message)" }

[Windows.UI.Notifications.ToastNotificationManager, Windows.UI.Notifications, ContentType = WindowsRuntime] | Out-Null
[Windows.Data.Xml.Dom.XmlDocument,                  Windows.Data.Xml.Dom,    ContentType = WindowsRuntime] | Out-Null

function Esc([string]$s) { [System.Security.SecurityElement]::Escape($s) }

function Show-Toast {
    param([string]$Message)
    # Clicking the toast body focuses the session (no decision to record).
    $focusUrl = "${Scheme}:focus"
    $xml = @"
<toast launch="$(Esc $focusUrl)" activationType="protocol"><visual><binding template="ToastGeneric"><text>Claude Code</text><text>$(Esc $Message)</text></binding></visual></toast>
"@
    $doc = [Windows.Data.Xml.Dom.XmlDocument]::new()
    $doc.LoadXml($xml)
    $toast = [Windows.UI.Notifications.ToastNotification]::new($doc)
    [Windows.UI.Notifications.ToastNotificationManager]::CreateToastNotifier($AppId).Show($toast)
}

function Show-PromptToast {
    param([string]$Id, [string]$Message, [int]$Port)
    # Custom scheme instead of http:// -> no browser tab, no focus theft.
    $allowUrl = "${Scheme}:decide?id=$Id&v=allow&port=$Port"
    $denyUrl  = "${Scheme}:decide?id=$Id&v=deny&port=$Port"
    # `tag` lets us programmatically dismiss this toast on permission_resolved.
    $tag = ($Id -replace '[^A-Za-z0-9_.-]', '_')
    if ($tag.Length -gt 16) { $tag = $tag.Substring(0, 16) }
    $xml = @"
<toast scenario="reminder"><visual><binding template="ToastGeneric"><text>Claude Code needs permission</text><text>$(Esc $Message)</text></binding></visual><actions><action content="Approve" activationType="protocol" arguments="$(Esc $allowUrl)"/><action content="Deny" activationType="protocol" arguments="$(Esc $denyUrl)"/></actions></toast>
"@
    $doc = [Windows.Data.Xml.Dom.XmlDocument]::new()
    $doc.LoadXml($xml)
    $toast = [Windows.UI.Notifications.ToastNotification]::new($doc)
    $toast.Tag = $tag
    [Windows.UI.Notifications.ToastNotificationManager]::CreateToastNotifier($AppId).Show($toast)
}

function Hide-PromptToast {
    param([string]$Id)
    $tag = ($Id -replace '[^A-Za-z0-9_.-]', '_')
    if ($tag.Length -gt 16) { $tag = $tag.Substring(0, 16) }
    try {
        [Windows.UI.Notifications.ToastNotificationManager]::History.Remove($tag, '', $AppId)
    } catch { }
}

# --- Loopback callback listener (button click -> this process) ------------------
$http = [System.Net.HttpListener]::new()
$http.Prefixes.Add("http://127.0.0.1:$CallbackPort/")
$http.Start()

# --- WebSocket plumbing ---------------------------------------------------------
$script:ws = $null
$script:cts = $null
$recvBuf = [byte[]]::new(65536)

function Send-Ws([string]$json) {
    if ($null -eq $script:ws -or $script:ws.State -ne [System.Net.WebSockets.WebSocketState]::Open) { return }
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($json)
    $seg = [System.ArraySegment[byte]]::new($bytes)
    # Single-threaded main loop => no concurrent sends; safe to block briefly.
    $script:ws.SendAsync($seg, [System.Net.WebSockets.WebSocketMessageType]::Text, $true, [System.Threading.CancellationToken]::None).Wait()
}

function Connect-Daemon {
    try {
        $script:cts = [System.Threading.CancellationTokenSource]::new()
        $script:ws = [System.Net.WebSockets.ClientWebSocket]::new()
        $script:ws.Options.KeepAliveInterval = [TimeSpan]::FromSeconds(20)
        $script:ws.ConnectAsync([Uri]$DaemonUrl, $script:cts.Token).Wait()
        Send-Ws (@{ type = 'hello'; token = $Token } | ConvertTo-Json -Compress)
        Write-Host ("[{0}] connected to {1}" -f (Get-Date -Format HH:mm:ss), $DaemonUrl)
        return $true
    } catch {
        Write-Host ("[{0}] connect failed: {1}" -f (Get-Date -Format HH:mm:ss), $_.Exception.Message)
        return $false
    }
}

function Handle-Frame([string]$raw) {
    if ([string]::IsNullOrWhiteSpace($raw)) { return }
    try { $msg = $raw | ConvertFrom-Json } catch { return }
    switch ($msg.type) {
        'permission_request' {
            Write-Host ("[{0}] permission {1}: {2}" -f (Get-Date -Format HH:mm:ss), $msg.id, $msg.summary)
            Show-PromptToast -Id $msg.id -Message $msg.summary -Port $CallbackPort
        }
        'permission_resolved' {
            Write-Host ("[{0}] resolved {1} ({2})" -f (Get-Date -Format HH:mm:ss), $msg.id, $msg.reason)
            Hide-PromptToast -Id $msg.id
        }
        'notification' {
            $m = if ($msg.message) { $msg.message } else { 'Claude Code needs your attention' }
            Write-Host ("[{0}] notify: {1}" -f (Get-Date -Format HH:mm:ss), $m)
            Show-Toast -Message $m
        }
        default { } # welcome / sessions_update / output / etc. — ignored
    }
}

function Send-Text { param($Context, [string]$Body)
    $buf = [System.Text.Encoding]::UTF8.GetBytes($Body)
    $Context.Response.ContentType = 'text/plain'
    $Context.Response.ContentLength64 = $buf.Length
    $Context.Response.OutputStream.Write($buf, 0, $buf.Length)
    $Context.Response.OutputStream.Close()
}

function Handle-Http($ctx) {
    $path = $ctx.Request.Url.AbsolutePath
    if ($ctx.Request.HttpMethod -eq 'GET' -and $path -eq '/decide') {
        $id = $ctx.Request.QueryString['id']; $v = $ctx.Request.QueryString['v']
        if ($id -and ($v -eq 'allow' -or $v -eq 'deny')) {
            Write-Host ("[{0}] decide {1}: {2}" -f (Get-Date -Format HH:mm:ss), $id, $v)
            Send-Ws (@{ type = 'permission_response'; id = $id; decision = $v } | ConvertTo-Json -Compress)
            Hide-PromptToast -Id $id
        }
        Send-Text $ctx 'ok'
    } else {
        $ctx.Response.StatusCode = 404; Send-Text $ctx 'not found'
    }
}

# --- Main loop: multiplex WebSocket receive + HTTP callback on one thread -------
# Everything runs on this single thread. The only async work is the .NET I/O
# tasks ($recvTask / $httpTask), so no PowerShell code runs off-runspace and
# there is never more than one outstanding ReceiveAsync. Tasks persist across
# iterations and are only re-armed once consumed.
Write-Host "Claude notify (WebSocket) listener -> $DaemonUrl  (Ctrl+C to stop)"
Write-Host "Approve/Deny will focus: $($FocusProcess -join ', ')"

$reconnectDelay = 1
$recvTask = $null
$httpTask = $null
$sb = [System.Text.StringBuilder]::new()

function Reset-Ws {
    try { if ($script:ws) { $script:ws.Dispose() } } catch { }
    $script:ws = $null
}

try {
    while ($true) {
        if ($null -eq $script:ws -or $script:ws.State -ne [System.Net.WebSockets.WebSocketState]::Open) {
            $recvTask = $null            # tied to the old socket; drop it
            [void]$sb.Clear()
            if (-not (Connect-Daemon)) {
                Start-Sleep -Seconds $reconnectDelay
                $reconnectDelay = [Math]::Min($reconnectDelay * 2, 30)
                continue
            }
            $reconnectDelay = 1
        }

        # (Re-)arm one outstanding receive and one HTTP accept.
        if ($null -eq $recvTask) {
            $seg = [System.ArraySegment[byte]]::new($recvBuf)
            $recvTask = $script:ws.ReceiveAsync($seg, $script:cts.Token)
        }
        if ($null -eq $httpTask) { $httpTask = $http.GetContextAsync() }

        $idx = [System.Threading.Tasks.Task]::WaitAny(@($recvTask, $httpTask), 1000)

        if ($idx -eq 0) {
            try {
                $res = $recvTask.GetAwaiter().GetResult()
            } catch {
                Reset-Ws; $recvTask = $null; continue
            }
            $recvTask = $null            # consumed — re-arm next iteration
            if ($res.MessageType -eq [System.Net.WebSockets.WebSocketMessageType]::Close) {
                Reset-Ws; continue
            }
            [void]$sb.Append([System.Text.Encoding]::UTF8.GetString($recvBuf, 0, $res.Count))
            if ($res.EndOfMessage) {
                Handle-Frame $sb.ToString()
                [void]$sb.Clear()
            }
        } elseif ($idx -eq 1) {
            $ctx = $httpTask.GetAwaiter().GetResult()
            $httpTask = $null            # consumed — re-arm next iteration
            Handle-Http $ctx
        }
        # idx -eq -1 => 1s timeout, loop again (keeps Ctrl+C responsive).
    }
}
finally {
    try { $http.Stop() } catch { }
    Reset-Ws
    try { if ($script:cts) { $script:cts.Cancel() } } catch { }
}
