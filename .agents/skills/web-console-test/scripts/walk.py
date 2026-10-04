#!/usr/bin/env python3
"""Run a scripted walk of the TV app through the web console's debug UI interface.

A walk is a Python file that declares lanes (one flow each). Every action checks what the app itself reads
from the screen (text, focus, selected / checked), so nobody has to look at a screenshot to know where the
walk is; screenshots are still taken at every `snap` for people to look at afterwards.

    uv run python -I .agents/skills/web-console-test/scripts/walk.py <walk file> --out <dir> [options]

See ../SKILL.md ("Scripted walks") for the walk-file functions. Needs adb for screenshots and a debug build
(`api/debug/ui` is only served by debug builds). The console address comes from `--url`, or from the file
`console.sh url <package>` writes ($CONSOLE_STATE_DIR/url).
"""
import argparse
import datetime
import json
import os
import pathlib
import re
import runpy
import subprocess
import sys
import tempfile
import time
import traceback
import urllib.error
import urllib.parse
import urllib.request

sys.stdout.reconfigure(encoding="utf-8")
sys.stderr.reconfigure(encoding="utf-8")

OK_KEYS = {"OK", "CENTER", "SELECT", "ENTER", "KEYCODE_DPAD_CENTER", "KEYCODE_ENTER", "KEYCODE_NUMPAD_ENTER", "23", "66", "160"}
TOAST_VISIBLE_MS = 4000
PNG_MAGIC = bytes([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A])
TOKEN_IN_URL = re.compile("(https?://[^/ ]+/)[A-Za-z0-9_-]{6,}")


class WalkFail(Exception):
    """An action did not find what the walk file says should be there."""


class ConsoleGone(WalkFail):
    """The console does not answer at all: the app or the device is gone, and no later lane can run."""


class WalkSkip(Exception):
    """The lane cannot run here (a precondition the walk file checks itself)."""


def mask(text):
    return TOKEN_IN_URL.sub(lambda m: m.group(1) + "<token>", str(text))


