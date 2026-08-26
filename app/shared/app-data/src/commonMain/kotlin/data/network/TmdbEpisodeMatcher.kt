/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.network

import androidx.compose.ui.text.intl.Locale
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.todayIn
import me.him188.ani.app.data.models.episode.EpisodeCollectionInfo
import me.him188.ani.datasources.api.EpisodeType
import kotlin.time.Clock

/**
 * Compose [Locale] -> TMDB 语言码 (`language-REGION`), 决定 TMDB 本地化字段 (分集简介等) 的语言.
 * 中文无地区时按简体处理 (TMDB 的中文翻译以 zh-CN 为主).
 */
fun Locale.toTmdbLanguage(): String = when {
    region.isNotEmpty() -> "$language-$region"
    language == "zh" -> "zh-CN"
    else -> language
}

/**
 * 把 TMDB 分集数据对齐到 Bangumi 分集, 返回 episodeId -> 分集数据.
 *
 * 播出日期优先 (0/+1/-1 天容差, 深夜档跨日两边常差一天, 实测 DanMachi III:
 * Bangumi 10-02 vs TMDB 10-03), 无日期的分集按集号兜底 (`byEpisodeNumber` 取的是
 * "本条目对应的那一季", 见 [tmdbOwnSeasonNumber]), 特别篇按集名精确匹配兜底.
 * 最后两道兜底都是"前后两集夹中间": 日期轴一道, 集号轴一道 (见函数体内的说明).
 *
 * @param subjectAirDate 条目自己的开播日 (`YYYY-MM-DD`). 只用于"单集条目且那一集没有日期"
 *   这一种情形, 见函数体内的说明; 传 null 则该兜底不生效.
 *
 * 详情页选集缩略图与探索页继续观看 hero 共用此逻辑, 保证同一集两处拿到同一张图.
 */
