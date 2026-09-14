/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.remote

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import me.him188.ani.app.data.models.danmaku.DanmakuRegexFilter
import me.him188.ani.app.data.network.TmdbImageService
import me.him188.ani.app.data.repository.player.DanmakuRegexFilterRepository
import me.him188.ani.app.data.repository.user.SettingsRepository
import me.him188.ani.app.domain.foundation.HttpClientProvider
import me.him188.ani.app.domain.settings.ProxyTester
import me.him188.ani.app.domain.settings.ServiceConnectionTester.TestState
import me.him188.ani.app.domain.settings.ServiceConnectionTesters
import me.him188.ani.app.ui.foundation.lan.LanHttpRequest
import me.him188.ani.app.ui.settings.tabs.network.ProxyUIConfig
import me.him188.ani.app.ui.settings.tabs.network.ProxyUIMode
import me.him188.ani.app.ui.settings.tabs.network.toDataSettings
import me.him188.ani.app.ui.settings.tabs.network.toUIConfig
import me.him188.ani.utils.ktor.ClientProxyConfigValidator
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import org.koin.mp.KoinPlatform
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

/**
 * Web 控制台的「设置」标签: 只收**要打字**的那几项 (代理地址与账号、BT 额外 tracker), 开关类设置留在电视上改 ——
 * 改完要当场看效果的东西, 放到手机上反而得来回抬头.
 *
 * 代理的存法与设置页一致 (`ProxyUIConfig.toDataSettings`), 地址用同一个校验器; 改完即生效, 不用重启 (HTTP 客户端池按
 * 当前代理取客户端, 数据源实例也随代理变化重建). 密码**只写不读**: 网页上不回显, 留空 = 保持原来的.
 */
internal object RemoteSettings {
    private val logger = logger<RemoteSettings>()

    private val settingsRepository: SettingsRepository get() = KoinPlatform.getKoin().get()
    private val danmakuFilters: DanmakuRegexFilterRepository get() = KoinPlatform.getKoin().get()

    /** 处理 `api/settings` 下的请求; 路径或方法不认识返回 null. */
    fun handle(request: LanHttpRequest): JsonObject? {
        val get = request.method == "GET" || request.method == "HEAD"
        val post = request.method == "POST"
        return runCatching {
            when {
                request.path == "api/settings" && get -> state()
                !post -> null
                request.path == "api/settings/proxy" -> saveProxy(request)
                request.path == "api/settings/proxy/test" -> testConnection()
                request.path == "api/settings/trackers" -> saveTrackers(request)
                // 「切到电视前台」开关, 状态与授权都在 TvRemoteControl
                request.path == "api/settings/front" -> TvRemoteControl.setBringToFront(request.formFields()["on"] == "1")
                // 「退出 Ani 后保留 Web 控制台」开关, 同样在 TvRemoteControl
                request.path == "api/settings/keep" -> TvRemoteControl.setKeepAlive(request.formFields()["on"] == "1")
                request.path == "api/settings/dmfilter/switch" -> setFilterSwitch(request.formFields()["on"] == "1")
                request.path == "api/settings/dmfilter/add" -> addFilter(request.formFields()["regex"].orEmpty())
                request.path == "api/settings/dmfilter/toggle" -> toggleFilter(request)
                request.path == "api/settings/dmfilter/delete" -> deleteFilter(request.formFields()["id"].orEmpty())
                else -> null
            }
        }.getOrElse {
            logger.warn(it) { "Remote settings request failed: ${request.method} ${request.path}" }
            result(false, tr("操作失败：{0}", it.message ?: it::class.simpleName))
        }
    }

