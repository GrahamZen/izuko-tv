/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.remote

/** 数据源页 WebDAV 卡片与添加表单的文案 (见 [RemoteCloudDrive], 译文表见 [REMOTE_I18N_TABLE]). */
internal fun webDavTexts() = listOf(
    RemoteText("已连接", "Connected", "已連接", "已連線"),
    RemoteText("连不上服务器或账号密码不对", "Can't reach the server, or the username or password is wrong", "連不上服務器或帳號密碼不對", "連不上伺服器或帳號密碼不對"),
    RemoteText("确认服务器开着、地址与账号密码没变", "Check that the server is on and that the address, username and password haven't changed", "確認服務器開著、地址與帳號密碼沒變", "確認伺服器開著、地址與帳號密碼沒變"),
    RemoteText("媒体文件夹", "Media folders", "媒體文件夾", "媒體資料夾"),
    RemoteText("移除", "Remove", "移除", "移除"),
    RemoteText("还没有添加：播放时在整个服务器里按番名找。添加放动画的文件夹，找得更快也更准。", "None added yet: playback searches the whole server by title. Add the folders that hold your anime for faster, more accurate matches.", "還沒有添加：播放時在整個服務器裡按番名找。添加放動畫的文件夾，找得更快也更準。", "還沒有新增：播放時在整個伺服器裡按番名找。新增放動畫的資料夾，找得更快也更準。"),
    RemoteText("往下找几层", "Levels to look into", "往下找幾層", "往下找幾層"),
    RemoteText("1 层：媒体文件夹 / 番名；2 层：媒体文件夹 / 季度 / 番名", "1 level: media folder / title. 2 levels: media folder / season / title.", "1 層：媒體文件夾 / 番名；2 層：媒體文件夾 / 季度 / 番名", "1 層：媒體資料夾 / 番名；2 層：媒體資料夾 / 季度 / 番名"),
    RemoteText("添加媒体文件夹", "Add media folder", "添加媒體文件夾", "新增媒體資料夾"),
    RemoteText("修改名字与地址", "Edit name and address", "修改名字與地址", "修改名字與地址"),
    RemoteText("WebDAV 地址", "WebDAV address", "WebDAV 地址", "WebDAV 地址"),
    RemoteText("添加后会打开这台服务器的文件夹，选上放动画的文件夹（媒体文件夹），找番更快也更准。", "After adding, the server's folders open so you can choose the ones that hold your anime (media folders) for faster, more accurate matches.", "添加後會打開這台服務器的文件夾，選上放動畫的文件夾（媒體文件夾），找番更快也更準。", "新增後會開啟這台伺服器的資料夾，選上放動畫的資料夾（媒體資料夾），找番更快也更準。"),
    RemoteText("点右上角的「+」添加本地文件夹（U 盘、移动硬盘、内部存储）或 WebDAV（NAS）。", "Tap “+” at the top right to add a local folder (USB drive, external disk, internal storage) or a WebDAV server (NAS).", "點右上角的「+」添加本地文件夾（U 盤、移動硬盤、內部存儲）或 WebDAV（NAS）。", "點右上角的「+」新增本機資料夾（隨身碟、外接硬碟、內部儲存空間）或 WebDAV（NAS）。"),
    RemoteText("你网盘里的和别人分享的都能看", "Plays what's in your drive and what others share", "你網盤裡的和別人分享的都能看", "你網盤裡的和別人分享的都能看"),
    RemoteText("登录后，播放时会按番名在你的{0}里找视频（选源列表里的「{1}」）；别人分享的{0}资源也能播：分享合集、网盘搜索这类数据源找到的，和你添加的分享链接，播放时转存到你的网盘。", "Once signed in, the player looks for videos in your {0} by the show name (under “{1}” in the source list). Shared {0} files play too: those found by sources such as share collections and drive search, and share links you add. They are saved to your drive when played.", "登錄後，播放時會按番名在你的{0}裡找影片（選源列表裡的「{1}」）；別人分享的{0}資源也能播：分享合集、網盤搜索這類數據源找到的，和你添加的分享鏈接，播放時轉存到你的網盤。", "登入後，播放時會按番名在你的{0}裡找影片（選源列表裡的「{1}」）；別人分享的{0}資源也能播：分享合集、網盤搜尋這類資料源找到的，和你新增的分享連結，播放時轉存到你的網盤。"),
    RemoteText("在 NAS 的系统设置里打开 WebDAV 服务，把地址填在这里。添加后，播放时会在服务器上按番名找视频，直接播放原文件。", "Turn on WebDAV in your NAS settings and enter its address here. Once added, playback looks for videos on the server by title and plays the original files.", "在 NAS 的系統設置裡打開 WebDAV 服務，把地址填在這裡。添加後，播放時會在服務器上按番名找視頻，直接播放原文件。", "在 NAS 的系統設定裡開啟 WebDAV 服務，把地址填在這裡。新增後，播放時會在伺服器上按番名找影片，直接播放原檔案。"),
    RemoteText("例如：家里的 NAS", "e.g. Home NAS", "例如：家裡的 NAS", "例如：家裡的 NAS"),
    RemoteText("上一级", "Up one level", "上一級", "上一級"),
    RemoteText("这里没有子文件夹", "No subfolders here", "這裡沒有子文件夾", "這裡沒有子資料夾"),
    RemoteText("已是媒体文件夹", "Already a media folder", "已是媒體文件夾", "已是媒體資料夾"),
    RemoteText("加为媒体文件夹", "Add as media folder", "加為媒體文件夾", "加為媒體資料夾"),
    RemoteText("删除{0}？账号密码与选的媒体文件夹一起删掉，服务器上的文件不受影响。", "Remove {0}? Its username, password and media folders are removed too. Files on the server are not affected.", "刪除{0}？帳號密碼與選的媒體文件夾一起刪掉，服務器上的文件不受影響。", "刪除{0}？帳號密碼與選的媒體資料夾一起刪掉，伺服器上的檔案不受影響。"),
    RemoteText("先填用户名", "Enter the username first", "先填用户名", "先填使用者名稱"),
    RemoteText("先填 WebDAV 地址", "Enter the WebDAV address first", "先填 WebDAV 地址", "先填 WebDAV 地址"),
    RemoteText("NAS", "NAS", "NAS", "NAS"),
    RemoteText("地址要以 http:// 或 https:// 开头", "The address must start with http:// or https://", "地址要以 http:// 或 https:// 開頭", "地址要以 http:// 或 https:// 開頭"),
    RemoteText("已添加{0}", "Added {0}", "已添加{0}", "已新增{0}"),
    RemoteText("这个网盘不能选媒体文件夹", "Media folders aren't available for this drive", "這個網盤不能選媒體文件夾", "這個網盤不能選媒體資料夾"),
    RemoteText("没有找到「{0}」的数据源", "Couldn't find the source for “{0}”", "沒有找到「{0}」的數據源", "沒有找到「{0}」的資料源"),
    RemoteText("已加为媒体文件夹", "Added as a media folder", "已加為媒體文件夾", "已加為媒體資料夾"),
    RemoteText("已移出媒体文件夹", "Removed from media folders", "已移出媒體文件夾", "已移出媒體資料夾"),
    RemoteText("订阅来的网盘不能在这里修改", "Drives from subscriptions can't be edited here", "訂閲來的網盤不能在這裡修改", "訂閱來的網盤不能在這裡修改"),
    RemoteText("订阅来的网盘不能在这里删除", "Drives from subscriptions can't be removed here", "訂閲來的網盤不能在這裡刪除", "訂閱來的網盤不能在這裡刪除"),
    RemoteText("已删除{0}", "Removed {0}", "已刪除{0}", "已刪除{0}"),
)
