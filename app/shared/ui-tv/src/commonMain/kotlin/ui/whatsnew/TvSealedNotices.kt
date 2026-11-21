/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.whatsnew

import kotlinx.coroutines.CancellationException
import me.him188.ani.app.data.network.SealedNoticesRepository
import me.him188.ani.app.ui.update.ReleaseNoteItem
import me.him188.ani.app.ui.update.parseReleaseHighlights
import org.koin.mp.KoinPlatform

/**
 * 加密的应用内通知 (见 [SealedNoticesRepository]): 给当前版本的几句, 拆成与 release 说明「## 通知」一样的条目.
 * 通知中心、「本版新功能」页、设置里那一页的通知都只读它; release 正文里的「## 通知」只给没有这套代码的旧版 (1.0.9-alpha04 及以前).
 */
internal object TvSealedNotices {
    /** 给 [version] 的加密通知; 没有、取不到 (也没存过)、没配钥匙时为空. */
    suspend fun forVersion(version: String): List<ReleaseNoteItem> {
        val lines = try {
            KoinPlatform.getKoin().get<SealedNoticesRepository>().noticesFor(version)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
        if (lines.isEmpty()) return emptyList()
        // 拼成「## 通知」一节交给同一个解析: 「标题：说明」的拆法与 release 说明里的一致
        val body = "## 通知\n" + lines.joinToString("\n") { "- $it" }
        return parseReleaseHighlights(version, body)?.notice.orEmpty()
    }
}
