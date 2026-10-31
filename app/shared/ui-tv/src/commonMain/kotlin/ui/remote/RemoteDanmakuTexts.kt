/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.remote

/** 弹幕服务器与导入弹幕的网页文案, 见 [RemoteDanmaku] 与 [DANMAKU_EXTRAS_SCRIPT]. */
internal fun danmakuServerTexts() = listOf(
    RemoteText("弹幕服务器", "Danmaku servers", "彈幕服務器", "彈幕伺服器"),
    RemoteText("兼容弹弹play 接口的服务器，每台在播放器的弹幕源里单独一项。播放时会把番名和集数发给这些服务器，只添加你信任的。", "Servers compatible with the dandanplay API. Each one appears as its own danmaku source in the player. While you watch, the title and episode number are sent to these servers, so only add ones you trust.", "兼容彈彈play 接口的服務器，每台在播放器的彈幕源裡單獨一項。播放時會把番名和集數發給這些服務器，只添加你信任的。", "相容彈彈play 介面的伺服器，每台在播放器的彈幕源裡單獨一項。播放時會把番名和集數發給這些伺服器，只新增你信任的。"),
    RemoteText("手动添加", "Added manually", "手動添加", "手動新增"),
    RemoteText("来自订阅「{0}」", "From subscription “{0}”", "來自訂閱「{0}」", "來自訂閱「{0}」"),
    RemoteText("测试连接", "Test connection", "測試連接", "測試連線"),
    RemoteText("名字（显示在播放器里，可以不填）", "Name (shown in the player, optional)", "名字（顯示在播放器裡，可以不填）", "名字（顯示在播放器裡，可以不填）"),
    RemoteText("不填就用域名。播放器里大约放得下 6 个字，太长或太像时不好认。", "Leave it empty to use the domain. The player fits about 12 Latin letters, so long or similar names are hard to tell apart.", "不填就用域名。播放器裡大約放得下 6 個字，太長或太像時不好認。", "不填就用網域。播放器裡大約放得下 6 個字，太長或太像時不好認。"),
    RemoteText("服务器地址", "Server address", "服務器地址", "伺服器位址"),
    RemoteText("填到 /api/v2 之前。带访问令牌的话，令牌写在地址里。", "Everything before /api/v2. If the server uses an access token, keep it in the address.", "填到 /api/v2 之前。帶訪問令牌的話，令牌寫在地址裡。", "填到 /api/v2 之前。帶存取權杖的話，權杖寫在位址裡。"),
    RemoteText("订阅文件的 danmakuServers 字段里写的服务器也会出现在这里。", "Servers listed in the danmakuServers field of a subscription file also appear here.", "訂閱文件的 danmakuServers 字段裡寫的服務器也會出現在這裡。", "訂閱檔案的 danmakuServers 欄位裡寫的伺服器也會出現在這裡。"),
    RemoteText("删除这台弹幕服务器？", "Delete this danmaku server?", "刪除這台彈幕服務器？", "刪除這台彈幕伺服器？"),
    RemoteText("地址要以 http:// 或 https:// 开头；弹弹play 官方已经内置，不用添加", "The address must start with http:// or https://. The official dandanplay service is built in and doesn’t need adding.", "地址要以 http:// 或 https:// 開頭；彈彈play 官方已經內置，不用添加", "位址要以 http:// 或 https:// 開頭；彈彈play 官方已經內建，不用新增"),
    RemoteText("这台服务器已经添加过了", "This server has already been added", "這台服務器已經添加過了", "這台伺服器已經新增過了"),
    RemoteText("已添加，连接正常", "Added. Connection OK.", "已添加，連接正常", "已新增，連線正常"),
    RemoteText("已添加，但连接测试没有通过：{0}", "Added, but the connection test failed: {0}", "已添加，但連接測試沒有通過：{0}", "已新增，但連線測試沒有通過：{0}"),
    RemoteText("找不到这台服务器", "Server not found", "找不到這台服務器", "找不到這台伺服器"),
    RemoteText("连接正常", "Connection OK", "連接正常", "連線正常"),
    RemoteText("连接测试没有通过：{0}", "Connection test failed: {0}", "連接測試沒有通過：{0}", "連線測試沒有通過：{0}"),
    RemoteText("服务器太久没有回应", "The server took too long to respond", "服務器太久沒有回應", "伺服器太久沒有回應"),
    RemoteText("连不上服务器", "Can’t reach the server", "連不上服務器", "連不上伺服器"),
    RemoteText("给这一集加上自己找的弹幕，只保存在这台电视上。", "Add danmaku you found yourself to this episode. They are stored only on this TV.", "給這一集加上自己找的彈幕，只保存在這台電視上。", "給這一集加上自己找的彈幕，只儲存在這台電視上。"),
    RemoteText("导入弹幕文件（XML、JSON）", "Import danmaku files (XML, JSON)", "導入彈幕文件（XML、JSON）", "匯入彈幕檔案（XML、JSON）"),
    RemoteText("粘贴这一集的网页链接", "Paste the web link to this episode", "粘貼這一集的網頁鏈接", "貼上這一集的網頁連結"),
    RemoteText("取弹幕", "Get danmaku", "取彈幕", "取彈幕"),
    RemoteText("导入", "Import", "導入", "匯入"),
    RemoteText("在「设置 → 播放与弹幕」添加弹幕服务器后，还能用网页链接取弹幕。", "Add a danmaku server under Settings → Playback and danmaku to also get danmaku from a web link.", "在「設置 → 播放與彈幕」添加彈幕服務器後，還能用網頁鏈接取彈幕。", "在「設定 → 播放與彈幕」新增彈幕伺服器後，還能用網頁連結取彈幕。"),
    RemoteText("正在让服务器取弹幕…", "Asking the server for danmaku…", "正在讓服務器取彈幕…", "正在讓伺服器取彈幕…"),
    RemoteText("导入完成：{0} 个成功，{1} 个失败", "Import finished: {0} succeeded, {1} failed", "導入完成：{0} 個成功，{1} 個失敗", "匯入完成：{0} 個成功，{1} 個失敗"),
    RemoteText("正在导入第 {0} / {1} 个文件", "Importing file {0} of {1}", "正在導入第 {0} / {1} 個文件", "正在匯入第 {0} / {1} 個檔案"),
    RemoteText("文件太大（最多 16 MB）", "File too large (16 MB at most)", "文件太大（最多 16 MB）", "檔案太大（最多 16 MB）"),
    RemoteText("弹幕文件", "Danmaku file", "彈幕文件", "彈幕檔案"),
    RemoteText("这不是弹幕文件（支持 XML 和弹弹play 的 JSON）", "This isn’t a danmaku file (XML and dandanplay JSON are supported)", "這不是彈幕文件（支持 XML 和彈彈play 的 JSON）", "這不是彈幕檔案（支援 XML 和彈彈play 的 JSON）"),
    RemoteText("文件里没有能显示的弹幕", "The file has no danmaku that can be shown", "文件裡沒有能顯示的彈幕", "檔案裡沒有能顯示的彈幕"),
    RemoteText("已导入 {0} 条弹幕", "Imported {0} danmaku", "已導入 {0} 條彈幕", "已匯入 {0} 條彈幕"),
    RemoteText("已导入 {0} 条弹幕（来自「{1}」）", "Imported {0} danmaku (from “{1}”)", "已導入 {0} 條彈幕（來自「{1}」）", "已匯入 {0} 條彈幕（來自「{1}」）"),
    RemoteText("这不是网页链接", "That isn’t a web link", "這不是網頁鏈接", "這不是網頁連結"),
    RemoteText("没有能按链接取弹幕的服务器，先在「设置 → 播放与弹幕」里添加", "No server can fetch danmaku by link. Add one under Settings → Playback and danmaku first.", "沒有能按鏈接取彈幕的服務器，先在「設置 → 播放與彈幕」裡添加", "沒有能按連結取彈幕的伺服器，先在「設定 → 播放與彈幕」裡新增"),
    RemoteText("服务器没有取到这个链接的弹幕。有的服务器只支持番剧页面的链接，手机分享的短链接也不一定支持，可以换成完整链接试试", "The server found no danmaku for this link. Some servers only support anime episode pages, and short links shared from phones may not work, so try the full link.", "服務器沒有取到這個鏈接的彈幕。有的服務器只支持番劇頁面的鏈接，手機分享的短鏈接也不一定支持，可以換成完整鏈接試試", "伺服器沒有取到這個連結的彈幕。有的伺服器只支援番劇頁面的連結，手機分享的短連結也不一定支援，可以換成完整連結試試"),
    RemoteText("服务器太久没有回应，请重试", "The server took too long to respond. Please try again.", "服務器太久沒有回應，請重試", "伺服器太久沒有回應，請重試"),
    RemoteText("服务器没能取到弹幕：{0}", "The server couldn’t fetch danmaku: {0}", "服務器沒能取到彈幕：{0}", "伺服器沒能取到彈幕：{0}"),
    RemoteText("找不到这批弹幕", "These danmaku no longer exist", "找不到這批彈幕", "找不到這批彈幕"),
    RemoteText("弹幕对不上？手动匹配", "Danmaku out of sync? Match manually", "彈幕對不上？手動匹配", "彈幕對不上？手動比對"),
    RemoteText("弹幕来源", "Danmaku source", "彈幕來源", "彈幕來源"),
    RemoteText("这个弹幕来源不可用", "This danmaku source isn’t available", "這個彈幕來源不可用", "這個彈幕來源不可用"),
    RemoteText("{0} 更新成功，包含 {1} 个数据源、{2} 台弹幕服务器", "{0} updated: {1} sources, {2} danmaku servers", "{0} 更新成功，包含 {1} 個數據源、{2} 台彈幕服務器", "{0} 更新成功，包含 {1} 個資料源、{2} 台彈幕伺服器"),
)
