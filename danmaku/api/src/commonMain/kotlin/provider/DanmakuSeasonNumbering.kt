/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.danmaku.api.provider

/**
 * 这一集在整季里的编号. Bangumi 把一季拆成几个条目 (如「第四季 丧失篇」与「第四季 夺还篇」) 时, 弹幕库常把整季当一部作品、
 * 接着编集号 (夺还篇第 7 集是整季第 18 集); 也有只收这一段、从第 1 集编起的.
 *
 * @property seasonEpisode 这一集是整季第几集
 * @property partEpisodeCount 这一集所在那一段 (Bangumi 条目) 的集数. 弹幕库那部作品的集号都不超过它 (容许多两集) 时,
 * 那部作品只收这一段、按段编号.
 */
class DanmakuSeasonNumbering(
    val seasonEpisode: Int,
    val partEpisodeCount: Int,
)
