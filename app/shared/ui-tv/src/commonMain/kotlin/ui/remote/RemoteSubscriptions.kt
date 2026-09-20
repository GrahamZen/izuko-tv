/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.remote

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.him188.ani.app.data.models.ApiFailure
import me.him188.ani.app.data.repository.media.MediaSourceSubscriptionRepository
import me.him188.ani.app.domain.media.fetch.MediaSourceManager
import me.him188.ani.app.domain.mediasource.subscription.MediaSourceSubscription
import me.him188.ani.app.domain.mediasource.subscription.MediaSourceSubscriptionUpdater
import me.him188.ani.app.ui.foundation.lan.LanHttpRequest
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import org.koin.mp.KoinPlatform
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.time.Duration

/**
 * Web 控制台设置标签「数据源」页里的**订阅**: 新装的用户第一步就是填订阅地址 (在线源全部来自订阅), 那是一长串从别处
 * 复制来的网址, 遥控器上几乎敲不对.
 *
 * 语义与设置页的订阅那一组一致 (`MediaSourceSubscriptionGroupState`): 添加 = 仓库里加一条再更新; 删除 = 连同它带来的
 * 数据源一起删; 「立即更新」= 强制更新全部. 比设置页多做的只有添加时的校验 (去空白、只收 http(s)、同一地址不重复加).
 *
 * **更新串行**: [MediaSourceSubscriptionUpdater] 自己没有锁, 两次更新交叠时各自对同一份旧列表做差集, 会把同一批源
 * 加两遍. 网页触发的更新都经 [updateMutex] 排队, 在后台跑 (拉订阅要几秒, 不占住 HTTP 请求), 网页按 `updating`
 * 轮询刷新. 应用自己的定时更新不经这把锁, 它间隔以小时计, 撞上的概率很小.
 */
internal object RemoteSubscriptions {
    private val logger = logger<RemoteSubscriptions>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("RemoteSubscriptions"))
    private val updateMutex = Mutex()

    @Volatile
    private var updating = false

    private val repository: MediaSourceSubscriptionRepository get() = KoinPlatform.getKoin().get()
    private val updater: MediaSourceSubscriptionUpdater get() = KoinPlatform.getKoin().get()
    private val manager: MediaSourceManager get() = KoinPlatform.getKoin().get()

    /** 处理 `api/sources/subs` 下的请求; 路径或方法不认识返回 null. */
    fun handle(request: LanHttpRequest): JsonObject? {
        val get = request.method == "GET" || request.method == "HEAD"
        val post = request.method == "POST"
        return runCatching {
            when {
                request.path == "api/sources/subs" && get -> list()
                !post -> null
                request.path == "api/sources/subs/add" -> add(request.formFields()["url"].orEmpty())
                request.path == "api/sources/subs/delete" -> request.formFields().let { f ->
                    // 长按多选删除时 ids 带逗号分隔的多个
                    delete((f["ids"] ?: f["id"]).orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() })
                }
                request.path == "api/sources/subs/refresh" -> refresh()
                else -> null
            }
        }.getOrElse {
            logger.warn(it) { "Remote subscription request failed: ${request.method} ${request.path}" }
            result(false, tr("操作失败：{0}", it.message ?: it::class.simpleName))
        }
    }

    private fun list(): JsonObject {
        val subs = runBlocking { repository.flow.first() }
        return buildJsonObject {
            put("updating", updating)
            putJsonArray("items") {
                for (sub in subs) addJsonObject {
                    put("id", sub.subscriptionId)
                    put("url", sub.url)
                    put("period", tr("每 {0}自动更新", periodText(sub.updatePeriod)))
                    val last = sub.lastUpdated
                    when {
                        last == null -> put("status", tr("还没有更新过"))
                        last.error != null || last.mediaSourceCount == null -> {
                            put("failed", true)
                            put("status", tr("{0} 更新失败：{1}", timeText(last.timeMillis), errorText(last.error)))
                        }

                        else -> put("status", tr("{0} 更新成功，包含 {1} 个数据源", timeText(last.timeMillis), last.mediaSourceCount))
                    }
                }
            }
        }
    }

    private fun add(raw: String): JsonObject {
        val url = raw.trim()
        if (url.isEmpty()) return result(false, tr("请填写订阅地址"))
        val uri = runCatching { URI(url) }.getOrNull()
        if (uri == null || uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrBlank()) {
            return result(false, tr("订阅地址要以 http:// 或 https:// 开头"))
        }
        val existing = runBlocking { repository.flow.first() }
        if (existing.any { it.url.trim() == url }) return result(false, tr("这个订阅已经添加过了"))
        runBlocking { repository.add(MediaSourceSubscription(subscriptionId = UUID.randomUUID().toString(), url = url)) }
        logger.info { "Remote control added a media source subscription" }
        // 不强制: 只拉还没更新过的 (即刚加的这条) 与本来就到期的, 不必把其余订阅也重拉一遍
        startUpdate(force = false)
        return result(true, tr("已添加，正在拉取订阅里的数据源"))
    }

    private fun delete(ids: List<String>): JsonObject {
        val subs = runBlocking { repository.flow.first() }.filter { it.subscriptionId in ids }
        if (subs.isEmpty()) return result(false, if (ids.size > 1) tr("找不到这些订阅") else tr("找不到这个订阅"))
        runBlocking {
            for (sub in subs) {
                // 同设置页: 先删它带来的数据源, 再删订阅本身 (仓库的 remove 不管数据源)
                val instanceIds = manager.getListBySubscriptionId(sub.subscriptionId).map { it.instanceId }
                manager.removeInstances(instanceIds)
                repository.remove(sub)
            }
        }
        logger.info { "Remote control deleted ${subs.size} media source subscription(s)" }
        return result(true, if (ids.size > 1) tr("已删除 {0} 个订阅及其数据源", subs.size) else tr("已删除订阅及其数据源"))
    }

    private fun refresh(): JsonObject {
        if (updating) return result(false, tr("正在更新，稍等一下"))
        startUpdate(force = true)
        return result(true, tr("正在更新全部订阅"))
    }

    private fun startUpdate(force: Boolean) {
        updating = true
        scope.launch {
            updateMutex.withLock {
                try {
                    updater.updateAllOutdated(force = force)
                } catch (e: Exception) {
                    // 单个订阅的失败记在它自己的 lastUpdated 里; 走到这里的是意料之外的错误
                    logger.warn(e) { "Remote subscription update failed" }
                } finally {
                    updating = false
                }
            }
        }
    }

    private fun periodText(d: Duration): String = when {
        d.inWholeHours >= 1 && d.inWholeMinutes % 60 == 0L -> tr("{0} 小时", d.inWholeHours)
        else -> tr("{0} 分钟", d.inWholeMinutes)
    }

    private fun timeText(millis: Long): String = SimpleDateFormat("MM-dd HH:mm", Locale.ROOT).format(Date(millis))

    private fun errorText(error: MediaSourceSubscription.UpdateError?): String = when (error?.failure) {
        null -> error?.message ?: tr("未知错误")
        ApiFailure.Unauthorized -> tr("未授权")
        ApiFailure.NetworkError -> tr("网络错误")
        ApiFailure.ServiceUnavailable -> tr("服务不可用")
    }

    private fun result(ok: Boolean, message: String): JsonObject = buildJsonObject {
        put("ok", ok)
        put("message", message)
    }
}
