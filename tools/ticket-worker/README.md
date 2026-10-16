# 故障工单 Worker

Web 控制台「设置 → 反馈」里提交的故障工单走这里: Worker 在私有工单仓库开 issue, 附件 (日志、照片) 传成 release 附件; 电视每天来查一次处理结果,
维护者的回复与回复里的测试包链接显示在控制台里, 用户能接着补充 (也能带附件), issue 关掉工单就结束.

## 流程

1. 用户在控制台写清问题, 点「＋」附上一段现场录制 (必须有; 日志默认只截录制期间, 可以往前多截几分钟), 还可以附「维护 → 性能诊断」里已有的报告和照片.
2. 电视 `POST /tickets` 开单 (问题、描述、环境、时间段、性能报告概要, 以及要传的附件 `files`), 拿到 issue 编号与凭证 `token`, 记在本机.
   正文「附件」一节每个文件先占一行「上传中…」.
3. 电视逐个 `PUT /tickets/<n>/files/<name>` (凭证在 `X-Izuko-Ticket` 头): `log.gz` (性能报告原文在前, 录制那段的日志在后) / `photo-<1..4>.jpg`.
   Worker 存成工单仓库 release `ticket-logs` 上的附件 `<n>-<name>` (仓库私有, 只有登录的成员打得开), 把占位换成链接.
4. 电视每天 `POST /tickets/status` (`{tickets: [{issue, token, since}]}`) 查一次; 没变化只回 `unchanged`.
5. 用户补充走 `POST /tickets/<n>/reply` (`{body, files}`), 评论里标成「用户补充」; 回的 `prefix` (如 `r2`) 加在这条附件名前, 照样经 `PUT files` 传.

凭证一律放请求头 / 请求体, 不放地址: 电视的应用日志会记请求地址, 而日志会被打包上传、分享.

接口细节、错误码见 [src/tickets.js](src/tickets.js) 开头的注释.

## 限制

| 项 | 值 |
|---|---|
| 只收故障 | 问题 ≥ 4 字, 描述 ≥ 5 字, 必须带时间段 (电视端另要求带现场录制, 录制最长 5 分钟) |
| 同一台电视同时开着的单 | 2 张 |
| 附件 | 每条最多一份日志 (gzip, ≤ 20 MB) 与 4 张照片 (JPEG / PNG / WebP, 每张 ≤ 5 MB), 按开头几个字节认类型; 只收声明过的 |
| 视频 | 不收: 免费档 Worker 每次请求只有 128 MB 内存 (这里整个读进来再转给 GitHub), 电视再从手机收、往外传几十上百 MB 也太慢 |
| 一次查状态 | ≤ 10 张单 |
| 限频 (每 IP 每分钟) | 开单与补充 6 次, 查状态 20 次; 传附件只能填声明过的位子, 不另限 |

**凭证**: `token` = HMAC-SHA256(`TICKET_SECRET`, `ticket:<n>`) 的前 32 位, Worker 不存. 换 `TICKET_SECRET` 会让所有电视手里的凭证失效.

**拉黑**: 工单仓库根目录 `blocklist.txt`, 一行一个设备号 (`#` 后是注释). 给 issue 打「拉黑」标签, 仓库里的工作流会写进去、删附件并关单;
Worker 缓存 5 分钟.

## 额度 (免费档)

每张单: 开单 1 次 + 附件 1~5 次 + 每天查 1 次 (开着期间), 补充另算. 按一年 500 张、平均开 30 天算, 每天约 50~100 次请求, 离 Workers 每天 10 万次很远.
每次请求的 GitHub 子请求: 开单 3 次 (拉黑表命中缓存时 2 次), 传附件 3~5 次, 查状态每张单 1~2 次; 都在 50 次上限内.
不用 KV / R2: 附件在 GitHub release 里 (单个 ≤ 2 GB, 总量不限), 关单后工作流删掉.

## 部署

现在的部署: `https://izuko-tickets.grahamzen.workers.dev` (Cloudflare 账号同 bangumi-tmdb-map 的报告中转), 工单仓库 `GrahamZen/izuko-tickets` (私有);
两个密钥的备份在维护者本机 `local.properties` 的 `izuko.tickets.*`. 改了代码在本目录 `npx wrangler@4 deploy --env=""` 重新部署 (密钥不用重设).

从头搭:

1. 建私有仓库 (改名的话同时改 `wrangler.toml` 的 `TICKETS_REPO` 与 app 的 `FeedbackTicketService.OFFICIAL_ENDPOINTS`), 建标签「故障反馈」「拉黑」,
   把 [tickets-repo/](tickets-repo/) 下的工作流拷进去 (`drop-log.yml` 关单后删日志).
2. 建 fine-grained 令牌, 只给这个仓库: Issues 读写, Contents 读写.
3. ```sh
   npx wrangler@4 login
   npx wrangler@4 deploy --env=""
   npx wrangler@4 secret put TICKETS_GITHUB_TOKEN --env=""
   npx wrangler@4 secret put TICKET_SECRET --env=""      # 随机长串, 例如 openssl rand -hex 32; 换了它电视手里的凭证全失效
   ```

## 本地调试

```sh
npm test                                   # 单测 (node --test, 不连网)
npx wrangler@4 dev --env dev --ip 0.0.0.0  # 假 GitHub, 状态在内存里
adb reverse tcp:8787 tcp:8787              # 电视上 127.0.0.1:8787 连到这台电脑
```

电视 debug 包「开发者开关 → 工单服务」选「本机 (adb reverse)」. 假仓库的维护者动作:

```sh
curl localhost:8787/__fake/issues                                                  # 全部单
curl -X POST localhost:8787/__fake/issues/1/comment -d '{"body":"装这个试试 https://example.com/a.apk"}'
curl -X POST localhost:8787/__fake/issues/1/close -d '{"reason":"completed"}'     # 或 not_planned
curl -X POST localhost:8787/__fake/block -d '{"device":"<16 位设备号>"}'
curl localhost:8787/__fake/assets/1-log.gz | gunzip | less                         # 看传上来的日志
```