class Console:
    def __init__(self, base, serial):
        self.base = base if base.endswith("/") else base + "/"
        self.serial = serial

    def request(self, method, path, params=None, timeout=90):
        params = {k: v for k, v in (params or {}).items() if v is not None}
        data = None
        url = self.base + path
        encoded = urllib.parse.urlencode({k: str(v) for k, v in params.items()})
        if method == "GET":
            if encoded:
                url += ("&" if "?" in url else "?") + encoded
        else:
            data = encoded.encode("utf-8")
        last = None
        # The console answers one connection at a time: an empty reply now and then (another client polling) is retried
        for _ in range(3):
            try:
                req = urllib.request.Request(url, data=data, method=method)
                if data is not None:
                    req.add_header("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
                with urllib.request.urlopen(req, timeout=timeout) as response:
                    body = response.read().decode("utf-8")
                if body.strip():
                    return json.loads(body)
                last = "empty reply"
            except urllib.error.HTTPError as e:
                detail = e.read().decode("utf-8", "replace")[:300]
                raise WalkFail(f"{method} {path}: HTTP {e.code} {detail}") from None
            except (urllib.error.URLError, ConnectionError, TimeoutError, json.JSONDecodeError) as e:
                last = repr(e)
            time.sleep(1)
        raise ConsoleGone(f"{method} {path}: no answer from the console ({mask(last)}); is the app still running?")

    def adb(self, *args, binary=False, check=True, timeout=60):
        cmd = ["adb"] + (["-s", self.serial] if self.serial else []) + list(args)
        done = subprocess.run(cmd, capture_output=True, timeout=timeout)
        if check and done.returncode != 0:
            raise WalkFail(f"adb {' '.join(args)}: {done.stderr.decode('utf-8', 'replace').strip()[:300]}")
        return done.stdout if binary else "\n".join(done.stdout.decode("utf-8", "replace").splitlines())

    def screenshot(self, path):
        for _ in range(3):
            data = self.adb("exec-out", "screencap", "-p", binary=True)
            if data.startswith(PNG_MAGIC) and len(data) > 20000:
                path.write_bytes(data)
                return
            time.sleep(1)
        raise WalkFail("adb screencap gave no usable image three times")


def _same_row(a, b):
    """Two [left, top, right, bottom] boxes share a row: they overlap by more than half of the lower one's height."""
    overlap = min(a[3], b[3]) - max(a[1], b[1])
    return overlap > min(a[3] - a[1], b[3] - b[1]) / 2


def element_label(e):
    return e.get("text") or (f"({e['desc']})" if e.get("desc") else f"<{e.get('role', '?')}>")


def element_flags(e):
    flags = [k for k in ("selected", "disabled") if e.get(k)]
    if e.get("checked"):
        flags.append("checked" if e["checked"] == "on" else "unchecked" if e["checked"] == "off" else "mixed")
    return " [" + ",".join(flags) + "]" if flags else ""


class Screen:
    """What the app read from its windows at one moment (the full answer of `api/debug/ui`)."""

    def __init__(self, data):
        self.data = data
        self.front = bool(data.get("front"))
        self.focus = data.get("focus")
        self.windows = data.get("windows", [])
        self.toasts = [t["text"] for t in data.get("toasts", []) if t.get("agoMs", 0) <= TOAST_VISIBLE_MS]

    @property
    def focus_label(self):
        return element_label(self.focus) if self.focus else ""

    def elements(self):
        # The window taking keys first: a dialog's own button before a same-named one on the page behind it
        ordered = sorted(self.windows, key=lambda w: not w.get("focused"))
        return [e for w in ordered for e in w.get("elements", [])]

    def top(self):
        """The elements of the window that takes keys (the dialog on top), without the page behind it."""
        return next((w.get("elements", []) for w in self.windows if w.get("focused")), [])

    def find(self, text, exact=False):
        hits = [e for e in self.elements() if text in (e.get("text") or "") or text in (e.get("desc") or "")]
        whole = [e for e in hits if text == (e.get("text") or "") or text == (e.get("desc") or "")]
        return whole if exact else whole + [e for e in hits if e not in whole]

    def has(self, text):
        return bool(self.find(text))

    def texts(self):
        return [element_label(e) for e in self.elements()]

    def render(self, volatile=(), scope="all"):
        """The stable text form saved beside each screenshot and compared between runs.

        scope: "all" = every window; "top" = only the window that takes keys (what is behind a dialog is incidental);
        "toasts" = only the toasts. Toasts are listed in every scope."""
        lines = []
        for w in self.windows:
            if scope == "toasts" or (scope == "top" and not w.get("focused")):
                continue
            title = f" {w['title']}" if w.get("title") else ""
            lines.append(f"[{w['kind']}{' *' if w.get('focused') else ''}]{title}")
            for e in w.get("elements", []):
                mark = ">" if e.get("focused") else "*" if e.get("interactive") else " "
                lines.append(f"  {mark} {element_label(e)}{element_flags(e)}")
        for toast in self.toasts:
            lines.append(f"toast: {toast}")
        text = "\n".join(lines) + "\n"
        for pattern, replacement in volatile:
            text = re.sub(pattern, replacement, text)
        return text


class Lane:
    def __init__(self, id, title, run, after):
        self.id, self.title, self.run, self.after = id, title, run, after


class Walk:
    def __init__(self, console, out, video):
        self.console = console
        self.out = out
        self.video = video
        self.lanes = []
        self.setup_fn = None
        self.teardown_fn = None
        self.name = None
        self.package = None
        self.volatile = []
        self.snaps = {}  # id -> manifest step
        self.kept = []
        self.current = None  # manifest entry of the running lane
        self.log_file = None
        self.console_gone = False  # the console stopped answering: the remaining lanes are skipped

    # ---- declarations ----
    def walk(self, name, package=None):
        self.name, self.package = name, package

    def lane(self, id, title, after=None):
        def register(fn):
            if any(l.id == id for l in self.lanes):
                raise SystemExit(f"walk file: lane id '{id}' is declared twice")
            self.lanes.append(Lane(id, title, fn, after))
            return fn
        return register

    def setup(self, fn):
        self.setup_fn = fn
        return fn

    def teardown(self, fn):
        self.teardown_fn = fn
        return fn

    def volatile_text(self, pattern, replacement="<…>"):
        self.volatile.append((pattern, replacement))

    def kept_shot(self, id, caption="", reason=""):
        self.kept.append({"id": id, "caption": caption, "reason": reason})

    # ---- plumbing ----
    def log(self, text):
        if self.log_file:
            self.log_file.write(f"{datetime.datetime.now().strftime('%H:%M:%S.%f')[:-3]} {mask(text)}\n")
            self.log_file.flush()

    def _checked(self, what, answer):
        if isinstance(answer, dict) and answer.get("ok") is False:
            extra = f" (the focus is on '{answer['focus']}')" if isinstance(answer.get("focus"), str) else ""
            raise WalkFail(f"{what}: {answer.get('message') or answer.get('error') or answer}{extra}")
        return answer

    def screen(self):
        return Screen(self._checked("read screen", self.console.request("GET", "api/debug/ui")))

    def _settled_screen(self, quiet=0.3, timeout=6.0):
        """The screen once what the app reads stops changing (fades do not show in it)."""
        deadline = time.monotonic() + timeout
        last, since = None, time.monotonic()
        while True:
            screen = self.screen()
            sig = json.dumps([[(e.get("text"), e.get("bounds"), e.get("focused"), e.get("selected"), e.get("checked")) for e in w.get("elements", [])]
                              for w in screen.windows], ensure_ascii=False)
            now = time.monotonic()
            if sig != last:
                last, since = sig, now
            elif now - since >= quiet:
                return screen
            if now >= deadline:
                return screen
            time.sleep(0.1)

    # ---- actions ----
    def get(self, path, **params):
        self.log(f"get {path} {params or ''}")
        return self._checked(f"GET {path}", self.console.request("GET", path, params))

    def call(self, path, **params):
        self.log(f"call {path} {params or ''}")
        return self._checked(f"POST {path}", self.console.request("POST", path, params))

    def adb(self, *args):
        self.log("adb " + " ".join(args))
        return self.console.adb(*args)

    def keys(self, spec, expect=None, timeout=None, gap=None, path=None):
        names = {re.split("[*@]", part.strip())[0].upper() for part in spec.split(",")}
        if names & OK_KEYS and not expect:
            raise WalkFail(f"keys('{spec}'): a confirm key needs expect=<text the focus must be on>; never press OK blind")
        self.log(f"keys {spec}" + (f" expect={expect}" if expect else ""))
        params = {"keys": spec, "expect": expect, "timeout": timeout, "gap": gap, "path": path}
        for attempt in range(6):
            answer = self.console.request("POST", "api/debug/ui/key", params)
            # No window of the app takes keys for a moment while a dialog opens or rebuilds its content: nothing was
            # pressed yet (no `sent`), so asking again is safe. A wrong focus (expect) is never retried.
            between_windows = answer.get("ok") is False and "sent" not in answer and "focus" not in answer and not self.screen().front
            if not between_windows or attempt == 5:
                break
            time.sleep(0.4)
        return self._checked(f"keys('{spec}'" + (f", expect='{expect}')" if expect else ")"), answer)

    def ok(self, expect, hold=None):
        return self.keys(f"OK@{hold}" if hold else "OK", expect=expect)

    def goto(self, text, exact=False):
        """Put the focus on the element with this text, without pressing anything on it.

        Directly where the element takes the focus; a row of options hands the focus to its selected one instead,
        and then LEFT / RIGHT along that row gets there."""
        self.log(f"goto {text}")
        self.console.request("POST", "api/debug/ui/click", {"text": text, "exact": 1 if exact else None, "press": 0})
        screen = self.screen()
        if screen.focus and text in screen.focus_label:
            return screen
        hits = screen.find(text, exact)
        if not hits:
            raise WalkFail(f"goto('{text}'): no such element on screen; the focus is on '{screen.focus_label}'")
        target, current = hits[0], screen.focus
        if current and _same_row(current["bounds"], target["bounds"]):
            window = next((w for w in screen.windows if w.get("focused")), None)
            row = sorted((e for e in (window or {}).get("elements", []) if e.get("interactive") and _same_row(e["bounds"], target["bounds"])),
                         key=lambda e: e["bounds"][0])
            at = [i for i, e in enumerate(row) if e["bounds"] == current["bounds"]]
            to = [i for i, e in enumerate(row) if e["bounds"] == target["bounds"]]
            if at and to and at[0] != to[0]:
                self.keys(f"{'RIGHT' if to[0] > at[0] else 'LEFT'}*{abs(to[0] - at[0])}")
                screen = self.screen()
                if screen.focus and text in screen.focus_label:
                    return screen
        raise WalkFail(f"goto('{text}'): the focus is on '{screen.focus_label}', and LEFT / RIGHT along its row does not reach it")

    def tap(self, text, exact=False, hold=None):
        """Put the focus on the element with this text (see goto) and press OK on it; hold = long press in ms."""
        self.goto(text, exact)
        return self.ok(text, hold)

    def see(self, text, timeout=5000):
        self.log(f"see {text}")
        answer = self._checked(f"see('{text}')", self.console.request("POST", "api/debug/ui/wait", {"text": text, "timeout": timeout}))
        if not answer.get("found"):
            focus = element_label(answer["focus"]) if answer.get("focus") else "nothing"
            raise WalkFail(f"see('{text}'): not on screen (nor in a toast) after {timeout} ms; the focus is on '{focus}'")
        return answer

    def gone(self, text, timeout=5000):
        self.log(f"gone {text}")
        answer = self._checked(f"gone('{text}')", self.console.request("POST", "api/debug/ui/wait", {"gone": text, "timeout": timeout}))
        if not answer.get("found"):
            raise WalkFail(f"gone('{text}'): still on screen after {timeout} ms")
        return answer

    def _eventually(self, check, timeout):
        """Run a check on fresh screens until it passes: the screen keeps settling for a moment after an action
        (a dialog that has appeared takes the keys a few frames later)."""
        deadline = time.monotonic() + timeout / 1000
        while True:
            screen = self.screen()
            problem = check(screen)
            if problem is None:
                return screen
            if time.monotonic() >= deadline:
                raise WalkFail(problem)
            time.sleep(0.15)

    def focus(self, text, timeout=3000):
        def check(screen):
            if not screen.front:
                return f"focus('{text}'): the app is not in front"
            return None if text in screen.focus_label else f"focus('{text}'): the focus is on '{screen.focus_label}'"
        return self._eventually(check, timeout)

    def has(self, *texts, timeout=3000):
        def check(screen):
            missing = [t for t in texts if not screen.has(t)]
            return f"has: not on screen: {missing}" if missing else None
        return self._eventually(check, timeout)

    def hasnt(self, *texts, timeout=3000):
        def check(screen):
            present = [t for t in texts if screen.has(t)]
            return f"hasnt: on screen but should not be: {present}" if present else None
        return self._eventually(check, timeout)

    def selected(self, text, value=True, timeout=3000):
        def check(screen):
            hits = screen.find(text)
            if not hits:
                return f"selected('{text}'): no such element on screen"
            return None if bool(hits[0].get("selected")) == value else f"selected('{text}', {value}): it is {'selected' if hits[0].get('selected') else 'not selected'}"
        return self._eventually(check, timeout)

    def checked(self, text, value=True, timeout=3000):
        def check(screen):
            hits = screen.find(text)
            if not hits:
                return f"checked('{text}'): no such element on screen"
            state = hits[0].get("checked")
            if state is None:
                return f"checked('{text}'): the element reports no checked state"
            return None if (state == "on") == value else f"checked('{text}', {value}): it is {state}"
        return self._eventually(check, timeout)

    def pause(self, millis):
        time.sleep(millis / 1000)

    def skip(self, reason):
        raise WalkSkip(reason)

    def note(self, text):
        steps = self.current["steps"] if self.current else []
        (steps[-1]["notes"] if steps else self.current.setdefault("notes", [])).append(text)

    def snap(self, id, caption="", delay=300, compare="top"):
        """Screenshot + what the app reads, under this id. `delay` lets fades finish (they do not show in what is read).

        `compare` is the part `--baseline` looks at for this snap: "top" (the window that takes keys, and the toasts),
        "toasts" (a toast over a page whose content does not matter here) or "all"."""
        if compare not in ("top", "toasts", "all"):
            raise WalkFail(f"snap('{id}'): compare is one of top / toasts / all")
        if not re.fullmatch("[A-Za-z0-9_-]+", id):
            raise WalkFail(f"snap('{id}'): ids use letters, digits, - and _ only")
        if id in self.snaps:
            raise WalkFail(f"snap('{id}'): this id was already used in lane '{self.snaps[id]['lane']}'")
        screen = self._settled_screen()
        if not screen.front:
            raise WalkFail(f"snap('{id}'): the app is not in front")
        time.sleep(delay / 1000)
        self.console.screenshot(self.out / "shots" / f"{id}.png")
        screen = self.screen()
        (self.out / "ui" / f"{id}.json").write_text(json.dumps(screen.data, ensure_ascii=False, indent=1), encoding="utf-8", newline="\n")
        (self.out / "ui" / f"{id}.txt").write_text(screen.render(self.volatile), encoding="utf-8", newline="\n")
        step = {"id": id, "caption": caption, "lane": self.current["id"] if self.current else None, "shot": f"shots/{id}.png", "ui": f"ui/{id}.txt",
                "focus": screen.focus_label, "toasts": screen.toasts, "notes": [], "compare": compare}
        self.snaps[id] = step
        if self.current:
            self.current["steps"].append(step)
        self.log(f"snap {id} focus='{screen.focus_label}'")
        print(f"    snap {id}  focus: {screen.focus_label or '-'}", flush=True)
        return screen

    def dsl(self):
        names = ["walk", "lane", "setup", "teardown", "volatile_text", "kept_shot", "get", "call", "adb", "keys", "ok", "goto", "tap", "see", "gone",
                 "focus", "has", "hasnt", "selected", "checked", "pause", "skip", "note", "snap", "screen"]
        scope = {name: getattr(self, name) for name in names}
        scope.update(WalkFail=WalkFail, WalkSkip=WalkSkip)
        return scope

    # ---- running ----
    def _failure(self, entry, error, walk_file):
        frames = [f for f in traceback.extract_tb(error.__traceback__) if pathlib.Path(f.filename) == walk_file]
        where = f"{walk_file.name}:{frames[-1].lineno}" if frames else ""
        message = str(error) if isinstance(error, WalkFail) else f"{type(error).__name__}: {error}"
        failure = {"message": mask(message), "at": where, "line": frames[-1].line if frames else ""}
        try:
            name = f"fail-{entry['id']}"
            self.console.screenshot(self.out / "fail" / f"{name}.png")
            screen = self.screen()
            (self.out / "fail" / f"{name}.txt").write_text(screen.render(), encoding="utf-8", newline="\n")
            failure.update(shot=f"fail/{name}.png", ui=f"fail/{name}.txt", focus=screen.focus_label)
        except Exception as e:  # the app may be gone; the failure itself is what matters
            failure["capture"] = mask(f"could not capture the screen: {e}")
        entry["failure"] = failure
        self.log(f"FAILED {entry['id']} {failure['message']} @ {where}")

    def _record_start(self, lane_id):
        if not self.video:
            return None
        remote = f"/sdcard/walk-{lane_id}.mp4"
        cmd = ["adb"] + (["-s", self.console.serial] if self.console.serial else []) + ["shell", "screenrecord", "--time-limit", "180", remote]
        return subprocess.Popen(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL), remote

    def _record_stop(self, recording, lane_id):
        if not recording:
            return None
        process, remote = recording
        self.console.adb("shell", "pkill", "-INT", "screenrecord", check=False)
        try:
            process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            process.kill()
        time.sleep(1)
        local = self.out / "video" / f"{lane_id}.mp4"
        self.console.adb("pull", remote, str(local), check=False)
        self.console.adb("shell", "rm", "-f", remote, check=False)
        return f"video/{lane_id}.mp4" if local.exists() else None

    def _part(self, entry, fn, walk_file):
        try:
            fn()
            return True
        except WalkSkip as e:
            entry["status"], entry["skipped"] = "skipped", str(e)
        except Exception as e:  # a bug in the walk file is reported like a failed step
            entry["status"] = "failed"
            self._failure(entry, e, walk_file)
            if isinstance(e, ConsoleGone):
                self.console_gone = True
        return False

    def run(self, walk_file, only, teardown=True):
        for sub in ("shots", "ui", "fail") + (("video",) if self.video else ()):
            (self.out / sub).mkdir(parents=True, exist_ok=True)
        self.log_file = open(self.out / "log.txt", "w", encoding="utf-8", newline="\n")
        manifest = {"walk": self.name or walk_file.stem, "file": str(walk_file), "package": self.package, "serial": self.console.serial,
                    "started": datetime.datetime.now().isoformat(timespec="seconds"), "lanes": [], "kept": self.kept}
        unknown = set(only or ()) - {l.id for l in self.lanes}
        if unknown:
            raise SystemExit(f"no such lane: {sorted(unknown)}; lanes: {[l.id for l in self.lanes]}")
        lanes = [l for l in self.lanes if not only or l.id in only]
        ready = True
        if self.setup_fn:
            entry = {"id": "setup", "title": "setup", "status": "passed", "steps": []}
            self.current = entry
            print("setup", flush=True)
            if not self._part(entry, self.setup_fn, walk_file):
                manifest["setup"] = entry
                ready = False
        for lane in lanes if ready else []:
            entry = {"id": lane.id, "title": lane.title, "status": "passed", "steps": []}
            manifest["lanes"].append(entry)
            if self.console_gone:
                entry["status"], entry["skipped"] = "skipped", "the console stopped answering in an earlier lane"
                print(f"lane {lane.id}: SKIPPED ({entry['skipped']})", flush=True)
                continue
            self.current = entry
            print(f"lane {lane.id}: {lane.title}", flush=True)
            started = time.monotonic()
            recording = self._record_start(lane.id)
            self._part(entry, lane.run, walk_file)
            if lane.after:
                after = {"id": f"{lane.id}-after", "steps": []}
                if not self._part(after, lane.after, walk_file) and "failure" in after:
                    entry["afterFailure"] = after["failure"]
            video = self._record_stop(recording, lane.id)
            if video:
                entry["video"] = video
            entry["seconds"] = round(time.monotonic() - started, 1)
            if entry["status"] != "passed":
                reason = entry.get("skipped") or f"{entry['failure']['message']}  @ {entry['failure']['at']}"
                print(f"  {entry['status'].upper()}: {reason}", flush=True)
        if self.teardown_fn and teardown and not self.console_gone:
            entry = {"id": "teardown", "title": "teardown", "status": "passed", "steps": []}
            self.current = entry
            print("teardown", flush=True)
            if not self._part(entry, self.teardown_fn, walk_file):
                manifest["teardown"] = entry
        manifest["finished"] = datetime.datetime.now().isoformat(timespec="seconds")
        self.log_file.close()
        return manifest


def compare(out, baseline, manifest, volatile):
    """Which snaps read differently from the baseline run: the ones whose pictures and captions are worth a look.

    Both sides are rendered again from what was read (ui/<id>.json), with this walk's volatile patterns and each snap's
    `compare` scope, so a baseline made before a pattern or a scope was added still compares fairly."""
    def text(directory, id, scope):
        data = json.loads((directory / "ui" / f"{id}.json").read_text(encoding="utf-8"))
        return Screen(data).render(volatile, scope)
    changed, added = [], []
    steps = {s["id"]: s for lane in manifest["lanes"] for s in lane["steps"]}
    produced = sorted(steps)
    for id in produced:
        if not (baseline / "ui" / f"{id}.json").exists():
            added.append(id)
        elif text(baseline, id, steps[id].get("compare", "top")) != text(out, id, steps[id].get("compare", "top")):
            changed.append(id)
    missing = sorted(p.stem for p in (baseline / "ui").glob("*.json") if p.stem not in produced)
    manifest["baseline"] = {"dir": str(baseline), "changed": changed, "added": added, "missing": missing}


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("walk_file")
    parser.add_argument("--out", help="directory for shots/, ui/, manifest.json (default: a new directory under the system temp dir)")
    parser.add_argument("--url", help="console address with its token (default: the file `console.sh url` wrote)")
    parser.add_argument("--serial", default=os.environ.get("ANDROID_SERIAL"), help="adb serial (default: $ANDROID_SERIAL)")
    parser.add_argument("--only", help="comma-separated lane ids to run")
    parser.add_argument("--list", action="store_true", help="list the lanes and exit")
    parser.add_argument("--baseline", help="an earlier run's output directory: report which snaps read differently")
    parser.add_argument("--no-teardown", action="store_true", help="leave the app as the last lane left it (to look at a failure by hand)")
    parser.add_argument("--video", action="store_true", help="screen-record every lane (adb screenrecord, at most 3 minutes each)")
    args = parser.parse_args()

    walk_file = pathlib.Path(args.walk_file).resolve()
    out = pathlib.Path(args.out).resolve() if args.out else pathlib.Path(tempfile.mkdtemp(prefix=f"izuko-walk-{walk_file.stem}-"))
    url = args.url
    if not url and not args.list:
        state = pathlib.Path(os.environ.get("CONSOLE_STATE_DIR") or pathlib.Path(tempfile.gettempdir()) / "izuko-console")
        url_file = state / "url"
        if not url_file.exists():
            raise SystemExit(f"no console address: pass --url, or run `console.sh url <package>` first (looked in {url_file})")
        url = url_file.read_text(encoding="utf-8").strip()
    walk = Walk(Console(url or "http://unused/", args.serial), out, args.video)
    runpy.run_path(str(walk_file), init_globals=walk.dsl())
    if args.list:
        for lane in walk.lanes:
            print(f"{lane.id}\t{lane.title}")
        return 0
    only = [s.strip() for s in args.only.split(",") if s.strip()] if args.only else None
    manifest = walk.run(walk_file, only, teardown=not args.no_teardown)
    if args.baseline:
        compare(out, pathlib.Path(args.baseline).resolve(), manifest, walk.volatile)
    (out / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=1), encoding="utf-8", newline="\n")

    lanes = manifest["lanes"]
    failed = [l for l in lanes if l["status"] == "failed"] + [manifest[k] for k in ("setup", "teardown") if k in manifest]
    print()
    print(f"{sum(l['status'] == 'passed' for l in lanes)} passed, {len(failed)} failed, {sum(l['status'] == 'skipped' for l in lanes)} skipped; "
          f"{sum(len(l['steps']) for l in lanes)} snaps -> {out}")
    for lane in failed:
        failure = lane["failure"]
        print(f"FAILED {lane['id']}: {failure['message']}")
        print(f"    at {failure['at']}: {failure['line']}")
        if failure.get("shot"):
            print(f"    screen then: {out / failure['shot']}  (focus: {failure.get('focus') or '-'})")
    if "baseline" in manifest:
        b = manifest["baseline"]
        print(f"against the baseline: {len(b['changed'])} read differently {b['changed']}, {len(b['added'])} new {b['added']}, "
              f"{len(b['missing'])} not produced {b['missing']}")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
