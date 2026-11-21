/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.notice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.him188.ani.app.data.repository.user.SettingsRepository
import me.him188.ani.app.domain.feedback.FeedbackNotices
import me.him188.ani.app.domain.feedback.FeedbackTickets
import me.him188.ani.app.platform.BackgroundRefresh
import me.him188.ani.app.platform.SystemNotification
import me.him188.ani.app.platform.SystemNotificationAuthorization
import me.him188.ani.app.platform.SystemNotifier
import me.him188.ani.utils.io.SystemPath
import me.him188.ani.utils.io.exists
import me.him188.ani.utils.io.readText
import me.him188.ani.utils.io.writeText
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.platform.currentTimeMillis
import org.koin.mp.KoinPlatform
import kotlin.concurrent.Volatile
import kotlin.time.Duration.Companion.milliseconds

/**
 * 应用内的通知转成系统通知 (见 docs/contributing/code/notices.md「系统通知」): 应用不在前台时新来的、[TvSystemNoticePolicy] 里那几种的通知,
 * 同时发一条系统通知; 设置「系统通知」开着时才发 (系统不允许时系统那边不显示). 点开就打开通知弹窗、停在这一条 ([openNotice]),
 * 在应用里看过了的从系统通知中心撤掉.
 *
 * 「新来的」= 应用在前台时没见过的: 在前台 (或设置关着) 时列着的都算已经处理过, 记下不发; 进了后台之后新出现的 (键或时间变了) 才发.
 * 发过的记在 [start] 给的文件里, 进程被收掉、在后台重新起来也不重发.
 *
 * 只有有系统通知的平台 (iOS) 在启动时 [start]; 后台刷新 ([BackgroundRefresh]) 里登记了取通知与发通知两件活.
 * 装好后头一次到前台时向系统要权限 ([askOnFirstLaunch]).
 */
object TvSystemNotices {
    private const val MAX_RECORDS = 300

    /** 记录里「这一次叫醒发了几条」往回看多久 (一次叫醒约 30 秒) */
    private const val RUN_WINDOW_MILLIS = 30_000L

    /** 取东西之后等这么久再发: 取到的经各自的来源流到通知列表要一点时间 */
    private val SETTLE = 800.milliseconds

    /** 头一次到前台后等这么久再要权限 (见 [askOnFirstLaunch]) */
    private val FIRST_ASK_DELAY = 1500.milliseconds

    private val logger = logger<TvSystemNotices>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Mutex()

    @Volatile
    private var notifier: SystemNotifier? = null

    @Volatile
    private var file: SystemPath? = null

    /** 记过的系统通知 (id 见 [systemNoticeId]), 先后照记下的顺序. */
    private val records = MutableStateFlow<List<SystemNoticeRecord>>(emptyList())

    /** 文件里本来有记录: 没有时 (头一回) 在后台起来看到的都当作处理过, 不一下子把旧的都发出去 */
    @Volatile
    private var primed = false

    private val _sent = MutableStateFlow<List<SentLog>>(emptyList())

    /** 这个进程里发过的 (调试接口看). */
    val sent: StateFlow<List<SentLog>> = _sent.asStateFlow()

    class SentLog(val atMillis: Long, val id: String, val title: String)

    val isStarted: Boolean get() = notifier != null

    /** 设置「系统通知」 */
    val enabled: Flow<Boolean> = flow {
        val settings = runCatching { KoinPlatform.getKoin().get<SettingsRepository>() }.getOrNull()
        if (settings == null) emit(false) else emitAll(settings.themeSettings.flow.map { it.systemNotifications })
    }.distinctUntilChanged()

