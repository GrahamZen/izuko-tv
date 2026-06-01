/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.network

import me.him188.ani.app.data.models.episode.EpisodeCollectionInfo
import me.him188.ani.app.data.models.episode.EpisodeInfo
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.EpisodeType
import me.him188.ani.datasources.api.PackedDate
import me.him188.ani.datasources.api.topic.UnifiedCollectionType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * TMDB 条目匹配里两条容易静默失效的规则: 同形字折叠表、年份否决的两侧不对称判据.
 */
class TmdbMatchingTest {
    @Test
    fun `同形字折叠 - 希腊 Z 折成拉丁 Z`() {
        // 機動戦士ガンダムΖΖ: 末两字是希腊大写 Ζ (U+0396), TMDB 录的是拉丁 ZZ.
        // 折叠形搜得到 tv/5660, 不折叠则 TMDB 直接 0 结果.
        assertEquals(
            "機動戦士ガンダムZZ",
            foldCompatibility("機動戦士ガンダムΖΖ"),
        )
        // 機動戦士Ζガンダム: 希腊 Ζ 在中间
        assertEquals(
            "機動戦士Zガンダム",
            foldCompatibility("機動戦士Ζガンダム"),
        )
    }

    @Test
    fun `同形字折叠 - 没有拉丁对应的希腊字母不动`() {
        // マクロスΔ 的 Δ 真的是希腊字母, 折掉就毁了这个标题
        assertEquals("マクロスΔ", foldCompatibility("マクロスΔ"))
        assertEquals("Ω", foldCompatibility("Ω"))
        assertEquals("Λ", foldCompatibility("Λ"))
        assertEquals("Σ", foldCompatibility("Σ"))
    }

    @Test
    fun `同形字折叠 - 西里尔同形字`() {
        // 西里尔 А В Е (U+0410 U+0412 U+0415) 与拉丁 A B E 同形
        assertEquals("ABE", foldCompatibility("АВЕ"))
        assertEquals("abe", foldCompatibility("аве"))
    }

    @Test
    fun `兼容折叠 - 原有三类不受影响`() {
        // 康熙部首 ⼄⼥ → 乙女 (乙女ゲー世界はモブに厳しい世界です)
        assertEquals("乙女", foldCompatibility("⼄⼥"))
        // 全角字母数字 → 半角
        assertEquals("AB12", foldCompatibility("ＡＢ１２"))
        // Unicode 罗马数字 → 拉丁 (無職転生Ⅱ)
        assertEquals("II", foldCompatibility("Ⅱ"))
        // 无需折叠的串原样返回
        assertEquals("進撃の巨人", foldCompatibility("進撃の巨人"))
    }

    @Test
    fun `年份否决 - tv 只查下界, 续季晚于剧首播是正常的`() {
        // 進撃の巨人 Season 3 (2018) 归属 2013 年的 tv/1429 —— TMDB 把续季并进同一剧条目,
        // first_air_date 是第一季的. 对称判据会误杀这一类.
        assertTrue(tmdbYearPlausible(candidateYear = 2013, subjectYear = 2018, isMovie = false))
        // 有頂天家族2 (2017) 归属 2013 年的剧条目
        assertTrue(tmdbYearPlausible(candidateYear = 2013, subjectYear = 2017, isMovie = false))
        // 灼眼のシャナII (2007) 归属 2005 年的剧条目
        assertTrue(tmdbYearPlausible(candidateYear = 2005, subjectYear = 2007, isMovie = false))
    }

    @Test
    fun `年份否决 - tv 条目年份早于剧首播则不可能`() {
        // 攻殻機動隊 1995 剧场版命中 2026 年的新 TV 剧: 条目比剧首播早 31 年, 只能是后来的作品
        assertFalse(tmdbYearPlausible(candidateYear = 2026, subjectYear = 1995, isMovie = false))
        // 機動戦士ガンダムΖΖ (1986) 不该命中 1979 年初代 —— 这条靠下界拦不住 (1986 晚于 1979),
        // 得靠同形字折叠先搜到正确条目; 这里只固定住"晚于首播一律放行"的语义
        assertTrue(tmdbYearPlausible(candidateYear = 1979, subjectYear = 1986, isMovie = false))
        // 容差 1 年: 跨年记录差一年不算不可能
        assertTrue(tmdbYearPlausible(candidateYear = 2021, subjectYear = 2020, isMovie = false))
        assertFalse(tmdbYearPlausible(candidateYear = 2022, subjectYear = 2020, isMovie = false))
    }

    @Test
    fun `年份否决 - movie 两侧都比, 容差一年`() {
        // 攻殻機動隊 1995 剧场版 → movie/9323 (1995-11-18)
        assertTrue(tmdbYearPlausible(candidateYear = 1995, subjectYear = 1995, isMovie = true))
        // デート・ア・バレット 前編: Bangumi 2021 / TMDB 2020, 容差不能收成 0
        assertTrue(tmdbYearPlausible(candidateYear = 2020, subjectYear = 2021, isMovie = true))
        // 2.0 重制版 (2008) 不该被 1995 的条目认领
        assertFalse(tmdbYearPlausible(candidateYear = 2008, subjectYear = 1995, isMovie = true))
        // 与 tv 不同: movie 也否决"晚于条目"的方向
        assertFalse(tmdbYearPlausible(candidateYear = 2026, subjectYear = 1995, isMovie = true))
    }