fun TmdbEpisodeStills.matchToEpisodes(
    episodes: List<EpisodeCollectionInfo>,
    subjectAirDate: String? = null,
): Map<Int, TmdbEpisodeMedia> {
    val result = mutableMapOf<Int, TmdbEpisodeMedia>()

    // **单集条目**(剧场版/OVA/特别篇) 的那一集没有播出日期时, 用**条目自己的开播日**当它的日期.
    // 这类条目在 TMDB 上通常是母番 season 0 里的一集, 而那一集的 air_date 与 Bangumi 的条目
    // 日期往往逐字相同 —— 实测 みなみけ 三个特别篇 (べつばら/おまたせ/夏やすみ) 的 S0 分集日期
    // 与条目日期完全一致, 其中两个的 Bangumi 分集是没有日期的, 于是原先一张图都拿不到.
    // 只对"恰好一集"的条目做: 多集条目全都套同一个日期会让每一集都去抢同一条 TMDB 数据
    // (みなみけ おかわり 13 集无日期就是这种), 那种情形由按季认领 + 集号索引负责.
    // 这里**不留 ±1 天容差**: 命中的是母番特别篇那一堆里的某一条, 容差会把相邻的另一个特别篇
    // 也拉进来; 且要求当天只有一集 (singleOrNull), 含糊就走下面的常规流程.
    if (episodes.size == 1 && episodes[0].episodeInfo.airDate.isInvalid && subjectAirDate != null) {
        byAirDate[subjectAirDate]?.singleOrNull()?.let {
            return mapOf(episodes[0].episodeId to it)
        }
    }

    // 同日多集连播 (如 無職転生Ⅲ 第1+2话一小时首播) 时 TMDB 同日期是多集列表,
    // 匹配要按"这是当日第几集"对位; 先数出每集在其播出日内的序号.
    val sameDateOrdinals = mutableMapOf<Int, Int>()
    run {
        val counts = mutableMapOf<String, Int>()
        for (episode in episodes) {
            val date = episode.episodeInfo.airDate
            if (date.isInvalid) continue
            val key = runCatching { LocalDate(date.year, date.month, date.day) }
                .getOrNull()?.toString() ?: continue
            val ordinal = counts.getOrElse(key) { 0 }
            sameDateOrdinals[episode.episodeId] = ordinal
            counts[key] = ordinal + 1
        }
    }

    // 各集的播出日 + 它按 ±1 天容差落在哪个 TMDB 日期键上 (按季投票与逐集取用共用这一份)
    val localDates = episodes.map { episode ->
        val date = episode.episodeInfo.airDate
        if (date.isInvalid) null else runCatching {
            LocalDate(date.year, date.month, date.day)
        }.getOrNull()
    }
    val sameDayKeys = localDates.map { local ->
        local?.let {
            sequenceOf(
                it.toString(),
                it.plus(1, DateTimeUnit.DAY).toString(),
                it.minus(1, DateTimeUnit.DAY).toString(),
            ).firstOrNull(byAirDate::containsKey)
        }
    }

    val preferredSeason = preferredSeasonByEpisodeNames(episodes, sameDayKeys)

    // 各集按日期锚定命中的 TMDB 日期键 (供下方三明治兜底定位锚点)
    val matchedDates = arrayOfNulls<String>(episodes.size)
    episodes.forEachIndexed { index, episode ->
        val local = localDates[index]
        val episodeNumber = episode.episodeInfo.sort.number
            ?.takeIf { it == it.toInt().toFloat() }?.toInt()

        val sameDayKey = sameDayKeys[index]
        val byDate = sameDayKey?.let(byAirDate::getValue)?.let { list ->
            // 同一天挤了多个季的候选时, 先收窄到投票认下的那一季 (见 [preferredSeasonByEpisodeNames]);
            // 收窄后为空 (那一季当天没有集) 就仍用整张当日列表.
            val candidates = seasonCandidates(sameDayKey, list, preferredSeason)
            // 与当日列表按序对位; 两边同日集数不一致时取末位保底
            candidates.getOrNull(sameDateOrdinals[episode.episodeId] ?: 0) ?: candidates.lastOrNull()
        }
        // **集名命中优先于日期**: 逐字同名 (含后缀认领, 见 findByEpisodeName) 是比日期更强的证据.
        // 日期轴会给出"看着很确定但错"的答案 —— 衍生短篇与正片同期播出时, Bangumi 把短篇的
        // 播出日记成**正片那天**而 TMDB 记的是次日 (网络上传日), 于是短篇条目的每一集都在日期轴上
        // **精确命中正片**, 整排选集卡拿到正片的图 (2026-09-06 用户报的
        // 「Re:ゼロから始める休憩時間 3rd season」16 集全是正片的图; 同族还有 4th season).
        // 那天只有正片一个候选, 所以 preferredSeasonByEpisodeNames 的"同日多季投票"也没票可投.
        //
        // 名字对不上太常见 (译名/空格/注音/副标题), 所以这一档**只在真的命中时**抢先;
        // 撞名的键在索引侧就整条丢弃了 (见 fetchEpisodeStills 里的 byName), 后缀认领另有
        // "全表唯一 + 至少 4 字符"两道闸门, 所以命中即可信.
        val byName = findByEpisodeName(episode.episodeInfo.name, episode.episodeInfo.nameCn)
        // 集号兜底仅限 Bangumi 分集完全没有日期的老番: 有日期却对不上说明匹配到的
        // TMDB 条目本身可疑 (如正传名命中单季外传), 按集号硬凑只会拿到错图.
        val media = byName
            ?: byDate
            ?: episodeNumber?.takeIf { local == null }?.let { byEpisodeNumber[it] }
        // 日期锚点只在**真的按日期取用**时记: 集名赢了就说明这条目的时间轴与 TMDB 对不齐,
        // 拿它当三明治插值的锚点会把邻集也带偏
        if (byName == null && byDate != null) matchedDates[index] = sameDayKey
        if (media != null) result[episode.episodeId] = media
    }

    // 三明治兜底: 单集停播顺延时两边对同一集记的日期能差一周 (SEED DESTINY 第 3 集:
    // Bangumi 记实播 10-30, TMDB 记原定 10-23), 超出 ±1 天容差. 若前后两集都已按
    // 日期锚定, 且 TMDB 时间轴上两锚点之间恰好只剩一个日期、当日只有一集, 则该集
    // 必然就是它 (两侧锚定保证不会错拿邻集的图).
    episodes.forEachIndexed { index, episode ->
        if (episode.episodeId in result) return@forEachIndexed
        if (episode.episodeInfo.airDate.isInvalid) return@forEachIndexed
        val prev = matchedDates.getOrNull(index - 1) ?: return@forEachIndexed
        val next = matchedDates.getOrNull(index + 1) ?: return@forEachIndexed
        if (prev >= next) return@forEachIndexed
        val media = byAirDate.keys.filter { it > prev && it < next }
            .singleOrNull()?.let(byAirDate::getValue)?.singleOrNull()
            ?: return@forEachIndexed
        result[episode.episodeId] = media
    }

    // **集号轴上的三明治**: 名字/日期都对不上, 但前后两集都已经命中时, 集号能把中间那一集夹出来.
    // 病例都是"两边的集名差一个字": SEED HDリマスター 第 10 集 Bangumi 作「分たれた道」而 TMDB
    // 作「分かたれた道」(少打一个 か); 名探偵コナン 第 662 集「小五郎さんはいい人（後編）」vs
    // 「小五郎さんはいいひと(後編)」; サムライチャンプルー 第 19 集 Bangumi 多带了英文副标题
    // 「因果応報 Unholy Union」. 这类落空只能靠位置补.
    //
    // **不做模糊匹配 (编辑距离)**: 前后两集的集号夹出唯一的中间号是强得多的证据 —— 同一季里
    // 集名差一个字并不罕见 (前編/後編), 而这条要求两侧都已独立锚定.
    //
    // 与上面按日期的三明治互补: 那条要求两边的**日期**夹得住 (复播/重制条目两边日期差好几年,
    // 一个日期锚点都没有, 全靠集名命中); 这条不管邻居是靠什么命中的.
    //
    // 首集/末集拿**季的边界**当另一个锚点, 否则这两集天生接不住 (按日期那条就有这个洞).
    // 算术自带保护: SEED DESTINY HDリマスター 的末集「選ばれた未来」在 TMDB S2 里没有对应集,
    // 它前一集锚在第 50 集 (正好是季末), 算出 nextNumber - prevNumber = 1 而不是 2, 正确放弃;
    // 「TMDB 一季 24 集而 Bangumi 拆成两个 12 集条目」这种情形算出来的差更大, 同样不触发.
    //
    // **至少要有一个真实的邻居锚点**, 所以只对两集以上的条目做: 单集条目两侧都是季边界, 等于
    // 没有任何证据 —— 而季里恰好也只有一集时算出来的差正好是 2, 会白送一张图, 绕过"有日期却
    // 对不上说明匹配到的条目本身可疑"那道闸门 (单测抓到的).
    //
    // **轴上只排正片**: 特别篇命中的是 TMDB 的 season 0, 而 [byEpisodeNumber] 只索引正片那一季 ——
    // 它在集号轴上是个空洞, 让它占一个位置就会把**相邻正片集**的锚点打断 (邻居"命中了"但反查不出
    // 集号, 于是直接放弃). 实测「カーニバル・ファンタズム」12 集正片 + 1 集 SP (与第 1 集同期):
    // SP 排在第 1 集前后任一侧, 第 1 集都拿不到图; 把 SP 从轴上摘掉后 13 集全有.
    // SP 自己的图不受影响 —— 它走的是日期/集名那两条路.
    val numberAxis = episodes.filter { it.episodeInfo.type == EpisodeType.MainStory }
    if (byEpisodeNumber.isNotEmpty() && numberAxis.size >= 2) {
        // 集号索引反查; 内容完全相同的集 (字段全空的占位集) 一律弃用, 免得夹错
        val numberByMedia = mutableMapOf<TmdbEpisodeMedia, Int?>()
        for ((number, media) in byEpisodeNumber) {
            numberByMedia[media] = if (media in numberByMedia) null else number
        }
        val lowest = byEpisodeNumber.keys.min()
        val highest = byEpisodeNumber.keys.max()
        numberAxis.forEachIndexed { index, episode ->
            if (episode.episodeId in result) return@forEachIndexed
            val prevNumber = if (index == 0) lowest - 1 else {
                result[numberAxis[index - 1].episodeId]?.let { numberByMedia[it] } ?: return@forEachIndexed
            }
            val nextNumber = if (index == numberAxis.lastIndex) highest + 1 else {
                result[numberAxis[index + 1].episodeId]?.let { numberByMedia[it] } ?: return@forEachIndexed
            }
            if (nextNumber - prevNumber != 2) return@forEachIndexed
            byEpisodeNumber[prevNumber + 1]?.let { result[episode.episodeId] = it }
        }
    }
    return result
}

