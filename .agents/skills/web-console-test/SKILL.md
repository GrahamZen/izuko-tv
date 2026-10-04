---
name: web-console-test
description: Test Izuko TV features on a real TV/box through the app's own web console (the LAN HTTP control page) instead of pressing remote keys — read player state, switch sources, seek/pause, resume from history, measure how fast playback resumes, all with curl from the dev machine. Use this first whenever the feature under test is reachable from the console (playback, source selection, episodes, search, history, caches, cloud drive/shares, danmaku, a few settings); settings go through the debug group; step-by-step key presses + screenshots only for TV-only UI (focus, layout, scrub preview bubble) and only with the user's approval.
---

# Testing through the Izuko TV web console

The TV app serves a control page on the LAN (`TvRemoteControl`, `app/shared/ui-tv/src/commonMain/kotlin/ui/remote/`). Everything the
phone page can do is a plain HTTP call, so a test can drive the TV deterministically from the dev machine: no focus guessing, no
controls that auto-hide between two `adb shell input` calls, and the JSON tells you the exact state.

**Rule: do as much as possible through the console, settings included.** A setting or a code constant you need in a given state goes
into the debug group of a debug build (see "Debug group") instead of walking the TV settings pages; remove it once the test is
confirmed. Fixed commands that need no screen reading (install, `am start`, force-stop) and screenshots to look at a problem are fine.
A test that can only be done by reading screenshots and pressing remote keys step by step (focus, layout, the scrub preview bubble)
needs the user's approval first. Device sharing, the session board and which device to use: `.agents/skills/tv-device-test/SKILL.md`.

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
and cache ids such as `api/caches/play id=孤独摇滚！-937291386` contain Chinese) gets mangled on the Windows command line: write it to
a UTF-8 file and pass `key@file` (curl reads it from the file), as `select` does; otherwise the app answers `这个数据源已不在列表里` /
`这条缓存已经不在了`.

- `GET api/player` → `available`, `background`, `selectedSource`, `selectedId`, `groups[{name, items[{id, title, size, resolution, url}]}]`,
  `playback{playing, position, duration, speed, volume}`. `available:false` with `reason` `none` (no player) or `background`.
- Control calls are refused unless the TV shows the app (`电视当前没有显示 Izuko…`): the box must be awake and the app in front.
  Waking the Shield switches the Sony TV to HDMI over CEC (see the env-tv-test-devices memory).
- `playback.position` updates about once a second; `seek-measure` therefore has ~1 s granularity. The PC and TV clocks differ by
  about a second, so line up console calls with logcat by events, not by timestamps.