    @Test
    fun `季号命中电影 - 非精确才扣住`() {
        // 進撃の巨人 Season 3: tv 搜索 0 结果 → 转 movie 命中合集剧场版《覚醒の咆哮》(非精确),
        // 而正确答案 tv/1429 在下一层 (根条目名「進撃の巨人」)
        assertTrue(tmdbTentativeSeasonHit("進撃の巨人 Season 3", isMovie = true, exactTitle = false))
        assertTrue(tmdbTentativeSeasonHit("無職転生 ～異世界行ったら本気だす～ 第2クール", isMovie = true, exactTitle = false))
    }

    @Test
    fun `季号命中电影 - 精确标题一律放行`() {
        // Batman: The Dark Knight Returns, Part 1 / South Park The Streaming Wars Part 2:
        // 条目自己就是某一 Part 的独立电影, 正确答案正是它自己那个 movie 条目.
        // 扣住会被下一层的合并版或**另一 Part** 挤掉 —— 实测过的三例回归就靠这一条挡住.
        assertFalse(
            tmdbTentativeSeasonHit("Batman: The Dark Knight Returns, Part 1", isMovie = true, exactTitle = true),
        )
        assertFalse(
            tmdbTentativeSeasonHit("South Park The Streaming Wars Part 2", isMovie = true, exactTitle = true),
        )
    }

    @Test
    fun `季号命中电影 - tv 命中不扣, 无季号不扣`() {
        // 命中 tv 的不扣: 季号条目归属剧条目本来就是正常的 (進撃 Final Season → tv/1429)
        assertFalse(tmdbTentativeSeasonHit("進撃の巨人 Season 3", isMovie = false, exactTitle = false))
        // 不带季号的查询没有"下一层更可信"的前提: 攻殻機動隊 1995 剧场版命中 movie/9323 必须立即采用
        assertFalse(
            tmdbTentativeSeasonHit("GHOST IN THE SHELL / 攻殻機動隊", isMovie = true, exactTitle = false),
        )
        assertFalse(tmdbTentativeSeasonHit("千と千尋の神隠し", isMovie = true, exactTitle = false))
    }

    @Test
    fun `削季号后缀 - 各种写法都要削到串尾`() {
        // 序数词 + 片假名混写: 此前一个削字候选都生成不出来, 归并到 2006 年那部不同的改编
        assertEquals(
            "Fate/stay night [Unlimited Blade Works]",
            tmdbStripSeasonSuffix("Fate/stay night [Unlimited Blade Works] 2ndシーズン"),
        )
        assertEquals("ワールドトリガー", tmdbStripSeasonSuffix("ワールドトリガー 3rdシーズン"))
        // **削到串尾**而不是只削掉标记本身 —— 常量的分组写错时这条会退化成 "Inside Job Part.1"
        assertEquals("Inside Job", tmdbStripSeasonSuffix("Inside Job Season 1 Part.1"))
        assertEquals("ケンガンアシュラ", tmdbStripSeasonSuffix("ケンガンアシュラ Season2 Part 2"))
        // 季标记后面跟篇章名的也要一起削掉
        assertEquals(
            "Re:ゼロから始める異世界生活",
            tmdbStripSeasonSuffix("Re:ゼロから始める異世界生活 4th season 喪失編"),
        )
        assertEquals("無職転生 ～異世界行ったら本気だす～", tmdbStripSeasonSuffix("無職転生 ～異世界行ったら本気だす～ 第2クール"))
        // 名字本体里的数字不能当季号削 (モブサイコ100 / STEINS;GATE 0)
        assertEquals("モブサイコ100", tmdbStripSeasonSuffix("モブサイコ100"))
        assertEquals("STEINS;GATE 0", tmdbStripSeasonSuffix("STEINS;GATE 0"))
        assertEquals("進撃の巨人", tmdbStripSeasonSuffix("進撃の巨人"))
    }

    @Test
    fun `削季号后缀 - 无编号的季标记不归这个函数`() {
        // 次篇 / 続編 / 第一季 (中文数字) / 第1シリーズ 这类不带阿拉伯数字的标记花样列不完,
        // 统一由候选层的**逐词去尾**兜住 (「ベルセルク 次篇」→「ベルセルク」= 2016 年那部,
        // 它的 S2 就是这个条目; 旧行为是回溯到 1997 年的 tv/35935, 12 集全无图).
        // 这里只固定住"这个函数不越权"的边界
        assertEquals("ベルセルク 次篇", tmdbStripSeasonSuffix("ベルセルク 次篇"))
        assertEquals("十万个冷笑话 第一季", tmdbStripSeasonSuffix("十万个冷笑话 第一季"))
    }

