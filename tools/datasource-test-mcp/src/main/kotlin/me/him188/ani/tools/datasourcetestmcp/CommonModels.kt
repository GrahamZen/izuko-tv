/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.tools.datasourcetestmcp

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * 条目/分集元数据的来源. 直连之后是 bangumi 自己 (见 `AniInfoService` 里的 `V0` / `NEXT`),
 * 不再经过 Ani 服务器 —— 各 `aniApiBaseUrl` 参数保留只为不破坏已有的 MCP 调用方.
 */
const val DEFAULT_ANI_API_BASE_URL = "https://api.bgm.tv"

/**
 * 一个执行阶段/引擎步骤的记录, 用于 trace 输出.
 */
@Serializable
data class StageResult(
    val name: String,
    val status: String,
    val summary: String,
    val details: JsonElement? = null,
    val errors: List<String> = emptyList(),
    val durationMillis: Long? = null,
)
