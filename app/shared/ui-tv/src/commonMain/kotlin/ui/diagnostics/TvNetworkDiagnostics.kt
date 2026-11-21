/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.diagnostics

import android.content.Context
import android.net.ConnectivityManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import me.him188.ani.app.data.repository.user.AccessTokenSession
import me.him188.ani.app.domain.foundation.BangumiEchDohList
import me.him188.ani.app.domain.foundation.BangumiEchTransport
import me.him188.ani.app.domain.foundation.BangumiRouteStats
import me.him188.ani.app.domain.foundation.diagnosis.NetworkDiagnosis
import me.him188.ani.app.domain.session.SessionManager
import me.him188.ani.app.ui.remote.tr
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import org.koin.mp.KoinPlatform
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.minutes

/**
 * 「网络体检」: 在 Web 控制台「设置 → 维护 → 性能诊断」里点 ([start]), 或者 Bangumi 请求接连所有线路都失败时自动做 ([install] 装上的监听).
 * 怎么测见 [NetworkDiagnosis], 结论见 [NetworkAnalysis]; 报告与设备体检、录制存在一起 (见 [TvPerfDiagnostics.saveReport]).
 */
internal object TvNetworkDiagnostics {
    private val logger = logger<TvNetworkDiagnostics>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    const val KIND = "network"
    const val AUTO_KIND = "netauto"

    /** 一次体检最多跑多久 (各请求自己有超时, 这是兜底). */
    private val RUN_TIMEOUT = 3.minutes

    sealed interface Status {
        data object Idle : Status

        /** [startedAtMillis] = System.currentTimeMillis 时钟; [step] = 正在做哪一步 (还没开始为 `null`). */
        data class Running(val startedAtMillis: Long, val auto: Boolean, val step: NetworkDiagnosis.Step?) : Status
    }

    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status.asStateFlow()

    @Volatile
    private var appContext: Context? = null
    private val installed = AtomicBoolean(false)
    private val trigger = NetworkAutoTrigger(startedAtMillis = System.currentTimeMillis())

    /** 界面装上时登记 (同 [TvPerfDiagnostics.attach]), 第一次时开始听「所有线路都失败」, 够多了就自动体检一次. */
    fun install(context: Context) {
        appContext = context.applicationContext
        if (!installed.compareAndSet(false, true)) return
        scope.launch {
            val stats = runCatching { KoinPlatform.getKoin().get<BangumiRouteStats>() }.getOrNull() ?: return@launch
            stats.allFailed.collect { event ->
                if (!trigger.due(event.at)) return@collect
                // 不在前台 (多半是电视休眠、系统断了后台的网) 或者整机没联网时不算: 那不是线路的问题
                if (_status.value != Status.Idle || !TvPerfDiagnostics.inForeground() || !hasNetwork()) return@collect
                trigger.fired(event.at)
                val reason = tr(
                    "{0} 分钟内有 {1} 个 Bangumi 请求所有线路都失败（最近一次：{2}，试过 {3}）",
                    NetworkAutoTrigger.WINDOW_MILLIS / 60_000, trigger.threshold, event.host + event.path, event.tried.joinToString(" → "),
                ) + (event.cause?.let { tr("：{0}", it) }.orEmpty())
                logger.warn { "[NetAuto] $reason" }
                start(reason)
            }
        }
    }

    /**
     * 开始一次网络体检 (大约一分钟, 在后台跑); [trigger] = 自动体检的原因, 手动点的为 `null`.
     * 返回 `null` = 已开始, 否则是不能开始的原因.
     */
    fun start(trigger: String? = null): String? {
        val context = appContext ?: return tr("电视上的 Izuko 还没打开界面")
        val running = Status.Running(System.currentTimeMillis(), auto = trigger != null, step = null)
        if (!_status.compareAndSet(Status.Idle, running)) return tr("正在网络体检")
        scope.launch {
            try {
                val koin = KoinPlatform.getKoin()
                // 加密直连取配置用的 DNS 清单第一次用到加密直连才建; 先建出来, 体检开始时它也跟着拉一遍
                koin.getOrNull<BangumiEchDohList>()
                val sessions = koin.get<SessionManager>()
                val diagnosis = NetworkDiagnosis(
                    context = context,
                    endpoints = koin.get(),
                    proxyProvider = koin.get(),
                    echTransport = koin.getOrNull<BangumiEchTransport>(),
                    echConfig = koin.get(),
                    echAddresses = koin.get(),
                    stats = koin.get(),
                    bangumiToken = { (sessions.sessionFlow.value as? AccessTokenSession)?.tokens?.bangumiAccessToken },
                )
                val report = withTimeoutOrNull(RUN_TIMEOUT) {
                    diagnosis.run { step -> _status.update { (it as? Status.Running)?.copy(step = step) ?: it } }
                }
                if (report == null) {
                    logger.warn { "Network diagnosis did not finish in $RUN_TIMEOUT" }
                    return@launch
                }
                val saved = TvPerfDiagnostics.saveReport(
                    kind = if (trigger != null) AUTO_KIND else KIND,
                    seconds = (report.durationMs / 1000).toInt(),
                    body = NetworkAnalysis.body(report, trigger),
                )
                logger.info { "Network diagnosis report ${saved?.get("id")}: ${saved?.let(TvPerfDiagnostics::headlineOf)}" }
            } catch (e: Throwable) {
                logger.warn(e) { "Network diagnosis failed" }
            } finally {
                _status.value = Status.Idle
            }
        }
        return null
    }

    private fun hasNetwork(): Boolean = runCatching {
        appContext?.getSystemService(ConnectivityManager::class.java)?.activeNetwork != null
    }.getOrDefault(true)
}

/**
 * 什么时候自动做一次网络体检: [WINDOW_MILLIS] 内有 [threshold] 个请求所有线路都失败, 离上次自动体检至少 [MIN_INTERVAL_MILLIS],
 * 进程起来头 [STARTUP_GRACE_MILLIS] 不算 (启动时请求扎堆、网络还没就绪, 失败不说明线路有问题).
 * 纯逻辑, 单测见 `NetworkAutoTriggerTest`.
 */
internal class NetworkAutoTrigger(
    private val startedAtMillis: Long,
    val threshold: Int = THRESHOLD,
) {
    private val recent = ArrayDeque<Long>()
    private var lastFiredAt: Long? = null

    /** 记一个「所有线路都失败」(在 [at] 时); 现在该自动体检了返回 `true`. 真去做了再调 [fired]. */
    @Synchronized
    fun due(at: Long): Boolean {
        if (at - startedAtMillis < STARTUP_GRACE_MILLIS) return false
        recent.addLast(at)
        while (recent.isNotEmpty() && at - recent.first() > WINDOW_MILLIS) recent.removeFirst()
        if (recent.size < threshold) return false
        return lastFiredAt.let { it == null || at - it >= MIN_INTERVAL_MILLIS }
    }

    /** 这次自动体检做了: 清空计数, 开始算间隔. */
    @Synchronized
    fun fired(at: Long) {
        lastFiredAt = at
        recent.clear()
    }

    companion object {
        const val WINDOW_MILLIS = 2 * 60_000L
        const val THRESHOLD = 5
        const val MIN_INTERVAL_MILLIS = 30 * 60_000L
        const val STARTUP_GRACE_MILLIS = 60_000L
    }
}