    @Test
    fun `逐词去尾的守卫 - 削剩形态词就得停`() {
        // 拿光秃秃的形态词当查询串必然命中随便一部同类作品: 实测「劇場版」命中 tv/154779
        // 「龙珠剧场版」(genre 16 + 日语原声, 过得了动画过滤), 于是「劇場版 魔法科高校の劣等生
        // 四葉継承編」从正确的母番退化成「劇場版 チェンソーマン レゼ篇」、「映画 ラブライブ！…」
        // 退化成「映画 聲の形」
        assertTrue("劇場版".isMediaFormWordOnly())
        assertTrue("映画".isMediaFormWordOnly())
        assertTrue("総集編 前編".isMediaFormWordOnly())
        assertTrue("劇場版3D".isMediaFormWordOnly())
        // **简体也必须收**: 关联回溯给的"根条目名"常常就是条目自己的中文名, 削出来的是简体 ——
        // 四个不相干的剧场版条目正是这么一起拿到「龙珠剧场版」剧照的
        assertTrue("剧场版".isMediaFormWordOnly())
        assertTrue("总集篇".isMediaFormWordOnly())
        // 真的作品名不能被误判
        assertFalse("ベルセルク".isMediaFormWordOnly())
        assertFalse("進撃の巨人 The Final".isMediaFormWordOnly())
        assertFalse("劇場版 呪術廻戦".isMediaFormWordOnly())
        assertFalse("凡人修仙传".isMediaFormWordOnly())
    }

    @Test
    fun `削季号后缀 - 拉丁尾词逐词去尾靠候选层做, 不归这个函数`() {
        // 「劇場版 Fate/stay night [Heaven's Feel] I.presage flower」没有季号标记,
        // 这个函数原样返回 —— 它的救赎在 searchQueryCandidates 的逐词去尾那层
        // (非 OVA 只去不含日文/中文的尾词). 这里只固定住"这个函数不越权"的边界.
        assertEquals(
            "劇場版 Fate/stay night [Heaven's Feel] I.presage flower",
            tmdbStripSeasonSuffix("劇場版 Fate/stay night [Heaven's Feel] I.presage flower"),
        )
    }

    @Test
    fun `剥版本后缀 - HD 重制 新编集版 导演剪辑版`() {
        // 「機動戦士ガンダムSEED DESTINY HDリマスター」: 整串在 TMDB 直搜 0 结果, 而这类后缀
        // 恰好躲过所有削字规则 (尾词含片假名 → 逐词不去; 末字 "ー" 是假名 → 逐字符立刻停),
        // 于是一个候选都生不出来 = 整条无匹配. 剥掉后命中 tv/20111 (它的 S2 正是 DESTINY).
        assertEquals(
            "機動戦士ガンダムSEED DESTINY",
            tmdbStripVersionSuffix("機動戦士ガンダムSEED DESTINY HDリマスター"),
        )
        assertEquals("機動戦士ガンダムSEED", tmdbStripVersionSuffix("機動戦士ガンダムSEED HDリマスター"))
        assertEquals("Re:ゼロから始める異世界生活", tmdbStripVersionSuffix("Re:ゼロから始める異世界生活 新編集版"))
        assertEquals("PSYCHO-PASS サイコパス", tmdbStripVersionSuffix("PSYCHO-PASS サイコパス 新編集版"))
        assertEquals("人間失格", tmdbStripVersionSuffix("人間失格 ディレクターズカット版"))
        assertEquals("機動戦士ガンダムF91", tmdbStripVersionSuffix("機動戦士ガンダムF91 完全版"))
        // 没有这类后缀的名字原样返回
        assertEquals("進撃の巨人", tmdbStripVersionSuffix("進撃の巨人"))
        assertEquals("機動戦士ガンダムΖΖ", tmdbStripVersionSuffix("機動戦士ガンダムΖΖ"))
    }

    @Test
    fun `季号识别 - 已知的覆盖边界`() {
        // 认得的写法
        assertTrue(tmdbTentativeSeasonHit("進撃の巨人 Season 3", isMovie = true, exactTitle = false))
        assertTrue(tmdbTentativeSeasonHit("ケンガンアシュラ Season2 Part 2", isMovie = true, exactTitle = false))
        assertTrue(tmdbTentativeSeasonHit("Re:ゼロから始める異世界生活 4th Season", isMovie = true, exactTitle = false))
        assertTrue(tmdbTentativeSeasonHit("刃牙道 第2クール", isMovie = true, exactTitle = false))
        assertTrue(tmdbTentativeSeasonHit("7SEEDS 第2期", isMovie = true, exactTitle = false))
        // 序数词 + 片假名混写 (2026-08-21 补)
        assertTrue(
            tmdbTentativeSeasonHit(
                "Fate/stay night [Unlimited Blade Works] 2ndシーズン",
                isMovie = true, exactTitle = false,
            ),
        )
        // **认不得**的写法 (与削字规则同源, 那边也不认): 裸数字季号与罗马数字季号.
        // 固定住这个边界, 免得以后误以为这条规则覆盖了它们.
        assertFalse(tmdbTentativeSeasonHit("有頂天家族2", isMovie = true, exactTitle = false))
        assertFalse(tmdbTentativeSeasonHit("灼眼のシャナII", isMovie = true, exactTitle = false))
    }

