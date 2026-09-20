/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.remote

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.him188.ani.app.data.models.player.EpisodeHistory
import me.him188.ani.app.data.repository.player.EpisodePlayHistoryRepository
import me.him188.ani.app.data.repository.subject.staticSubjectImageLargeUrl
import me.him188.ani.app.navigation.AniNavigator
import me.him188.ani.app.navigation.SubjectDetailPlaceholder
import me.him188.ani.app.ui.foundation.lan.LanHttpRequest
import me.him188.ani.app.ui.main.TvUpNextStore
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import org.koin.mp.KoinPlatform
import java.time.Instant
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import kotlin.time.Duration.Companion.seconds

/**
 * 手机「设置」里的**播放记录**: 电视本地播放进度表按番合并 (每部只留最近动过的那一集, 最近的在上). 点一部 = 电视打开详情页;
 * 点 ▶ = 接着看: 没看完就播那一集 (位置由播放器按 episodeId 从进度表续), 看完了播下一集 —— 规则与动作面板「接下来播放」卡
 * 同一份 ([TvUpNextStore.resolveTarget]).
 *
 * 每行的卡片底图是这部番的竖版封面 ([remoteCoverUrl]). 试过先用这一集的 TMDB 剧照: w300 在手机上明显糊, 用户定下来
 * 播放记录用封面、剧照给「播放器」标签那张卡 (见 [RemoteEpisodeArt]).
 *
 * 进度表是**软删除**的: 看完一集只打 `deletedAtMillis` 墓碑, 番名 / 封面 / 集号都还在, 所以看完的番也列得出来 (写「已看完」).
 * 代价是在电视播放历史页里手动删掉的那一集也会以「已看完」出现 —— 墓碑分不出是看完还是删掉, 与「接下来播放」卡同一个取舍.
 */
internal object RemoteHistory {
    private val logger = logger<RemoteHistory>()
    private val history: EpisodePlayHistoryRepository get() = KoinPlatform.getKoin().get()

    /** `api/history` 下的请求; 路径或方法不认识返回 null. */
    fun handle(request: LanHttpRequest, navigator: AniNavigator?, scope: CoroutineScope): JsonObject? {
        val get = request.method == "GET" || request.method == "HEAD"
        val post = request.method == "POST"
        return when {
            request.path == "api/history" && get -> list(lite = "lite=1" in request.query.split('&'))
            request.path == "api/history/open" && post -> open(request, navigator, scope)
            request.path == "api/history/play" && post -> play(request, navigator, scope)
            request.path == "api/history/delete" && post -> delete(request)
            else -> null
        }
    }

    /** 每部番最近动过的那一集, 最近的在前. 很老的记录可能没有条目 id, 跳不过去, 不列. 手机上删掉过的番 (见 [delete]) 不列. */
    private fun latestPerSubject(): List<EpisodeHistory> {
        val all = runBlocking { withTimeoutOrNull(READ_TIMEOUT) { history.allHistoriesFlow.first() } }.orEmpty()
        val hidden = hiddenAt()
        val seen = HashSet<Int>()
        return all.sortedByDescending { it.versionMillis }
            .filter { h -> h.subjectId?.let { id -> h.versionMillis > (hidden[id] ?: Long.MIN_VALUE) } == true }
            .filter { h -> h.subjectId?.let(seen::add) == true }
            .take(MAX_SUBJECTS)
    }

