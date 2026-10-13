/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.whatsnew

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import me.him188.ani.app.domain.foundation.HttpClientProvider
import me.him188.ani.app.domain.foundation.get
import me.him188.ani.app.domain.update.UpdateManager
import me.him188.ani.app.platform.currentAniBuildConfig
import me.him188.ani.app.ui.profile.TvUserProfilePicker
import me.him188.ani.app.ui.update.ReleaseHighlights
import me.him188.ani.app.ui.update.ReleaseNoteItem
import me.him188.ani.app.ui.update.ReleaseNotesAnswer
import me.him188.ani.app.ui.update.ReleaseNotesFetcher
import me.him188.ani.app.ui.update.ReleaseNotesStore
import me.him188.ani.app.ui.update.isNewerVersion
import me.him188.ani.app.ui.update.parseReleaseHighlights
import me.him188.ani.app.ui.update.releaseNotesStore
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import org.koin.mp.KoinPlatform
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * 「本版新功能」页显示的内容.
 *
 * @param releases 列出的各版 (见 [me.him188.ani.app.ui.update.releasesToShow]), 新的在前; 只有通知时为空
 * @param notice 当前版本的通知 (见 [ReleaseHighlights.notice])
 * @param full 设置里打开的: 小改动与修复逐条列出. 启动时那一页只写一共几条
 */
@Immutable
class WhatsNewContent(
    val version: String,
    val releases: List<ReleaseHighlights>,
    val notice: List<ReleaseNoteItem>,
    val full: Boolean,
) {
    val minorCount: Int get() = releases.sumOf { it.minor.size }
}

/** 设置里那一页的读取状态, 见 [TvWhatsNew.settingsContent]. */
sealed interface WhatsNewLoad {
    data object Loading : WhatsNewLoad
    class Ready(val content: WhatsNewContent) : WhatsNewLoad
    data object Missing : WhatsNewLoad
    data object Unreachable : WhatsNewLoad
}

/**
 * 更新后第一次打开时盖在主页上的「本版新功能」页, 以及发版后补的通知.
 *
 * - **介绍新功能**: 当前版本比记下的「介绍过的版本」新时出现一次. 列哪几版只看当前版本 (见
 *   [me.him188.ani.app.ui.update.releasesToShow]): 正式版只列它自己, 测试版列同一版本号从 alpha01 起的全部测试版; 与从哪一版升上来无关.
 *   首次引导要出现 (新装) 时不介绍, 直接记下.
 *   说明的来源: 先 GitHub (一次取到那几版, 也带着发版后补的通知); 首屏好了、且开始等了 [INTRODUCE_WAIT] 还没取到 (或 GitHub 连不上),
 *   就用存下的那几版 (下载安装包时存的、以前取到的); 连当前版本都没存就这次不出现, 下次打开再试 ([MAX_FAILED_LAUNCHES] 次后放弃).
 * - **通知**: 已经介绍过的版本, 每次冷启动去 GitHub 取一次这一版的说明, 「## 通知」一节有没显示过的内容 (改过也算) 就只显示通知.
 *   不为它多等: 首屏好了还没取到就看存下的那份 (上次取到的), 这次取到的留给下次.
 *
 * 与首次引导一样不露出主页: 冷启动时说明一到就盖在启动页上 ([showOnLaunch]), 首屏在它与启动页背后接着加载;
 * 启动页等首屏好了才撤 (这一页先关掉的话露出来的是还在走进度条的启动页).
 *
 * 状态按整台设备记 (SharedPreferences, 不分用户). 一个进程只在启动时出现一次; 设置里随时能打开 ([settingsContent]).
 */
object TvWhatsNew {
    private const val PREFS = "tv_whats_new"
    private const val KEY_SEEN_VERSION = "seen_version"
    private const val KEY_SEEN_NOTICE = "seen_notice"
    private const val KEY_FAILED_VERSION = "failed_version"
    private const val KEY_FAILED_COUNT = "failed_count"

    /** 取不到说明的启动超过这么多次就不再介绍这一版 (连不上 GitHub 也没有存下的说明). */
    private const val MAX_FAILED_LAUNCHES = 3

    /** 要介绍新功能时至少等说明这么久, 首屏比它先好的话启动页多盖到这时候; 每个版本只有一次. */
    private val INTRODUCE_WAIT = 3.seconds

    /** 这一页盖上以后最多等这么久它画出来 (见 [onCovered]); 画不出来也不能让启动页一直盖着. */
    private val COVER_WAIT = 3.seconds

    private val logger = logger<TvWhatsNew>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 启动时盖着的那一页; 非 null = 正显示. TV 根部的 [TvWhatsNewHost] 画它. */
    val launchContent = MutableStateFlow<WhatsNewContent?>(null)

    private var launchCheck: LaunchCheck? = null
    private val decisionLock = Mutex()
    private var decision: Boolean? = null
    private var covered = CompletableDeferred<Unit>()
    private var prepared = false

