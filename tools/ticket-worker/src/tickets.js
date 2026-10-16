// Izuko TV 故障工单: Web 控制台「设置 → 反馈」里写清问题、附上数据后提交, 这里在私有工单仓库开 issue、存附件;
// 电视每天来查一次处理结果与维护者的回复, 用户可以接着补充 (也能带附件). 说明见 README.md.
//
// 接口 (都要带 X-Izuko-Client 头, 值是 app 版本号; 没有的 403):
//   POST /tickets                    开单 {device, summary, description, app, env{}, window{from, to}, perf{}?, files[]} → {issue, token}
//                                    perf{}: 附上的性能诊断报告的概要 (每份一行, 正文多一节, 加「性能」标签; 报告原文在日志附件开头)
//                                    files[]: 这条要传的附件 [{name, size}], name 见 FILE_RE; 正文「附件」一节先占位, 传上来再换成链接
//   PUT  /tickets/<n>/files/<name>   传一个声明过的附件 (日志 gzip / 照片), 凭证放 X-Izuko-Ticket 头 → {ok, url}
//   POST /tickets/status             查处理结果 {tickets: [{issue, token, since?}]} → {tickets: [...]};
//                                    since 是上次回来的 updated, 没变的只回 unchanged. 维护者回复里的 .apk 链接列在 apks,
//                                    其中 GitHub release 附件的 SHA-256 在 digests ({链接: 十六进制}): 电视经镜像下载时按它校验;
//                                    单张的 error: forbidden (凭证不对) / not_found (单不在了) / upstream (这次没查成, 下次再查)
//   POST /tickets/<n>/reply          用户补充 {body, files[]}, 凭证放 X-Izuko-Ticket 头 → {ok, prefix};
//                                    这条的附件名前面加 prefix (如 r2-log.gz), 照样经 PUT files 传
//
// 凭证: token = HMAC-SHA256(TICKET_SECRET, "ticket:<n>") 的前 32 位十六进制, 只交给开单的那台电视, 这里不存.
// 凭证不放进地址: 电视的应用日志会记请求地址, 日志又会被打包上传、分享.
// 只收故障: 必须有问题、足够长的描述与时间段, 附件另传; 同一台电视同时最多 MAX_OPEN 张开着的单.
// 附件只收声明过的 (每条最多一份日志、MAX_PHOTOS 张照片), 各有大小上限, 按开头几个字节认类型; 存成工单仓库 release 的附件 <n>-<name>.
// 拉黑: 工单仓库根目录 blocklist.txt, 一行一个设备号 (工单仓库的 block-device 工作流在 issue 打上「拉黑」标签时写进去).
// 防刷: 每个 IP 开单与补充 TICKET_LIMITER、查状态 STATUS_LIMITER (Workers 的限频绑定; 没绑时不限); 传附件只能填已声明的位子, 不另限.
//
// 绑定: TICKETS_REPO (vars), TICKETS_GITHUB_TOKEN 与 TICKET_SECRET (secret);
// 本地调试 FAKE_GITHUB = "1": 不连 GitHub, 用内存里的假仓库, 另有 /__fake/ 下的几个接口模拟维护者回复、关单、拉黑.

import { fakeAdmin } from "./fake-github.js";

export const LABEL = "故障反馈";
/** 附了性能诊断报告的单另加的标签. */
export const PERF_LABEL = "性能";
export const MAX_OPEN = 2;
export const MIN_DESCRIPTION = 5;
export const MAX_DESCRIPTION = 4000;
export const MAX_SUMMARY = 80;
export const MAX_LOG_BYTES = 20 * 1024 * 1024;
export const MAX_PHOTO_BYTES = 5 * 1024 * 1024;
export const MAX_PHOTOS = 4;
export const MAX_STATUS = 10;

const CLIENT_HEADER = "x-izuko-client";
const TICKET_HEADER = "x-izuko-ticket";
const APP_RE = /^[\w.+\- ]{1,40}$/;
const DEVICE_RE = /^[0-9a-f]{16}$/;
/** 附件名: 日志 log.gz, 照片 photo-<1..4>.<jpg|png|webp>; 补充里的前面加 r<k>-. */
export const FILE_RE = /^(r\d{1,3}-)?(log\.gz|photo-[1-4]\.(jpg|png|webp))$/;
const USER_REPLY = "<!-- izuko:user-reply -->";
const USER_REPLY_TITLE = "**用户补充：**";
const FILES_MARK = "<!-- izuko:files -->";
const MAINTAINERS = new Set(["OWNER", "MEMBER", "COLLABORATOR"]);
const APK_RE = /https?:\/\/[^\s)<>"']+\.apk\b/g;

function json(body, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json; charset=utf-8" } });
}

