/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.remote

/** 网络体检 (性能诊断页的那一块, 反馈里附的网络体检报告) 在控制台上的文案译文, 见 RemoteI18nTable. */
internal fun networkDiagnosisTexts() = listOf(
    RemoteText("网络体检", "Network check", "網絡體檢", "網路體檢"),
    RemoteText("自动网络体检", "Automatic network check", "自動網絡體檢", "自動網路體檢"),
    RemoteText("网络体检报告", "Network check reports", "網絡體檢報告", "網路體檢報告"),
    RemoteText("「维护 → 性能诊断」里做过的网络体检，含自动做的", "Network checks run under Maintenance → Performance diagnostics, including automatic ones", "「維護 → 性能診斷」裡做過的網絡體檢，含自動做的", "「維護 → 效能診斷」裡做過的網路體檢，含自動做的"),
    RemoteText("自动 · 网络异常", "Auto · Network trouble", "自動 · 網絡異常", "自動 · 網路異常"),
    RemoteText("网络体检把 Bangumi 每条线路（官方、加密直连、镜像）上常用的请求和反馈、清单、TMDB 等服务各测一遍，记下域名解析、连接、握手各花多久、失败在哪一步，大约一分钟。Bangumi 请求接连连不上时会自动做一次，标着「自动」。", "The network check runs the common requests on every Bangumi route (official, encrypted direct, mirror) and tests feedback, lists, TMDB and other services, recording how long DNS lookups, connecting and handshakes take and which step failed. It takes about a minute. It also runs on its own when Bangumi requests keep failing, marked “Auto”.", "網絡體檢把 Bangumi 每條線路（官方、加密直連、鏡像）上常用的請求和反饋、清單、TMDB 等服務各測一遍，記下域名解析、連接、握手各花多久、失敗在哪一步，大約一分鐘。Bangumi 請求接連連不上時會自動做一次，標著「自動」。", "網路體檢把 Bangumi 每條線路（官方、加密直連、映象）上常用的請求和反饋、清單、TMDB 等服務各測一遍，記下域名解析、連線、握手各花多久、失敗在哪一步，大約一分鐘。Bangumi 請求接連連不上時會自動做一次，標著「自動」。"),
    RemoteText("Bangumi 请求接连失败，正在自动网络体检（{0} 秒）：{1}", "Bangumi requests keep failing. Running an automatic network check ({0} s): {1}", "Bangumi 請求接連失敗，正在自動網絡體檢（{0} 秒）：{1}", "Bangumi 請求接連失敗，正在自動網路體檢（{0} 秒）：{1}"),
    RemoteText("正在网络体检（{0} 秒）：{1}", "Running a network check ({0} s): {1}", "正在網絡體檢（{0} 秒）：{1}", "正在網路體檢（{0} 秒）：{1}"),
    RemoteText("自动体检的原因：{0}", "Why it ran automatically: {0}", "自動體檢的原因：{0}", "自動體檢的原因：{0}"),
    RemoteText("解析域名", "DNS lookup", "解析域名", "解析域名"),
    RemoteText("连接", "connect", "連接", "連線"),
    RemoteText("加密握手", "TLS handshake", "加密握手", "加密握手"),
    RemoteText("发请求", "send request", "發請求", "發請求"),
    RemoteText("等回应", "wait for response", "等回應", "等回應"),
    RemoteText("准备", "setup", "準備", "準備"),
    RemoteText("没发（前面的都连不上）", "Not sent (nothing before it connected)", "沒發（前面的都連不上）", "沒發（前面的都連不上）"),
    RemoteText("解析 {0}", "DNS {0}", "解析 {0}", "解析 {0}"),
    RemoteText("连接 {0}", "connect {0}", "連接 {0}", "連線 {0}"),
    RemoteText("握手 {0}", "handshake {0}", "握手 {0}", "握手 {0}"),
    RemoteText("首字节 {0}", "first byte {0}", "首字節 {0}", "首位元組 {0}"),
    RemoteText("共 {0} 毫秒", "{0} ms total", "共 {0} 毫秒", "共 {0} 毫秒"),
    RemoteText("失败在{0}", "Failed at {0}", "失敗在{0}", "失敗在{0}"),
    RemoteText("换过加密配置重试", "retried with a new encryption config", "換過加密配置重試", "換過加密配置重試"),
    RemoteText("有线", "Ethernet", "有線", "有線"),
    RemoteText("移动网络", "Mobile data", "移動網絡", "行動網路"),
    RemoteText("系统判定上不了外网", "System reports no internet access", "系統判定上不了外網", "系統判定上不了外網"),
    RemoteText("没联网", "Offline", "沒聯網", "沒聯網"),
    RemoteText("DNS 服务器", "DNS servers", "DNS 服務器", "DNS 伺服器"),
    RemoteText("私人 DNS：{0}", "Private DNS: {0}", "私人 DNS：{0}", "私人 DNS：{0}"),
    RemoteText("系统 {0}", "System {0}", "系統 {0}", "系統 {0}"),
    RemoteText("应用 {0}", "App {0}", "應用 {0}", "應用 {0}"),
    RemoteText("Bangumi 线路", "Bangumi routes", "Bangumi 線路", "Bangumi 線路"),
    RemoteText("现在走{0}", "now using {0}", "現在走{0}", "現在走{0}"),
    RemoteText("用不了（设备不支持或设了代理）", "Unavailable (not supported on this device, or a proxy is set)", "用不了（設備不支持或設了代理）", "用不了（裝置不支援或設了代理）"),
    RemoteText("用的是包里带的配置", "Using the built-in config", "用的是包裡帶的配置", "用的是包裡帶的配置"),
    RemoteText("配置 {0} 秒前取过", "Config fetched {0} s ago", "配置 {0} 秒前取過", "配置 {0} 秒前取過"),
    RemoteText("没登录", "Not signed in", "沒登錄", "沒登入"),
    RemoteText("时钟", "Clock", "時鐘", "時鐘"),
    RemoteText("比服务器快 {0} 秒", "{0} s ahead of the server", "比服務器快 {0} 秒", "比伺服器快 {0} 秒"),
    RemoteText("用时", "Duration", "用時", "用時"),
    RemoteText("（设置里没开）", " (off in settings)", "（設置裡沒開）", "（設定裡沒開）"),
    RemoteText("Cloudflare", "Cloudflare", "Cloudflare", "Cloudflare"),
    RemoteText("域名解析", "DNS lookups", "域名解析", "域名解析"),
    RemoteText("（疑似被污染）", "(possibly poisoned)", "（疑似被污染）", "（疑似被汙染）"),
    RemoteText("系统 DNS：{0}", "System DNS: {0}", "系統 DNS：{0}", "系統 DNS：{0}"),
    RemoteText("加密 DNS：{0}", "Encrypted DNS: {0}", "加密 DNS：{0}", "加密 DNS：{0}"),
    RemoteText("其他服务", "Other services", "其他服務", "其他服務"),
    RemoteText("{0} 个请求还没结束，最久的等了 {1} 秒", "{0} requests still pending, the oldest for {1} s", "{0} 個請求還沒結束，最久的等了 {1} 秒", "{0} 個請求還沒結束，最久的等了 {1} 秒"),
    RemoteText("最近 10 分钟成功 {0} 次、失败 {1} 次", "Last 10 min: {0} succeeded, {1} failed", "最近 10 分鐘成功 {0} 次、失敗 {1} 次", "最近 10 分鐘成功 {0} 次、失敗 {1} 次"),
    RemoteText("{0} 秒前", "{0} s ago", "{0} 秒前", "{0} 秒前"),
    RemoteText("应用里的 Bangumi 请求", "Bangumi requests in the app", "應用裡的 Bangumi 請求", "應用裡的 Bangumi 請求"),
)