    /**
     * 删一部番的播放记录 (手机上左滑「删除」): 同电视播放历史页的删除 ([EpisodePlayHistoryRepository.removeAll], 软删除),
     * 再记下「删到哪一刻」—— 进度表是软删除的, 删掉留下的墓碑与「看完了」分不出来 (见类注释), 不另记的话它会以「已看完」
     * 照旧列在这里. 之后又看了 (有比那一刻新的记录) 就重新列出.
     *
     * 长按多选删除时 `ids` 带逗号分隔的多部, 一次删完; 回 `deleted` = 真删掉的那些 (找不到的不算).
     */
    private fun delete(request: LanHttpRequest): JsonObject {
        val fields = request.formFields()
        val ids = (fields["ids"] ?: fields["id"]).orEmpty().split(',').mapNotNull { it.trim().toIntOrNull() }.distinct()
        if (ids.isEmpty()) return result(false, tr("无效的条目"))
        var name: String? = null
        // 已经删掉的番: 删完回读墓碑时间那一步超时的话, 用它们兜底记「删到哪一刻」
        var removed: List<Int> = emptyList()
        val deletedAt = runCatching {
            runBlocking {
                withTimeoutOrNull(READ_TIMEOUT * ids.size.coerceAtMost(4)) {
                    val mine = history.allHistoriesFlow.first().filter { it.subjectId in ids }
                    name = mine.firstNotNullOfOrNull { it.subjectName?.takeIf(String::isNotBlank) }
                    history.removeAll(mine.map { it.episodeId })
                    removed = mine.mapNotNull { it.subjectId }.distinct()
                    // 墓碑的时间以进度表自己记的为准 (不拿本机时钟另算), 删完再读一次
                    history.allHistoriesFlow.first().filter { it.subjectId in ids }
                        .groupBy { it.subjectId!! }.mapValues { (_, list) -> list.maxOf { it.versionMillis } }
                }
            }
        }.getOrElse {
            logger.warn(it) { "Failed to delete remote history for subjects $ids" }
            return result(false, tr("删除失败，请重试"))
        } ?: run {
            if (removed.isEmpty()) return result(false, tr("删除超时，请重试"))
            // 已经删了, 只是回读超时: 不记的话墓碑会以「已看完」照旧列出来. 同一台机器, 此刻不早于墓碑时间
            logger.warn { "Remote history delete: re-read timed out after removal, hiding $removed at local time" }
            val now = System.currentTimeMillis()
            removed.associateWith { now }
        }
        if (deletedAt.isEmpty()) return result(false, if (ids.size > 1) tr("播放记录里找不到这些番，刷新一下再试") else tr("播放记录里找不到这部番，刷新一下再试"))
        hide(deletedAt)
        logger.info { "Remote history delete: subjects ${deletedAt.keys}" }
        val message = when {
            ids.size > 1 -> tr("已删除 {0} 部番的播放记录", deletedAt.size)
            else -> name?.let { tr("已删除「{0}」的播放记录", it) } ?: tr("已删除播放记录")
        }
        return buildJsonObject {
            put("ok", true)
            put("message", message)
            putJsonArray("deleted") { deletedAt.keys.forEach { add(it) } }
        }
    }