/**
 * 按**集名**投票认领"本条目对应 TMDB 的哪一季", 只在同一天挤了不止一个季的候选时才有票可投
 * (见 [TmdbEpisodeStills.byAirDateOrigin]); 认不出来返回 null, 由调用方退回"当日第几集"的原口径.
 *
 * **为什么需要这一票**: TMDB 常把同期放送的短篇挂在正传条目的 season 0 下, 且与正片**逐集同日** ——
 * 实测「さわらないで小手指くん」(tv/283880) 的 S0 是 12 集占位壳 (零剧照), 与正片同日同集数;
 * 「ちょっとだけ愛が重いダークエルフ」(tv/271003)、「ループ7回目の悪役令嬢」(tv/232926) 的 S0 则是
 * 有整套剧照的ミニアニメ. 日期这条判据在这里完全失效 (两季一样), 而 S0 排在正片前面时正片条目的每
 * 一集都会拿到 S0 的数据: 前者表现为**12 集全无图**, 后两者表现为**每集都显示短篇的图** (更隐蔽).
 * 原先靠 Bangumi 关系链判定的"正传把 S0 殿后"救不了这类: 走 Ani 关系索引那条路拿不到「主线故事」
 * 出边, `isDerivative` 是 null, 殿后规则整个不生效 (见 `TmdbImageService.resolveLineageViaAni`).
 *
 * **集名只用来"确认", 不用来"否证"**: 两边名字对不上太常见 (译名不同、空格、注音括号、副标题、
 * 一边把两段并进一个名字), 所以判据是"某一季得票**严格多于**其它季就认它", 而不是"名字对不上就
 * 拒掉这一季"; 一票都没有 (或最高票并列) 时什么都不做. 比较用的是**原语言**集名的首段
 * (见 [tmdbEpisodeSegmentKey]) —— Bangumi 的原名与 TMDB 原语言集名同源, 中文名是两边各自的译文,
 * 几乎必然对不上.
 */