    private fun state(): JsonObject = runBlocking {
        val proxy = settingsRepository.proxySettings.flow.first().toUIConfig()
        val torrent = settingsRepository.anitorrentConfig.flow.first()
        // 挂起调用都放在 buildJsonObject 外面 (它的构建块不是协程)
        val filterConfig = settingsRepository.danmakuFilterConfig.flow.first()
        val filters = danmakuFilters.flow.first()
        buildJsonObject {
            putJsonObject("proxy") {
                put("mode", proxy.mode.name)
                put("url", proxy.manualUrl)
                put("username", proxy.manualUsername.orEmpty())
                put("hasPassword", !proxy.manualPassword.isNullOrEmpty())
            }
            put("trackers", torrent.extraTrackers)
            put("front", TvRemoteControl.frontState())
            put("keep", TvRemoteControl.keepState())
            putJsonObject("dmfilter") {
                put("enabled", filterConfig.enableRegexFilter)
                putJsonArray("items") {
                    for (x in filters) addJsonObject {
                        put("id", x.id)
                        put("regex", x.regex)
                        put("on", x.enabled)
                    }
                }
            }
        }
    }

    // ---------------------------- 弹幕屏蔽词 ----------------------------
    // 与设置页那一组同一份数据; 改了即生效, 正在播的那一集会立刻按新规则重新过滤 (不用登录)

    private fun setFilterSwitch(on: Boolean): JsonObject {
        runBlocking { settingsRepository.danmakuFilterConfig.update { copy(enableRegexFilter = on) } }
        return result(true, if (on) tr("已启用弹幕屏蔽") else tr("已关闭弹幕屏蔽"))
    }

    /**
     * 加一条. 必须先校验能编译: 播放器那边编译规则时没有保护, 写错的正则会在弹幕会话里直接抛异常.
     * 普通的词本身就是合法的正则, 所以只说「支持正则」.
     */
    private fun addFilter(raw: String): JsonObject {
        val regex = raw.trim()
        if (regex.isEmpty()) return result(false, tr("请输入要屏蔽的词"))
        runCatching { Regex(regex, RegexOption.IGNORE_CASE) }.onFailure {
            return result(false, tr("正则写法有误。只想按字屏蔽的话，特殊符号 ( ) [ ] . * + ? 前面加 \\"))
        }
        val existing = runBlocking { danmakuFilters.flow.first() }
        if (existing.any { it.regex == regex }) return result(false, tr("这条已经有了"))
        runBlocking { danmakuFilters.add(DanmakuRegexFilter(id = UUID.randomUUID().toString(), name = "", regex = regex, enabled = true)) }
        return result(true, tr("已添加，立即生效"))
    }

    private fun toggleFilter(request: LanHttpRequest): JsonObject {
        val fields = request.formFields()
        val id = fields["id"].orEmpty()
        val on = fields["on"] == "1"
        val current = runBlocking { danmakuFilters.flow.first() }.find { it.id == id } ?: return result(false, tr("找不到这条屏蔽词"))
        runBlocking { danmakuFilters.update(id, current.copy(enabled = on)) }
        return result(true, "")
    }

    /** 仓库按整个对象相等来删, 所以用刚读出来的那份 (手机上的可能已经过时). */
    private fun deleteFilter(id: String): JsonObject {
        val current = runBlocking { danmakuFilters.flow.first() }.find { it.id == id } ?: return result(false, tr("找不到这条屏蔽词"))
        runBlocking { danmakuFilters.remove(current) }
        return result(true, tr("已删除"))
    }

