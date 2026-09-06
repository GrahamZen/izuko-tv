# Bangumi 直连鉴权探针 (2026-09-05)

去掉 Ani 服务器依赖的方案里，最大的未验证风险是 **bangumi OAuth 换来的 access_token 能不能当
`next.bgm.tv/p1` 的 `HTTPBearer`**：p1 的 spec 把 `HTTPBearer` 描述成「登录后创建的个人令牌 (PAT)」，
如果它不收 OAuth token，所有带鉴权的调用（收藏读写、分集进度、自己的评分）就必须退回 `api.bgm.tv/v0`。

用设备上真实的 OAuth token（Ani 的 bgm 应用颁发，从 `files/datastore/authSession` 的
`bangumiAccessToken` 取）逐条打过，结论是 **p1 全收，不需要退回 v0**。

## 结果

| 请求 | 结果 |
|---|---|
| `GET next.bgm.tv/p1/collections/subjects?subjectType=2&limit=1` + Bearer | 200，返回收藏列表 |
| `GET api.bgm.tv/v0/me` + Bearer | 200 |
| `GET next.bgm.tv/p1/me` + Bearer | **200** |
| `GET next.bgm.tv/p1/subjects/302286/episodes` + Bearer | 200，每集带 `collection.status` |
| `PATCH next.bgm.tv/p1/collections/episodes/1127992`，body `{"type":2}` | 200 `{}`，回读一致 |
| `GET /p1/subjects/{id}`、`/relations`、`/characters`、`/staffs/persons`、`/recs`、`/comments`、`/trending/subjects`、`/calendar` | 全部 200 |

匿名读（无 token）此前也已验证：条目、分集、角色、关系、calendar 都是 200，**游客模式可以完整保留**。

## 这推翻了方案里的两条

1. 方案 §0.2 说 `/p1/me` 只认 Cookie 不认 Bearer，所以自己的资料必须走 v0 —— **实测 p1 收 Bearer**，
   不必混用 v0。
2. 方案 §0.3 把「p1 是否接受 OAuth token」列为最大风险、并按不通的情况给 S3/S4 备了一套 v0 写法
   （+3 人日）—— **不需要了**。

`api.bgm.tv/v0` 仍然要留一条路：p1 的搜索只返回 `SlimSubject`（没有 `date`/`tags`/`summary`），
搜索走 v0 的 `POST /v0/search/subjects`。

## 复现方法

```bash
adb shell "run-as me.him188.ani.tv.debug2 cat files/datastore/authSession"   # 取 bangumiAccessToken
curl -s -o /dev/null -w "%{http_code}\n" -H "Authorization: Bearer $TOKEN" \
  "https://next.bgm.tv/p1/collections/subjects?subjectType=2&limit=1"
```

写测试要用**幂等写**（把已是「看过」的那一集再写一次同样的状态），别拿没看过的集做实验。