private fun TmdbEpisodeStills.preferredSeasonByEpisodeNames(
    episodes: List<EpisodeCollectionInfo>,
    sameDayKeys: List<String?>,
): Int? {
    if (byAirDateOrigin.isEmpty()) return null
    val votes = mutableMapOf<Int, Int>()
    episodes.forEachIndexed { index, episode ->
        val origins = sameDayKeys[index]?.let(byAirDateOrigin::get) ?: return@forEachIndexed
        val wanted = tmdbEpisodeSegmentKey(episode.episodeInfo.name).takeIf { it.isNotEmpty() }
            ?: return@forEachIndexed
        // 同一个集名同时挂在两个季上 (两季都叫「特別編」这类) 说明这一票分不出来, 弃掉
        val season = origins.filter { it.nameKey == wanted }.map { it.seasonNumber }.distinct()
            .singleOrNull() ?: return@forEachIndexed
        votes[season] = votes.getOrElse(season) { 0 } + 1
    }
    val top = votes.values.maxOrNull() ?: return null
    return votes.entries.singleOrNull { it.value == top }?.key
}

/**
 * 当日候选里属于 [preferredSeason] 的那些; 出处缺失/与当日列表对不齐 (旧缓存) 或那一季当天没有集
 * 时原样返回 [list].
 */
private fun TmdbEpisodeStills.seasonCandidates(
    dateKey: String,
    list: List<TmdbEpisodeMedia>,
    preferredSeason: Int?,
): List<TmdbEpisodeMedia> {
    if (preferredSeason == null) return list
    val origins = byAirDateOrigin[dateKey] ?: return list
    if (origins.size != list.size) return list
    return list.filterIndexed { index, _ -> origins[index].seasonNumber == preferredSeason }
        .ifEmpty { list }
}

/**
 * [EpisodeCollectionInfo.episodeInfo] 播出日期的 `YYYY-MM-DD` 形式;
 * 无效日期返回 null. 供 [TmdbImageService.getEpisodeStills] 的 `newestWantedAirDate` 参数用.
 */
fun EpisodeCollectionInfo.airDateStringOrNull(): String? {
    val date = episodeInfo.airDate
    if (date.isInvalid) return null
    return runCatching { LocalDate(date.year, date.month, date.day).toString() }.getOrNull()
}

/**
 * "应当已经播出"的最新一集的日期 (`YYYY-MM-DD`); 全部无日期或均未播出返回 null.
 * 供 [TmdbImageService.getEpisodeStills] 的 `newestWantedAirDate` 参数用:
 * 连载番最后几集的日期在未来, TMDB 不可能有数据, 以"今天"截断, 避免无意义的陈旧重取.
 */
fun List<EpisodeCollectionInfo>.newestAiredDateStringOrNull(): String? {
    val today = Clock.System.todayIn(TimeZone.currentSystemDefault()).toString()
    return mapNotNull { it.airDateStringOrNull() }.filter { it <= today }.maxOrNull()
}