/** 用户写的字: 去掉控制字符, 不让它伪造正文里的标记 (<!-- -->). */
function clean(text, max) {
  return String(text ?? "")
    .replace(/[\u0000-\u0008\u000b\u000c\u000e-\u001f]/g, "")
    .replace(/<!--/g, "&lt;!--")
    .trim()
    .slice(0, max);
}

export function deviceMarker(device) {
  return `<!-- izuko-ticket device=${device} -->`;
}

/** 附件在正文 / 评论里的占位: 传上来之前是「上传中…」, 之后整行换成链接. */
export function filePlaceholder(name) {
  return `<!-- file:${name} -->上传中…`;
}

async function hmacHex(secret, message) {
  const key = await crypto.subtle.importKey("raw", new TextEncoder().encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  const sig = new Uint8Array(await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(message)));
  return [...sig].map((b) => b.toString(16).padStart(2, "0")).join("");
}

export async function tokenFor(env, issue) {
  return (await hmacHex(env.TICKET_SECRET, `ticket:${issue}`)).slice(0, 32);
}

async function tokenOk(env, issue, token) {
  if (typeof token !== "string" || token.length !== 32) return false;
  const expected = await tokenFor(env, issue);
  // 定长逐位比, 不提前返回
  let diff = 0;
  for (let i = 0; i < 32; i++) diff |= expected.charCodeAt(i) ^ token.charCodeAt(i);
  return diff === 0;
}

async function limited(limiter, key) {
  if (!limiter) return false;
  const { success } = await limiter.limit({ key });
  return !success;
}

function isPhoto(name) {
  return /photo-\d\./.test(name);
}

/** 一条 (开单 / 补充) 声明的附件: 名字合法、不重复、照片不超过 MAX_PHOTOS、声明的大小不超上限. 不合法返回错误码. */
export function parseFiles(files, prefix = "") {
  if (files === undefined) return { names: [] };
  if (!Array.isArray(files)) return { error: "bad_files" };
  const names = [];
  for (const f of files) {
    const name = f && typeof f.name === "string" ? prefix + f.name : "";
    if (!FILE_RE.test(name) || names.includes(name)) return { error: "bad_files" };
    const limit = isPhoto(name) ? MAX_PHOTO_BYTES : MAX_LOG_BYTES;
    if (typeof f.size === "number" && f.size > limit) return { error: "file_too_large", name };
    names.push(name);
  }
  if (names.filter(isPhoto).length > MAX_PHOTOS) return { error: "too_many_photos" };
  return { names };
}

/** 「附件」一节: 每个文件一行占位. */
function filesSection(names) {
  if (!names.length) return [];
  let photo = 0;
  return [
    FILES_MARK,
    ...names.map((name) => `- ${isPhoto(name) ? `照片 ${++photo}` : "日志"}：${filePlaceholder(name)}`),
  ];
}

/** 开单请求 → issue 标题与正文; 不合法返回错误码. */
export function parseTicket(body) {
  if (!body || typeof body !== "object") return { error: "bad_request" };
  if (typeof body.device !== "string" || !DEVICE_RE.test(body.device)) return { error: "bad_device" };
  const summary = clean(body.summary, MAX_SUMMARY).replace(/\s+/g, " ");
  if (summary.length < 4) return { error: "summary_too_short" };
  const description = clean(body.description, MAX_DESCRIPTION);
  if ([...description].length < MIN_DESCRIPTION) return { error: "description_too_short" };
  const window = body.window || {};
  if (typeof window.from !== "string" || typeof window.to !== "string" || !(Date.parse(window.from) <= Date.parse(window.to))) {
    return { error: "bad_window" };
  }
  const files = parseFiles(body.files);
  if (files.error) return files;
  const app = typeof body.app === "string" && APP_RE.test(body.app) ? body.app : "";
  const table = (obj, limit) => Object.entries(obj && typeof obj === "object" ? obj : {})
    .slice(0, limit)
    .map(([k, v]) => `| ${clean(k, 60).replace(/\|/g, "/")} | ${clean(v, 200).replace(/\|/g, "/").replace(/\s+/g, " ")} |`);
  const rows = table(body.env, 12);
  const perfRows = table(body.perf, 8);
  const issueBody = [
    deviceMarker(body.device),
    "### 问题", "", summary, "",
    "### 描述", "", description, "",
    "### 环境", "", "| 项 | 值 |", "|---|---|", `| 应用 | ${app || "?"} |`, `| 设备号 | ${body.device.slice(0, 8)} |`, ...rows, "",
    ...(perfRows.length ? ["### 性能报告", "", "| 项 | 值 |", "|---|---|", ...perfRows, "", "整份报告在日志附件的开头。", ""] : []),
    "### 时间段", "", `${clean(window.from, 40)} ~ ${clean(window.to, 40)}`, "",
    ...(files.names.length ? ["### 附件", "", ...filesSection(files.names), ""] : []),
  ].join("\n");
  return {
    title: `[故障] ${summary}`,
    body: issueBody,
    device: body.device,
    labels: perfRows.length ? [LABEL, PERF_LABEL] : [LABEL],
  };
}

