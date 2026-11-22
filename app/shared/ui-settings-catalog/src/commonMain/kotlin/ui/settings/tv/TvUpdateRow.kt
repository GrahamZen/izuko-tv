/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.settings.tv

import me.him188.ani.app.data.network.protocol.ReleaseClass
import me.him188.ani.app.ui.update.AppUpdatePresentation
import me.him188.ani.app.ui.update.AppUpdateState

/** 这次进设置后最近一次查成的更新类型; 还没查成过为 null. 只认最近一次: 最外层那一份结果只有一份, 换个类型查过之后, 之前那个类型的就不在了. */
class TvUpdateChecked(val releaseClass: ReleaseClass?) {
    fun isFor(current: ReleaseClass?): Boolean = releaseClass != null && releaseClass == current
}

/** 在「检查更新」那一行按确定做什么, 见 [tvUpdateRowStep]. */
internal enum class TvUpdateRowStep {
    /** 正在检查, 不接 */
    None,
    Check,

    /** 同通知里的「自动更新」: 开始下载、看进度或安装 (见 UpdatePromptState.requestDownload) */
    Update,
}

/**
 * 在「检查更新」那一行按确定做什么: 这次进设置后、按此刻的更新类型还没查过 ([checked] 为 false) 时总是先查, 查到了再按才是更新.
 * 界面最外层那一份更新状态可能早就查到过新版本、甚至下好了, 但那是别的时候、也许是另一个更新类型查到的, 按一下不能直接拿它去装.
 * 正在下载、安装时不查, 带去看进度.
 */
internal fun tvUpdateRowStep(p: AppUpdatePresentation, checked: Boolean): TvUpdateRowStep = when {
    p.isCheckingUpdate -> TvUpdateRowStep.None
    p.state is AppUpdateState.Downloading || p.state is AppUpdateState.Installing -> TvUpdateRowStep.Update
    !checked || p.checkUpdateError != null -> TvUpdateRowStep.Check
    p.state is AppUpdateState.HasNewVersion -> TvUpdateRowStep.Update
    else -> TvUpdateRowStep.Check
}
