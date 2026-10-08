/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.rule

import me.him188.ani.app.data.repository.RepositoryNetworkException
import me.him188.ani.app.domain.mediasource.codec.ExportedMediaSourceData
import me.him188.ani.app.domain.mediasource.codec.ExportedMediaSourceDataList
import me.him188.ani.app.domain.mediasource.codec.MediaSourceCodecManager
import me.him188.ani.app.domain.mediasource.subscription.SubscriptionUpdateData
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger

/**
 * 订阅地址填的是 AniBaka 的规则库索引 (或一条规则) 时: 每次更新订阅都重新下载并转换成规则源, 再交给订阅原有的增删改流程,
 * 于是定期更新、整组启停、单个源的启停保留都与普通订阅一样.
 *
 * 规则文件有没下载下来的, 这次更新整个算失败, 原有的源保持不动: 订阅按名字比对, 这次缺了的源会被删掉、下次再新建,
 * 用户对它的启停设置就丢了. 转换不了的规则 (用到规则源没有的步骤) 每次结果都一样, 跳过并记日志.
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
            logger.info { "AniBaka subscription $url: skipped ${result.failures.size} rules: ${result.failures.joinToString("; ")}" }
        }
        return SubscriptionUpdateData(
            ExportedMediaSourceDataList(
                result.sources.map { arguments ->
                    ExportedMediaSourceData(
                        RuleMediaSource.FactoryId,
                        RuleMediaSourceCodec.currentVersion,
                        MediaSourceCodecManager.json.encodeToJsonElement(RuleMediaSourceArguments.serializer(), arguments),
                    )
                },
            ),
        )
    }
}
