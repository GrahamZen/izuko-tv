/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.notice

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * 通知弹窗右栏正文的一块 (见 [TvNoticeColumnItem.blocks]): 只是数据, 安卓上的原生视图与 Compose 画法 (iPad) 各照它画一份.
 * 几种: 一段话 [TvNoticeParagraph]、来往记录里的一条 [TvNoticeMessage]、一张卡片 [TvNoticeCard]、二维码 [TvNoticeQr].
 */
@Immutable
sealed interface TvNoticeBlock

/**
 * 一段字的颜色: 正文色、淡一档 (补充说明)、主色 (要紧的, 如处理结果)、成功的绿 (如手机已连接)、出错的红.
 * 状态行 ([TvNoticeQr.status]、[TvNoticeCard.status]) 本来就是淡一档的字, [Normal] 与 [Secondary] 在那里一样.
 */
enum class TvNoticeTone { Normal, Secondary, Emphasis, Ok, Error }

/**
 * 来往记录里的一条 (画成气泡): [meta] 是气泡里的第一行 (谁、什么时候), [text] 是正文 (null = 只有那一行);
 * [mine] = 自己发的, 靠右; [fresh] = 没看过的新回复, [meta] 旁一个红点.
 */
@Immutable
data class TvNoticeMessage(
    val meta: String,
    val text: String?,
    val mine: Boolean,
    val fresh: Boolean = false,
) : TvNoticeBlock

/**
 * 一张卡片 (如工单回复里的测试包): 图标、标题 (长的截到两行)、下面一行状态 (颜色见 [statusTone]);
 * [progress] (0..1) 在标题行最右写百分比、下面画一条进度, [indeterminate] 画不定式的条, 都没有就不画条.
 */
@Immutable
data class TvNoticeCard(
    val icon: ImageVector,
    val title: String,
    val status: String?,
    val statusTone: TvNoticeTone = TvNoticeTone.Normal,
    val progress: Float? = null,
    val indeterminate: Boolean = false,
) : TvNoticeBlock
