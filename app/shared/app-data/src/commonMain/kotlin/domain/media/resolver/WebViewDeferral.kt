/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.resolver

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withTimeoutOrNull
import me.him188.ani.datasources.api.Media
import kotlin.concurrent.Volatile
import kotlin.time.Duration.Companion.seconds

/**
 * 网页资源解析到要转去 WebView 嗅探的那一刻, 先看看这一集手上有没有不用嗅探的资源可换 (判断见 `WebViewDeferralExtension`).
 *
 * 先直连、取不到才嗅探的数据源, 画像没记过时选源照普通源排 (见 `WebViewSources`); 嗅探在电视上要几秒到几十秒,
 * 真走到这一步时有直连的就换过去, 没有才嗅探.
 */
object WebViewDeferral {
    /** 当前播放会话登记的判断: 改选了别的资源返回 true */
    @Volatile
    private var gate: (suspend (Media) -> Boolean)? = null

    /** 登记 [gate], 关掉返回值时撤下 (已被后来登记的换掉时不动). */
    fun register(gate: suspend (Media) -> Boolean): AutoCloseable {
        this.gate = gate
        return AutoCloseable { if (this.gate === gate) this.gate = null }
    }

    /**
     * 解析器开 WebView 嗅探 [media] 之前调用. 改选了别的资源时选中项一变, 这次解析就被取消;
     * 等 [CANCEL_WAIT] 还没被取消 (改选没生效) 就照常嗅探.
     */
    suspend fun beforeSniff(media: Media) {
        val gate = gate ?: return
        if (!gate(media)) return
        withTimeoutOrNull(CANCEL_WAIT) { awaitCancellation() }
    }

    private val CANCEL_WAIT = 5.seconds
}
