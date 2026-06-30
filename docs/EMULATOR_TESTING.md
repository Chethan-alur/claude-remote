# Emulator Testing on Windows (start-to-finish runbook)

This runbook lets a **Claude Code session running on Windows** (or you, by hand)
build the Claude Remote Android app, run it in an Android **emulator** on the
Windows machine, point it at the daemon, and **verify the UI via screenshots**.

Why the emulator instead of the physical Pixel: the Windows machine is on the
corporate LAN, so the emulator reaches the daemon directly (no phone, no VPN,
no `scp`/`adb install` dance), and a Claude Code session co-located with the
emulator can screenshot the screen and drive taps — turning blind UI work into
a fast visual loop.

> **For the agent:** the core loop is **`adb` to act → screenshot → view the PNG → decide next action**. Take a screenshot after every UI step and actually open the PNG to verify before continuing. Don't assume a tap worked.

---

## 0. Context and prerequisites

**Architecture being tested**

```
[Android emulator on Windows]  --ws://10.141.1.136:8770-->  [daemon on the Linux VM]
                                                                  | spawns
                                                                  v
                                                            [claude in a PTY]
```

- The **daemon runs on the Linux VM** (`10.141.1.136`), not on Windows — it is Linux-only. It must be running before the app can do anything:
  ```bash
  # on the VM (SSH):
  cd /home/calur/github/claude-remote/daemon && .venv/bin/claude-remote-daemon -v --port 8770
  ```
- The emulator connects to the daemon at the VM's real IP **`10.141.1.136`**, port **`8770`** (match whatever the daemon was started with). **Do not use `10.0.2.2`** — that is the emulator's alias for the *Windows host*, where no daemon runs.

**Locked versions (must match the daemon build)**

| Component | Value |
|---|---|
| JDK | 17 (Temurin) |
| Android platform / compileSdk | `android-34` |
| Build-tools | `34.0.0` |
| System image | `system-images;android-34;google_apis;x86_64` |
| App id / launch activity | `com.claude.remote` / `.ui.MainActivity` |
| Gradle | 8.7 (the `gradlew.bat` wrapper downloads it automatically) |

**Machine prerequisites**

- Windows 10/11, x86_64, ~8 GB free disk.
- **Hardware virtualization enabled** (WHPX / Hyper-V) — the single biggest risk on a corporate machine. Verify first (Step 0a). If it's locked off and you can't enable it, the emulator won't boot — fall back to the physical Pixel.
- Network route to the VM. Verify:
  ```powershell
  Test-NetConnection 10.141.1.136 -Port 8770    # TcpTestSucceeded should be True (daemon running)
  ```
- PowerShell (commands below are PowerShell). If on old PowerShell and downloads fail with TLS errors, run once:
  ```powershell
  [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
  ```

### 0a. Verify virtualization

```powershell
Get-ComputerInfo -Property HyperVisorPresent, HyperVRequirementVirtualizationFirmwareEnabled
```
- `HyperVisorPresent = True` (a hypervisor like Hyper-V/WSL2 is active) **or** the firmware-virtualization flag `True` means you're fine.
- If virtualization is disabled in firmware, or "Windows Hypervisor Platform" is off and you lack admin to enable it, **stop here and use the physical device** — see `IMPLEMENTATION_PLAN.md`.

---

## 1. Install the JDK and Android SDK (no admin, all under your home dir)

Everything goes in `~\android-tools` and `~\android-sdk`; remove with `Remove-Item -Recurse` later. No installer, no admin.

```powershell
$tools = "$HOME\android-tools"
$sdk   = "$HOME\android-sdk"
New-Item -ItemType Directory -Force -Path $tools, "$sdk\cmdline-tools" | Out-Null

# --- JDK 17 (Temurin, portable zip) ---
Invoke-WebRequest "https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jdk/hotspot/normal/eclipse" -OutFile "$tools\jdk17.zip"
Expand-Archive "$tools\jdk17.zip" -DestinationPath "$tools\jdk17" -Force
$env:JAVA_HOME = (Get-ChildItem "$tools\jdk17" -Directory | Select-Object -First 1).FullName

# --- Android command-line tools ---
Invoke-WebRequest "https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip" -OutFile "$tools\cmdtools.zip"
Expand-Archive "$tools\cmdtools.zip" -DestinationPath "$sdk\cmdline-tools" -Force
Rename-Item "$sdk\cmdline-tools\cmdline-tools" "latest" -ErrorAction SilentlyContinue

# --- env for this session ---
$env:ANDROID_HOME = $sdk
$env:Path = "$env:JAVA_HOME\bin;$sdk\platform-tools;$sdk\emulator;$sdk\cmdline-tools\latest\bin;$env:Path"

java -version    # expect: openjdk version "17...."
```

