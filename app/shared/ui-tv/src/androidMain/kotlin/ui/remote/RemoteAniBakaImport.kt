/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.remote

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import me.him188.ani.app.domain.foundation.HttpClientProvider
import me.him188.ani.app.domain.foundation.get
import me.him188.ani.app.domain.mediasource.rule.AniBakaRuleImporter
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import org.koin.mp.KoinPlatform
import kotlin.coroutines.cancellation.CancellationException

/**
 * 控制台导入里的 AniBaka 规则: 粘贴规则 JSON, 或规则 / 规则库索引的地址 (由电视下载).
 */
internal object RemoteAniBakaImport {
    private val logger = logger<RemoteAniBakaImport>()
    private val httpClientProvider: HttpClientProvider get() = KoinPlatform.getKoin().get()
    private val client by lazy { httpClientProvider.get() }

    /** 不是 AniBaka 规则时返回 `null`. */
    fun importOrNull(text: String): AniBakaRuleImporter.ImportResult? = runBlocking {
        try {
            AniBakaRuleImporter.import(text) { url -> client.use { get(url).bodyAsText() } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 只有贴的是地址时才会走到这里 (下载失败)
            logger.warn(e) { "Failed to download AniBaka rules from $text" }
            AniBakaRuleImporter.ImportResult(emptyList(), listOf("下载失败: ${e.message ?: e::class.simpleName}"))
        }
    }
}
