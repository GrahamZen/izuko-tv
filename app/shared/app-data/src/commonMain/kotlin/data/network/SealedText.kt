/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.network

import me.him188.ani.app.domain.together.platformAesGcmOpen
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * 放在公开地方、只给装了应用的人看的密文 (加密的应用配置、加密的通知): `v1.<nonce>.<密文>`, 两段都是 base64url (不补 `=`);
 * AES-256-GCM, 钥匙是编进包里的 [appConfigKey][me.him188.ani.app.platform.AniBuildConfig.appConfigKey], 关联数据是用途 ([Purpose]) ——
 * 同一把钥匙加密的两种东西互相顶替不了. 与 `scripts/sealed_text.py`、build-logic 的 `appConfig.kt` 同一个格式, 改了一起改.
 */
@OptIn(ExperimentalEncodingApi::class)
internal object SealedText {
    enum class Purpose(val associatedData: String) {
        APP_CONFIG("izuko-app-config/v1"),
        NOTICES("izuko-notices/v1"),
    }

    private const val VERSION = "v1"
    private val base64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)

    /** 解开 [token] 得到明文; 格式不对、钥匙不对、用途不对、被改过时为 `null`. */
    fun open(token: String, key: String, purpose: Purpose): String? {
        val parts = token.trim().split('.')
        if (parts.size != 3 || parts[0] != VERSION) return null
        return runCatching {
            platformAesGcmOpen(base64.decode(key), base64.decode(parts[1]), base64.decode(parts[2]), purpose.associatedData.encodeToByteArray())
        }.getOrNull()?.decodeToString()
    }

    /** 明文的逐行 `名字=值` (按第一个 `=` 拆, 值里可以再有 `=`); 名字可以重复, 顺序照原文. */
    fun lines(plain: String): List<Pair<String, String>> = plain.lineSequence()
        .mapNotNull { line -> line.indexOf('=').takeIf { it > 0 }?.let { line.substring(0, it).trim() to line.substring(it + 1).trim() } }
        .toList()
}