*(Optional: persist these so a future shell/agent inherits them)*
```powershell
[Environment]::SetEnvironmentVariable("JAVA_HOME", $env:JAVA_HOME, "User")
[Environment]::SetEnvironmentVariable("ANDROID_HOME", $sdk, "User")
```

> Alternative JDK install if you prefer winget: `winget install --id EclipseAdoptium.Temurin.17.JDK -e`, then set `$env:JAVA_HOME` to its install path.

---

## 2. Install SDK components + accept licenses

```powershell
$sdkm = "$sdk\cmdline-tools\latest\bin\sdkmanager.bat"

# accept all licenses (send a stream of 'y')
("y`n" * 50) | & $sdkm --licenses

& $sdkm "platform-tools" "platforms;android-34" "build-tools;34.0.0" `
        "emulator" "system-images;android-34;google_apis;x86_64"

adb version   # confirms platform-tools installed
```

---

## 3. Create and boot an emulator (AVD)

```powershell
$avdm = "$sdk\cmdline-tools\latest\bin\avdmanager.bat"

# create (answer 'no' to the custom-hardware-profile prompt)
"no" | & $avdm create avd -n claude_test -k "system-images;android-34;google_apis;x86_64" -d pixel_7

# launch (non-blocking)
Start-Process "$sdk\emulator\emulator.exe" -ArgumentList "-avd","claude_test","-no-snapshot","-gpu","auto"

# wait until fully booted
adb wait-for-device
do { Start-Sleep 2; $b = (adb shell getprop sys.boot_completed) 2>$null } until ($b -match "1")
adb devices    # expect: emulator-5554   device
```

> If the emulator shows a black screen or won't start, retry with `-gpu swiftshader_indirect` (software rendering). See Troubleshooting.

---

## 4. Get the app and build the APK

Two paths. **Path B builds on Windows** (use this if you want to iterate on the
app); **Path A just installs the prebuilt APK** from the VM (fastest if you only
want to test the current build).

### Path A — install the prebuilt APK (no build)
```powershell
scp calur@10.141.1.136:/home/calur/github/claude-remote/android/app/build/outputs/apk/debug/app-debug.apk "$HOME\app-debug.apk"
adb install -r "$HOME\app-debug.apk"
```

### Path B — copy the source and build on Windows
```powershell
# copy just the android project (OpenSSH scp ships with Windows 10+)
scp -r calur@10.141.1.136:/home/calur/github/claude-remote/clients/android "$HOME\claude-remote-android"
cd "$HOME\claude-remote-android"
# prune any copied build artifacts so the build is clean
Remove-Item -Recurse -Force .\app\build, .\.gradle -ErrorAction SilentlyContinue

.\gradlew.bat :app:assembleDebug    # first run downloads Gradle 8.7 + deps (a few minutes)
adb install -r .\app\build\outputs\apk\debug\app-debug.apk
```

> The build needs internet to `dl.google.com` and `repo1.maven.org` (Windows has it). `JAVA_HOME` must be set (Step 1). The project is already version-aligned (AGP 8.5.0 / Gradle 8.7 / Kotlin 2.0.0) and has `android.useAndroidX=true` and `usesCleartextTraffic="true"` set, so no edits are needed.

---

## 5. Launch the app

```powershell
adb shell am start -n com.claude.remote/.ui.MainActivity
Start-Sleep 2
```

Grant the notification permission if prompted (Android 13+ shows a dialog):
```powershell
adb shell pm grant com.claude.remote android.permission.POST_NOTIFICATIONS
```

---

## 6. Point the app at the daemon

The app stores the daemon address in settings (default is the emulator alias,
which is wrong here). Set it to the VM. **Two methods — Method A is no-UI and
most reliable for an agent; Method B is the visual flow.**

