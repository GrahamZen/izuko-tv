/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.models.preference

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

/**
 * 用户在某个条目的播放器里手动换过的字幕与音轨, 按条目持久化; 之后各集打开时按它选 (见 `TrackChoicePolicy`).
 * 记的是语言 (`zh-Hans`, `ja`) 或轨道名, 不是轨道序号 —— 各集文件里轨道的顺序不一定相同.
 *
 * @param subtitle 字幕: 语言键 / `off` (关掉) / `label:轨道名`; 没换过为 null
 * @param audio 音轨: 语言键 / `label:轨道名`; 没换过或换回自动为 null
 */
@Immutable
@Serializable
data class SubjectTrackChoice(
    val subtitle: String? = null,
    val audio: String? = null,
)
