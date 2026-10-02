/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.rule

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.him188.ani.app.data.repository.RepositoryNetworkException
import me.him188.ani.app.domain.mediasource.codec.ExportedMediaSourceData
import me.him188.ani.app.domain.mediasource.codec.MediaSourceCodecManager
import me.him188.ani.app.domain.mediasource.subscription.SubscriptionUpdateData
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger

/**
 * 订阅地址填的是 AniBaka 的规则库索引 (或一条规则) 时: 每次更新订阅都重新下载并转换成规则源, 再交给订阅原有的增删改流程,
 * 于是定期更新、整组启停、单个源的启停保留都与普通订阅一样.
 *
 * 订阅按名字比对, 这次缺了的源会被删掉、下次再新建, 用户对它的启停设置就丢了. 所以:
 * - 规则文件有没下载下来的, 这次更新整个算失败, 原有的源保持不动.
 * - 转换不了的规则 (用到这个版本还不认得的步骤或选项) 记成「要新版才认得」的数据源 ([unsupportedEntry]):
 *   订阅更新对这种数据源保留本地已有的那个 (上次转换成功的版本), 没有就不建. 规则库改了写法而导入器还没跟上时, 已有的源照旧可用.
 */
object AniBakaSubscription {
    private val logger = logger<AniBakaSubscription>()

    /**
     * [content] 是从订阅地址 [url] 下载到的内容; 是 AniBaka 规则时转换成订阅数据, 不是时返回 `null`.
     * [download] 用来下载规则库索引里列出的规则文件.
     *
     * @throws RepositoryNetworkException 有规则文件没下载下来
     */
    suspend fun decodeOrNull(content: String, url: String, download: suspend (url: String) -> String): SubscriptionUpdateData? {
        val result = AniBakaRuleImporter.importContent(content, url, download) ?: return null
        if (result.downloadFailed) {
            throw RepositoryNetworkException("Some AniBaka rules could not be downloaded: ${result.failures.joinToString("; ")}")
        }
        if (result.failures.isNotEmpty()) {
            logger.info { "AniBaka subscription $url: ${result.failures.size} rules unsupported, keeping existing ones: ${result.failures.joinToString("; ")}" }
        }
        return SubscriptionUpdateData(
            mediaSources = result.converted.map { converted ->
                when (converted) {
                    is AniBakaRuleImporter.Converted.Ok -> ExportedMediaSourceData(
                        RuleMediaSource.FactoryId,
                        RuleMediaSourceCodec.currentVersion,
                        MediaSourceCodecManager.json.encodeToJsonElement(RuleMediaSourceArguments.serializer(), converted.arguments),
                    )

                    is AniBakaRuleImporter.Converted.Unsupported -> unsupportedEntry(converted.name)
                }
            },
        )
    }

    /**
     * 转换不了的规则在订阅数据里的样子: 版本比这个客户端认得的高一档, 只有名字. 订阅更新解码时认它为「要新版才认得」,
     * 按名字对上本地已有的就原样保留, 不删也不改.
     */
    internal fun unsupportedEntry(name: String): ExportedMediaSourceData = ExportedMediaSourceData(
        RuleMediaSource.FactoryId,
        RuleMediaSourceCodec.currentVersion + 1,
        buildJsonObject { put("name", name) },
    )
}
