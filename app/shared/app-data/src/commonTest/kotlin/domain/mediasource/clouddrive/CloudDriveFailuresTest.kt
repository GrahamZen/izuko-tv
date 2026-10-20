/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.clouddrive

import me.him188.ani.app.domain.media.resolver.MediaResolutionException
import me.him188.ani.app.domain.media.resolver.ResolutionFailures
import me.him188.ani.app.domain.player.PlaybackFailureLog.Reason
import me.him188.ani.app.domain.player.VideoLoadingState
import me.him188.ani.app.domain.player.extension.PlaybackFailureReportExtension
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CloudDriveFailuresTest {
    /** 播放时解析器把网盘的异常包成 ENGINE_ERROR 交给播放器. */
    private fun resolving(e: Throwable) = MediaResolutionException(ResolutionFailures.ENGINE_ERROR, e)

    private fun reasonOf(e: Throwable) = PlaybackFailureReportExtension.reasonOf(VideoLoadingState.UnknownError(resolving(e)))

    @Test
    fun `a full drive is found under the resolution failure with the size it needed`() {
        val full = CloudDriveCapacityException("网盘空间不够", requiredBytes = 23_777L * 1024 * 1024)
        assertEquals(CloudDriveFailure.Full(full.requiredBytes), resolving(full).cloudDriveFailure())
        assertEquals(Reason.DRIVE_FULL, reasonOf(full))
        assertTrue(resolving(full).isCloudDriveAccountProblem())
    }

    @Test
    fun `a dead share is told apart from a drive refusing to save it`() {
        val dead = CloudDriveShareUnavailableException("41006", "分享不可用 (shareToken): HTTP 200, code=41006, 分享已失效")
        assertEquals(CloudDriveFailure.ShareUnavailable, resolving(dead).cloudDriveFailure())
        assertEquals(Reason.NO_MATCHING_FILE, reasonOf(dead))
        assertFalse(resolving(dead).isCloudDriveAccountProblem())

        val refused = CloudDriveShareUnavailableException(null, "网盘拒绝转存 a.mkv: 无权限", saveRefused = true, displayMessage = "拒绝转存：无权限")
        assertEquals(CloudDriveFailure.Other("拒绝转存：无权限"), resolving(refused).cloudDriveFailure())
    }

    @Test
    fun `other drive errors keep the drive's own words without the technical part`() {
        val apiError = CloudDriveApiException(
            "网盘接口 download 出错: HTTP 200, code=31001, 文件已被删除",
            code = "31001",
            displayMessage = "文件已被删除",
        )
        assertEquals(CloudDriveFailure.Other("文件已被删除"), resolving(apiError).cloudDriveFailure())
        assertEquals(CloudDriveFailure.Other("转存超时"), resolving(CloudDriveApiException("转存超时")).cloudDriveFailure())
        assertEquals(CloudDriveFailure.LoginRequired, resolving(CloudDriveAuthException()).cloudDriveFailure())
        assertNull(resolving(IllegalStateException("boom")).cloudDriveFailure())
    }
}
