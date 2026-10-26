/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.models.episode

/**
 * 一集的时长 ([EpisodeInfo.durationSeconds]) 与资源的时长比对.
 *
 * 资源比这一集短得多或长得多 (短于 [MIN_RATIO] 倍、长于 [MAX_RATIO] 倍) 就不是这一集: 同名的剧场版、真人电影、短剧、预告.
 * 网盘列文件时就知道时长, 选源时去掉; 别的资源播放器打开后才知道, 那时换一条 (见 `observeEpisodeDurationMismatch`).
 * 正常的出入都在范围里: 剪掉片头片尾、BD 加长、两集合在一个文件里.
 */
object EpisodeDurations {
    /** 资源短于这一集的这么多倍就不是这一集 (短剧 2 分钟之于 24 分钟的一集) */
    const val MIN_RATIO = 0.4

    /** 资源长于这一集的这么多倍就不是这一集 (112 分钟的真人电影之于 25 分钟的一集) */
    const val MAX_RATIO = 2.5

    /** 资源时长 [actualSeconds] 与这一集的时长 [expectedSeconds] 差得离谱; 任一边不知道 (`null` 或不大于 0) 时为 `false`. */
    fun isMismatch(actualSeconds: Long, expectedSeconds: Int?): Boolean {
        if (actualSeconds <= 0 || expectedSeconds == null || expectedSeconds <= 0) return false
        val ratio = actualSeconds.toDouble() / expectedSeconds
        return ratio < MIN_RATIO || ratio > MAX_RATIO
    }

    /**
     * Bangumi 维基填的时长 (`00:24:10`、`24:10`、`24分钟`、`24m`) 换成秒; 认不出或为 0 时为 `null`.
     * 分集列表接口另给了服务器解析好的秒数, 单集详情接口只有这个文本.
     */
    fun parse(text: String): Int? {
        val trimmed = text.trim()
        CLOCK.matchEntire(trimmed)?.let { match ->
            val parts = match.value.split(':').map { it.toInt() }
            val seconds = parts.fold(0) { total, part -> total * 60 + part }
            return seconds.takeIf { it > 0 }
        }
        MINUTES.matchEntire(trimmed)?.let { return (it.groupValues[1].toInt() * 60).takeIf { seconds -> seconds > 0 } }
        return null
    }

    private val CLOCK = Regex("""\d{1,2}(?::\d{1,2}){1,2}""")
    private val MINUTES = Regex("""(\d{1,3})\s*(?:分钟|分|min|m)""", RegexOption.IGNORE_CASE)
}