    private fun saveProxy(request: LanHttpRequest): JsonObject {
        val fields = request.formFields()
        val mode = ProxyUIMode.entries.firstOrNull { it.name == fields["mode"] } ?: return result(false, tr("无效的代理模式"))
        val url = fields["url"].orEmpty().trim()
        val username = fields["username"].orEmpty().trim()
        val password = fields["password"].orEmpty()
        if (mode == ProxyUIMode.CUSTOM && !ClientProxyConfigValidator.isValidProxy(url)) {
            return result(false, tr("代理地址格式不对，例如 http://192.168.1.2:7890 或 socks5://192.168.1.2:1080"))
        }
        runBlocking {
            val old = settingsRepository.proxySettings.flow.first().toUIConfig()
            // 密码只写不读: 没填就沿用原来的; 用户名清空 = 不要认证
            val keptPassword = password.ifEmpty { old.manualPassword.orEmpty() }
            val config = ProxyUIConfig(
                mode = mode,
                manualUrl = if (mode == ProxyUIMode.CUSTOM) url else old.manualUrl,
                manualUsername = username.ifEmpty { null },
                manualPassword = if (username.isEmpty()) null else keptPassword,
            )
            settingsRepository.proxySettings.update { config.toDataSettings() }
        }
        logger.info { "Remote control saved proxy settings: mode=$mode" }
        return result(
            true,
            when (mode) {
                ProxyUIMode.DISABLED -> tr("已关闭代理")
                // 设置页在 Android 上同样不显示「检测到的系统代理」: 这一档在电视上取不到系统代理, 等于不用
                ProxyUIMode.SYSTEM -> tr("已改为跟随系统（电视上通常等于不使用代理）")
                ProxyUIMode.CUSTOM -> tr("已保存，立即生效")
            },
        )
    }

    /**
     * 测一遍各服务能不能连上 (同设置页「保存并测试」与动作面板那一行). 用一个一次性的 [ProxyTester]: 起它的测试循环,
     * 等全部测完 (每项自带 10~15 秒超时), 取结果后关掉. 按当前生效的代理测.
     */
    private fun testConnection(): JsonObject {
        val koin = KoinPlatform.getKoin()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val tester = ProxyTester(koin.get<HttpClientProvider>(), scope, koin.get<TmdbImageService>())
            val results = runBlocking {
                scope.launch { tester.testRunnerLoop() }
                withTimeoutOrNull(TEST_TIMEOUT) { tester.testResult.first { it.allCompleted() } }
            } ?: return result(false, tr("测试超时"))
            return buildJsonObject {
                put("ok", true)
                put("message", if (results.anyFailed()) tr("部分服务连不上") else tr("全部连接正常"))
                putJsonArray("items") {
                    for ((id, state) in results.idToStateMap) addJsonObject {
                        put("name", SERVICE_NAMES[id]?.let { tr(it) } ?: id)
                        when (state) {
                            is TestState.Success -> {
                                put("ok", true)
                                put("text", "${state.time.inWholeMilliseconds} ms")
                            }

                            else -> {
                                put("ok", false)
                                put("text", tr("连接失败"))
                            }
                        }
                    }
                }
            }
        } finally {
            scope.cancel()
        }
    }

    private fun saveTrackers(request: LanHttpRequest): JsonObject {
        // 每行一个, 去掉空行与首尾空白; 与设置页同一个字段 (BT 下载开始前与内置 tracker 一起添加)
        val text = request.formFields()["text"].orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() }
        val bad = text.firstOrNull { line -> TRACKER_SCHEMES.none { line.startsWith(it, ignoreCase = true) } }
        if (bad != null) return result(false, tr("这一行不像 tracker 地址：{0}", bad))
        runBlocking { settingsRepository.anitorrentConfig.update { copy(extraTrackers = text.joinToString("\n")) } }
        return result(true, if (text.isEmpty()) tr("已清空额外 tracker") else tr("已保存 {0} 个 tracker，下次开始 BT 下载时生效", text.size))
    }

    private fun result(ok: Boolean, message: String): JsonObject = buildJsonObject {
        put("ok", ok)
        put("message", message)
    }

    private val TEST_TIMEOUT = 25.seconds
    private val TRACKER_SCHEMES = listOf("udp://", "http://", "https://", "ws://", "wss://")
    private val SERVICE_NAMES = mapOf(
        ServiceConnectionTesters.ID_ANI to "Animeko 服务器",
        ServiceConnectionTesters.ID_BANGUMI to "Bangumi",
        ServiceConnectionTesters.ID_BANGUMI_NEXT to "Bangumi Next",
        ServiceConnectionTesters.ID_TMDB to "TMDB 接口",
        ServiceConnectionTesters.ID_TMDB_IMAGE to "TMDB 图床",
    )
}