    @Test
    fun `同名重制版 - 按年份就近打破平手`() {
        // TMDB 上 うる星やつら 有 1981 与 2022 两个逐字同名的 tv 条目, 两个都是精确命中,
        // 名字里没有任何可区分信息 —— 只能靠年份. 2022 重制版与它的第2期都该取 2022 那个.
        assertTrue(tmdbYearProximity(2022, 2024) < tmdbYearProximity(1981, 2024))
        assertTrue(tmdbYearProximity(2022, 2022) < tmdbYearProximity(1981, 2022))
        // 1981 原版条目仍该取 1981 那个
        assertTrue(tmdbYearProximity(1981, 1981) < tmdbYearProximity(2022, 1981))
        // ゲゲゲの鬼太郎 有六个同名条目 (1968/1971/1985/1996/2007/2018)
        assertTrue(tmdbYearProximity(1996, 1996) < tmdbYearProximity(1968, 1996))
        assertTrue(tmdbYearProximity(2018, 2018) < tmdbYearProximity(2007, 2018))
        // 年份未知的排最后
        assertEquals(Int.MAX_VALUE, tmdbYearProximity(null, 2024))
        assertEquals(Int.MAX_VALUE, tmdbYearProximity(2022, null))
    }

    @Test
    fun `认领本条目对应的季 - 按季首播日`() {
        // tv/56354 みなみけ 的季 (季号 to 首播日)
        val minamike = listOf(
            0 to "2009-06-23", 1 to "2007-10-08", 2 to "2008-01-06",
            3 to "2009-01-04", 4 to "2013-01-06", 5 to null,
        )
        // みなみけ おかわり (bgm 890, 2008-01-06): 13 集全无播出日期, 只能靠认季 + 集号
        assertEquals(2, tmdbOwnSeasonNumber(minamike, "2008-01-06"))
        // みなみけ おかえり (bgm 889, 2009-01-04)
        assertEquals(3, tmdbOwnSeasonNumber(minamike, "2009-01-04"))
        // ±1 天容差: みなみけ (bgm 283) 记 10-07, TMDB 记 10-08
        assertEquals(1, tmdbOwnSeasonNumber(minamike, "2007-10-07"))
        // みなみけ べつばら (bgm 3016, 2009-06-23) 是特别篇, 落在 S0 —— 季名认不出它, 日期能
        assertEquals(0, tmdbOwnSeasonNumber(minamike, "2009-06-23"))
        // 对不上任何一季就放弃 (宁可无图, 认错一季会让整季分集全拿错数据)
        assertEquals(null, tmdbOwnSeasonNumber(minamike, "2013-08-06"))
        assertEquals(null, tmdbOwnSeasonNumber(minamike, null))
        assertEquals(null, tmdbOwnSeasonNumber(emptyList(), "2008-01-06"))
    }

    @Test
    fun `认领本条目对应的季 - 正片优先于特别篇, 含糊则放弃`() {
        // 特别篇常与当季正片同期首播: 两个都落在容差内时取正片
        assertEquals(1, tmdbOwnSeasonNumber(listOf(0 to "2020-04-01", 1 to "2020-04-01"), "2020-04-01"))
        // 两季正片都在容差内 = 数据含糊, 放弃
        assertEquals(null, tmdbOwnSeasonNumber(listOf(1 to "2020-04-01", 2 to "2020-04-02"), "2020-04-01"))
    }

    private fun episode(id: Int, sort: Int, airDate: PackedDate = PackedDate.Invalid) =
        EpisodeCollectionInfo(
            EpisodeInfo(
                episodeId = id,
                type = EpisodeType.MainStory,
                sort = EpisodeSort(sort),
                airDate = airDate,
            ),
            UnifiedCollectionType.NOT_COLLECTED,
        )

    @Test
    fun `单集条目无分集日期 - 用条目开播日命中母番特别篇`() {
        // みなみけ 的三个特别篇在 TMDB 母番 season 0 里, 每一集的 air_date 与 Bangumi 的
        // **条目**日期逐字相同, 而 Bangumi 的**分集**没有日期 —— 原先一张图都拿不到.
        val stills = TmdbEpisodeStills(
            byAirDate = mapOf(
                "2009-06-23" to listOf(TmdbEpisodeMedia(stillUrl = "betsubara")),
                "2012-10-05" to listOf(TmdbEpisodeMedia(stillUrl = "omatase")),
                "2013-08-06" to listOf(TmdbEpisodeMedia(stillUrl = "natsuyasumi")),
            ),
        )
        // 夏やすみ (bgm 80205): 1 集、无日期、条目日期 2013-08-06 → S0E3
        val one = listOf(episode(id = 100, sort = 14))
        assertEquals(
            mapOf(100 to TmdbEpisodeMedia(stillUrl = "natsuyasumi")),
            stills.matchToEpisodes(one, subjectAirDate = "2013-08-06"),
        )
        // べつばら (bgm 3016) 同理
        assertEquals(
            mapOf(100 to TmdbEpisodeMedia(stillUrl = "betsubara")),
            stills.matchToEpisodes(one, subjectAirDate = "2009-06-23"),
        )
        // 不传条目日期时该兜底不生效
        assertEquals(emptyMap(), stills.matchToEpisodes(one))
        // 条目日期对不上任何一集时也不生效
        assertEquals(emptyMap(), stills.matchToEpisodes(one, subjectAirDate = "2011-01-01"))
    }

