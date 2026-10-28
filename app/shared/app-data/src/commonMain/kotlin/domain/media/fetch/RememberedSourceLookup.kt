/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.fetch

import kotlinx.coroutines.flow.first
import me.him188.ani.app.data.repository.media.EpisodePreferencesRepository
import org.koin.core.component.KoinComponent
import org.koin.core.component.get

/**
 * 按条目 id 读自动选源记住的源 ([EpisodePreferencesRepository.getPreferredWebMediaSource]), 开查时把它排最前
 * (见 [MediaSourceMediaFetcher] 的 `rememberedSourceOf`). 条目 id 不是数字时为 null.
 */
internal fun KoinComponent.rememberedSourceLookup(): suspend (subjectId: String) -> String? = { subjectId ->
    subjectId.toIntOrNull()?.let { get<EpisodePreferencesRepository>().getPreferredWebMediaSource(it).first() }
}
