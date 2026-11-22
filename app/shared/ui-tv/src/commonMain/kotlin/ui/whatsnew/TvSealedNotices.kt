/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.whatsnew

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import me.him188.ani.app.data.network.SealedNoticesRepository
import org.koin.mp.KoinPlatform
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * 加密的应用内通知 (见 [SealedNoticesRepository]): 给当前版本的几句, 一句一行原文 (写法见 TvNoticeMarkup).
 * 只有通知中心读它 (见 TvReleaseNotice); release 正文里的「## 通知」只给没有这套代码的旧版 (1.0.9-alpha04 及以前).
 */
internal object TvSealedNotices {
    /** 冷启动时最多等这次的检查 (取或判定不用取) 这么久, 再用现在的那份. */
    private val FIRST_CHECK_WAIT = 5.seconds

    private val repository: SealedNoticesRepository?
        get() = runCatching { KoinPlatform.getKoin().get<SealedNoticesRepository>() }.getOrNull()

    /** 给 [version] 的加密通知 (冷启动这次的检查做完以后的那份); 没有、没配钥匙时为空. */
    suspend fun forVersion(version: String): List<String> =
        repository?.currentFor(version, FIRST_CHECK_WAIT).orEmpty().mapNotNull(::cleanLine)

    /** 给 [version] 的加密通知, 之后取到新的再给 (运行中发的通知也收得到). */
    fun flowFor(version: String): Flow<List<String>> =
        repository?.noticesFor(version)?.map { lines -> lines.mapNotNull(::cleanLine) } ?: emptyFlow()

    /** 离上次取超过 [age] 就现在取一次 (见 [SealedNoticesRepository.refreshIfOlderThan]). */
    suspend fun refreshIfOlderThan(age: Duration) {
        repository?.refreshIfOlderThan(age)
    }

    // 写法里的「- 」列表记号可写可不写
    private fun cleanLine(line: String): String? = line.trim().removePrefix("- ").trim().ifEmpty { null }
}