### Method A — pre-seed settings (no taps)
```powershell
# ensure the prefs dir exists, then write host/port (debug app is run-as-able)
adb shell run-as com.claude.remote mkdir -p /data/data/com.claude.remote/shared_prefs
$xml = @"
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <string name="daemon_host">10.141.1.136</string>
    <int name="daemon_port" value="8770" />
</map>
"@
# write it via run-as
$xml -replace "`r`n","`n" | adb shell "run-as com.claude.remote sh -c 'cat > /data/data/com.claude.remote/shared_prefs/claude_remote.xml'"
# restart the app so it reads the new settings
adb shell am force-stop com.claude.remote
adb shell am start -n com.claude.remote/.ui.MainActivity
```

### Method B — type it in the UI (screenshot-driven)
1. Screenshot, view it, locate the **"Daemon"** button (top bar of the Sessions screen).
2. Tap it, clear the Host field, type the IP, set the port, tap **Save & reconnect**:
```powershell
adb shell input tap <X_of_Daemon_button> <Y>      # coords from the screenshot
# focus Host field:
adb shell input tap <X_of_host_field> <Y>
adb shell input keyevent 123                       # MOVE_END
1..25 | ForEach-Object { adb shell input keyevent 67 }   # delete existing text
adb shell input text "10.141.1.136"
# (repeat focus/clear/type for the Port field -> 8770)
# tap "Save & reconnect"
adb shell input tap <X_of_save> <Y>
```

---

## 7. Screenshot-verify the end-to-end flow

**Take a screenshot the reliable way** (PowerShell's `>` corrupts binary; pull instead):
```powershell
adb shell screencap -p /sdcard/screen.png
adb pull /sdcard/screen.png .\screen.png
```
Then **open `screen.png`** and verify each stage:

1. **Connected** — the Sessions screen top bar shows **● connected** (green). If it shows **● offline**, the daemon isn't reachable (see Troubleshooting).
2. **Open project** — tap the **Open project** FAB; on the project screen, type a folder that exists on the VM, e.g. `/home/calur/github/claude-remote`.
3. **List sessions** — tap **List sessions**; verify the past session(s) appear with titles + timestamps.
4. **Start new** (recommended over resuming a live session) — tap **Start new**; the app should navigate into the **terminal** screen.
5. **Send a prompt** — type into the input field, tap **Send**, screenshot again.

```powershell
# useful driving primitives
adb shell input tap X Y
adb shell input text "say%shello"     # %s = space; or avoid spaces
adb shell input keyevent 66           # ENTER
adb shell input keyevent 4            # BACK
```

> **Expected, not a bug:** the terminal output will look like garbled escape codes. `claude` is a full-screen terminal UI and the app currently renders raw ANSI as plain text (the xterm.js renderer is still pending). For now, verify *connection, navigation, session listing, and that prompts are delivered* — confirm delivery on the VM with:
> ```bash
> pgrep -af "claude --resume|/claude$"   # a daemon-spawned claude session should be running
> ```

---

## 8. Troubleshooting

| Symptom | Fix |
|---|---|
| Emulator: "x86 emulation requires hardware acceleration" / WHPX missing | Enable **Windows Hypervisor Platform** (`Optionalfeatures.exe`, needs admin) or BIOS virtualization. If corp-locked, use the physical device. |
| Emulator black screen / hangs on boot | Relaunch with `-gpu swiftshader_indirect`; or `-no-snapshot -wipe-data`. |
| `adb devices` empty after boot | `adb kill-server; adb start-server; adb devices`. |
| App top bar shows **● offline** | Daemon not running or unreachable. On the VM: is `claude-remote-daemon --port 8770` up? From Windows: `Test-NetConnection 10.141.1.136 -Port 8770`. Confirm the in-app port matches the daemon's. |
| "List sessions"/"Start new" do nothing, red banner shown | Same as above — not connected. |
| Error toast `session_creation_failed: cwd does not exist` | The folder you typed must be an **absolute path on the VM**, not a Windows path. |
| Build fails downloading deps | Ensure internet to `dl.google.com` + `repo1.maven.org`; `JAVA_HOME` set; retry `.\gradlew.bat :app:assembleDebug --info`. |
| Cleartext / `ws://` blocked | Already handled — the manifest sets `usesCleartextTraffic="true"`. |

---

## 9. Teardown (optional)

```powershell
adb emu kill
& "$sdk\cmdline-tools\latest\bin\avdmanager.bat" delete avd -n claude_test
Remove-Item -Recurse -Force "$HOME\android-sdk", "$HOME\android-tools", "$HOME\claude-remote-android"
```

---

## Appendix — what's verifiable today vs pending

- **Verifiable now:** connection state, daemon address settings, project/session listing (the VS Code–style session browser), session create/resume, prompt delivery, notifications.
- **Pending (so don't flag as bugs):** the terminal renders raw ANSI (xterm.js renderer not yet built); pairing/auth UI (daemon runs without `--require-auth`, any token accepted); WsClient auto-reconnect/heartbeat.
