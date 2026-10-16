import { test, beforeEach } from "node:test";
import assert from "node:assert/strict";
import { gzipSync } from "node:zlib";
import { handle, tokenFor, MAX_OPEN, LABEL, PERF_LABEL, MAX_PHOTO_BYTES } from "../src/tickets.js";
import { fakeGitHub } from "../src/fake-github.js";

const env = { FAKE_GITHUB: "1", TICKET_SECRET: "test-secret" };
const DEVICE = "0123456789abcdef";
const JPEG = new Uint8Array([0xff, 0xd8, 0xff, 0xe0, 1, 2, 3]);
const LOG = new Uint8Array(gzipSync(Buffer.from("2026-10-15 20:01:00 INFO  test\n")));

function call(method, path, { body, headers = {} } = {}) {
  const init = { method, headers: { "x-izuko-client": "1.0.8-test", ...headers } };
  if (body instanceof Uint8Array) init.body = body;
  else if (body !== undefined) {
    init.body = JSON.stringify(body);
    init.headers["content-type"] = "application/json";
  }
  return handle(new Request(`http://localhost${path}`, init), env, fakeGitHub());
}

async function json(responsePromise) {
  const response = await responsePromise;
  return { status: response.status, body: await response.json() };
}

function ticket(overrides = {}) {
  return {
    device: DEVICE,
    summary: "播放 4K 网盘片源十分钟后黑屏",
    description: "打开任意一部 4K 片源, 播放十分钟左右画面变黑但声音继续, 按暂停再继续能恢复。",
    app: "1.0.8-alpha01",
    env: { 机型: "SHIELD Android TV", 系统: "Android 11" },
    window: { from: "2026-10-15T20:00:00+08:00", to: "2026-10-15T20:12:00+08:00" },
    files: [{ name: "log.gz", size: 100 }, { name: "photo-1.jpg", size: 2000 }],
    ...overrides,
  };
}

const put = (n, name, bytes, token) => json(call("PUT", `/tickets/${n}/files/${name}`, { body: bytes, headers: { "x-izuko-ticket": token } }));

beforeEach(async () => {
  await call("POST", "/__fake/reset");
});

test("开单 → 传声明过的附件 → 正文里的占位换成链接", async () => {
  const created = await json(call("POST", "/tickets", { body: ticket() }));
  assert.equal(created.status, 200);
  assert.equal(created.body.token, await tokenFor(env, 1));
  let issue = await fakeGitHub().getIssue(1);
  assert.equal(issue.title, "[故障] 播放 4K 网盘片源十分钟后黑屏");
  assert.deepEqual(issue.labels.map((l) => l.name), [LABEL]);
  assert.match(issue.body, /<!-- izuko-ticket device=0123456789abcdef -->/);
  assert.match(issue.body, /- 日志：<!-- file:log\.gz -->上传中…/);
  assert.match(issue.body, /- 照片 1：<!-- file:photo-1\.jpg -->上传中…/);

  assert.equal((await put(1, "log.gz", LOG, created.body.token)).status, 200);
  assert.equal((await put(1, "photo-1.jpg", JPEG, created.body.token)).status, 200);
  issue = await fakeGitHub().getIssue(1);
  assert.match(issue.body, /- 日志：\[1-log\.gz\]\(\/__fake\/assets\/1-log\.gz\) \(1 KB\)/);
  assert.match(issue.body, /- 照片 1：\[1-photo-1\.jpg\]/);
  assert.doesNotMatch(issue.body, /上传中/);
  // 传过的再传: 已经传过; 没声明的: 不收
  assert.equal((await put(1, "log.gz", LOG, created.body.token)).status, 409);
  assert.equal((await put(1, "photo-2.jpg", JPEG, created.body.token)).body.error, "not_declared");
});