    /**
     * 开始: 记录存在 [recordFile]. 一个进程一次, 启动时 (界面组合之前) 调. 顺带接上通知中心 ([TvNotices.install]), 后台刷新时没有界面也算得出通知.
     */
    fun start(notifier: SystemNotifier, recordFile: SystemPath) {
        if (this.notifier != null) return
        this.notifier = notifier
        this.file = recordFile
        primed = recordFile.exists()
        records.value = runCatching { recordFile.readText() }.getOrNull()?.let(::decodeSystemNoticeRecords).orEmpty()
        TvNotices.install()

        BackgroundRefresh.register(BackgroundRefresh.Job("feedback", BackgroundRefresh.Stage.Fetch) { fetchFeedback() })
        BackgroundRefresh.register(
            BackgroundRefresh.Job("notices", BackgroundRefresh.Stage.Notify) {
                delay(SETTLE)
                val shown = TvNotices.shown.filterNotNull().first()
                val posted = sync(shown, active = notifier.appActive.value, enabled = enabled.first())
                // 取到的一流到列表, 一直开着的那一路可能已经先发了: 一并报这一次叫醒里发了几条
                val recent = _sent.value.count { it.atMillis >= currentTimeMillis() - RUN_WINDOW_MILLIS }
                "posted $posted, sent in this run $recent, active ${notifier.appActive.value}"
            },
        )

        scope.launch {
            combine(TvNotices.shown.filterNotNull(), enabled, notifier.appActive) { shown, on, active -> Triple(shown, on, active) }
                .collect { (shown, on, active) -> sync(shown, active, on) }
        }
        scope.launch {
            notifier.opened.collect { payload ->
                logger.info { "System notification opened: $payload" }
                openNotice(payload)
            }
        }
        scope.launch { askOnFirstLaunch(notifier) }
    }

    /**
     * 点开了 [key] 那条的系统通知: 打开通知弹窗、停在这一条 (在别的签里看的开到那个签), 同手机控制台的「在电视上打开」.
     * 点开冷启动时列表还没算出来, 等它出来; 这一条已经不在列表里时开在最上面.
     */
    private suspend fun openNotice(key: String) {
        val list = TvNotices.shown.filterNotNull().first()
        val item = list.firstOrNull { it.key == key }
        val index = item?.let { found -> list.filter { it.kind.tab == found.kind.tab }.indexOf(found) } ?: 0
        withContext(Dispatchers.Main) {
            TvNotices.open(focusKey = item?.key, focusIndex = index.coerceAtLeast(0), tab = item?.kind?.tab ?: TvNoticeTab.NOTICES)
        }
    }

    /**
     * 装好后头一次到前台 (系统还没问过权限) 就向系统要权限: 允许了同时打开设置「系统通知」, 不允许就留着关.
     * 系统每次安装只弹一次; 之后在设置里打开时, 被拒绝过的提示去系统设置.
     */
    private suspend fun askOnFirstLaunch(notifier: SystemNotifier) {
        notifier.appActive.first { it }
        if (notifier.authorization() != SystemNotificationAuthorization.NotDetermined) return
        // 等第一屏起来再弹, 不盖在启动画面上
        delay(FIRST_ASK_DELAY)
        if (notifier.authorization() != SystemNotificationAuthorization.NotDetermined) return
        val granted = notifier.requestAuthorization()
        logger.info { "System notification permission asked on first launch: granted=$granted" }
        if (!granted) return
        runCatching { KoinPlatform.getKoin().get<SettingsRepository>() }.getOrNull()
            ?.themeSettings?.update { copy(systemNotifications = true) }
    }

    /** 反馈的回复与处理结果 (同通知中心的刷新, 一分钟内只查一次). */
    private suspend fun fetchFeedback(): String? {
        val tickets = runCatching { KoinPlatform.getKoin().get<FeedbackTickets>() }.getOrNull()?.takeIf { it.available }
            ?: return "unavailable"
        return if (tickets.syncNow()) null else "failed"
    }

