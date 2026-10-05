/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.main

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import me.him188.ani.app.domain.media.cache.DeleteCacheWhenMarkedDoneUseCase
import me.him188.ani.app.ui.foundation.widgets.LocalToaster
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.cache_deleted_when_done
import me.him188.ani.app.ui.lang.cache_deleted_when_done_after_playback
import me.him188.ani.app.ui.lang.cache_deleted_when_done_playing
import org.jetbrains.compose.resources.getString

/**
 * 「标记看过后删除缓存」删了缓存时弹提示 (见 [DeleteCacheWhenMarkedDoneUseCase.deletions]). 标记可能在任何一页 (或 Web 控制台) 发生,
 * 所以挂在 TV 根组合上.
 */
@Composable
fun TvMarkedDoneCacheDeletionToasts() {
    val toaster = LocalToaster.current
    LaunchedEffect(toaster) {
        DeleteCacheWhenMarkedDoneUseCase.deletions.collect { deleted ->
            toaster.toast(
                when {
                    deleted.count == 0 -> getString(Lang.cache_deleted_when_done_after_playback, deleted.subjectName)
                    deleted.waitingForPlayback > 0 -> getString(Lang.cache_deleted_when_done_playing, deleted.subjectName, deleted.count)
                    else -> getString(Lang.cache_deleted_when_done, deleted.subjectName, deleted.count)
                },
            )
        }
    }
}
