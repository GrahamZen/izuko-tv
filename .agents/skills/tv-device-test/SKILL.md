---
name: tv-device-test
description: Test Izuko TV on the shared test devices (Android TV emulators, the Sony BRAVIA, the NVIDIA Shield) — the shared device board every session registers on before touching a device, which device to pick, building and installing one package for several features, cold-starting without the Sony's TV center stealing focus, the uhid virtual remote for holding and repeating keys, frame / memory measurement, and each device's pitfalls. Read before any on-device test; drive the app itself through the web-console-test skill.
---

# Testing on the shared TV devices

Several agent sessions work in this repository at the same time and share the same few devices. Two things keep them out of each
other's way: a **shared board** that says who is using what, and **driving the app through its web console** instead of remote keys.

## 1. Rules

Board: `C:\Users\93125\.claude\projects\d--Documents-GitHub-animeko\memory\test-board.md` (one Markdown table row per session).
Full rules: `feedback-device-testing-rules.md` in the same folder. In short:

- **Register when you start changing code**: your session name and goal. Before a test, add the device you will occupy (until
  roughly when) and the package you install. Update the status at each milestone. Delete your row after you commit. Edit only
  your own row (exact-string edit), never rewrite the file.
- **Check the board before using a device.** If another session is operating it, wait or pick another device.
- **Drive the app through the web console** (`.agents/skills/web-console-test/SKILL.md`), especially settings: put what you need
  into the debug group of a debug build and remove it once the test is confirmed. Fixed commands that need no screen reading
  (install, `am start`, force-stop) are fine. Screenshots to look at a problem are fine.
- **Tests that can only be done by reading screenshots and pressing keys step by step need the user's approval first** (only
  after you have tried to find a console way).
- **Device choice**: functional tests on an emulator first; what an emulator cannot do (ARM-only code, behaviour of a real
  Android 11 box) on the Shield; features that behave the same on both TVs (most non-performance features, source selection
  logic) may go to the Shield while the emulators are busy; performance on the Sony.
- **One build for many features**: build from the main working tree with everybody's current changes, so one install tests
  several features. Note on the board when a change conflicts or may crash. Big risky changes go to a worktree when the user says so.
- **When to install** is your call, based on the board: never interrupt another session's test; if another session's change is
  nearly done, wait and install once for both; if waiting would take too long, install and test yourself.
- **Devices do not wait for each other**: install and AOT-compile on every device you need in parallel (start the Sony's install +
  AOT in the background first, it takes longest), and test on one device while another is still compiling.
- **Check memory before a test** with `scripts/mem.sh ensure` (cleans only when the device is low); never write a new cleanup script.
- Before the final commit the user usually asks for one test on the Shield; plan for it.

## 2. Devices