    @Test
    fun `日期录错的分集 - 按集名精确一致兜底`() {
        // ハイスクールD×D BorN 第 12 集: Bangumi 把日期录成 2016-06-20 (实为 2015-06-20),
        // 按日期落空; 集号兜底只管"完全没有日期"的集; 三明治插值要求前后两个锚点而它是最后一集.
        // 集名是唯一还站得住的证据 —— 两边**原名**逐字相同 (中文名不同译者, 对不上).
        val stills = TmdbEpisodeStills(
            byAirDate = mapOf("2015-06-20" to listOf(TmdbEpisodeMedia(stillUrl = "s3e12"))),
            byEpisodeNumber = mapOf(12 to TmdbEpisodeMedia(stillUrl = "s3e12")),
            byEpisodeName = mapOf("いつでもいつまでも" to TmdbEpisodeMedia(stillUrl = "s3e12")),
        )
        val ep12 = EpisodeCollectionInfo(
            EpisodeInfo(
                episodeId = 12,
                type = EpisodeType.MainStory,
                sort = EpisodeSort(12),
                name = "いつでも、いつまでも！",
                nameCn = "无论何时，无论多久！",
                airDate = PackedDate(2016, 6, 20),
            ),
            UnifiedCollectionType.NOT_COLLECTED,
        )
        assertEquals(
            mapOf(12 to TmdbEpisodeMedia(stillUrl = "s3e12")),
            stills.matchToEpisodes(listOf(ep12)),
        )
        // 集名也对不上时才真的没辙
        val stillsNoName = TmdbEpisodeStills(byAirDate = stills.byAirDate, byEpisodeNumber = stills.byEpisodeNumber)
        assertEquals(emptyMap(), stillsNoName.matchToEpisodes(listOf(ep12)))
    }

    @Test
    fun `多集条目无分集日期 - 不能套条目开播日`() {
        // 否则 13 集会全部去抢同一条 TMDB 数据 (みなみけ おかわり 就是 13 集无日期);
        // 那种情形该由"按季认领 + 集号索引"负责
        val stills = TmdbEpisodeStills(
            byAirDate = mapOf("2008-01-06" to listOf(TmdbEpisodeMedia(stillUrl = "s2e1"))),
        )
        val many = listOf(episode(id = 1, sort = 1), episode(id = 2, sort = 2))
        assertEquals(emptyMap(), stills.matchToEpisodes(many, subjectAirDate = "2008-01-06"))
    }

    // SEED HDリマスター 那一季: 复播条目两边日期差十年, 一个日期锚点都没有, 全靠集名命中;
    // 第 10 集两边差一个字 (Bangumi「分たれた道」少打了 か), 只能靠集号夹出来
    private fun seedStills(highest: Int = 12) = TmdbEpisodeStills(
        byEpisodeNumber = (1..highest).associateWith { TmdbEpisodeMedia(stillUrl = "s1e$it") },
        byEpisodeName = mapOf(
            "消えていく光" to TmdbEpisodeMedia(stillUrl = "s1e9"),
            "分かたれた道" to TmdbEpisodeMedia(stillUrl = "s1e10"),
            "目覚める刃" to TmdbEpisodeMedia(stillUrl = "s1e11"),
        ),
    )

    /**
     * 复播条目的分集: **有日期, 但那个日期在 TMDB 那边根本不存在** (SEED HDリマスター 是 2012 年
     * 复播, TMDB 记的是 2002 年原播). 日期必须给 —— 无日期的话既有的"集号兜底"会先命中,
     * 就测不到集号三明治了.
     */
    private fun named(id: Int, name: String) = EpisodeCollectionInfo(
        EpisodeInfo(
            episodeId = id, type = EpisodeType.MainStory, sort = EpisodeSort(id),
            name = name, airDate = PackedDate(2012, 3, id.coerceIn(1, 28)),
        ),
        UnifiedCollectionType.NOT_COLLECTED,
    )

    @Test
    fun `集号三明治 - 前后两集夹出中间那一集`() {
        val episodes = listOf(
            named(9, "消えていく光"),
            named(10, "分たれた道"), // 与 TMDB 差一个字, 按集名落空
            named(11, "目覚める刃"),
        )
        val matched = seedStills().matchToEpisodes(episodes)
        assertEquals(TmdbEpisodeMedia(stillUrl = "s1e10"), matched[10])
    }

    @Test
    fun `集号三明治 - 中间缺两集就放弃`() {
        // SEED HDリマスター 第 24 集: 重制版把 TMDB 的 25/26 合成了一集, 前后锚点相差 3,
        // 夹不出唯一答案 —— 宁可无图也不能猜
        val stills = TmdbEpisodeStills(
            byEpisodeNumber = (1..30).associateWith { TmdbEpisodeMedia(stillUrl = "s1e$it") },
            byEpisodeName = mapOf(
                "二人だけの戦争" to TmdbEpisodeMedia(stillUrl = "s1e24"),
                "果てなき輪舞" to TmdbEpisodeMedia(stillUrl = "s1e27"),
            ),
        )
        val episodes = listOf(
            named(23, "二人だけの戦争"),
            named(24, "平和の国へ"),
            named(25, "果てなき輪舞"),
        )
        assertNull(stills.matchToEpisodes(episodes)[24])
    }

