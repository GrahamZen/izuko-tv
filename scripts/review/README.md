# fork 代码审查台账

全量审一次之后, 追上游、整理历史都只审变了的部分。

台账按文件记下审过的那一对内容: 比较基准里的版本和 fork 的版本, 各一个 blob 哈希。fork 对一个文件的改动就是
「基准版 → fork 版」, 两个哈希都没变, 改动就逐字节没变, 上次的结论接着有效。提交 SHA 只用来取出要审的那一版
(`--rev`), 不进结论。所以重排、合并、改写历史 (终树不变) 不产生待审项; 追上游之后只有上游也动过的文件和 fork
自己改过的文件进队列, `show` 也只打出要看的那段差。

    uv run --no-project python scripts/review/review.py status          # 概况
    ... queue --group                                                   # 按模块汇总, 切批用
    ... queue --rev <R> [--kind K] <路径…>                              # 待审文件
    ... show --rev <R> <路径…>                                          # 要看的差
    ... approve --rev <R> -m <批名> <路径…>                             # 审过, 没问题
    ... issue --rev <R> -m <问题> <文件…>                               # 审过, 有没解决的问题
    ... upstream                                                        # 上游这一轮碰到了 fork 的哪些代码
    ... upstream --approve --rev <R> -m <说明>

`--rev` 写 status / queue 打出来的「目标」。审的过程中 HEAD 可能已经变了, 台账只记 `--rev` 那一版的内容,
之后又改的部分照样回到队列。工作区里没提交的改动不审, 提交以后才进队列。

## 比较基准

基准 = 目标所在的上游提交, 加上栈底几条按 `rules.txt` 的 `base-commit` 算上游的提交 (撤销 #3569 的那条 revert:
它恢复的是上游自己的代码)。`UPSTREAM` 指着 `rebase/base-<日期>` 这种「上游 + revert」的分支时, 会往下找到真正的上游提交。

## 待审的种类

| 种类 | 情况 | `show` 给的 | 审什么 |
|---|---|---|---|
| added / modified / deleted | 这个文件的 fork 改动还没审过 | 基准 → fork 的整段差 (deleted 只给行数和残留引用的查法) | 新文件看完整实现; 改的看改动和它连着的调用; 删的看该不该删、有没有残留 |
| remerged | 上游改了这个文件, fork 补丁也变了 (解过冲突 / 合并改写) | fork 补丁的变化 (只比改动行) + 上游这次的改动 | 合并有没有合错、上游的改动有没有被吃掉 |
| dropped-upstream | 上游改了这个文件, fork 版一个字没动 (整取 fork 版、保持删除) | 上游这次的改动 | 丢掉的上游改动 fork 要不要搬过来 (常是上游修 bug) |
| vanished | fork 的改动没了, 文件和上游一样了 | 没了的 fork 改动 + 上游这次的改动 | 是有意去掉、上游换了做法, 还是 rebase 时丢了 |
| replayed | 上游改了这个文件, fork 补丁原样重放 | 上游这次的改动 + 离它 20 行以内的 fork 块 | 上游的改动和挨着的 fork 代码合不合得来; queue 里标着离多远 |
| fork-changed | 上游版没变, fork 版变了 | 上次审过的 fork 版 → 现在 | 两版 fork 之间的差 |

自动免审的: 审过没变的; 上游已吸收 (上游版 = 审过的 fork 版); 改名 (git 的改名检测认出来, 结论跟到新路径,
内容也变了的按 fork-changed 只审差); `rules.txt` 里 skip / keep-deleted 的。

只有上游改、fork 没碰的文件不逐个审。`upstream` 从台账记的上游基点起, 逐条看上游提交里这类文件,
按包和名字查两个方向: fork 代码 import 了它的包、用到了这次改动落在的那个函数 / 声明; 上游新代码调到了 fork 改过的函数。
范围由上游这一轮的提交数决定 (一轮几十条), 不会摊到 fork 全部代码上。只是线索: 它找得到「fork 在用的上游函数行为变了」,
找不到不经名字的耦合 (同一份设置、同一张表), 每条提交的标题仍要过一眼。测试文件不参与 (rebase 后照常要跑)。