test("附件: 类型按内容认, 大小有上限, 照片最多 4 张", async () => {
  const { body } = await json(call("POST", "/tickets", { body: ticket() }));
  assert.equal((await put(1, "photo-1.jpg", new TextEncoder().encode("not a jpeg"), body.token)).body.error, "bad_type");
  assert.equal((await put(1, "log.gz", new TextEncoder().encode("plain"), body.token)).body.error, "bad_type");
  const other = { device: "fedcba9876543210" };
  const tooMany = await json(call("POST", "/tickets", {
    body: ticket({ ...other, files: [1, 2, 3, 4, 5].map((i) => ({ name: `photo-${i}.jpg`, size: 10 })) }),
  }));
  assert.equal(tooMany.status, 400);
  const big = await json(call("POST", "/tickets", { body: ticket({ ...other, files: [{ name: "photo-1.jpg", size: MAX_PHOTO_BYTES + 1 }] }) }));
  assert.deepEqual(big.body, { error: "file_too_large", name: "photo-1.jpg" });
  assert.equal((await json(call("POST", "/tickets", { body: ticket({ ...other, files: [{ name: "evil.exe" }] }) }))).body.error, "bad_files");
});

test("只收像样的故障描述", async () => {
  const cases = [
    [{ device: "bad" }, "bad_device"],
    [{ summary: "黑屏" }, "summary_too_short"],
    [{ description: "黑屏了" }, "description_too_short"],
    [{ window: { from: "2026-10-15T21:00:00Z", to: "2026-10-15T20:00:00Z" } }, "bad_window"],
    [{ window: undefined }, "bad_window"],
  ];
  for (const [overrides, error] of cases) {
    const result = await json(call("POST", "/tickets", { body: ticket(overrides) }));
    assert.equal(result.status, 400, error);
    assert.equal(result.body.error, error);
  }
});