    @Test
    fun `集号三明治 - 末集靠季边界当锚点, 但季里没有下一集时放弃`() {
        // 前一集锚在季末那一集上 → 差 1 而不是 2 → 放弃 (SEED DESTINY HDリマスター 的
        // 末集「選ばれた未来」在 TMDB S2 里确实没有对应集)
        val stills = TmdbEpisodeStills(
            byEpisodeNumber = (1..12).associateWith { TmdbEpisodeMedia(stillUrl = "s2e$it") },
            byEpisodeName = mapOf("最後の力" to TmdbEpisodeMedia(stillUrl = "s2e12")),
        )
        val episodes = listOf(named(11, "最後の力"), named(12, "選ばれた未来"))
        assertNull(stills.matchToEpisodes(episodes)[12])
        // 单集条目一律不做: 两侧都是季边界 = 没有任何真实锚点
        assertEquals(emptyMap(), stills.matchToEpisodes(listOf(named(1, "誰も知らない話"))))
        // 反之: 前一集锚在倒数第二集上时, 末集就是季末那一集
        val ok = listOf(named(11, "最後の力"), named(12, "終わらない明日へ"))
        val stills2 = TmdbEpisodeStills(
            byEpisodeNumber = (1..12).associateWith { TmdbEpisodeMedia(stillUrl = "s2e$it") },
            byEpisodeName = mapOf("最後の力" to TmdbEpisodeMedia(stillUrl = "s2e11")),
        )
        assertEquals(TmdbEpisodeMedia(stillUrl = "s2e12"), stills2.matchToEpisodes(ok)[12])
    }

    @Test
    fun `年份否决 - 任一侧年份未知就放行`() {
        assertTrue(tmdbYearPlausible(candidateYear = null, subjectYear = 1995, isMovie = false))
        assertTrue(tmdbYearPlausible(candidateYear = 2026, subjectYear = null, isMovie = false))
        assertTrue(tmdbYearPlausible(candidateYear = null, subjectYear = null, isMovie = true))
    }

    // tv/20111 的原语言季名 (zh-CN 下会被本地化成「机动战士高达SEED 第 1 季」, 认不出来)
    private val seedSeasons = listOf(
        0 to "特別編",
        1 to "機動戦士ガンダムSEED",
        2 to "機動戦士ガンダムSEED DESTINY",
    )

    @Test
    fun `按季名认领 - 复播条目剥掉版本后缀后对上原作那一季`() {
        // 两个 HD リマスター 条目的开播日 (2012 / 2013) 离两季首播日 (2002 / 2004) 都很远,
        // 按日期认不出季; 剥掉后缀后季名逐字相同
        assertEquals(1, tmdbSeasonNumberByName(seedSeasons, "機動戦士ガンダムSEED HDリマスター"))
        assertEquals(2, tmdbSeasonNumberByName(seedSeasons, "機動戦士ガンダムSEED DESTINY HDリマスター"))
        // 原条目本身 (没有版本后缀) 也一样认得出来
        assertEquals(2, tmdbSeasonNumberByName(seedSeasons, "機動戦士ガンダムSEED DESTINY"))
    }

    @Test
    fun `按季名认领 - 只认逐字相同, 不做削字`() {
        // **关键的不变量**: 比较的是"条目名剥版本后缀"这一个串, 不是搜索用的削字候选 ——
        // 否则「進撃の巨人 The Final Season」会被削成「進撃の巨人」而认成第 1 季
        assertNull(
            tmdbSeasonNumberByName(
                listOf(1 to "進撃の巨人", 2 to "進撃の巨人 Season 2", 4 to "進撃の巨人 The Final Season"),
                "進撃の巨人 The Final Season 完結編",
            ),
        )
        // 超集/子集都不算
        assertNull(tmdbSeasonNumberByName(seedSeasons, "機動戦士ガンダム"))
        assertNull(tmdbSeasonNumberByName(seedSeasons, "機動戦士ガンダムSEED C.E.73 STARGAZER"))
    }

    @Test
    fun `按季名认领 - 含糊就放弃`() {
        // 同名两季 (TMDB 偶有重复录入) 认不出是哪一季, 认错一季等于整季拿错图
        assertNull(tmdbSeasonNumberByName(listOf(1 to "みなみけ", 3 to "みなみけ"), "みなみけ"))
        // 没有独立命名的季叫「シーズンN」/「Season N」, 不会撞上条目名
        assertNull(tmdbSeasonNumberByName(listOf(1 to "シーズン1", 2 to "シーズン2"), "みなみけ"))
        // 季名缺失
        assertNull(tmdbSeasonNumberByName(listOf(1 to null), "みなみけ"))
    }

    @Test
    fun `集名索引 - 去掉假名注音括号`() {
        // 实测 SEED: 两边都可能给汉字标读音, 标的位置还不一样
        assertEquals(tmdbEpisodeNameKey("宇宙の傷跡"), tmdbEpisodeNameKey("宇宙(そら)の傷跡"))
        assertEquals(tmdbEpisodeNameKey("舞い降りる剣"), tmdbEpisodeNameKey("舞い降りる剣（つるぎ）"))
        assertEquals(tmdbEpisodeNameKey("暁の宇宙(そら)へ"), tmdbEpisodeNameKey("暁の宇宙へ（そらへ）"))
    }

    @Test
    fun `集名索引 - 括号里不全是假名就不动`() {
        // 「(前編)」这类括号是真内容: 去掉会让 前編/後編 撞成同一个键, 撞键的会被整条丢弃
        assertTrue(tmdbEpisodeNameKey("救われるラミリス 前編") != tmdbEpisodeNameKey("救われるラミリス(後編)"))
        assertTrue(tmdbEpisodeNameKey("いつか(TV)") != tmdbEpisodeNameKey("いつか"))
        // 普通集名原样归一化
        assertEquals(tmdbEpisodeNameKey("偽りの平和"), tmdbEpisodeNameKey("偽りの平和"))
        assertEquals("", tmdbEpisodeNameKey("（そら）"))
    }