async function createTicket(request, env, gh) {
  let ticket;
  try {
    ticket = parseTicket(await request.json());
  } catch {
    ticket = { error: "bad_request" };
  }
  if (ticket.error) return json({ error: ticket.error, ...(ticket.name ? { name: ticket.name } : {}) }, 400);
  if ((await gh.blocklist()).has(ticket.device)) return json({ error: "blocked" }, 403);
  const marker = deviceMarker(ticket.device);
  const open = (await gh.listOpen(LABEL)).filter((i) => (i.body || "").includes(marker));
  if (open.length >= MAX_OPEN) return json({ error: "too_many_open", issues: open.map((i) => i.number) }, 429);
  const issue = await gh.createIssue({ title: ticket.title, body: ticket.body, labels: ticket.labels });
  return json({ issue: issue.number, token: await tokenFor(env, issue.number) });
}

/** 按开头几个字节认: gzip / JPEG / PNG / WebP. */
function typeOk(name, bytes) {
  if (name.endsWith(".gz")) return bytes[0] === 0x1f && bytes[1] === 0x8b;
  if (name.endsWith(".jpg")) return bytes[0] === 0xff && bytes[1] === 0xd8 && bytes[2] === 0xff;
  if (name.endsWith(".png")) return bytes[0] === 0x89 && bytes[1] === 0x50 && bytes[2] === 0x4e && bytes[3] === 0x47;
  if (name.endsWith(".webp")) {
    const tag = (from) => String.fromCharCode(...bytes.slice(from, from + 4));
    return tag(0) === "RIFF" && tag(8) === "WEBP";
  }
  return false;
}

async function uploadFile(request, env, gh, n, name, token) {
  if (!(await tokenOk(env, n, token))) return json({ error: "forbidden" }, 403);
  if (!FILE_RE.test(name)) return json({ error: "bad_name" }, 400);
  const limit = isPhoto(name) ? MAX_PHOTO_BYTES : MAX_LOG_BYTES;
  if (Number(request.headers.get("content-length") || "0") > limit) return json({ error: "too_large" }, 413);
  const bytes = new Uint8Array(await request.arrayBuffer());
  if (bytes.length === 0) return json({ error: "empty" }, 400);
  if (bytes.length > limit) return json({ error: "too_large" }, 413);
  if (!typeOk(name, bytes)) return json({ error: "bad_type" }, 400);
  const issue = await gh.getIssue(n);
  if (!issue || !(issue.labels || []).some((l) => (l.name || l) === LABEL)) return json({ error: "not_found" }, 404);
  // 占位在正文 (开单时声明的) 或用户补充的评论里 (补充时声明的, 名字带 r<k>- 前缀)
  const placeholder = filePlaceholder(name);
  let target = (issue.body || "").includes(placeholder) ? { body: issue.body } : null;
  if (!target && name.startsWith("r")) {
    const comment = (await gh.listComments(n)).find((c) => (c.body || "").includes(placeholder));
    if (comment) target = { body: comment.body, comment: comment.id };
  }
  if (!target) {
    // 没有这个位子: 传过了 (占位已换成链接), 或者根本没声明过
    const uploaded = (issue.body || "").includes(`${n}-${name}`) || (await gh.listComments(n)).some((c) => (c.body || "").includes(`${n}-${name}`));
    return json({ error: uploaded ? "already_uploaded" : "not_declared" }, uploaded ? 409 : 404);
  }
  const asset = await gh.uploadAsset(`${n}-${name}`, bytes);
  const kb = Math.max(1, Math.round(bytes.length / 1024));
  const replaced = target.body.replace(placeholder, `[${n}-${name}](${asset.url}) (${kb} KB)`);
  if (target.comment) await gh.updateComment(target.comment, replaced);
  else await gh.updateIssueBody(n, replaced);
  return json({ ok: true, url: asset.url });
}

/** 用户补充的评论: 去掉标记, 正文与附件分开 (附件名只回名字, 链接在私有仓库里, 用户打不开). */
function userReply(body) {
  const text = body.slice(USER_REPLY.length).trim().replace(USER_REPLY_TITLE, "").trim();
  const at = text.indexOf(FILES_MARK);
  const files = at < 0 ? [] : [...text.slice(at).matchAll(/<!-- file:([^ ]+) -->|\[\d+-([^\]]+)\]\(/g)].map((m) => m[1] || m[2]);
  return { body: (at < 0 ? text : text.slice(0, at)).trim(), files };
}

