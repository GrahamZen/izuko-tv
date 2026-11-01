/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import me.him188.ani.app.navigation.AniNavigator
import me.him188.ani.app.navigation.SettingsTab
import me.him188.ani.app.ui.foundation.lan.LanHttpRequest
import me.him188.ani.app.ui.settings.tv.TvSettingsNav
import me.him188.ani.app.ui.settings.tv.TvSettingsState
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import java.lang.ref.WeakReference
import kotlin.time.Duration.Companion.seconds

/**
 * 控制台调试操作里的电视设置页 (`api/debug/settings/...`, 由 [RemoteDebugActions] 转来): 测试脚本打开设置页、读页面、按某一行, 不必在电视上按键.
 * 电视设置页 (TvSettingsPage) 开着时登记自己 ([attach]), 这里读的、按的就是那一页的状态, 与遥控器操作走同一条路.
 *
 * - `POST api/debug/settings/open` `tab=STORAGE` ([SettingsTab] 的名字): 设置页开着就切到这一类, 没开就打开并落在这一类.
 * - `GET api/debug/settings/page`: 当前分类、中栏各行 (id / 种类 / 标题 / 行尾值 / 开关 / 打勾)、各行的说明栏 (标题 / 正文)、最近一次提示 ([onToast])、有没有待确认的弹窗.
 * - `POST api/debug/settings/click` `row=<行 id>` 或 `title=<中栏标题>` [`confirm=1`]: 同在那一行按确定; `confirm=1` 时冒出的确认框直接确认.
 * - `POST api/debug/settings/back`: 中栏退一层 (同按返回).
 */
internal object RemoteDebugSettingsPage {
    private val logger = logger<RemoteDebugSettingsPage>()

    @Volatile
    private var page: WeakReference<TvSettingsState>? = null

    @Volatile
    private var lastToast: Pair<Long, String>? = null

    /** 设置页组合起来时登记, 离开时 [detach]. */
    fun attach(state: TvSettingsState) {
        page = WeakReference(state)
    }

    fun detach(state: TvSettingsState) {
        if (page?.get() === state) page = null
    }

    /** 设置页弹的提示 (清理完、测试结果等), 读页面时一并给出. */
    fun onToast(message: String) {
        lastToast = System.currentTimeMillis() to message
    }

    fun handle(request: LanHttpRequest, navigator: AniNavigator?): JsonObject? {
        val get = request.method == "GET" || request.method == "HEAD"
        val post = request.method == "POST"
        val fields = if (post) request.formFields() else emptyMap()
        return when {
            request.path == "api/debug/settings/open" && post -> open(fields["tab"].orEmpty(), navigator)
            request.path == "api/debug/settings/page" && get -> page()
            request.path == "api/debug/settings/click" && post -> click(fields["row"], fields["title"], fields["confirm"] == "1")
            request.path == "api/debug/settings/back" && post -> onMain { current()?.backInRight() }.let { result(it != null, "back=$it") }
            else -> null
        }
    }

    private fun current(): TvSettingsState? = page?.get()

    private fun open(tab: String, navigator: AniNavigator?): JsonObject {
        val target = SettingsTab.entries.firstOrNull { it.name == tab } ?: return result(false, "unknown tab: $tab")
        val opened = current()
        if (opened != null) {
            opened.nav.value = TvSettingsNav(categoryId = target.name)
            return result(true, "switched to ${target.name}")
        }
        val nav = navigator ?: return result(false, "navigator not ready")
        TvRemoteControl.notifyRemoteNavigation()
        onMain { nav.navigateSettings(target) }
        return result(true, "opened ${target.name}")
    }

    private fun page(): JsonObject {
        val state = current() ?: return result(false, "settings page is not open")
        // 文案模板还没读齐时整页为 null, 等一下
        val content = runBlocking { withTimeoutOrNull(5.seconds) { state.content.filterNotNull().first() } }
            ?: return result(false, "settings page content not ready")
        return buildJsonObject {
            put("ok", true)
            put("category", state.nav.value.categoryId)
            putJsonArray("drill") { state.nav.value.drill.forEach { add(it) } }
            content.right.title?.let { put("screenTitle", it) }
            putJsonArray("rows") {
                for (row in content.right.rows) {
                    addJsonObject {
                        put("id", row.id)
                        put("kind", row.kind.name)
                        put("title", row.title)
                        put("value", row.value)
                        row.toggle?.let { put("toggle", it) }
                        if (row.checked) put("checked", true)
                        content.info[row.id]?.let { info ->
                            putJsonObject("info") {
                                put("title", info.title)
                                put("body", info.body)
                                info.qr?.let { put("qrStatus", it.status) }
                            }
                        }
                    }
                }
            }
            lastToast?.let { (at, text) ->
                putJsonObject("lastToast") {
                    put("text", text)
                    put("agoMs", System.currentTimeMillis() - at)
                }
            }
            put("pendingConfirm", state.pendingConfirm.value != null)
        }
    }

    private fun click(rowId: String?, title: String?, confirm: Boolean): JsonObject {
        val state = current() ?: return result(false, "settings page is not open")
        val id = rowId?.takeIf { it.isNotBlank() }
            ?: state.content.value?.right?.rows?.firstOrNull { it.title == title }?.id
            ?: return result(false, "no row titled $title")
        logger.info { "Remote debug: settings row clicked: $id" }
        onMain { state.onRowClicked(id) }
        val pending = state.pendingConfirm.value != null
        if (pending && confirm) onMain { state.confirmPending() }
        return buildJsonObject {
            put("ok", true)
            put("row", id)
            put("confirmShown", pending)
            put("confirmed", pending && confirm)
        }
    }

    private fun <T> onMain(block: () -> T): T = runBlocking { withContext(Dispatchers.Main) { block() } }

    private fun result(ok: Boolean, message: String): JsonObject = buildJsonObject {
        put("ok", ok)
        put("message", message)
    }
}