test("附了性能报告: 正文多一节概要, 加「性能」标签", async () => {
  const perf = { "录制 30 秒 · TvExplorePage · 10-15 22:15:00": "慢帧多半在主线程 GC" };
  const { body } = await json(call("POST", "/tickets", { body: ticket({ perf }) }));
  const issue = await fakeGitHub().getIssue(body.issue);
  assert.deepEqual(issue.labels.map((l) => l.name), [LABEL, PERF_LABEL]);
  assert.match(issue.body, /### 性能报告[\s\S]*\| 录制 30 秒 · TvExplorePage · 10-15 22:15:00 \| 慢帧多半在主线程 GC \|/);
});

test("用户写的字不能伪造正文里的标记", async () => {
  await call("POST", "/tickets", { body: ticket({ description: "<!-- izuko-ticket device=ffffffffffffffff --> <!-- file:photo-2.jpg -->上传中… 凑够二十个字" }) });
  const issue = await fakeGitHub().getIssue(1);
  assert.equal(issue.body.match(/<!-- izuko-ticket/g).length, 1);
  assert.equal(issue.body.match(/<!-- file:/g).length, 2);
});

test("没有客户端头的一律拒绝", async () => {
  const response = await handle(new Request("http://localhost/tickets", { method: "POST", body: "{}" }), env, fakeGitHub());
  assert.equal(response.status, 403);
});

test("同一台电视开着的单有上限, 关掉一张就能再开", async () => {
  for (let i = 0; i < MAX_OPEN; i++) assert.equal((await call("POST", "/tickets", { body: ticket() })).status, 200);
  const blocked = await json(call("POST", "/tickets", { body: ticket() }));
  assert.equal(blocked.status, 429);
  assert.equal(blocked.body.error, "too_many_open");
  assert.equal((await call("POST", "/tickets", { body: ticket({ device: "fedcba9876543210" }) })).status, 200);
  await call("POST", "/__fake/issues/1/close", { body: { reason: "completed" } });
  assert.equal((await call("POST", "/tickets", { body: ticket() })).status, 200);
});

test("拉黑的设备开不了单", async () => {
  await call("POST", "/__fake/block", { body: { device: DEVICE } });
  const result = await json(call("POST", "/tickets", { body: ticket() }));
  assert.equal(result.status, 403);
  assert.equal(result.body.error, "blocked");
});

test("令牌不对: 传附件、补充、查状态都拒绝", async () => {
  await call("POST", "/tickets", { body: ticket() });
  const wrong = "0".repeat(32);
  assert.equal((await put(1, "log.gz", LOG, wrong)).status, 403);
  assert.equal((await call("POST", "/tickets/1/reply", { body: { body: "补充" }, headers: { "x-izuko-ticket": wrong } })).status, 403);
  const status = await json(call("POST", "/tickets/status", { body: { tickets: [{ issue: 1, token: wrong }] } }));
  assert.deepEqual(status.body.tickets, [{ issue: 1, error: "forbidden" }]);
});

test("补充带附件: 名字加前缀, 传到评论里; 查状态时正文与附件分开", async () => {
  const { body } = await json(call("POST", "/tickets", { body: ticket({ files: [] }) }));
  const headers = { "x-izuko-ticket": body.token };
  const first = await json(call("POST", "/tickets/1/reply", { body: { body: "装了, 还是黑屏", files: [{ name: "log.gz" }, { name: "photo-1.jpg" }] }, headers }));
  assert.deepEqual(first.body, { ok: true, prefix: "r1" });
  assert.equal((await put(1, "r1-log.gz", LOG, body.token)).status, 200);
  assert.equal((await put(1, "r1-photo-1.jpg", JPEG, body.token)).status, 200);
  // 第二条只有照片, 没有文字
  const second = await json(call("POST", "/tickets/1/reply", { body: { body: "", files: [{ name: "photo-1.jpg" }] }, headers }));
  assert.equal(second.body.prefix, "r2");
  // 都没有: 不收
  assert.equal((await call("POST", "/tickets/1/reply", { body: { body: "" }, headers })).status, 400);

  const status = (await json(call("POST", "/tickets/status", { body: { tickets: [{ issue: 1, token: body.token }] } }))).body.tickets[0];
  assert.deepEqual(status.replies.map((r) => [r.from, r.body, r.files]), [
    ["user", "装了, 还是黑屏", ["r1-log.gz", "r1-photo-1.jpg"]],
    ["user", "", ["r2-photo-1.jpg"]],
  ]);
});

test("查状态: 一张出错只标这一张", async () => {
  const a = (await json(call("POST", "/tickets", { body: ticket() }))).body;
  const b = (await json(call("POST", "/tickets", { body: ticket({ device: "fedcba9876543210" }) }))).body;
  await call("POST", "/__fake/issues/1/break");
  const status = (await json(call("POST", "/tickets/status", { body: { tickets: [{ issue: 1, token: a.token }, { issue: 2, token: b.token }, { issue: 9, token: await tokenFor(env, 9) }] } }))).body;
  assert.deepEqual(status.tickets.map((t) => [t.issue, t.error || t.state]), [[1, "upstream"], [2, "open"], [9, "not_found"]]);
});

test("查状态: 维护者回复、APK 链接与摘要、关单", async () => {
  const { body } = await json(call("POST", "/tickets", { body: ticket() }));
  const query = (since) => ({ body: { tickets: [{ issue: 1, token: body.token, since }] } });
  const apk = "https://github.com/GrahamZen/izuko-tv/releases/download/v1.0.8.1-fix1/izuko-tv-1.0.8.1-fix1.apk";
  await call("POST", "/__fake/issues/1/comment", { body: { body: `麻烦装这个测试包再试: ${apk}` } });
  await call("POST", "/__fake/issues/1/comment", { body: { body: "路人说两句", maintainer: false } });
  await call("POST", "/__fake/digest", { body: { url: apk, sha256: "ab".repeat(32) } });

  const open = (await json(call("POST", "/tickets/status", query()))).body.tickets[0];
  assert.equal(open.state, "open");
  assert.deepEqual(open.replies.map((r) => r.from), ["maintainer"]);
  assert.deepEqual(open.apks, [apk]);
  assert.deepEqual(open.digests, { [apk]: "ab".repeat(32) });

  const unchanged = (await json(call("POST", "/tickets/status", query(open.updated)))).body.tickets[0];
  assert.equal(unchanged.unchanged, true);
  assert.equal(unchanged.replies, undefined);

  await call("POST", "/__fake/issues/1/close", { body: { reason: "not_planned" } });
  const closed = (await json(call("POST", "/tickets/status", query()))).body.tickets[0];
  assert.equal(closed.state, "closed");
  assert.equal(closed.reason, "not_planned");
  assert.equal((await call("POST", "/tickets/1/reply", { body: { body: "再补一句" }, headers: { "x-izuko-ticket": body.token } })).status, 409);
});