/** 一张单的处理结果; [since] 之后没变化时只回 unchanged. */
async function ticketStatus(gh, n, since) {
  const issue = await gh.getIssue(n);
  if (!issue || !(issue.labels || []).some((l) => (l.name || l) === LABEL)) return { issue: n, error: "not_found" };
  const base = {
    issue: n,
    state: issue.state,
    reason: issue.state === "closed" ? issue.state_reason || null : null,
    updated: issue.updated_at,
  };
  if (since && Date.parse(issue.updated_at) <= Date.parse(since)) return { ...base, unchanged: true };
  const comments = await gh.listComments(n);
  const replies = [];
  const apks = [];
  for (const c of comments) {
    const body = c.body || "";
    if (body.startsWith(USER_REPLY)) {
      replies.push({ from: "user", at: c.created_at, ...userReply(body) });
    } else if (MAINTAINERS.has(c.author_association)) {
      replies.push({ from: "maintainer", at: c.created_at, body });
      for (const m of body.matchAll(APK_RE)) if (!apks.includes(m[0])) apks.push(m[0]);
    }
  }
  return { ...base, replies, apks, digests: apks.length ? await gh.assetDigests(apks) : {} };
}

async function status(request, env, gh) {
  let asked;
  try {
    asked = (await request.json()).tickets;
  } catch {
    asked = null;
  }
  if (!Array.isArray(asked)) return json({ error: "bad_request" }, 400);
  const tickets = [];
  for (const t of asked.slice(0, MAX_STATUS)) {
    const issue = t && Number.isInteger(t.issue) && t.issue > 0 ? t.issue : null;
    if (issue === null || !(await tokenOk(env, issue, t.token))) {
      tickets.push({ issue, error: "forbidden" });
      continue;
    }
    // 一张出错 (GitHub 一时不通) 只标这一张, 不拖累别的
    tickets.push(await ticketStatus(gh, issue, typeof t.since === "string" ? t.since : null).catch(() => ({ issue, error: "upstream" })));
  }
  return json({ tickets });
}

async function reply(request, env, gh, n, token) {
  if (!(await tokenOk(env, n, token))) return json({ error: "forbidden" }, 403);
  let request_;
  try {
    request_ = await request.json();
  } catch {
    request_ = {};
  }
  const body = clean(request_.body, MAX_DESCRIPTION);
  const issue = await gh.getIssue(n);
  if (!issue || !(issue.labels || []).some((l) => (l.name || l) === LABEL)) return json({ error: "not_found" }, 404);
  if (issue.state !== "open") return json({ error: "closed" }, 409);
  // 这是用户的第几条补充: 附件名前缀, 同一张单里不撞名
  const index = (await gh.listComments(n)).filter((c) => (c.body || "").startsWith(USER_REPLY)).length + 1;
  const prefix = `r${index}-`;
  const files = parseFiles(request_.files, prefix);
  if (files.error) return json({ error: files.error, ...(files.name ? { name: files.name } : {}) }, 400);
  if (!body && !files.names.length) return json({ error: "bad_request" }, 400);
  const section = filesSection(files.names);
  await gh.addComment(n, [USER_REPLY, USER_REPLY_TITLE, "", body, ...(section.length ? ["", ...section] : [])].join("\n"));
  return json({ ok: true, prefix: `r${index}` });
}

export async function handle(request, env, gh) {
  const url = new URL(request.url);
  if (env.FAKE_GITHUB === "1" && url.pathname.startsWith("/__fake/")) return fakeAdmin(url, request, gh);
  if (!url.pathname.startsWith("/tickets")) return json({ error: "not_found" }, 404);
  if (!APP_RE.test(request.headers.get(CLIENT_HEADER) || "")) return json({ error: "forbidden" }, 403);
  const ip = request.headers.get("cf-connecting-ip") || "unknown";
  const path = url.pathname.replace(/\/+$/, "");
  const token = request.headers.get(TICKET_HEADER);
  try {
    if (path === "/tickets/status" && request.method === "POST") {
      if (await limited(env.STATUS_LIMITER, ip)) return json({ error: "rate_limited" }, 429);
      return await status(request, env, gh);
    }
    const file = /^\/tickets\/(\d{1,9})\/files\/([\w.-]{1,40})$/.exec(path);
    if (file && request.method === "PUT") return await uploadFile(request, env, gh, parseInt(file[1], 10), file[2], token);
    if (await limited(env.TICKET_LIMITER, ip)) return json({ error: "rate_limited" }, 429);
    if (path === "/tickets" && request.method === "POST") return await createTicket(request, env, gh);
    const m = /^\/tickets\/(\d{1,9})\/reply$/.exec(path);
    if (m && request.method === "POST") return await reply(request, env, gh, parseInt(m[1], 10), token);
    return json({ error: "not_found" }, 404);
  } catch (e) {
    return json({ error: "upstream", message: String(e.message || e) }, 502);
  }
}
