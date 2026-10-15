/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.rule

internal actual fun platformAesCbcDecrypt(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray =
    throw UnsupportedOperationException("iOS 上没有接 AES 解密, 用到 crypto 步骤的规则源取不到地址")
