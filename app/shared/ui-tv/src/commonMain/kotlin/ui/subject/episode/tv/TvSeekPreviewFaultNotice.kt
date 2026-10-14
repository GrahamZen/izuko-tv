/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.episode.tv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.flow.filterNotNull
import me.him188.ani.app.domain.player.SeekPreviewDecoderFault
import me.him188.ani.app.ui.foundation.widgets.LocalToaster
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.player_seek_preview_crashed_switched_to_full_screen
import me.him188.ani.app.ui.lang.player_seek_preview_switched_to_full_screen
import org.jetbrains.compose.resources.stringResource

/**
 * 拖动预览换输出弄坏了这台设备的解码器 (或上次在小画面预览时闪退)、自动改成画在全屏上时 (见 [SeekPreviewDecoderFault]) 弹一次气泡,
 * 说明预览为什么变了样、去哪儿改回来. 不画任何东西.
 */
@Composable
internal fun TvSeekPreviewFaultNotice() {
    val toaster = LocalToaster.current
    val decoderErrorText = stringResource(Lang.player_seek_preview_switched_to_full_screen)
    val crashedText = stringResource(Lang.player_seek_preview_crashed_switched_to_full_screen)
    LaunchedEffect(toaster, decoderErrorText, crashedText) {
        SeekPreviewDecoderFault.notice.filterNotNull().collect { reason ->
            toaster.toast(
                when (reason) {
                    SeekPreviewDecoderFault.Reason.DECODER_ERROR -> decoderErrorText
                    SeekPreviewDecoderFault.Reason.CRASHED -> crashedText
                },
            )
            SeekPreviewDecoderFault.consumeNotice()
        }
    }
}