    /**
     * @param introduce 要介绍新功能; false = 只看有没有新的通知
     * @param cached 存下的那几版说明 (没存当前版本时为 null)
     * @param answer 网上取的结果
     */
    private class LaunchCheck(
        val version: String,
        val introduce: Boolean,
        val cached: Deferred<List<ReleaseHighlights>?>,
        val answer: Deferred<ReleaseNotesAnswer>,
    )

    /**
     * TV 根部一组合就调 (不等首页): 开始准备内容. 一个进程只做一次.
     * [onboardingPending] = 这次要走首次引导 (新装或重新走引导): 记下当前版本, 这次什么都不出现.
     */
    fun prepare(context: Context, onboardingPending: Boolean) {
        if (prepared) return
        prepared = true
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val version = currentAniBuildConfig.versionName
        val seen = prefs.getString(KEY_SEEN_VERSION, null)
        if (onboardingPending) {
            prefs.edit().putString(KEY_SEEN_VERSION, version).apply()
            return
        }
        val introduce = seen == null || isNewerVersion(version, seen)
        // 降级装回旧版: 记成当前版本, 再升上去时照常介绍
        if (!introduce && seen != version) prefs.edit().putString(KEY_SEEN_VERSION, version).apply()
        val store = KoinPlatform.getKoin().get<UpdateManager>().releaseNotesStore()
        val fetcher = ReleaseNotesFetcher(KoinPlatform.getKoin().get<HttpClientProvider>().get())
        logger.info { "Launch check: version=$version, seen=$seen, introduce=$introduce" }
        launchCheck = LaunchCheck(
            version = version,
            introduce = introduce,
            cached = scope.async { store.forRelease(version)?.let(::parseAll) },
            answer = scope.async {
                val answer = if (introduce) fetcher.forRelease(version) else fetcher.of(version, withMirrors = false)
                if (answer is ReleaseNotesAnswer.Found) store.save(answer, version)
                answer
            },
        )
    }

    /**
     * 这次启动要不要盖「本版新功能」页, 要就盖上, 等它画出来再返回 true. 只决定一次, 之后再问拿同一个结果:
     * 冷启动进首页时启动页一出来就问 (盖住了启动页就不淡出, 主页不露出来); 先选人时等选人页关了, 由根部弹二维码之前问.
     * 说明最晚等到 [firstScreenReady] (首屏在启动页背后好了) —— 再晚启动页就撤了, 弹出来会打断用户; 介绍新功能时另外至少等 [INTRODUCE_WAIT].
     * [onHomePage] 在盖上那一刻再问一次: 不在首页 (进程重建恢复到别的页、从首页频道直接进了播放器) 就不盖, 留到下次打开.
     */
    suspend fun showOnLaunch(
        context: Context,
        onHomePage: () -> Boolean,
        firstScreenReady: suspend () -> Unit = {},
    ): Boolean = decisionLock.withLock {
        decision ?: decide(context, onHomePage, firstScreenReady).also { decision = it }
    }

    private suspend fun decide(context: Context, onHomePage: () -> Boolean, firstScreenReady: suspend () -> Unit): Boolean {
        val check = launchCheck ?: return false
        launchCheck = null
        TvUserProfilePicker.visible.first { !it }
        val answer = awaitAnswer(check, firstScreenReady)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val content = if (check.introduce) introduction(check, answer, prefs) else notice(check, answer, prefs)
        if (content == null) return false
        if (!onHomePage()) {
            logger.info { "Not on the home page, showing it next launch" }
            return false
        }
        val editor = prefs.edit()
            .putString(KEY_SEEN_VERSION, check.version)
            .remove(KEY_FAILED_VERSION)
            .remove(KEY_FAILED_COUNT)
        if (content.notice.isNotEmpty()) editor.putString(KEY_SEEN_NOTICE, noticeKey(check.version, content.notice))
        editor.apply()
        logger.info { "Showing ${content.releases.map { it.version }}, notice=${content.notice.size}" }
        covered = CompletableDeferred()
        launchContent.value = content
        if (withTimeoutOrNull(COVER_WAIT) { covered.await() } == null) logger.info { "Page not drawn in $COVER_WAIT" }
        return true
    }

    /** 网上取的说明, 最晚等到首屏好了 (介绍新功能时另外至少 [INTRODUCE_WAIT]); 没等到返回 null. */
    private suspend fun awaitAnswer(check: LaunchCheck, firstScreenReady: suspend () -> Unit): ReleaseNotesAnswer? {
        val start = TimeSource.Monotonic.markNow()
        return coroutineScope {
            val giveUp = async {
                firstScreenReady()
                if (check.introduce) delay(INTRODUCE_WAIT - start.elapsedNow())
            }
            select<ReleaseNotesAnswer?> {
                check.answer.onAwait { it }
                giveUp.onAwait { null }
            }.also { giveUp.cancel() }
        }
    }

