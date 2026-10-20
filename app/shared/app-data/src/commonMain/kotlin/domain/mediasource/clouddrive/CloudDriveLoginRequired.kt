/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.clouddrive

import me.him188.ani.app.domain.media.fetch.MediaSourceFetchState

/*
 * 没登录网盘造成的失败: 网盘源与网盘分享搜索源没登录时查询直接失败 (见 CloudDriveMediaSource / CloudDriveShareSearchMediaSource),
 * 播放网盘里的文件也要登录 (分享要转存). 界面上写「未登录」并给扫码登录, 数据源画像与播放失败统计里不算这个源的毛病.
 */

/** 这次失败里的 [CloudDriveAuthException] (沿 cause 往下找, 播放时它被包在解析异常里); 不是没登录造成的为 null. */
fun Throwable.cloudDriveAuthException(): CloudDriveAuthException? = causeOfType()

/** 沿 cause 往下找第一个 [T] (至多 8 层, 链可能自引用); 没有为 null. */
internal inline fun <reified T : Throwable> Throwable.causeOfType(): T? {
    var current: Throwable? = this
    var depth = 0
    while (current != null && depth++ < 8) {
        if (current is T) return current
        current = current.cause?.takeIf { it !== current }
    }
    return null
}

fun Throwable.isCloudDriveLoginRequired(): Boolean = cloudDriveAuthException() != null

/** 数据源这次查询因为没登录网盘而失败: 返回那个异常 (带着是哪个网盘), 否则为 null. */
val MediaSourceFetchState.cloudDriveLoginRequired: CloudDriveAuthException?
    get() = (this as? MediaSourceFetchState.Failed)?.cause?.cloudDriveAuthException()