Every route with its parameters and response fields: `.agents/skills/web-console-api/SKILL.md` (written for agents that only
have the console address; the handlers in `TvRemoteControl.kt` and the `Remote*.kt` next to it are the source of truth).
Other routes:
`api/player/episode` `api/player/upnext` `api/player/refetch` `api/player/full-search` `api/player/request` `api/player/cache`
`api/player/details` `api/player/track` `api/player/danmaku/*` `api/player/review*` `api/player/drive*` (cloud drive picks)
`api/player/shares*`, `api/search*`, `api/history*`, `api/caches*` / `api/cache*`, `api/sources/subs*`, `api/profiles*`,
`api/account*`, `api/settings*` (proxy, Bangumi endpoint, TMDB images, catalog items such as trackers and subtitle groups,
danmaku filters, the debug group — **not** the player settings), `api/tv/front`,
`api/migrate/*` (moving to another TV: `export/manifest|shared|profile?id=|database?id=` are what the old TV hands out;
`preview` / `start` take the old TV's console address as `url`, then poll `status`, see `RemoteDeviceMigration`).

### Debug group (debug builds only)

A debug build adds 设置 → 调试 to the console, for things you flip again and again while developing (an effect on/off,
an edge distance). It starts empty; put what you need there instead of walking the TV settings pages:

```bash
$C devswitch                     # list: key = value (editor) title
$C devswitch dev.wallEdge 32     # a dev switch (toggle: 1 = on, empty = off); the change applies at once
$C devswitch someSettingKey 1    # a registered setting
```

- A temporary knob in code: declare it at the bottom of `DevSwitches`
  (`app/shared/app-platform/src/commonMain/kotlin/platform/DevSwitches.kt`), e.g.
  `val wallEdge = number("wallEdge", "海报墙左右边距 (dp)", default = 48f, range = 0f..160f)` or `toggle("heroBlur", "hero 模糊背景", default = true)`,
  and read `DevSwitches.wallEdge.value` where the constant was (Compose state: composables recompose when it changes). Values live in
  memory only (an app restart resets them) and a release build never changes them.
- An existing TV setting: add a `RemoteSettingSpec` to `RemoteSettingsCatalog.debugItems` (`ui/remote/RemoteGenericSettings.kt`),
  written like the public catalog entries; it is stored in the settings as usual.
- Install the debug build, `$C url <pkg>`, then `$C devswitch …`. Delete the entries when the work is done.

The debug group also has a read-only **播放链路探针** card (it stays; it is not one of the entries you delete), backed by
`GET api/settings/debug/probes` (`RemoteDebugProbes`; `PlayerProbes` in video-player-api is written by the local HLS proxy in
app-data for `hls` and by `LibassExoPlayerMediampPlayer` for `video`):

```bash
$C probes                        # current decoder + color triplet, and the last HLS ad-filter decisions
```

- `video`: decoder name, whether the NVIDIA dataspace workaround is on, codecs, and colorSpace / colorRange / colorTransfer.
  `complete=False` (one of the three missing) is the fake-HDR cause on the Shield (see `ColorInfoRepair.kt`), so check this
  before you look at the screen.
- `hls`: for each media playlist the ad filter saw (the PTS-continuity filter in `HlsManifestFilter`, run by the local HLS
  proxy `PlatformHlsPlaybackPreparer` while 设置「过滤贴片广告」 is on), the segment count and either how much it removed or the
  reason code it left it alone with: `no_discontinuity`, `single_group`, `no_ad`, `ad_break_too_long`, `encrypted`, `byterange`,
  `live_or_incomplete_playlist`, `invalid_playlist`. A playlist the player opened directly (no `127.0.0.1` address in the
  `Set media data` log line) never reached the filter: the proxy could not fetch it, or the filter setting is off.
  Compare the same source before and after changing the filter.
- These are recorded for the playback since the app started (HLS keeps the last 20); play something first, then read.

### Debug actions (`api/debug/*`, debug builds only)

Actions a test needs, as plain HTTP calls instead of remote keys (`RemoteDebugActions.kt`; refused unless the build has the debug
group). Pass values with `curl --data-urlencode` (GET: add `-G`), e.g. `curl -s -G --data-urlencode "sql=SELECT …" "$(cat
$CONSOLE_STATE_DIR/url)api/debug/db"`.

- **Database**: `GET api/debug/db?sql=SELECT …` returns `columns` and up to 200 `rows` (blobs as their size).
  `POST api/debug/db sql=UPDATE …` runs one statement in a transaction and notifies Room, so open pages update.
  Use it to set up states (expire a subject / its episodes with `lastFetched=0`, delete episodes, mark a subject as another
  collection type to fake a change made on another device) and to check them afterwards; it also works on the release package
  name, where `run-as` does not. Enum columns hold the enum name (`collectionType='WISH'`).
- **Collection list refresh**: `POST api/debug/collections/refresh type=WISH|DOING|DONE|ON_HOLD|DROPPED|ALL`, the same step as
  pulling to refresh on the collection page; returns `inSync`.
- **Main screen tab**: `POST api/debug/nav page=Exploration|Collection|CacheManagement` pops back to the main screen and switches
  the tab like the side rail does (it does not push a second main screen).
- **Block requests** (`DebugNetworkHooks`): `POST api/debug/net/block rules=api.bgm.tv,api.bangumi.vip hang=3`. A request whose
  URL contains one of the fragments fails like an unreachable host (`hang` seconds first = a timeout); `rules=` clears; memory
  only. It sits after the Bangumi route rewrite, so `api.bgm.tv` alone takes down only the origin route (a request carrying
  credentials has no other route unless ECH is on); list every route host (`api.bgm.tv`, `api.bangumi.vip`,
  `api.bgm-tv.ech.invalid`) to take the whole API down. Requests made by the player or a WebView do not pass through it.
- **Count requests**: `POST api/debug/requests/reset` starts recording; `GET api/debug/requests[?raw=1]` groups them by endpoint
  (number segments become `{n}`) with their statuses (`BLOCKED` for blocked ones), `raw=1` adds each request; `POST
  api/debug/requests/stop`. Keeps the last 2000.
- **Settings page** (`RemoteDebugSettingsPage.kt`): `POST api/debug/settings/open tab=STORAGE` (a `SettingsTab` name) opens the
  settings page on that category, or switches to it; `GET api/debug/settings/page` lists the category, the middle column's rows
  (`id` / `kind` / `title` / `value` / `toggle` / `checked`), the info column (`info.title` / `body`), the last toast
  (`lastToast{text, agoMs}`) and `pendingConfirm`; `POST api/debug/settings/click row=<row id>` or `title=<middle column title>`
  `[confirm=1]` presses OK on that row (`confirm=1` also confirms the dialog it opens); `POST api/debug/settings/back` goes up a level.

### The screen itself: read it, press keys, click by text (`api/debug/ui*`, debug builds only)

Works on every page and dialog without the page registering anything (`RemoteDebugUi.kt`): the app reads its own windows (native
views through the view tree, Compose through the semantics tree) and sends itself key events: through the system input pipeline
(the remote's own path) up to Android 12, and from Android 13 on, where the system no longer lets an app inject keys, by handing
them to the window that takes keys the way the system does (`keyPath` in the answer says which; `path=system|app` forces one).
Use it to walk a flow and check what it shows; one call is one step plus its result.

```bash
$C ui                            # every window, top first: its elements (> focused, * can take focus), the focus row, recent toasts
$C ui brief                      # only the focus, its row and the first texts of each window
$C key "DOWN,RIGHT*3,OK"         # press keys (each waits for the screen to settle), then print where the focus landed
$C key OK expect=订阅            # press only if the focused element's text contains 订阅; otherwise nothing is pressed
$C key "OK@800"                  # hold OK for 0.8 s: a long press, with the system's repeat rhythm
$C key "LEFT@1500,200ms,BACK" gap=100   # a hold, a pause, a key; gap = fixed delay between keys instead of waiting
$C click 订阅                     # move the focus onto the element with this text and press OK (press=0: only focus it)
$C wait 已订阅 timeout=8000       # until the text is on screen or in a toast; wait-gone <text> for the opposite
```

- Key names: `UP DOWN LEFT RIGHT OK BACK MENU PLAY FF REW NEXT PREV STOP ENTER`, any Android key name (`CAPTIONS`) or a key code.
  `NAME*3` repeats, `NAME@800` holds for 800 ms, `300ms` pauses. At most 200 steps and 60 s per call.
- The answer after `key` / `click` / `wait` is the brief form (`full=1` for all elements) plus `sent` / `settled` / `matches` /
  `via` / `found`. `settled=false` only means the screen kept changing for the whole `timeout` (playback progress, a countdown).
- **Never press OK blind.** A prompt that shows up late (the new-version prompt a few seconds after a cold start, a dialog that
  finishes loading) takes the focus, and the OK you meant for something else lands on its default button. Read the focus in the
  previous answer, and pass `expect=<text>` on every `key` that confirms something: it checks the focus and presses in one call.
- `click` puts the focus on the element directly. It is for getting somewhere; to test how the focus moves, use `key`.
  A row of options that hands the focus to its selected one cannot be entered this way: go to the row, then LEFT / RIGHT.
  It presses OK only after the focus has landed on that element, and fails otherwise (it never presses OK on something else).
- The in-app key path skips the system dispatcher and the IME: with a soft keyboard open a real key goes to the IME first, and
  media keys nobody handles are not forwarded to the media session.
- Toasts are system toasts outside the app's windows: they are in `toasts[]` (and `wait` sees them), not among the elements.
- It cannot see what is painted straight onto a canvas (badge numbers, progress bars, focus glow and dim, video) or whether
  something is covered or clipped: look at a screenshot for those. Selected state is there only where the UI declares it
  (a native view's `isSelected`, Compose `selected` semantics).
- Keys only reach the app while it is in front (`front:false` and an `ok:false` answer otherwise); system dialogs are out of reach.
  While a dialog is opening no window takes keys for a few frames: `key` waits up to 1.5 s for one before it gives up.
- A hold that opens a dialog (a long press) goes the way a real remote's does once the dialog takes the keys: the repeats stop,
  the window underneath gets a cancelled key-up, and the key-up itself lands on the dialog (which never saw the key-down).
- The console address shown on the TV (the startup QR dialog) comes back with the token masked.

### Scripted walks: a whole flow in one run (`scripts/walk.py`)

When the same flow has to be walked again after every UI change (an interaction walkthrough, a regression of a dialog chain),
write it down once as a walk file and let `walk.py` run it. Every step checks what the app reads from its own screen, so nobody
has to look at a screenshot to know where the walk is; a screenshot and the text the app read are still saved at every `snap`,
for people to look at afterwards and to compare between runs.

```bash
bash $C url <package>                      # once per app start: the runner reads the same console address
W="uv run python -I .agents/skills/web-console-test/scripts/walk.py"
$W <walk.py> --out <dir>                   # run every lane; exit code 1 if a lane failed
$W <walk.py> --out <dir> --only confirm,settings    # only these lanes
$W <walk.py> --out <dir> --baseline <earlier dir>   # also list the snaps whose text differs from that run
$W <walk.py> --list                        # lane ids and titles
$W <walk.py> --out <dir> --no-teardown     # leave the app as the last lane left it, to look at a failure by hand
$W <walk.py> --out <dir> --video           # adb screenrecord of every lane (3 minutes at most each)
```

`<dir>` gets `shots/<id>.png`, `ui/<id>.txt` (what the app read, in a stable form) and `ui/<id>.json`, `manifest.json` (lanes,
snaps, failures), `log.txt` (every action with its time), and for a failed lane `fail/fail-<lane>.png` + `.txt`: the screen at
the moment it failed. A lane that fails stops there; its `after`, the remaining lanes and the teardown still run.

A walk file is Python; the runner hands it these functions (no imports needed):

```python
walk("订阅交互走查", package="…tv.debug2")
volatile_text(r"\d+ ?%", "<百分比>")        # text that differs on every run, so --baseline does not report it

@setup                                     # before the first lane (@teardown: after the last, also after failures)
def _(): ...

@lane("confirm", "点「订阅」")              # one flow; it sets up its own state so --only works
def _():
    call("api/debug/subscribe/set", confirm=1)          # POST to the console (get(...) for GET); ok:false fails the lane
    call("api/debug/subscribe/open", what="subscribe", subject=665390)
    see("不再询问", timeout=8000)           # wait until the text is on screen or in a toast (gone(text): until it left)
    focus("订阅")                           # the focused element's text contains this
    has("新剧集", "全部剧集"); hasnt("已完结")   # texts on screen right now
    selected("不需要"); checked("不再询问", False)    # the state the element reports
    snap("w_confirm", "连载中的番：焦点在「订阅」")     # screenshot + what the app read, under this id
    keys("UP,RIGHT*2")                      # remote keys, same grammar as `console.sh key`
    ok("新剧集")                            # OK, pressed only if the focus is on this text; ok(text, hold=900) = long press
    goto("稍后设置")                        # put the focus on the element with this text, pressing nothing on it
    tap("订阅 2 部")                        # goto + ok
    screen()                               # the current screen: .focus_label, .has(text), .find(text), .top() (the dialog's own elements), .toasts
    note("两条提示同时弹出")                 # an observation, stored with the last snap
    pause(3500); skip("这台设备上没有下载")   # wait; give the lane up as skipped
```

- A confirm key always carries the text the focus must be on: `keys("OK")` without `expect=` is refused by the runner.
- `focus` / `has` / `hasnt` / `selected` / `checked` keep looking for up to 3 s (`timeout=`): a dialog that has just appeared
  takes the keys a few frames later.
- `goto` first asks the app to focus the element; a row of options hands the focus to its selected one instead, and then it
  walks LEFT / RIGHT along that row. It is for getting somewhere; to show how the focus moves, use `keys` and check with `focus`.
- `--baseline` compares, for each snap, the window that takes keys plus the toasts: what sits behind a dialog (a carousel, a row
  of recommendations) changes on its own. A toast shot over such a page takes `compare="toasts"`; `compare="all"` compares
  every window. Both runs are rendered again from `ui/<id>.json`, so an older baseline still compares fairly.
- System toasts queue up: three toasts in a row are on screen one after another, about 3.5 s each. Wait them out before a
  screenshot that should not carry an old one, and give a toast shot `delay=600` so it has faded in.
- Text is matched by containment, and the page behind a dialog is still read: 「第 17 集」 also matches the button 「播放第 17 集」.
  Use a piece only the element you mean has (「第 17 集 · 」), and `screen().top()` when listing what a dialog shows.
- Things a dialog remembers between openings (a help card left open) are still there in the next lane: set them, do not assume.
- State that only real waiting produces (a download that fails, a source that rate-limits) is laid over what the UI reads with a
  debug endpoint of that feature, when it has one; say in the caption that it was staged.
- Keep walk files next to the feature's other test notes (`walks/` in this skill). Their output never goes into the repository.

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

- On a debug build, press keys through the console (`$C key …`, see "The screen itself" above): no process per key, real holds,
  and the answer says where the focus landed. The rest of this section is for packages without the debug group (release builds).
- Each `adb shell input keyevent` call starts a process (~0.5 s). Controls auto-hide after a few seconds and a "double press" window
  is ~0.6 s, so chain keys in **one** shell: `adb shell "input keyevent KEYCODE_DPAD_DOWN; sleep 0.8; input keyevent KEYCODE_DPAD_LEFT KEYCODE_DPAD_LEFT"`.
- TV player: up/down shows the controls (focus on the progress bar); left/right on the progress bar scrubs with the preview bubble;
  down again → icon row → episode strip. Back cancels a scrub.
- After any interruption, look first (`dumpsys activity activities | grep mResumedActivity` + a screenshot), then press.

## 5. Things the console cannot reach

- Player settings (`VideoScaffoldConfig`, e.g. 「网盘视频边下边播」) and the other TV settings pages: flip them on the TV
  (rail → 设置 → category → right pane), then verify the effect through the console + logcat. If you will flip one repeatedly,
  register it in the debug group first (see "Debug group").
- The scrub preview bubble and anything else that only exists on the TV screen.
- Per-package state you may need to inspect on a debuggable build: `adb shell run-as <package> ...`
  (e.g. `cat shared_prefs/tv_decoder_concurrency.xml`, `du -sk cache/playback-cache`). Back up a file before editing it and put it back
  after the test.

## 6. Bugs in the console page itself

The page is JavaScript inside `RemoteControlPage.kt`; a click handler can fail where curl calls succeed (a tap that lands on
the icon inside a button, `e.target` being the `svg` / `path`). Click it in a real browser engine instead of reading the code:

```bash
uv run --no-project --with playwright python -I .agents/skills/web-console-test/scripts/click_page.py \
  "$CONSOLE_STATE_DIR/url" open-player play-upnext --text
# open-player: click on the icon -> POST open
# open-player: click on the label -> POST open
```

- It drives the installed Microsoft Edge headless (Playwright `channel="msedge"`, nothing to download), with touch enabled
  and a phone-sized viewport, and prints the POST requests each click sends.
- A button only exists in some TV states (`open-player` needs a retained session in the background, `play-upnext` a next
  episode while nothing plays): set that state up through the console first; a missing button is reported, not clicked.
