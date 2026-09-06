/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.settings.tabs.about

import androidx.compose.runtime.Immutable
import com.mikepenz.aboutlibraries.Libs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 开源许可页的一条, 只留给人看的几项 (给不直接依赖 aboutlibraries 的界面, 如电视设置页).
 *
 * @property id 构件坐标 (`group:artifact`), 列表里不重复.
 * @property homepage 主页, 没有时是源码仓库; 都没有为 null.
 */
@Immutable
data class OpenSourceLibraryInfo(
    val id: String,
    val name: String,
    val version: String?,
    val homepage: String?,
    val licenses: List<OpenSourceLicenseInfo>,
)

/** [spdxId] 是许可证的短名 (如 `Apache-2.0`), 没有时为 null. */
@Immutable
data class OpenSourceLicenseInfo(val name: String, val spdxId: String?, val url: String?)

/**
 * 读出开源库列表 (同 [OpenSourceLibrariesTab]: 合并几份 aboutlibraries 数据, 按名字排), 在后台线程解析.
 */
suspend fun loadOpenSourceLibraryList(loadLibrariesJsons: suspend () -> List<ByteArray>): List<OpenSourceLibraryInfo> {
    val jsons = loadLibrariesJsons()
    return withContext(Dispatchers.Default) {
        mergeOpenSourceLibraries(jsons.map { Libs.Builder().withJson(it.decodeToString()).build() })
            .libraries
            .distinctBy { it.uniqueId }
            .map { library ->
                OpenSourceLibraryInfo(
                    id = library.uniqueId,
                    name = library.name,
                    version = library.artifactVersion?.takeIf { it.isNotBlank() },
                    homepage = library.website?.takeIf { it.isNotBlank() } ?: library.scm?.url?.takeIf { it.isNotBlank() },
                    licenses = library.licenses.map { OpenSourceLicenseInfo(it.name, it.spdxId?.takeIf { id -> id.isNotBlank() }, it.url) },
                )
            }
    }
}