    /** 手机上删掉的番: subjectId → 删到哪一刻 (那时它记录里最新的 versionMillis). 存在电视本地, 重启还在. */
    private val prefs: SharedPreferences by lazy {
        KoinPlatform.getKoin().get<Context>().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun hiddenAt(): Map<Int, Long> =
        prefs.getStringSet(KEY_HIDDEN, emptySet()).orEmpty().mapNotNull { e ->
            val parts = e.split(':')
            val id = parts.getOrNull(0)?.toIntOrNull()
            val at = parts.getOrNull(1)?.toLongOrNull()
            if (id != null && at != null) id to at else null
        }.toMap()

    private fun hide(subjects: Map<Int, Long>) {
        val all = hiddenAt() + subjects
        prefs.edit().putStringSet(KEY_HIDDEN, all.map { (id, t) -> "$id:$t" }.toSet()).apply()
    }

    private const val PREFS_NAME = "tv_remote_history"
    private const val KEY_HIDDEN = "hidden_subjects"

    /** @param lite 设置里的入口卡片: 只要条数和最近那部, 不带图 */
    private fun list(lite: Boolean): JsonObject {
        val now = ZonedDateTime.now()
        return buildJsonObject {
            put("ok", true)
            putJsonArray("items") {
                latestPerSubject().forEach { h ->
                    addJsonObject {
                        put("id", h.subjectId)
                        put("title", h.subjectName?.takeIf { it.isNotBlank() } ?: tr("未知条目"))
                        put("line", progressLine(h))
                        if (!h.isDeleted) {
                            h.durationMillis?.takeIf { it > 0 }?.let { put("percent", (h.positionMillis * 100 / it).toInt().coerceIn(0, 100)) }
                        }
                        timeText(h.versionMillis, now)?.let { put("time", it) }
                        // 封面候选: 镜像站原图直连 → 电视转发 → Bangumi 600 宽 (见 remoteCoverCandidates)
                        if (!lite) h.subjectId?.let { id ->
                            putJsonArray("imgs") { remoteCoverCandidates(id, h.subjectImageUrl).forEach { add(it) } }
                        }
                    }
                }
            }
        }
    }

    private fun open(request: LanHttpRequest, navigator: AniNavigator?, scope: CoroutineScope): JsonObject {
        val subjectId = request.formFields()["id"]?.toIntOrNull() ?: return result(false, tr("无效的条目"))
        val nav = navigator ?: return result(false, tr("电视还没准备好"))
        val h = latestPerSubject().firstOrNull { it.subjectId == subjectId }
        TvRemoteControl.notifyRemoteNavigation()
        scope.launch(Dispatchers.Main) {
            runCatching { nav.navigateSubjectDetails(subjectId, h?.placeholder(subjectId)) }
                .onFailure { logger.warn(it) { "Failed to open subject details from remote history" } }
        }
        return result(true, h?.subjectName?.takeIf { it.isNotBlank() }?.let { tr("已在电视上打开「{0}」", it) } ?: tr("已在电视上打开详情页"))
    }

    private fun play(request: LanHttpRequest, navigator: AniNavigator?, scope: CoroutineScope): JsonObject {
        val subjectId = request.formFields()["id"]?.toIntOrNull() ?: return result(false, tr("无效的条目"))
        val nav = navigator ?: return result(false, tr("电视还没准备好"))
        val latest = latestPerSubject().firstOrNull { it.subjectId == subjectId }
            ?: return result(false, tr("播放记录里找不到这部番，刷新一下再试"))
        val title = latest.subjectName.orEmpty()
        val target = runCatching { runBlocking { TvUpNextStore.resolveTarget(latest) } }
            .onFailure { logger.warn(it) { "Failed to resolve what to play for remote history, subject $subjectId" } }
            .getOrNull()
        if (target == null) {
            // 看完了且没有下一集 (最后一集 / 下一集还没播出 / 拿不到剧集列表): 退而打开详情页
            TvRemoteControl.notifyRemoteNavigation()
            scope.launch(Dispatchers.Main) {
                runCatching { nav.navigateSubjectDetails(subjectId, latest.placeholder(subjectId)) }
                    .onFailure { logger.warn(it) { "Failed to open subject details from remote history" } }
            }
            return result(true, tr("「{0}」没有能接着播的下一集，已在电视上打开详情页", title))
        }
        logger.info { "Remote history play: subject $subjectId episode ${target.episodeId} continuing=${target.continuing}" }
        // 电视正在播这部番时就地换集, 不再叠一个新的播放页 (见 TvRemoteControl.playEpisode)
        val how = TvRemoteControl.playEpisode(nav, scope, subjectId, target.episodeId) {
            logger.warn(it) { "Failed to start playback from remote history" }
        }
        val name = target.subjectTitle.ifBlank { title }
        val ep = if (target.episodeSort.isNotEmpty()) tr(" 第 {0} 话", target.episodeSort) else ""
        val msg = when (how) {
            TvRemoteControl.RemotePlayResult.AlreadyPlaying -> tr("电视正在播「{0}」{1}", name, ep)
            TvRemoteControl.RemotePlayResult.Switched -> tr("已在电视上换到「{0}」{1}", name, ep)
            TvRemoteControl.RemotePlayResult.Opened ->
                (if (target.continuing) tr("已在电视上接着播「{0}」", name) else tr("已在电视上播放「{0}」", name)) + ep
        }
        return result(true, msg, player = true)
    }

    private fun EpisodeHistory.placeholder(subjectId: Int) =
        SubjectDetailPlaceholder(id = subjectId, name = subjectName.orEmpty(), coverUrl = subjectImageUrl.orEmpty())

    /** 「第 7 话 · 看到 12:34 / 23:40」/「第 7 话已看完」. */
    private fun progressLine(h: EpisodeHistory): String {
        val ep = h.episodeSort?.let { tr("第 {0} 话", it.renderSort()) } ?: h.episodeName?.takeIf { it.isNotBlank() } ?: tr("上次那一集")
        if (h.isDeleted) return tr("{0}已看完", ep)
        val duration = h.durationMillis?.takeIf { it > 0 }
        return tr("{0} · 看到 {1}", ep, h.positionMillis.clock()) + (duration?.let { " / ${it.clock()}" } ?: "")
    }

    /** 今天 / 昨天写到分钟, 更早的写日期 (跨年带年份). */
    private fun timeText(millis: Long, now: ZonedDateTime): String? {
        if (millis <= 0) return null
        val t = Instant.ofEpochMilli(millis).atZone(now.zone)
        val days = ChronoUnit.DAYS.between(t.toLocalDate(), now.toLocalDate())
        val hm = "%02d:%02d".format(t.hour, t.minute)
        return when {
            days == 0L -> tr("今天 {0}", hm)
            days == 1L -> tr("昨天 {0}", hm)
            t.year == now.year -> tr("{0}月{1}日", t.monthValue, t.dayOfMonth)
            else -> tr("{0}年{1}月{2}日", t.year, t.monthValue, t.dayOfMonth)
        }
    }

    /** `12.0` → `12`, `20.5` → `20.5`. */
    private fun Float.renderSort(): String = if (this == toInt().toFloat()) toInt().toString() else toString()

    private fun Long.clock(): String {
        val t = (this / 1000).coerceAtLeast(0)
        val h = t / 3600
        val m = t % 3600 / 60
        val s = t % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    /** @param player 电视进了播放页: 网页随后切到「播放器」标签 (同搜索结果) */
    private fun result(ok: Boolean, message: String, player: Boolean = false): JsonObject = buildJsonObject {
        put("ok", ok)
        put("message", message)
        if (player) put("player", true)
    }

    private const val MAX_SUBJECTS = 50
    private val READ_TIMEOUT = 5.seconds
}

/**
 * 手机列表项 (播放记录 / 搜索结果) 的封面底图地址: Bangumi 图片站的封面换成它自带缩放服务的 600 宽 (`r/600/pic/cover/l/`,
 * 实测 600×848 约 80KB; 原图 `l/` 常见上千像素、近 1MB; `c/` 只有 150 宽, 在 3 倍屏上明显糊). 封面框约 190 网页像素宽,
 * 3 倍屏要 ~560 像素, 600 正好不糊. 原图不到 600 宽的 (老条目) 缩放服务原样给. 别处的图 (如 Ani 镜像站, 只有原图) 原样.
 */
internal fun remoteCoverUrl(url: String): String = url.replace(BANGUMI_COVER, "/r/600/pic/cover/l/")

/**
 * 手机列表项 (播放记录 / 搜索结果) 的竖版封面候选, 网页依次试 (拉不到换下一张):
 * 1. **Ani 镜像站原图, 手机直连**: 同上游 App 的条目封面 (`staticSubjectImageLargeUrl`), 面向国内、手机一般直连得到;
 *    原图交给浏览器自己按显示尺寸解码最清楚 (试过电视缩小再给, 真机明显发虚, 见 [RemoteImageProxy]);
 * 2. **经电视转发同一张**: 手机连不上镜像站时, 电视用自己的网络 (代理) 拉, 原样转;
 * 3. **Bangumi 图片站 600 宽** ([remoteCoverUrl]): 镜像站里还没有的新条目.
 * 只在 main 成立: 纯直连分支没有 Ani 服务器.
 */
internal fun remoteCoverCandidates(subjectId: Int, bangumiUrl: String?): List<String> {
    val mirror = staticSubjectImageLargeUrl(subjectId)
    return buildList {
        add(mirror)
        add(RemoteImageProxy.proxied(mirror))
        bangumiUrl?.takeIf { it.isNotBlank() }?.let(::remoteCoverUrl)?.takeIf { it != mirror }?.let(::add)
    }
}

private val BANGUMI_COVER = Regex("""/(?:r/\d+/)?pic/cover/[lcm]/""")