| Device | Address | System | Notes |
|---|---|---|---|
| Emulators (`tv36`, `tv36b`, `tv36c`) | `emulator-5554`, others on the port you start them on | Android 16 TV, x86 with ARM translation | Functional tests. Start / screenshots / UI tree: `.agents/skills/android-ui-verify/scripts/droid.sh`. `tv36b` carries another session's data — do not uninstall its packages. |
| NVIDIA Shield | `10.0.0.203:5555` | Android 11, arm64-v8a, Tegra X1 | Own monitor (it used to share the Sony's screen through HDMI-CEC). The user's own Izuko install lives here: test with a debug or suffixed package, never touch `io.github.grahamzen.anime.tv`. |
| Sony BRAVIA BF1 | `10.0.0.250:5555` | Android 12, armeabi-v7a (32-bit), MediaTek + Mali | Performance tests. CPU 3–5× slower than the Shield; `animator_duration_scale` is 0.6. |

Both TVs run release builds signed with the same key. The Android 11 TV emulator image has no ARM translation, so the app's own
ABI check rejects it — use the Android 16 images.

## 3. Builds and installs

Build with `./scripts/gradle-lock.sh <task> [args…]` like every build in this repository (see AGENTS.md).

- Functional tests: `./scripts/gradle-lock.sh :app:android:assembleDefaultTvDebug -Pani.android.abis=<abi>` → package
  `io.github.grahamzen.anime.tv.debug2` (Shield `arm64-v8a`, Sony `armeabi-v7a`, emulator `x86` or `armeabi-v7a`).
- Performance: release + AOT (`adb shell cmd package compile -m speed -f <pkg>`; minutes on the Sony, so run it in the background
  right after the install and do other work meanwhile). A freshly installed package compiles to `verify`
  until its baseline profile is installed: `adb shell am broadcast -a androidx.profileinstaller.action.INSTALL_PROFILE -n <pkg>/androidx.profileinstaller.ProfileInstallReceiver` first.
- A/B or a package next to someone else's: `-Pani.android.appIdSuffix=<name>` (package `io.github.grahamzen.anime<name>.tv`).
- Install only after the build succeeded (`… | tee log; grep -q "BUILD SUCCESSFUL" log && adb install -r …`): otherwise you install
  whatever APK the output folder still holds, possibly another session's.
- Debug builds from different trees all say `4.9.0-dev`; tell them apart by `dumpsys package <pkg> | grep lastUpdateTime` and
  `sha256sum` of the installed APK (`adb shell pm path <pkg>`) against your own copy.
- Launch: `adb shell am start -n <pkg>/me.him188.ani.android.activity.MainActivity`. Do not launch with `monkey -p`: it injects a
  random event, and during a cold start that becomes an ANR kill.

## 4. Helpers (`scripts/`)

```bash
D=.agents/skills/tv-device-test/scripts
ANDROID_SERIAL=10.0.0.250:5555 PKG=io.github.grahamzen.anime.tv.debug2 source $D/tv.sh
tv_cold_start                  # Sony-safe cold start: HOME → am kill → am start, waits for the foreground
tv_user_keys 60                # physical-remote presses in the last 60 s → someone is using the TV, do not inject keys
tv_measure scroll "$D/hid.sh keys 'HOLD_D:1500'" 2   # framestats around an action
tv_shot after                  # screenshot with retries

SERIAL=10.0.0.250:5555 $D/hid.sh pipe &       # virtual remote (uhid); keep it running in the background
SERIAL=10.0.0.250:5555 $D/hid.sh keys "D:600 HOLD_D:1200 OK:800 BACK:800"
```

- `hid.sh` is for holding keys, fast repeats and exact gaps (`input keyevent --longpress` is not a real long press; on the Sony
  `sendevent` is blocked by SELinux). Usages: 0x41 select, 0x42–0x45 up/down/left/right, 0x224 back.
- `mem.sh check | clean [pkg…] | ensure [pkg…]` (`ANDROID_SERIAL=…`): MemAvailable, the biggest processes, and cleanup
  (force-stop the packages you name, then `am kill-all`, which ends cached background processes) below `MIN_AVAIL_MB` (default 600).
  `kill-all` also ends other sessions' idle apps: clean only a device the board shows free.
- `memloop.sh` runs on the device and samples the app's memory once per round (see its header).
- `throttle_proxy.py` slows the network of one package on a real TV (no root), e.g. to make playback stall mid-episode and read the
  `[Rebuffer]` lines: `uv run python -I throttle_proxy.py 8899 rate.txt` on the PC (bytes per second in `rate.txt`, `0` = unlimited,
  re-read every 0.5 s) → `adb reverse tcp:8899 tcp:8899` → console `post api/settings/proxy mode=CUSTOM url=http://127.0.0.1:8899`
  (debug package only; the player follows the app proxy) → play, then write a low rate (20000) and wait for the ~50 s buffer to drain.
  Afterwards `mode=DISABLED`, `adb reverse --remove tcp:8899`, stop the proxy. On an emulator use `adb emu network speed gsm` instead.
  An optional third argument names a file of host names (one per line, re-read every 0.5 s): requests to them are refused and their
  open tunnels closed, e.g. `api.bgm.tv` alone makes episode lists fail while subjects (`next.bgm.tv`) still load; a line
  `hold <host>` instead holds back what that host sends until the line is removed (release within the app's 30 s read timeout).
- Pressing back and down at the same time on the Sony is the system's TalkBack shortcut.

## 5. Measuring frames

- Read `dumpsys gfxinfo <pkg> framestats` **by column name**: the Shield (Android 11) prints the old 17 columns (`FrameCompleted` at
  index 13), the Sony 22 columns — and on the Sony `FrameInterval` and `FrameStartTime` hold each other's values. Count a dropped
  frame when two neighbouring frames' `IntendedVsync` are more than 1.5 frames apart.
- Before measuring, check that `Total frames rendered` is rising and that `dumpsys activity activities | grep ResumedActivity` is
  your package (the TV center or another package may be in front).
- Switch variants without restarting (cold starts land on different pages): an `am start` with extras (onNewIntent) or a console
  debug switch.
- `screenrecord` produces no frames while the screen is still and starts about 1 s after a log marker; use `ffprobe -show_entries frame=pts_time`.

## 6. Device pitfalls

Sony
- After a force-stop the TV center (`com.mediatek.tv.oneworld.tvcenter`) takes the foreground once, about 30 s later; force-stopping
  the app while it is in front makes the TV switch to its HDMI page. Use `tv_cold_start`.
- `uiautomator dump` can fail silently and leave the previous XML on /sdcard: delete it first; no `dumped to` means it failed.
- The TV Settings "Install unknown apps" list caches app names and switch states inside the settings process; after changes from
  outside (reinstall, `appops set`) run `am force-stop com.android.tv.settings` and reopen it.

Shield
- logcat drops lines (without a chatty marker); for the app's own timeline read the console's `api/logs/app.log`.
- A long press needs a monkey script (`DispatchKey` down, `UserWait(500)`, three repeats 60 ms apart, up; `monkey -f <script> 1`).
- Never record `simpleperf` call graphs of all threads (`-g`): the box kernel-panics and reboots about 20 s later.
- While asleep (`dumpsys power | grep mWakefulness` = Asleep) an app started there gets no network and its console does not start.
- A TCL browser is installed; external links open there. Back to the app with an explicit `am start -n` (LEANBACK_LAUNCHER).

Both
- To act right after a log line (press a key the moment a page appears), poll `adb logcat -d -s <Tag>:I | grep -q …` every 0.3 s.
  `adb logcat | grep -m1 …` returns only when logcat writes its next line, which on a quiet device can be 15 s late. A 4K
  `screencap` takes several seconds on the Shield; keep it out of time-critical steps.
- A rejected or interrupted command may already have run halfway (package installed, setting changed): check the device state
  (`lastUpdateTime`, foreground) before you continue.
- Android 8–11 kill the app when its "install unknown apps" permission is granted.
