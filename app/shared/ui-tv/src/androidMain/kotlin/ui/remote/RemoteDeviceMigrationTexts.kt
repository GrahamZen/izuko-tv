/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.remote

/** 「换电视」(网页 [DEVICE_MIGRATION_SCRIPT] 与 [RemoteDeviceMigration]) 的译文, 格式同 [REMOTE_I18N_TABLE]. */
internal fun deviceMigrationTexts() = listOf(
    // 网页
    RemoteText("换电视", "Move from another TV", "換電視", "換電視"),
    RemoteText(
        "把另一台电视上 Izuko 的用户、收藏与播放记录、设置、数据源和登录一次搬到这台。两台电视要连在同一个网络里，并且都更新到最新版。",
        "Move Izuko's users, collections and playback history, settings, sources and sign-ins from another TV to this one in one go. Both TVs must be on the same network and updated to the latest version.",
        "把另一台電視上 Izuko 的用戶、收藏與播放記錄、設置、數據源和登錄一次搬到這台。兩台電視要連在同一個網絡裡，並且都更新到最新版。",
        "把另一台電視上 Izuko 的使用者、收藏與播放記錄、設定、資料源和登入一次搬到這台。兩台電視要連在同一個網路裡，並且都更新到最新版。",
    ),
    RemoteText(
        "1. 在旧电视的控制台里打开这一页，点「复制本机地址」。",
        "1. Open this page in the old TV's console and tap “Copy this TV's address”.",
        "1. 在舊電視的控制台裡打開這一頁，點「複製本機地址」。",
        "1. 在舊電視的控制台裡開啟這一頁，點「複製本機網址」。",
    ),
    RemoteText(
        "2. 回到新电视的控制台，把地址粘贴到下面，点「读取」。",
        "2. Back in the new TV's console, paste the address below and tap “Read”.",
        "2. 回到新電視的控制台，把地址貼上到下面，點「讀取」。",
        "2. 回到新電視的控制台，把網址貼上到下面，點「讀取」。",
    ),
    RemoteText("复制本机地址", "Copy this TV's address", "複製本機地址", "複製本機網址"),
    RemoteText("旧电视控制台的地址", "Old TV's console address", "舊電視控制台的地址", "舊電視控制台的網址"),
    RemoteText("读取", "Read", "讀取", "讀取"),
    RemoteText("已复制本机地址", "Address copied", "已複製本機地址", "已複製本機網址"),
    RemoteText("请长按下面的地址手动复制", "Long-press the address below to copy it", "請長按下面的地址手動複製", "請長按下面的網址手動複製"),
    RemoteText("正在读取旧电视…", "Reading the old TV…", "正在讀取舊電視…", "正在讀取舊電視…"),
    RemoteText("开始搬", "Start", "開始搬", "開始搬"),
    RemoteText(
        "开始搬？这台电视的设置会换成旧电视的，搬完这台电视会重启。",
        "Start moving? This TV's settings will be replaced with the old TV's, and this TV restarts when it's done.",
        "開始搬？這台電視的設置會換成舊電視的，搬完這台電視會重啟。",
        "開始搬？這台電視的設定會換成舊電視的，搬完這台電視會重新啟動。",
    ),
    RemoteText(
        "电视重启后，刷新本页就能看到搬过来的用户。",
        "Once the TV has restarted, refresh this page to see the users that moved over.",
        "電視重啟後，刷新本頁就能看到搬過來的用戶。",
        "電視重新啟動後，重新整理本頁就能看到搬過來的使用者。",
    ),
    RemoteText("电视正在重启，过一会儿刷新本页。", "The TV is restarting. Refresh this page in a moment.", "電視正在重啟，過一會兒刷新本頁。", "電視正在重新啟動，過一會兒重新整理本頁。"),
    RemoteText("应用更新、日志、性能诊断、设置备份、换电视", "App update, logs, performance diagnostics, settings backup, moving from another TV", "應用更新、日誌、性能診斷、設置備份、換電視", "應用更新、日誌、效能診斷、設定備份、換電視"),

    // 电视回的
    RemoteText("请粘贴旧电视控制台的完整地址", "Paste the old TV's full console address", "請貼上舊電視控制台的完整地址", "請貼上舊電視控制台的完整網址"),
    RemoteText(
        "这是这台电视自己的地址。请在旧电视的控制台里复制它的地址",
        "That's this TV's own address. Copy the address from the old TV's console.",
        "這是這台電視自己的地址。請在舊電視的控制台裡複製它的地址",
        "這是這台電視自己的網址。請在舊電視的控制台裡複製它的網址",
    ),
    RemoteText("旧电视：Izuko {0}", "Old TV: Izuko {0}", "舊電視：Izuko {0}", "舊電視：Izuko {0}"),
    RemoteText(
        "{0} 个数据源、{1} 个订阅，连同设置、网盘与 PikPak 账号、弹幕屏蔽词一起搬过来",
        "{0} sources and {1} subscriptions move over, along with settings, cloud drive and PikPak accounts, and danmaku filters",
        "{0} 個數據源、{1} 個訂閲，連同設置、網盤與 PikPak 帳號、彈幕屏蔽詞一起搬過來",
        "{0} 個資料源、{1} 個訂閱，連同設定、網盤與 PikPak 帳號、彈幕遮蔽詞一起搬過來",
    ),
    RemoteText(
        "这台电视还没有人用过，旧电视的 1 号用户直接放进这台的 1 号用户。",
        "Nobody has used this TV yet, so the old TV's user 1 goes straight into this TV's user 1.",
        "這台電視還沒有人用過，舊電視的 1 號用戶直接放進這台的 1 號用戶。",
        "這台電視還沒有人用過，舊電視的 1 號使用者直接放進這台的 1 號使用者。",
    ),
    RemoteText(
        "这台电视原有的用户与收藏不动，旧电视的用户作为新用户加进来。",
        "This TV's existing users and collections stay as they are; the old TV's users are added as new users.",
        "這台電視原有的用戶與收藏不動，舊電視的用戶作為新用戶加進來。",
        "這台電視原有的使用者與收藏不動，舊電視的使用者作為新使用者加進來。",
    ),
    RemoteText(
        "这台电视的设置会换成旧电视的，数据源与订阅两边合并。缓存的视频不搬。",
        "This TV's settings are replaced with the old TV's; sources and subscriptions from both are merged. Cached videos don't move.",
        "這台電視的設置會換成舊電視的，數據源與訂閲兩邊合併。緩存的視頻不搬。",
        "這台電視的設定會換成舊電視的，資料源與訂閱兩邊合併。快取的影片不搬。",
    ),
    RemoteText(
        "搬完这台电视会重启。旧电视上的数据不动；同一个 Bangumi 账号在两台电视上都用的话，其中一台过几天可能要重新登录。",
        "This TV restarts when it's done. Nothing changes on the old TV; if you keep using the same Bangumi account on both TVs, one of them may need to sign in again after a few days.",
        "搬完這台電視會重啟。舊電視上的數據不動；同一個 Bangumi 帳號在兩台電視上都用的話，其中一台過幾天可能要重新登錄。",
        "搬完這台電視會重新啟動。舊電視上的資料不動；同一個 Bangumi 帳號在兩台電視上都用的話，其中一台過幾天可能要重新登入。",
    ),
    RemoteText("Bangumi，已登录", "Bangumi, signed in", "Bangumi，已登錄", "Bangumi，已登入"),
    RemoteText("Bangumi，没登录", "Bangumi, not signed in", "Bangumi，沒登錄", "Bangumi，沒登入"),
    RemoteText("收藏 {0} 部，播放记录 {1} 条", "{0} collected, {1} playback records", "收藏 {0} 部，播放記錄 {1} 條", "收藏 {0} 部，播放記錄 {1} 條"),
    RemoteText("放进这台的 1 号用户", "into this TV's user 1", "放進這台的 1 號用戶", "放進這台的 1 號使用者"),
    RemoteText("作为新用户加进来", "added as a new user", "作為新用戶加進來", "作為新使用者加進來"),
    RemoteText("正在搬，搬完再试", "A move is in progress. Try again when it's done.", "正在搬，搬完再試", "正在搬，搬完再試"),
    RemoteText("正在导入收藏，导完再搬", "Collections are being imported. Move after that's done.", "正在導入收藏，導完再搬", "正在匯入收藏，匯完再搬"),
    RemoteText(
        "电视上没有显示 Izuko。先在电视上打开 Izuko 再试",
        "Izuko isn't showing on the TV. Open Izuko on the TV, then try again.",
        "電視上沒有顯示 Izuko。先在電視上打開 Izuko 再試",
        "電視上沒有顯示 Izuko。先在電視上開啟 Izuko 再試",
    ),
    RemoteText("正在连接旧电视…", "Connecting to the old TV…", "正在連接舊電視…", "正在連線舊電視…"),
    RemoteText("开始搬了", "Started", "開始搬了", "開始搬了"),
    RemoteText("现在停不了", "It can't be stopped now", "現在停不了", "現在停不了"),
    RemoteText("已停止，这台电视上什么都没改", "Stopped. Nothing on this TV was changed.", "已停止，這台電視上什麼都沒改", "已停止，這台電視上什麼都沒改"),
    RemoteText("正在下载设置和数据源…", "Downloading settings and sources…", "正在下載設置和數據源…", "正在下載設定和資料源…"),
    RemoteText("正在下载「{0}」的数据…", "Downloading {0}'s data…", "正在下載「{0}」的數據…", "正在下載「{0}」的資料…"),
    RemoteText("正在写入…", "Writing…", "正在寫入…", "正在寫入…"),
    RemoteText("搬完了，电视马上重启", "Done. The TV restarts now.", "搬完了，電視馬上重啟", "搬完了，電視馬上重新啟動"),
    RemoteText("搬的时候出错了：{0}", "Something went wrong while moving: {0}", "搬的時候出錯了：{0}", "搬的時候出錯了：{0}"),
    RemoteText(
        "旧电视上的 Izuko 比这台新，先在这台的控制台「维护 → 应用更新」里更新",
        "The old TV has a newer Izuko than this one. Update this TV first under “Maintenance → App update” in its console.",
        "舊電視上的 Izuko 比這台新，先在這台的控制台「維護 → 應用更新」裡更新",
        "舊電視上的 Izuko 比這台新，先在這台的控制台「維護 → 應用更新」裡更新",
    ),
    RemoteText(
        "搬完了。请在电视上退出 Izuko 再重新打开",
        "Done. Quit Izuko on the TV and open it again.",
        "搬完了。請在電視上退出 Izuko 再重新打開",
        "搬完了。請在電視上退出 Izuko 再重新開啟",
    ),
    RemoteText(
        "旧电视给的数据读不懂，两台电视都更新到最新版再试",
        "Couldn't read the old TV's data. Update both TVs to the latest version and try again.",
        "舊電視給的數據讀不懂，兩台電視都更新到最新版再試",
        "舊電視給的資料讀不懂，兩台電視都更新到最新版再試",
    ),
    RemoteText("没收全，网络断了一下，再试一次", "The download was cut short by a network hiccup. Try again.", "沒收全，網絡斷了一下，再試一次", "沒收全，網路斷了一下，再試一次"),
    RemoteText(
        "这个地址打不开，可能旧电视控制台的地址变了。在旧电视的控制台里重新复制",
        "That address doesn't work; the old TV's console address may have changed. Copy it again from the old TV's console.",
        "這個地址打不開，可能舊電視控制台的地址變了。在舊電視的控制台裡重新複製",
        "這個網址開啟不了，可能舊電視控制台的網址變了。在舊電視的控制台裡重新複製",
    ),
    RemoteText(
        "旧电视上的 Izuko 没有换电视功能，先在它的控制台「维护 → 应用更新」里更新",
        "The old TV's Izuko can't move data yet. Update it first under “Maintenance → App update” in its console.",
        "舊電視上的 Izuko 沒有換電視功能，先在它的控制台「維護 → 應用更新」裡更新",
        "舊電視上的 Izuko 沒有換電視功能，先在它的控制台「維護 → 應用更新」裡更新",
    ),
    RemoteText("旧电视出错了（HTTP {0}），稍后再试", "The old TV ran into an error (HTTP {0}). Try again later.", "舊電視出錯了（HTTP {0}），稍後再試", "舊電視出錯了（HTTP {0}），稍後再試"),
    RemoteText(
        "连不上旧电视。确认两台电视在同一个网络里，旧电视上 Izuko 开着",
        "Can't reach the old TV. Make sure both TVs are on the same network and Izuko is open on the old TV.",
        "連不上舊電視。確認兩台電視在同一個網絡裡，舊電視上 Izuko 開著",
        "連不上舊電視。確認兩台電視在同一個網路裡，舊電視上 Izuko 開著",
    ),
)
