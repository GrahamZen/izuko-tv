/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.clouddrive

/**
 * 播网盘里的文件失败时界面上说得清的原因 (见 [cloudDriveFailure]). 解析器把网盘的异常包成解析失败交给播放器,
 * 播放页沿 cause 认出它, 写「网盘空间不够」「分享已失效」或网盘自己的说法, 而不是「未知错误」.
 */
sealed interface CloudDriveFailure {
    /** 没登录或登录已失效 ([CloudDriveAuthException]). */
    data object LoginRequired : CloudDriveFailure

    /** 网盘空间不够, 分享里的文件转存不进去 ([CloudDriveCapacityException]); [requiredBytes] 是要转存的大小. */
    data class Full(val requiredBytes: Long) : CloudDriveFailure

    /** 分享打不开: 已取消、被封或要提取码 ([CloudDriveShareUnavailableException]). */
    data object ShareUnavailable : CloudDriveFailure

    /** 网盘报了别的错 (含拒绝转存); [message] 是网盘自己的说法或一句不带技术细节的说明 ([CloudDriveApiException.displayMessage]). */
    data class Other(val message: String) : CloudDriveFailure
}

/** 这次失败里网盘那一层的原因 (沿 cause 往下找); 不是网盘的异常造成的为 null. */
fun Throwable.cloudDriveFailure(): CloudDriveFailure? = when (val e = causeOfType<CloudDriveApiException>()) {
    null -> null
    is CloudDriveAuthException -> CloudDriveFailure.LoginRequired
    is CloudDriveCapacityException -> CloudDriveFailure.Full(e.requiredBytes)
    is CloudDriveShareUnavailableException ->
        if (e.saveRefused) CloudDriveFailure.Other(e.displayMessage) else CloudDriveFailure.ShareUnavailable
    else -> CloudDriveFailure.Other(e.displayMessage)
}

/** 这次失败里的 [CloudDriveCapacityException] (网盘满了, 转存不进去); 不是为 null. */
fun Throwable.cloudDriveCapacityException(): CloudDriveCapacityException? = causeOfType()

/** 自己网盘的问题 (没登录、空间不够) 造成的失败: 换哪个资源都一样, 不是数据源的毛病. */
fun Throwable.isCloudDriveAccountProblem(): Boolean = isCloudDriveLoginRequired() || cloudDriveCapacityException() != null