    @Test
    fun `空壳条目判据 - 只有占位时长不算有图`() {
        // TMDB 上版本条目常是空壳: 分集只有集号与 runtime, 一张剧照都没有 (如 tv/332355)
        val stub = TmdbEpisodeStills(
            byAirDate = mapOf("2012-01-01" to listOf(TmdbEpisodeMedia(runtimeMinutes = 25))),
            byEpisodeNumber = mapOf(1 to TmdbEpisodeMedia(runtimeMinutes = 25)),
        )
        assertFalse(stub.hasAnyStill())
        assertFalse(stub.isEmpty()) // 注意: 它不是"空"的, 所以光靠 isEmpty 认不出来
        assertTrue(
            TmdbEpisodeStills(byAirDate = mapOf("2002-10-05" to listOf(TmdbEpisodeMedia(stillUrl = "s1e1"))))
                .hasAnyStill(),
        )
    }
    /**
     * 同日两季的构造: S0 是与正片逐集同日的短篇/占位壳, [s0Stills] 决定它有没有图
     * (实测两种都有: tv/283880 的 S0 零剧照, tv/271003 的 S0 有整套ミニアニメ剧照).
     * 出处按"正片在后"给 —— 那正是 `isDerivative` 为 null 时的真实入索引顺序.
     */
    private fun collidingSeasons(
        dates: List<String>,
        mainNames: List<String>,
        specialNames: List<String>,
        s0Stills: Boolean = false,
    ) = TmdbEpisodeStills(
        byAirDate = dates.indices.associate { i ->
            dates[i] to listOf(
                TmdbEpisodeMedia(stillUrl = if (s0Stills) "s0e${i + 1}" else null, runtimeMinutes = 12),
                TmdbEpisodeMedia(stillUrl = "s1e${i + 1}", runtimeMinutes = 12),
            )
        },
        byAirDateOrigin = dates.indices.associate { i ->
            dates[i] to listOf(
                TmdbEpisodeOrigin(0, tmdbEpisodeSegmentKey(specialNames[i])),
                TmdbEpisodeOrigin(1, tmdbEpisodeSegmentKey(mainNames[i])),
            )
        },
    )

    private fun dated(id: Int, name: String, date: PackedDate) = EpisodeCollectionInfo(
        EpisodeInfo(
            episodeId = id, type = EpisodeType.MainStory, sort = EpisodeSort(id),
            name = name, airDate = date,
        ),
        UnifiedCollectionType.NOT_COLLECTED,
    )

    @Test
    fun `同日两季 - 按集名投票认下正片那一季`() {
        // さわらないで小手指くん (bgm 541547 / tv 283880): S0 是 12 集占位壳 (零剧照) 与正片逐集同日,
        // Bangumi 日期比 TMDB 早一天. S0 排在前面时 12 集全拿到空数据 (有时长、没图).
        val stills = collidingSeasons(
            dates = listOf("2025-10-06", "2025-10-13"),
            mainNames = listOf("さわらないで小手指くん／ミニアニメ劇場　その１", "何しに来たんだ あおばちゃん②"),
            specialNames = listOf("第1話", "第2話"),
        )
        val episodes = listOf(
            dated(1, "さわらないで小手指くん", PackedDate(2025, 10, 5)),
            dated(2, "何しに来たんだ あおばちゃん②", PackedDate(2025, 10, 12)),
        )
        val matched = stills.matchToEpisodes(episodes)
        // 第 2 集集名逐字相同 -> 认下 S1; 第 1 集只对上首段, 也跟着认下的那一季走
        assertEquals("s1e1", matched[1]?.stillUrl)
        assertEquals("s1e2", matched[2]?.stillUrl)
    }

    @Test
    fun `同日两季 - S0 有整套剧照时同样按集名认季`() {
        // ちょっとだけ愛が重いダークエルフ (bgm 511264 / tv 271003): S0 是有整套剧照的ミニアニメ,
        // 光看"有没有图"分不出两季 —— 原先每集显示的是短篇的图, 静默错图.
        // 这里两边并段方向相反: Bangumi 把两段并进一个集名, TMDB 正片季只有前半.
        val stills = collidingSeasons(
            dates = listOf("2025-04-07", "2025-04-14"),
            mainNames = listOf("異世界で出会った女の子", "つうがく"),
            specialNames = listOf("ミニアニメ劇場 その1", "ミニアニメ劇場 その2"),
            s0Stills = true,
        )
        val episodes = listOf(
            dated(1, "異世界で出会った女の子／ミニアニメ劇場 その1", PackedDate(2025, 4, 6)),
            dated(2, "つうがく／ミニアニメ劇場 その2", PackedDate(2025, 4, 13)),
        )
        val matched = stills.matchToEpisodes(episodes)
        assertEquals("s1e1", matched[1]?.stillUrl)
        assertEquals("s1e2", matched[2]?.stillUrl)
    }