    /** [TvWhatsNewHost] 的窗口画出来了 (进场动画也放完了), 盖在下面的启动页可以撤了. */
    internal fun onCovered() {
        covered.complete(Unit)
    }

    private suspend fun introduction(
        check: LaunchCheck,
        answer: ReleaseNotesAnswer?,
        prefs: SharedPreferences,
    ): WhatsNewContent? {
        val releases = (answer as? ReleaseNotesAnswer.Found)?.notes?.let(::parseAll) ?: check.cached.await()
        if (releases == null) {
            if (answer == ReleaseNotesAnswer.Missing) {
                logger.info { "No release notes for ${check.version}, nothing to introduce" }
                prefs.edit().putString(KEY_SEEN_VERSION, check.version).apply()
            } else {
                recordFailure(check.version, prefs)
            }
            return null
        }
        val notice = releases.firstOrNull { it.version == check.version }?.notice.orEmpty()
        if (releases.none { it.features.isNotEmpty() } && notice.isEmpty()) {
            logger.info { "No new features in ${releases.map { it.version }}" }
            prefs.edit().putString(KEY_SEEN_VERSION, check.version).apply()
            return null
        }
        return WhatsNewContent(check.version, releases, notice, full = false)
    }

    private suspend fun notice(
        check: LaunchCheck,
        answer: ReleaseNotesAnswer?,
        prefs: SharedPreferences,
    ): WhatsNewContent? {
        val fetched = (answer as? ReleaseNotesAnswer.Found)?.notes?.firstOrNull { it.first == check.version }
            ?.let { (v, body) -> parseReleaseHighlights(v, body) }
        val notice = (fetched ?: check.cached.await()?.firstOrNull { it.version == check.version })?.notice.orEmpty()
        if (notice.isEmpty() || prefs.getString(KEY_SEEN_NOTICE, null) == noticeKey(check.version, notice)) return null
        return WhatsNewContent(check.version, releases = emptyList(), notice = notice, full = false)
    }

    private fun recordFailure(version: String, prefs: SharedPreferences) {
        val count = if (prefs.getString(KEY_FAILED_VERSION, null) == version) prefs.getInt(KEY_FAILED_COUNT, 0) + 1 else 1
        logger.info { "Release notes of $version unavailable ($count/$MAX_FAILED_LAUNCHES)" }
        val editor = prefs.edit()
        if (count >= MAX_FAILED_LAUNCHES) {
            editor.putString(KEY_SEEN_VERSION, version).remove(KEY_FAILED_VERSION).remove(KEY_FAILED_COUNT)
        } else {
            editor.putString(KEY_FAILED_VERSION, version).putInt(KEY_FAILED_COUNT, count)
        }
        editor.apply()
    }

    private fun parseAll(notes: List<Pair<String, String>>): List<ReleaseHighlights> =
        notes.mapNotNull { (version, body) -> parseReleaseHighlights(version, body) }

    /** 存下取到的说明: 这次没赶上的下次直接用, 设置里那一页也先显示它. */
    private suspend fun ReleaseNotesStore.save(answer: ReleaseNotesAnswer.Found, version: String) {
        answer.notes.forEach { (v, body) -> put(v, body) }
        retainFor(version)
    }

    /** 记「这条通知看过了」的键: 内容改了 (发版人补了一句) 就是新的一条, 会再显示一次. */
    private fun noticeKey(version: String, notice: List<ReleaseNoteItem>) = "$version:${notice.hashCode()}"

    /**
     * 设置「软件更新 - 本版新功能」: 与启动时同样的几版, 小改动与修复逐条列出. 存下的先显示, 再去 GitHub 取最新的
     * (发版后补的通知在那边), 取到了换上.
     */
    fun settingsContent(): Flow<WhatsNewLoad> = flow {
        val version = currentAniBuildConfig.versionName
        val koin = KoinPlatform.getKoin()
        val store: ReleaseNotesStore = koin.get<UpdateManager>().releaseNotesStore()
        fun content(releases: List<ReleaseHighlights>) = WhatsNewContent(
            version, releases, notice = releases.firstOrNull { it.version == version }?.notice.orEmpty(), full = true,
        )

        val cached = store.forRelease(version)?.let(::parseAll)?.takeIf { it.isNotEmpty() }
        if (cached != null) emit(WhatsNewLoad.Ready(content(cached)))
        when (val answer = ReleaseNotesFetcher(koin.get<HttpClientProvider>().get()).forRelease(version)) {
            is ReleaseNotesAnswer.Found -> {
                store.save(answer, version)
                val fresh = parseAll(answer.notes)
                emit(if (fresh.isNotEmpty()) WhatsNewLoad.Ready(content(fresh)) else WhatsNewLoad.Missing)
            }

            ReleaseNotesAnswer.Missing -> if (cached == null) emit(WhatsNewLoad.Missing)
            ReleaseNotesAnswer.Unreachable -> if (cached == null) emit(WhatsNewLoad.Unreachable)
        }
    }.flowOn(Dispatchers.IO)
}
