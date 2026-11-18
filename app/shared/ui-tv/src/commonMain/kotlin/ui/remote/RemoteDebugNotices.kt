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
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.him188.ani.app.data.models.notice.Notice
import me.him188.ani.app.domain.notice.Notices
import me.him188.ani.app.ui.foundation.lan.LanHttpRequest
import me.him188.ani.app.ui.notice.TvNotices
import me.him188.ani.app.ui.update.ReleaseNoteItem
import me.him188.ani.app.ui.whatsnew.TvWhatsNew
import me.him188.ani.utils.platform.currentTimeMillis
import org.koin.mp.KoinPlatform

/**
 * 调试: 造通知、看通知 (见 docs/contributing/code/notices.md). 只有 debug 包能用 (经 [RemoteDebugActions]).
 *
 * - `GET api/debug/notices`: 列出来的通知 (键、种类、那句话、新不新、跟不跟状态) 与通知弹窗开没开;
 * - `POST api/debug/notices/post` `kind=<种类> [key=<键>] [arg.<名>=<值> …]`: 发一条消息 (键不给时用种类 + 此刻);
 * - `POST api/debug/notices/release` `title=<一句> [title=<另一句> …]`: 当前版本的「## 通知」换成这几句 (同发版后补了通知);
 * - `POST api/debug/notices/clear`: 撤回全部消息, 「## 通知」清空 (看过的记录留着);
 * - `POST api/debug/notices/open`: 打开通知弹窗 (同头像上的「通知」);
 * - `POST api/debug/notices/foreground`: 当作刚从后台回到前台 (有新的就等到首页自动打开).
 */
internal object RemoteDebugNotices {
    private val notices: Notices get() = KoinPlatform.getKoin().get()

    fun handle(request: LanHttpRequest, scope: CoroutineScope): JsonObject? {
        TvNotices.install()
        val post = request.method == "POST"
        return when (request.path) {
            "api/debug/notices" -> state()
            "api/debug/notices/post" -> if (post) post(request) else null
            "api/debug/notices/release" -> if (post) release(request) else null
            "api/debug/notices/clear" -> if (post) clear() else null
            "api/debug/notices/open" -> if (post) {
                scope.launch(Dispatchers.Main) { TvNotices.open() }
                result(true, "opened")
            } else null

            "api/debug/notices/foreground" -> if (post) {
                TvNotices.onReturnToForeground()
                result(true, "foreground")
            } else null

            else -> null
        }
    }

    private fun state(): JsonObject = buildJsonObject {
        put("ok", true)
        put("dialog", TvNotices.dialog.value != null)
        putJsonArray("items") {
            for (n in TvNotices.shown.value.orEmpty()) addJsonObject {
                put("key", n.key)
                put("kind", n.notice.kind)
                put("text", n.text)
                put("at", n.notice.atMillis)
                put("fresh", !n.entry.seen)
                put("pending", n.entry.pending)
            }
        }
    }

    private fun post(request: LanHttpRequest): JsonObject {
        val fields = request.formFieldList()
        val kind = fields.lastOrNull { it.first == "kind" }?.second.orEmpty()
        if (kind.isEmpty()) return result(false, "kind?")
        val now = currentTimeMillis()
        val key = fields.lastOrNull { it.first == "key" }?.second?.takeIf { it.isNotEmpty() } ?: "$kind:$now"
        val args = fields.filter { it.first.startsWith("arg.") }.associate { it.first.removePrefix("arg.") to it.second }
        runBlocking { notices.post(Notice(kind, key, now, args)) }
        return result(true, key)
    }

    private fun release(request: LanHttpRequest): JsonObject {
        val titles = request.formFieldList().filter { it.first == "title" }.map { it.second }
        TvWhatsNew.releaseNotice.value = TvWhatsNew.ReleaseNotice("debug", titles.map { ReleaseNoteItem(it, null) })
        return result(true, "${titles.size} notices")
    }

    private fun clear(): JsonObject {
        TvWhatsNew.releaseNotice.value = null
        val messages = TvNotices.shown.value.orEmpty().filter { !it.entry.pending }.map { it.key }
        runBlocking { messages.forEach { notices.retract(it) } }
        return result(true, "${messages.size} cleared")
    }

    private fun result(ok: Boolean, message: String): JsonObject = buildJsonObject {
        put("ok", ok)
        put("message", message)
    }
}
