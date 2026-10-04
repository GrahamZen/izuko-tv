# Repository Guidelines

This is the repository for the app. For the server, you can navigate to ../ani-api-server

Read docs/contributing for project guidelines. Before modifying a subsystem, check docs/contributing/code/ for its documentation (e.g. the media framework docs cover terminology, class-level code maps, and the playback flow) and read the relevant docs first.

Additional requirements:

- You should add imports, instead of using fully qualified names in code.
- For Android Instrumented tests, you can just use `@Test`, no need to write `@RunWith` to the class.
- **新代码尽量「新文件 + 在上游文件里留一行钩子」**, 别把成段的 fork 逻辑写进上游文件里。这个仓库是 fork, 每次跟上游
  `./scripts/fork-rebase.sh` 都要按文件解一遍冲突 —— 两边都改过的文件就是每轮的固定税 (2026-10-05: 226 个文件 / 122 处要人手)。
  实测 fork 在一个上游文件里改 >300 行时冲突率 48%, 缩到 1~2 行只剩 12%, 而且剩下那点是「取上游新版 + 把钩子放回去」的一眼活。
  **事后再抽基本抽不动** (已有的热文件里 fork 改动「纯新增块」只占 14%~36%, 都是就地改写与删除), 所以要在写的时候就这么写。
  抽不动的那类靠 `scripts/rebase/take-fork.txt` 整取 fork 版; 整块用不上的上游代码 (如上游自带的 Android TV 客户端) 直接删掉,
  以后上游改它一律自动走「保持删除」。
- 注释和文档应直接描述当前设计、职责、行为与约束，不要用“不再…”“改为…”“新设计…”等措辞叙述开发过程，也不要记录未上线方案、被纠正的错误假设或对话历史。只有在解释兼容性或迁移逻辑确有必要时，才说明已发布版本的历史行为。

## UI Verification

- **Prefer reusable interactive UI tests** over driving a real window: use `runAniComposeUiTest` (`utils/ui-testing`) with synthetic input (`performClick`, `performTextInput`, `sendKeyEvent`) and assert on semantics — focus, text, state, bounds. They run without OS input — no focus stealing, no real mouse — and stay in the repo as regression tests. When you verify a UI change manually, consider leaving such a test behind.
- `assertScreenshot` compares against golden images only on desktop; on Android it is a no-op. Android and TV device tests do not capture or compare pixels: expose visual state that semantics cannot express (blur, dim, glow, whether an animation runs) through `TvVisualSemantics` in TV code, and test color or geometry calculations as pure functions in host tests. Check the rendered look manually with the skills below.
- Reserve the skills below for what headless tests cannot cover: JCEF, VLC/mpv playback, native libraries, packaging, window chrome, emulator behavior.
- Screenshots and recordings taken as verification evidence never go into the repository: git history keeps every binary forever, even after the file or branch is deleted. Upload them as GitHub attachments (the `github-image-upload` skill, when available) and embed the `https://github.com/user-attachments/...` URLs in the PR description or comment. If the upload fails, post no images and describe what you verified in text. Do not commit files, push branches, or create refs just to get an image URL. App resources and `assertScreenshot` baselines are not evidence and belong in the repository as usual.

## Agent Skills

- **On a real TV / box, test through the app's web console first** (player state, source switching, seek / pause, history, search, caches, cloud drives): `.agents/skills/web-console-test/SKILL.md`, helper `.agents/skills/web-console-test/scripts/console.sh`. Fall back to remote keys and screenshots only for TV-only UI the console cannot reach.
- For interactive Android UI verification (start emulator, install the app, then tap/swipe/type and verify via screenshots, UI-hierarchy dumps, logcat, and Figma design comparison), use the repo-local skill at `.agents/skills/android-ui-verify/SKILL.md`. Its toolbox script is `.agents/skills/android-ui-verify/scripts/droid.sh` (run `droid.sh help`). `.claude/skills/android-ui-verify` is a symlink to it for Claude Code auto-discovery.
- For desktop/PC executable validation (Compose Desktop, JCEF, VLC/native libraries, packaging, macOS window screenshots), use the repo-local skill at `.agents/skills/desktop-ui-verify/SKILL.md`.

## Generating Client

If you change server API, you can then use `./gradlew generateOpenApiForAnimeko` to automatically re-generate the client. Don't manually write http client.
