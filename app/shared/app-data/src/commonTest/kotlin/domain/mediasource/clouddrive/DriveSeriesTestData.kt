/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.clouddrive

import me.him188.ani.app.data.network.SeriesGraphTable
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.source.MediaFetchRequest

/**
 * 网盘认部的测试用的系列关系图与查询请求, 取自真实数据 (2026-10): 死神千年血战篇各部与 2004 年的 TV 版、辉夜、鬼灭、我独自升级、Re:Zero、
 * 浪客剑心、间谍过家家、进击的巨人最终季各段、石纪元科学与未来各段.
 */
internal object DriveSeriesTestData {
    val table = SeriesGraphTable.parse(
        listOf(
            "# bangumi-series-graph v1 max_id=714439 dump=dump-2026-10-20.210332Z.zip | 列: …",
            "1600\tBLEACH\t死神\t2004-10-05\t366\tTV\t\t\t302286\t\t",
            "302286\tBLEACH 千年血戦篇\t死神 千年血战篇\t2022-10-10\t13\tTV\t\t1600\t412916\t\t",
            "412916\tBLEACH 千年血戦篇-訣別譚-\t死神 千年血战篇-诀别谭-\t2023-07-08\t13\tTV\t\t302286\t457326\t14\t1",
            "457326\tBLEACH 千年血戦篇-相剋譚-\t死神 千年血战篇-相克谭-\t2024-10-05\t14\tTV\t\t412916\t530725\t27\t",
            "530725\tBLEACH 千年血戦篇-禍進譚-\t死神 千年血战篇-祸进谭-\t2026-07-25\t10\tTV\t\t457326\t\t41\t",
            "248175\tかぐや様は告らせたい～天才たちの恋愛頭脳戦～\t辉夜大小姐想让我告白～天才们的恋爱头脑战～\t2019-01-12\t12\tTV\t\t\t293049\t\t",
            "293049\tかぐや様は告らせたい?～天才たちの恋愛頭脳戦～\t辉夜大小姐想让我告白?～天才们的恋爱头脑战～\t2020-04-11\t12\tTV\t\t248175\t317613\t\t",
            "317613\tかぐや様は告らせたい-ウルトラロマンティック-\t辉夜大小姐想让我告白-超级浪漫-\t2022-04-08\t13\tTV\t\t293049\t389474,425211\t\t",
            "389474\tかぐや様は告らせたい-ファーストキッスは終わらない-\t辉夜大小姐想让我告白-初吻不会结束-\t2022-12-17\t1\t剧场版\t\t317613\t\t\t",
            "425211\tかぐや様は告らせたい-ファーストキッスは終わらない-\t辉夜大小姐想让我告白-初吻不会结束-\t2023-03-31\t4\tTV\t\t317613\t\t\t",
            "245665\t鬼滅の刃\t鬼灭之刃\t2019-04-06\t26\tTV\t\t\t350764\t\t",
            "317002\t鬼滅の刃 那田蜘蛛山編\t鬼灭之刃 那田蜘蛛山篇\t2020-10-17\t1\tTV\t\t\t322102\t\t",
            "322102\t鬼滅の刃 柱合会議・蝶屋敷編\t鬼灭之刃 柱合会议・蝶屋敷篇\t2020-12-20\t1\tTV\t\t317002\t350764\t\t",
            "350764\t鬼滅の刃 無限列車編\t鬼灭之刃 无限列车篇\t2021-10-10\t7\tTV\t\t245665,322102\t328195,422759\t\t",
            "328195\t鬼滅の刃 遊郭編\t鬼灭之刃 游郭篇\t2021-12-05\t11\tTV\t\t350764\t\t\t",
            "422759\t鬼滅の刃 遊郭編 特別編集版\t鬼灭之刃 游郭篇 特別编集版\t2023-04-01\t2\tTV\t\t350764\t\t\t",
            "390353\t俺だけレベルアップな件\t我独自升级\t2024-01-06\t12\tTV\t\t\t487630\t\t1",
            "487630\t俺だけレベルアップな件 Season 2 -Arise from the Shadow-\t我独自升级 第二季 -起于暗影-\t2025-01-04\t13\tTV\t\t390353\t\t13\t",
            "140001\tRe:ゼロから始める異世界生活\tRe：从零开始的异世界生活\t2016-04-03\t25\tTV\t\t\t278826\t\t",
            "278826\tRe:ゼロから始める異世界生活 2nd season\tRe：从零开始的异世界生活 第二季\t2020-07-08\t13\tTV\t\t140001\t425998\t26\t",
            "425998\tRe:ゼロから始める異世界生活 3rd season 襲擊編\tRe：从零开始的异世界生活 第三季 袭击篇\t2024-10-02\t8\tTV\t\t278826\t510728\t51\t",
            "510728\tRe:ゼロから始める異世界生活 3rd season 反擊編\tRe：从零开始的异世界生活 第三季 反击篇\t2025-02-05\t8\tTV\t\t425998\t\t59\t",
            "363102\tるろうに剣心 －明治剣客浪漫譚－\t浪客剑心 －明治剑客浪漫谭－\t2023-07-06\t24\tTV\t\t\t470599\t\t",
            "470599\tるろうに剣心 －明治剣客浪漫譚－ 京都動乱\t浪客剑心 －明治剑客浪漫谭－ 京都动乱\t2024-10-03\t23\tTV\t\t363102\t\t25\t",
            "329906\tSPY×FAMILY\t间谍过家家\t2022-04-09\t12\tTV\t\t\t373267\t\t",
            "373267\tSPY×FAMILY 第2クール\t间谍过家家 第2部分\t2022-10-01\t13\tTV\t\t329906\t\t13\t1",
            "263750\t進撃の巨人 Season 3 Part.2\t进击的巨人 第三季 Part.2\t2019-04-28\t10\tTV\t\t\t285666\t50\t",
            "285666\t進撃の巨人 The Final Season\t进击的巨人 最终季\t2020-12-06\t16\tTV\t\t263750\t331752\t60\t",
            "331752\t進撃の巨人 The Final Season Part.2\t进击的巨人 最终季 Part.2\t2022-01-09\t12\tTV\t\t285666\t376739\t76\t",
            "376739\t進撃の巨人 The Final Season 完結編 前編\t进击的巨人 最终季 完结篇 前篇\t2023-03-03\t1\tTV\t\t331752\t415779\t\t",
            "415779\t進撃の巨人 The Final Season 完結編 後編\t进击的巨人 最终季 完结篇 后篇\t2023-11-04\t1\tTV\t\t376739\t\t\t",
            "424372\tDr.STONE NEW WORLD 第2クール\t石纪元 新世界 第2部分\t2023-10-12\t11\tTV\t\t\t471578\t12\t",
            "471578\tDr.STONE SCIENCE FUTURE\t石纪元 科学与未来\t2025-01-09\t12\tTV\t\t424372\t508003\t\t",
            "508003\tDr.STONE SCIENCE FUTURE 第2クール\t石纪元 科学与未来 第2部分\t2025-07-10\t12\tTV\t\t471578\t508004\t13\t",
            "508004\tDr.STONE SCIENCE FUTURE 第3クール\t石纪元 科学与未来 第3部分\t2026-04-02\t13\tTV\t\t508003\t\t25\t",
        ).joinToString("\n").encodeToByteArray(),
    )!!

