#!/usr/bin/env bash
# 把一张故障工单 (私有仓库 GrahamZen/izuko-tickets 的 issue) 连同附件拉到本地看:
#   ticket.md        正文 (问题、描述、环境、时间段、附件清单) 与全部回复, 新的在后
#   <编号>-*.gz/.jpg 附件 (release ticket-logs 里 <编号>- 开头的; 补充里的带 r<k>- 前缀), 日志另解压成 .log
# 关单时附件会被删 (tickets-repo 的 drop-log 工作流), 那时只有正文与回复。要装好 gh 并登录能读这个私有仓库的账号。
#
# 用法: tools/ticket-worker/fetch-ticket.sh <编号> [输出目录]    默认 ${TMPDIR:-/tmp}/izuko-tickets/<编号>
set -euo pipefail

REPO=GrahamZen/izuko-tickets
n="${1:?usage: fetch-ticket.sh <issue number> [out dir]}"
out="${2:-${TMPDIR:-/tmp}/izuko-tickets/$n}"
mkdir -p "$out"

gh issue view "$n" -R "$REPO" --json number,title,state,stateReason,labels,createdAt,updatedAt,body,comments --jq '
  "# #\(.number) \(.title)\n\n状态: \(.state) \(.stateReason // "") · 标签: \([.labels[].name] | join(", ")) · 开单 \(.createdAt) · 更新 \(.updatedAt)\n\n\(.body)\n"
  + ([.comments[] | "\n---\n**\(.author.login)** \(.createdAt)\n\n\(.body)\n"] | join(""))' > "$out/ticket.md"

if ! gh release download ticket-logs -R "$REPO" -p "$n-*" -D "$out" --clobber 2>/dev/null; then
  echo "(没有附件: 还没传上去, 或关单后已删)"
fi
for gz in "$out"/*.gz; do
  [ -e "$gz" ] || continue
  gunzip -c "$gz" > "${gz%.gz}.log"
done

where="$out"
command -v cygpath >/dev/null 2>&1 && where=$(cygpath -m "$out")
echo "工单 #$n → $where"
ls -la "$out" | tail -n +4
for log in "$out"/*.log; do
  [ -e "$log" ] || continue
  echo "== $(basename "$log"): $(wc -l < "$log") 行"
  grep -a "Ani started" "$log" | sed -E 's/.*(version: [^,]+).*/  \1/' | sort | uniq -c || true
  echo "  闪退 (ANI FATAL): $(grep -a -c "ANI FATAL" "$log" || true)"
done
