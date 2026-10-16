// 工单仓库 (私有) 的 GitHub 接口. 令牌 TICKETS_GITHUB_TOKEN: 只给工单仓库的 fine-grained 令牌,
// 权限 Issues 读写 + Contents 读写 (读 blocklist.txt; 复现日志传成 release 附件要 Contents 写).
// 仓库是私有的, 附件链接只有登录的仓库成员打得开.

const API = "https://api.github.com";
const UPLOADS = "https://uploads.github.com";
/** 复现日志都挂在 tag 为这个的 release 上 (附件不进 git 历史, 关单后删掉就干净了). */
const LOG_RELEASE = "ticket-logs";
const BLOCKLIST_TTL = 300;
const RELEASE_ASSET_RE = /^https:\/\/github\.com\/([\w.-]+)\/([\w.-]+)\/releases\/download\/([^/]+)\/([^/?#]+)$/;

export function realGitHub(env) {
  const repo = env.TICKETS_REPO;
  const headers = (extra = {}) => ({
    authorization: `Bearer ${env.TICKETS_GITHUB_TOKEN}`,
    accept: "application/vnd.github+json",
    "x-github-api-version": "2022-11-28",
    "user-agent": "izuko-tickets",
    ...extra,
  });

  async function call(path, init = {}, base = API) {
    const response = await fetch(`${base}${path}`, {
      ...init,
      headers: headers(init.body && typeof init.body === "string" ? { "content-type": "application/json" } : init.headers || {}),
    });
    // 删掉的 issue 回 410
    if (response.status === 404 || response.status === 410) return null;
    if (!response.ok) throw new Error(`GitHub ${response.status} ${path}`);
    return response.status === 204 ? {} : response.json();
  }

  async function logRelease() {
    const found = await call(`/repos/${repo}/releases/tags/${LOG_RELEASE}`);
    if (found) return found;
    return call(`/repos/${repo}/releases`, {
      method: "POST",
      body: JSON.stringify({ tag_name: LOG_RELEASE, name: LOG_RELEASE, prerelease: true, body: "故障工单的复现日志, 每张单一个 `<issue 号>-log.gz`." }),
    });
  }

  return {
    async listOpen(label) {
      const issues = [];
      for (let page = 1; page <= 3; page++) {
        const batch = (await call(`/repos/${repo}/issues?state=open&labels=${encodeURIComponent(label)}&per_page=100&page=${page}`)) || [];
        issues.push(...batch.filter((i) => !i.pull_request));
        if (batch.length < 100) break;
      }
      return issues;
    },
    createIssue({ title, body, labels }) {
      return call(`/repos/${repo}/issues`, { method: "POST", body: JSON.stringify({ title, body, labels }) });
    },
    getIssue(n) {
      return call(`/repos/${repo}/issues/${n}`);
    },
    updateIssueBody(n, body) {
      return call(`/repos/${repo}/issues/${n}`, { method: "PATCH", body: JSON.stringify({ body }) });
    },
    async listComments(n) {
      return (await call(`/repos/${repo}/issues/${n}/comments?per_page=100`)) || [];
    },
    addComment(n, body) {
      return call(`/repos/${repo}/issues/${n}/comments`, { method: "POST", body: JSON.stringify({ body }) });
    },
    updateComment(id, body) {
      return call(`/repos/${repo}/issues/comments/${id}`, { method: "PATCH", body: JSON.stringify({ body }) });
    },
    async uploadAsset(name, bytes) {
      const release = await logRelease();
      // 上次传上去了、只是正文没改成 (回应丢了): 同名附件已在, 用它
      const existing = (release.assets || []).find((a) => a.name === name);
      if (existing) return { url: existing.browser_download_url };
      const asset = await call(`/repos/${repo}/releases/${release.id}/assets?name=${encodeURIComponent(name)}`, {
        method: "POST",
        headers: { "content-type": "application/gzip" },
        body: bytes,
      }, UPLOADS);
      return { url: asset.browser_download_url };
    },
    /** GitHub release 附件链接 → SHA-256 (接口的 digest 字段); 别的链接、查不到的不给. 同一个 release 只查一次. */
    async assetDigests(urls) {
      const digests = {};
      const releases = new Map();
      for (const url of urls) {
        const m = RELEASE_ASSET_RE.exec(url);
        if (!m) continue;
        const [, owner, name, tag, file] = m;
        const key = `${owner}/${name}/${tag}`;
        if (!releases.has(key)) {
          releases.set(key, await call(`/repos/${owner}/${name}/releases/tags/${encodeURIComponent(decodeURIComponent(tag))}`).catch(() => null));
        }
        const asset = (releases.get(key)?.assets || []).find((a) => a.name === decodeURIComponent(file));
        const digest = asset?.digest || "";
        if (digest.startsWith("sha256:")) digests[url] = digest.slice("sha256:".length).toLowerCase();
      }
      return digests;
    },
    async blocklist() {
      // 几分钟缓存一次: 每次开单都读一遍文件不值得
      const cache = typeof caches !== "undefined" ? caches.default : null;
      const key = new Request(`https://izuko-tickets.invalid/blocklist/${repo}`);
      let text = null;
      if (cache) {
        const hit = await cache.match(key);
        if (hit) text = await hit.text();
      }
      if (text === null) {
        const response = await fetch(`${API}/repos/${repo}/contents/blocklist.txt`, { headers: headers({ accept: "application/vnd.github.raw" }) });
        text = response.ok ? await response.text() : "";
        if (cache) await cache.put(key, new Response(text, { headers: { "cache-control": `max-age=${BLOCKLIST_TTL}` } }));
      }
      return new Set(text.split("\n").map((l) => l.replace(/#.*/, "").trim()).filter(Boolean));
    },
  };
}