    /** 按这一刻列着的通知发、撤系统通知; 回发了几条. */
    private suspend fun sync(shown: List<TvShownNotice>, active: Boolean, enabled: Boolean): Int = lock.withLock {
        val notifier = notifier ?: return 0
        val before = records.value
        val wasPrimed = primed
        val plan = planSystemNotices(
            shown.map { SystemNoticeCandidate(systemNoticeId(it), it.key, it.kind.id, it.entry.seen) },
            before,
            kinds = TvSystemNoticePolicy.kinds,
            postNew = enabled && !active && primed,
        )
        primed = true
        val byId = shown.associateBy(::systemNoticeId)
        for (id in plan.post) {
            val notice = byId[id] ?: continue
            notifier.post(
                SystemNotification(id = id, title = notice.title, body = notice.summary, group = notice.kind.id, payload = notice.key),
            )
            _sent.value = (listOf(SentLog(currentTimeMillis(), id, notice.title)) + _sent.value).take(50)
            logger.info { "Posted system notification $id" }
        }
        if (plan.remove.isNotEmpty()) notifier.remove(plan.remove)
        // 头一回 (没有记录文件) 也写一次, 下次起来就不算头一回了
        if (plan.records != before || !wasPrimed) {
            records.value = plan.records.takeLast(MAX_RECORDS)
            file?.let { f -> runCatching { f.writeText(encodeSystemNoticeRecords(records.value)) } }
        }
        plan.post.size
    }

    /** 当前记着的 (调试接口看). */
    internal val recordsSnapshot: List<SystemNoticeRecord> get() = records.value
}

/**
 * 发不发系统通知的种类: 在这里加一行就接上一种. 只放「用户不在应用里时也想马上知道」的事 (见通知设计第 8 条), 而且要在这个平台上真有的:
 * iPad 上现在只有反馈的回复与处理结果 (公告、新版本等其余种类只在应用里列; 订阅的通知还在订阅分支上).
 */
internal object TvSystemNoticePolicy {
    val kinds: Set<String> = setOf(FeedbackNotices.KIND)
}

/** 一条通知的系统通知 id: 键与时间 (同一件事有了新的一次算新的一条, 同通知气泡). */
internal fun systemNoticeId(notice: TvShownNotice): String = tvNoticeBubbleKey(notice)

/** 列着的一条通知里, 决定发不发要看的几样. */
internal data class SystemNoticeCandidate(val id: String, val key: String, val kind: String, val seen: Boolean)

/** 记过的一条: [delivered] = 发到了系统通知中心、还没撤 (撤了或当作处理过的为 false). */
internal data class SystemNoticeRecord(val id: String, val delivered: Boolean)

internal class SystemNoticePlan(val post: List<String>, val remove: List<String>, val records: List<SystemNoticeRecord>)

/**
 * 这一刻要发、要撤哪些系统通知. [postNew] 为 false (应用在前台、设置关着、头一回) 时新出现的只记下不发.
 * 发: [kinds] 里的、没看过的、没记过的. 撤: 发出去了的里, 已经看过或不再列着的.
 */
internal fun planSystemNotices(
    shown: List<SystemNoticeCandidate>,
    records: List<SystemNoticeRecord>,
    kinds: Set<String>,
    postNew: Boolean,
): SystemNoticePlan {
    val known = records.map { it.id }.toSet()
    val fresh = shown.filter { !it.seen && it.kind in kinds && it.id !in known }
    val post = if (postNew) fresh.map { it.id } else emptyList()
    val unseenIds = shown.filter { !it.seen }.map { it.id }.toSet()
    val remove = records.filter { it.delivered && it.id !in unseenIds }.map { it.id }
    val removed = remove.toSet()
    val updated = records.map { if (it.id in removed) it.copy(delivered = false) else it } +
        fresh.map { SystemNoticeRecord(it.id, delivered = postNew) }
    return SystemNoticePlan(post, remove, updated)
}

internal fun encodeSystemNoticeRecords(records: List<SystemNoticeRecord>): String =
    records.joinToString("\n") { (if (it.delivered) "1\t" else "0\t") + it.id.replace('\n', ' ') }

internal fun decodeSystemNoticeRecords(text: String): List<SystemNoticeRecord> = text.lineSequence().mapNotNull { line ->
    val tab = line.indexOf('\t')
    if (tab <= 0) null else SystemNoticeRecord(line.substring(tab + 1), delivered = line.substring(0, tab) == "1")
}.toList()
