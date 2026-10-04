---
name: web-console-test
description: Test Izuko TV features on a real TV/box through the app's own web console (the LAN HTTP control page) instead of pressing remote keys — read player state, switch sources, seek/pause, resume from history, measure how fast playback resumes, all with curl from the dev machine. Use this first whenever the feature under test is reachable from the console (playback, source selection, episodes, search, history, caches, Quark drive/shares, danmaku, a few settings); fall back to key presses + screenshots only for TV-only UI (focus, layout, scrub preview bubble, settings pages).
---

# Testing through the Izuko TV web console

The TV app serves a control page on the LAN (`TvRemoteControl`, `app/shared/ui-tv/src/commonMain/kotlin/ui/remote/`). Everything the
phone page can do is a plain HTTP call, so a test can drive the TV deterministically from the dev machine: no focus guessing, no
controls that auto-hide between two `adb shell input` calls, and the JSON tells you the exact state.

**Rule: if the console can do it, test it through the console.** Use remote keys (`adb shell input keyevent`) and screenshots only
for what the console cannot reach: TV-only UI (focus, layout, the scrub preview bubble), the TV settings pages, and anything visual.

Helper: `.agents/skills/web-console-test/scripts/console.sh` (Git Bash on Windows works; needs adb, curl, `uv run python`).
`ANDROID_SERIAL` picks the device (default the Shield `10.0.0.203:5555`).

## 1. Find the console of the package under test

```bash
C=.agents/skills/web-console-test/scripts/console.sh
$C url io.github.grahamzen.anime.tv.debug2      # prints http://IP:PORT/<token>/ (token masked), stores it for later calls
```

- The address is logged at startup: `Remote control reachable at http://IP:PORT/TOKEN/`. The token belongs to the package (it stays
  the same across restarts); the port is the first free one, so run `url` again after a restart or a crash.
- **Several Izuko packages can be installed and running at once** (release, debug2, `appIdSuffix` builds), each with its own console
  on its own port and token. `url` matches the logcat line to the pid of the package you name. Taking "the last reachable line" picks
  the wrong app: you get `{"available":false,"reason":"none"}` while the TV is visibly playing.
- Never paste the token into reports or commits (the startup dialog on the TV shows it too; don't quote screenshots of it).
- **Wait for the app before calling it.** A debug build without AOT takes a while to cold start; check
  `adb shell dumpsys window | grep mFocusedWindow` (not the first `mCurrentFocus` line: after a crash or ANR a stale display section
  reports `null`). The startup "Web 控制台" dialog closes with `$C post api/launch-dialog/close`.
- **Restarting the package under test**: press HOME first (`adb shell input keyevent KEYCODE_HOME`), then `am force-stop`. Force-stopping
  the app in front resumes whatever activity is below it; if that is another Izuko package, it comes to the front and resumes its own
  retained playback session, downloading alongside your test until lmkd kills it (seen with the release app below debug2).

## 2. Player

```bash
$C state                         # title / source / position / candidate groups (one line per data source)
$C candidates PanSou             # candidates of one source: index, size, resolution, title
$C select PanSou 0               # switch to that candidate (same path as picking it in the TV source panel)
$C control pause|play|toggle     # also: back / forward (±10 s), mute
$C control seek 300000           # absolute position in ms
$C control speed 1.5             # control volume 0.3
$C seek-measure back 30          # seek 30 s back and report how long until playback moves again
$C seek-measure abs 300          # same for an absolute target (seconds)
$C history-play 501963           # resume a subject from play history (opens the player on the TV)
$C open-player                   # bring a retained background session back to the front
```

Raw calls: `$C get api/player`, `$C post api/player/control action=seek ms=60000`, `$C post api/player/episode id=1704817`
(episode ids are in `GET api/player` → `episodes[{id, label, current}]`). A value with non-ASCII text (web-source candidate ids
contain Chinese) gets mangled on the Windows command line: write it to a UTF-8 file and pass `key@file` (curl reads it from the
file), as `select` does; otherwise the app answers `这个数据源已不在列表里`.

- `GET api/player` → `available`, `background`, `selectedSource`, `selectedId`, `groups[{name, items[{id, title, size, resolution, url}]}]`,
  `playback{playing, position, duration, speed, volume}`. `available:false` with `reason` `none` (no player) or `background`.
- Control calls are refused unless the TV shows the app (`电视当前没有显示 Izuko…`): the box must be awake and the app in front.
  Waking the Shield switches the Sony TV to HDMI over CEC (see the env-tv-test-devices memory).
- `playback.position` updates about once a second; `seek-measure` therefore has ~1 s granularity. The PC and TV clocks differ by
  about a second, so line up console calls with logcat by events, not by timestamps.

Other routes (read the handlers in `TvRemoteControl.kt` and the `Remote*.kt` next to it for parameters):
`api/player/episode` `api/player/upnext` `api/player/refetch` `api/player/full-search` `api/player/request` `api/player/cache`
`api/player/details` `api/player/track` `api/player/danmaku/*` `api/player/review*` `api/player/drive*` (Quark drive picks)
`api/player/shares*`, `api/search*`, `api/history*`, `api/caches*` / `api/cache*`, `api/sources/subs*`, `api/profiles*`,
`api/account*`, `api/settings*` (proxy, Bangumi endpoint, TMDB images, trackers, danmaku filters — **not** the player settings),
`api/tv/front`.

## 3. Evidence: logcat next to the console

Start one capture per test session and grep it afterwards:

```bash
adb -s $ANDROID_SERIAL logcat -c
adb -s $ANDROID_SERIAL logcat -v time > logcat.txt    # run in the background
grep -E "\( *$PID\)" logcat.txt | grep -E "Session status|Opened dl-|Read [0-9]+ KiB from the playback disk cache"   # logcat pads short pids: "( 5715)"
```

- App tags are the class names (`app.videoplayer.media.ParallelRangeDataSource`, `app.ui.subject.episode.RetainedPlaybackSessionHolder`, …).
- When the app disappears, ask the system why before guessing: `adb shell dumpsys activity exit-info <package>`
  (`reason=2 (SIGNALED) status=9` with a large `pss` in the foreground = killed for memory) and
  `adb logcat -d -b events | grep -E "am_proc_died|am_low_memory"`.

## 4. When you still need keys

- Each `adb shell input keyevent` call starts a process (~0.5 s). Controls auto-hide after a few seconds and a "double press" window
  is ~0.6 s, so chain keys in **one** shell: `adb shell "input keyevent KEYCODE_DPAD_DOWN; sleep 0.8; input keyevent KEYCODE_DPAD_LEFT KEYCODE_DPAD_LEFT"`.
- TV player: up/down shows the controls (focus on the progress bar); left/right on the progress bar scrubs with the preview bubble;
  down again → icon row → episode strip. Back cancels a scrub.
- After any interruption, look first (`dumpsys activity activities | grep mResumedActivity` + a screenshot), then press.

## 5. Things the console cannot reach

- Player settings (`VideoScaffoldConfig`, e.g. 「网盘视频边下边播」) and the other TV settings pages: flip them on the TV
  (rail → 设置 → category → right pane), then verify the effect through the console + logcat.
- The scrub preview bubble and anything else that only exists on the TV screen.
- Per-package state you may need to inspect on a debuggable build: `adb shell run-as <package> ...`
  (e.g. `cat shared_prefs/tv_decoder_concurrency.xml`, `du -sk cache/playback-cache`). Back up a file before editing it and put it back
  after the test.