    /**
     * 条目 [subjectId] 的第 [ep] 集: 本篇 [count] 集, 系列连续集号 (sort) 从 [firstSort] 起, 条目内序号 (ep) 从 1 起.
     */
    fun request(subjectId: Int, names: List<String>, firstSort: Int, count: Int, ep: Int = 1) = MediaFetchRequest(
        subjectId = subjectId.toString(),
        episodeId = "e$ep",
        subjectNames = names,
        episodeSort = EpisodeSort(firstSort + ep - 1),
        episodeEp = EpisodeSort(ep),
        episodeName = "",
        episodes = (1..count).map { MediaFetchRequest.Episode("e$it", EpisodeSort(firstSort + it - 1), EpisodeSort(it)) },
    )

    val separation = request(412916, listOf("死神 千年血战篇-诀别谭-", "BLEACH 千年血戦篇-訣別譚-"), 14, 13, ep = 2)
    val conflict = request(457326, listOf("死神 千年血战篇-相克谭-", "BLEACH 千年血戦篇-相剋譚-"), 27, 14)
    val calamity = request(530725, listOf("死神 千年血战篇-祸进谭-", "BLEACH 千年血戦篇-禍進譚-"), 41, 10)
    val kyoto = request(470599, listOf("浪客剑心 －明治剑客浪漫谭－ 京都动乱", "るろうに剣心 －明治剣客浪漫譚－ 京都動乱"), 25, 23)
    val soloLeveling2 = request(487630, listOf("我独自升级 第二季 -起于暗影-", "俺だけレベルアップな件 Season 2 -Arise from the Shadow-"), 13, 13)
    val counterattack = request(510728, listOf("Re：从零开始的异世界生活 第三季 反击篇", "Re:ゼロから始める異世界生活 3rd season 反擊編"), 59, 8)
    val finalSeason = request(285666, listOf("进击的巨人 最终季", "進撃の巨人 The Final Season"), 60, 16)
    val scienceFuture2 = request(508003, listOf("石纪元 科学与未来 第2部分", "Dr.STONE SCIENCE FUTURE 第2クール"), 13, 12)
}
