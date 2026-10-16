// 本地调试用的假工单仓库 (FAKE_GITHUB = "1"): 状态放在内存里, 进程在就一直在. /__fake/ 下的接口模拟维护者那边的动作:
//   GET  /__fake/issues                 全部 issue (含评论与日志附件名)
//   GET  /__fake/assets/<名字>          下载传上来的日志
//   POST /__fake/issues/<n>/comment     维护者回复 {body}
//   POST /__fake/issues/<n>/close       关单 {reason: "completed" | "not_planned"}
//   POST /__fake/block                  拉黑 {device}
//   POST /__fake/digest                 给一个 release 附件链接配 SHA-256 {url, sha256} (假装它是 GitHub release 上的)
//   POST /__fake/reset                  清空
//   POST /__fake/issues/<n>/break       之后查这张单时 GitHub 出错 (测一张出错不拖累别的)

const state = { next: 1, nextComment: 1, issues: new Map(), assets: new Map(), blocked: new Set(), digests: new Map(), broken: new Set() };

function now() {
  return new Date().toISOString();
}

function touch(issue) {
  issue.updated_at = now();
}

export function fakeGitHub() {
  return {
    async listOpen(label) {
      return [...state.issues.values()].filter((i) => i.state === "open" && i.labels.some((l) => l.name === label));
    },
    async createIssue({ title, body, labels }) {
      const issue = {
        number: state.next++,
        title,
        body,
        labels: labels.map((name) => ({ name })),
        state: "open",
        state_reason: null,
        created_at: now(),
        updated_at: now(),
        comments: [],
      };
      state.issues.set(issue.number, issue);
      return issue;
    },
    async getIssue(n) {
      if (state.broken.has(n)) throw new Error(`GitHub 502 /issues/${n}`);
      return state.issues.get(n) || null;
    },
    async updateIssueBody(n, body) {
      const issue = state.issues.get(n);
      issue.body = body;
      touch(issue);
      return issue;
    },
    async listComments(n) {
      return state.issues.get(n)?.comments || [];
    },
    async addComment(n, body) {
      const issue = state.issues.get(n);
      // 经令牌发的评论在真仓库里是令牌主人 (维护者) 发的
      issue.comments.push({ id: state.nextComment++, body, created_at: now(), author_association: "OWNER" });
      touch(issue);
      return {};
    },
    async updateComment(id, body) {
      for (const issue of state.issues.values()) {
        const comment = issue.comments.find((c) => c.id === id);
        if (comment) {
          comment.body = body;
          touch(issue);
        }
      }
      return {};
    },
    async uploadAsset(name, bytes) {
      state.assets.set(name, bytes);
      return { url: `/__fake/assets/${name}` };
    },
    async blocklist() {
      return state.blocked;
    },
    async assetDigests(urls) {
      return Object.fromEntries(urls.filter((u) => state.digests.has(u)).map((u) => [u, state.digests.get(u)]));
    },
  };
}

function json(body, status = 200) {
  return new Response(JSON.stringify(body, null, 2), { status, headers: { "content-type": "application/json; charset=utf-8" } });
}

export async function fakeAdmin(url, request) {
  const path = url.pathname.replace(/\/+$/, "");
  const body = request.method === "POST" ? await request.json().catch(() => ({})) : {};
  if (path === "/__fake/issues") {
    return json([...state.issues.values()].map((i) => ({ ...i, assets: [...state.assets.keys()].filter((a) => a.startsWith(`${i.number}-`)) })));
  }
  if (path === "/__fake/reset") {
    state.next = 1;
    state.nextComment = 1;
    state.issues.clear();
    state.assets.clear();
    state.blocked.clear();
    state.digests.clear();
    state.broken.clear();
    return json({ ok: true });
  }
  if (path === "/__fake/block") {
    state.blocked.add(String(body.device || ""));
    return json({ ok: true, blocked: [...state.blocked] });
  }
  if (path === "/__fake/digest") {
    state.digests.set(String(body.url || ""), String(body.sha256 || ""));
    return json({ ok: true });
  }
  const asset = /^\/__fake\/assets\/(.+)$/.exec(path);
  if (asset) {
    const bytes = state.assets.get(decodeURIComponent(asset[1]));
    return bytes ? new Response(bytes, { headers: { "content-type": "application/gzip" } }) : json({ error: "not_found" }, 404);
  }
  const broken = /^\/__fake\/issues\/(\d+)\/break$/.exec(path);
  if (broken) {
    state.broken.add(parseInt(broken[1], 10));
    return json({ ok: true });
  }
  const m = /^\/__fake\/issues\/(\d+)\/(comment|close)$/.exec(path);
  const issue = m && state.issues.get(parseInt(m[1], 10));
  if (!issue) return json({ error: "not_found" }, 404);
  if (m[2] === "comment") {
    issue.comments.push({ id: state.nextComment++, body: String(body.body || ""), created_at: now(), author_association: body.maintainer === false ? "NONE" : "OWNER" });
  } else {
    issue.state = "closed";
    issue.state_reason = body.reason === "not_planned" ? "not_planned" : "completed";
  }
  touch(issue);
  return json({ ok: true, issue: issue.number });
}
