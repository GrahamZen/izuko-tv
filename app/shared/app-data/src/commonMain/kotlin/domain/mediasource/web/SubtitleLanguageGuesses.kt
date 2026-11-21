/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.web

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import me.him188.ani.datasources.api.topic.SubtitleLanguage
import me.him188.ani.datasources.api.topic.titles.LabelFirstRawTitleParser

/**
 * 网页源从线路名、集名猜字幕语言 ([LabelFirstRawTitleParser.parseSubtitleLanguages]) 的结果.
 * 每个视频都要把这两个名字整个解析一遍, 而同一页上的线路名都一样, 集名 (「第01集」) 在各个源之间也大多相同.
 */
internal object SubtitleLanguageGuesses {
    private const val SIZE = 2048
    private val lock = SynchronizedObject()
    /** 按最近用过排序: 命中就挪到末尾, 超出 [SIZE] 从头上淘汰. */
    private val guesses = LinkedHashMap<String, List<SubtitleLanguage>>()

    fun of(parser: LabelFirstRawTitleParser, text: String): List<SubtitleLanguage> {
        synchronized(lock) { guesses.remove(text)?.also { guesses[text] = it } }?.let { return it }
        val result = parser.parseSubtitleLanguages(text)
        synchronized(lock) {
            guesses[text] = result
            if (guesses.size > SIZE) guesses.remove(guesses.keys.first())
        }
        return result
    }
}