    @Test
    fun `同日两季 - 一票都投不出来时维持原顺序`() {
        // 集名只用来"确认"不用来"否证": 两边名字全对不上 (译名/副标题差异) 时什么都不做,
        // 仍按"当日第几集"取第一个候选 —— 否则就是拿另一种猜法换掉现有猜法.
        val stills = collidingSeasons(
            dates = listOf("2025-10-06"),
            mainNames = listOf("正片第一集"),
            specialNames = listOf("短篇第一集"),
            s0Stills = true,
        )
        val matched = stills.matchToEpisodes(listOf(dated(1, "两边都对不上的译名", PackedDate(2025, 10, 6))))
        assertEquals("s0e1", matched[1]?.stillUrl)
    }

    @Test
    fun `同日两季 - 认下 S0 的衍生条目照样取 S0`() {
        // 反向: 归并到本篇的衍生条目 (它的集名与 S0 对得上) 必须仍然拿 S0 的数据,
        // 不能被"正片优先"之类的固定顺序抢走.
        val stills = collidingSeasons(
            dates = listOf("2025-10-06", "2025-10-13"),
            mainNames = listOf("正片第一集", "正片第二集"),
            specialNames = listOf("ミニアニメ劇場 その1", "ミニアニメ劇場 その2"),
            s0Stills = true,
        )
        val episodes = listOf(
            dated(1, "ミニアニメ劇場 その1", PackedDate(2025, 10, 6)),
            dated(2, "ミニアニメ劇場 その2", PackedDate(2025, 10, 13)),
        )
        val matched = stills.matchToEpisodes(episodes)
        assertEquals("s0e1", matched[1]?.stillUrl)
        assertEquals("s0e2", matched[2]?.stillUrl)
    }

    @Test
    fun `同日两季 - 旧缓存没有出处时不受影响`() {
        // byAirDateOrigin 是新加的字段, 旧缓存反序列化出来是空的: 投票拿不到票, 走原口径.
        val base = collidingSeasons(
            dates = listOf("2025-10-06"),
            mainNames = listOf("さわらないで小手指くん"),
            specialNames = listOf("第1話"),
            s0Stills = true,
        )
        val legacy = TmdbEpisodeStills(byAirDate = base.byAirDate)
        assertEquals(
            "s0e1",
            legacy.matchToEpisodes(listOf(dated(1, "さわらないで小手指くん", PackedDate(2025, 10, 6))))[1]?.stillUrl,
        )
        // 出处与当日列表对不齐 (残缺缓存) 时同样退回原口径, 不能按错位的下标去筛
        val misaligned = TmdbEpisodeStills(
            byAirDate = base.byAirDate,
            byAirDateOrigin = mapOf("2025-10-06" to listOf(TmdbEpisodeOrigin(1, "さわらないで小手指くん"))),
        )
        assertEquals(
            "s0e1",
            misaligned.matchToEpisodes(listOf(dated(1, "さわらないで小手指くん", PackedDate(2025, 10, 6))))[1]?.stillUrl,
        )
    }

    @Test
    fun `同日两季 - 同日双集连播的对位不被收窄打乱`() {
        // 同一天两集正片 (無職転生Ⅲ 第1+2话) + 同日一集 S0: 收窄到认下的季之后,
        // "当日第几集"必须在收窄后的列表里数, 否则第 2 集会错位.
        val stills = TmdbEpisodeStills(
            byAirDate = mapOf(
                "2024-04-07" to listOf(
                    TmdbEpisodeMedia(stillUrl = "s0e1"),
                    TmdbEpisodeMedia(stillUrl = "s1e1"),
                    TmdbEpisodeMedia(stillUrl = "s1e2"),
                ),
            ),
            byAirDateOrigin = mapOf(
                "2024-04-07" to listOf(
                    TmdbEpisodeOrigin(0, tmdbEpisodeSegmentKey("ミニアニメ その1")),
                    TmdbEpisodeOrigin(1, tmdbEpisodeSegmentKey("転生")),
                    TmdbEpisodeOrigin(1, tmdbEpisodeSegmentKey("再会")),
                ),
            ),
        )
        val matched = stills.matchToEpisodes(
            listOf(
                dated(1, "転生", PackedDate(2024, 4, 7)),
                dated(2, "再会", PackedDate(2024, 4, 7)),
            ),
        )
        assertEquals("s1e1", matched[1]?.stillUrl)
        assertEquals("s1e2", matched[2]?.stillUrl)
    }

    @Test
    fun `集名首段 - 只给投票用, 不动集名索引那条兜底`() {
        // 分隔符前面那段是正片, 两种并段方向都能对上
        assertEquals(
            tmdbEpisodeSegmentKey("さわらないで小手指くん"),
            tmdbEpisodeSegmentKey("さわらないで小手指くん／ミニアニメ劇場　その１"),
        )
        assertEquals(
            tmdbEpisodeSegmentKey("ミニアニメ"),
            tmdbEpisodeSegmentKey("ミニアニメ ｜第１話「予測不能の求婚者」"),
        )
        // 没有分隔符时与 tmdbEpisodeNameKey 一致 (注音括号照样去掉)
        assertEquals(tmdbEpisodeNameKey("宇宙の傷跡"), tmdbEpisodeSegmentKey("宇宙(そら)の傷跡"))
        // 集名索引那条**不切段**: 切了会让「A／その1」「A／その2」撞键, 而撞键整条弃用
        assertTrue(tmdbEpisodeNameKey("結末／その1") != tmdbEpisodeNameKey("結末／その2"))
    }
}