## 首次全量

1. `status`、`queue --group` 看量, 按模块切批, 每批 2000~4000 行; ui-tv、app-data 这种大模块按子目录再切。
2. 每批交给一个审查会话或子代理, 给它目标 `R` 和路径, 提示词照下面。几批可以同时审: 写台账是 CAS, 互相不覆盖。
3. deleted 按目录整批看: 删的意图 + 残留引用 (`git grep`), 整目录 approve。
4. 测试跟着它测的代码一起看, 看断言有没有意义, 不逐行抠。不想审测试就在 `rules.txt` 加一行 `skip **/src/*Test`。

给审查者的提示词:

```
审 fork 代码, 目标提交 <R>, 范围 <路径>。做法见 scripts/review/README.md。
1. `uv run --no-project python scripts/review/review.py queue --rev <R> <路径>` 列文件, 逐个 `show --rev <R> <文件>` 看要审的那段差。
2. 只找真问题: 正确性、竞态与生命周期、资源泄漏、和上游接缝上的约定 (调用方 / 被调用方) 对不对得上。不提风格。
   调用处用 grep / srclight 查, 只看和这段差相关的。
3. 没问题的 `approve --rev <R> -m "<批名>" <文件…>`; 有问题的 `issue --rev <R> -m "<触发条件 → 后果>" <文件>`。不改代码。
4. 最后报: 审了几个文件、记了哪些问题 (文件:行、触发条件、后果)。
```

记了问题的文件, 修好提交以后那几行会以 fork-changed 回到队列, 审过 approve 就关掉。不修也没关系的, 点名这个文件
`approve --rev <R> <文件>` 关掉; 整目录 approve 不会顺带关掉内容没变的文件上的问题。

## 每次追上游 / 整理历史之后

- 只整理历史 (终树不变): `status` 应该是 0, 什么都不用做。
- 追上游: `fork-rebase.sh verify` 最后会打 `status`。按 remerged → dropped-upstream → vanished → replayed → 其余的顺序审,
  再跑 `upstream` 对上游这一轮的提交, 对完 `upstream --approve`。
- 测试结果不进台账: 追上游之后照常编两个变体、跑测试 (fork-rebase.sh 的卡口)。审查结论可以继承, 测试不能。

## 发正式版前

`status` 里待审是 0, 剩下的要么审掉, 要么记成 issue 写明为什么不处理; 未解决的问题过一遍。

## rules.txt

- `skip`: 生成代码 (审生成它的配置)、第三方接口描述、二进制、文档、翻译。
- `keep-deleted`: fork 整块删掉的上游功能 (上游 TV 客户端、Ani 服务端的功能)。删了但功能搬到了别处的路径别加:
  上游改它时要看要不要搬过去 (dropped-upstream 会报)。
- `base-commit`: 栈底恢复上游代码的提交。

改了规则只影响以后算出来的队列, 台账不用动。

## 台账存在哪

`refs/review/ledger`, 不在任何分支上: 不进 main 的历史、不跟 rebase 打架, 主目录和各 worktree 共用一份。
树里 `ledger.tsv` (路径 → 两个 blob)、`issues.tsv`、`upstream` (上游交互审到哪), 外加 `blobs/` 存着这些 blob,
rebase 之后旧提交被回收, 审过的旧版内容也一直取得到。每次写台账是一条提交, `git log -p refs/review/ledger -- ledger.tsv`
看哪一批审了哪些文件。

- 备份 / 换机器: `git push origin refs/review/ledger` (推送等用户发话), 另一边 `git fetch origin refs/review/ledger:refs/review/ledger`。
- 台账存着审过的文件内容。清敏感信息改写历史时, 改过的文件先重新 approve, 再 `compact` 去掉台账的历史; 推过远端的也要强推。
- 试验用另一个 ref: `--ledger refs/review/trial`, 用完 `git update-ref -d refs/review/trial`。

单测: `uv run --no-project python scripts/review/test_review.py`
