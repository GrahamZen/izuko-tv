/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.notice

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.Feedback
import androidx.compose.material.icons.outlined.Update
import androidx.compose.material.icons.rounded.Star
import androidx.compose.ui.graphics.vector.ImageVector
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.tv_notice_category_announcement
import me.him188.ani.app.ui.lang.tv_notice_category_collection
import me.him188.ani.app.ui.lang.tv_notice_category_feedback
import me.him188.ani.app.ui.lang.tv_notice_category_update
import org.jetbrains.compose.resources.StringResource

/**
 * 通知的类别: 列表行左边的图标、右栏标题上面的签. 按处理它要去哪一处分, 图标取应用里同一处已有的 (侧边栏、设置的分类).
 */
enum class TvNoticeCategory(val label: StringResource, val icon: ImageVector) {
    /** 发版后补的通知 */
    Announcement(Lang.tv_notice_category_announcement, Icons.Outlined.Campaign),

    /** 有新版本 */
    Update(Lang.tv_notice_category_update, Icons.Outlined.Update),

    /** 反馈的回复与处理结果 */
    Feedback(Lang.tv_notice_category_feedback, Icons.Outlined.Feedback),

    /** 收藏 (如本地收藏待处理) */
    Collection(Lang.tv_notice_category_collection, Icons.Rounded.Star),
}
