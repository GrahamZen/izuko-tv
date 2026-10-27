/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.remote

import kotlinx.coroutines.flow.first
import me.him188.ani.app.data.models.preference.MediaCacheSettings
import me.him188.ani.app.data.repository.user.SettingsRepository
import me.him188.ani.app.domain.media.cache.library.MediaStorageSetup
import me.him188.ani.app.domain.media.cache.location.MediaCacheLocation
import me.him188.ani.app.domain.media.cache.location.MediaCacheLocationChoice
import me.him188.ani.app.domain.media.cache.location.MediaCacheLocationProblem
import me.him188.ani.app.domain.media.cache.location.MediaCacheLocations
import me.him188.ani.app.domain.media.cache.location.MediaCacheMover
import me.him188.ani.app.domain.media.cache.location.mediaCacheLocationChoices
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.cache_move_started
import me.him188.ani.app.ui.lang.settings_storage_cache_location
import me.him188.ani.app.ui.lang.settings_storage_cache_location_description
import me.him188.ani.app.ui.lang.settings_storage_cache_location_disconnected
import me.him188.ani.app.ui.lang.settings_storage_cache_location_fat32
import me.him188.ani.app.ui.lang.settings_storage_cache_location_internal
import me.him188.ani.app.ui.lang.settings_storage_cache_location_read_only
import me.him188.ani.app.ui.lang.settings_storage_cache_location_space
import me.him188.ani.app.ui.lang.settings_storage_cache_move_nothing
import me.him188.ani.app.ui.lang.settings_storage_cache_move_pending
import me.him188.ani.app.ui.lang.settings_storage_cache_move_progress
import me.him188.ani.app.ui.lang.settings_storage_cache_move_target_unavailable
import me.him188.ani.app.ui.lang.tv_background_task_cache_move
import me.him188.ani.app.ui.lang.tv_background_task_cache_move_waiting
import me.him188.ani.datasources.api.topic.FileSize.Companion.bytes
import org.jetbrains.compose.resources.getString
import org.koin.mp.KoinPlatform

/**
 * 「缓存位置」卡片, 同电视设置页「存储」里那两项: 下拉现列此刻接着、能用的盘 (带剩余空间), 不能用的盘在卡片状态里说为什么;
 * 状态里还有搬家的进度或按现在的设置还在别处的缓存, 第二个按钮把它们搬过去 (见 [MediaStorageSetup.moveLeftovers], 片库里的一起).
 * 改盘本身只写设置 (同电视设置引导里选「只放新的」); 要连已有的一起换, 再按第二个按钮.
 * 文案都用设置页的, 不用补网页译文 (按钮文字除外).
 */
internal object RemoteCacheLocationSetting {
    val spec = RemoteSettingSpec(
        key = "cacheLocation",
        settings = { mediaCacheSettings },
        serializer = MediaCacheSettings.serializer(),
        fieldName = "defaultLocation",
        title = Lang.settings_storage_cache_location,
        description = Lang.settings_storage_cache_location_description,
        editor = RemoteSettingEditor.Options { options() },
        status = { status() },
        action = RemoteSettingAction("把已有缓存移到这里") { startMove() },
    )

    private fun mover(): MediaCacheMover? = KoinPlatform.getKoin().getOrNull()

    private fun setup(): MediaStorageSetup? = KoinPlatform.getKoin().getOrNull()

    private suspend fun selected(): String? =
        KoinPlatform.getKoin().get<SettingsRepository>().mediaCacheSettings.flow.first().defaultLocation

    /** 没有这项实现的平台只有内部存储 */
    private fun locations(): MediaCacheLocations? = KoinPlatform.getKoin().getOrNull()

    private suspend fun options(): List<RemoteSettingEditor.Options.Option> {
        val locations = locations()
            ?: return listOf(RemoteSettingEditor.Options.Option(null, getString(Lang.settings_storage_cache_location_internal)))
        locations.refresh()
        val selected = selected()
        return mediaCacheLocationChoices(locations.locations.value, selected).map { choice ->
            RemoteSettingEditor.Options.Option(choice.id, labelOf(choice))
        }
    }

    private suspend fun status(): String? {
        val unusable = locations()?.locations?.value.orEmpty().mapNotNull { location ->
            when (location.unusable) {
                null -> null
                MediaCacheLocationProblem.FAT32 -> getString(Lang.settings_storage_cache_location_fat32, nameOf(location))
                MediaCacheLocationProblem.READ_ONLY -> getString(Lang.settings_storage_cache_location_read_only, nameOf(location))
            }
        }
        return (unusable + listOfNotNull(moveStatus())).takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    /** 搬家的进度 (或在等停播), 没在搬时别的位置上还有多少缓存 (都在这里时不说) */
    private suspend fun moveStatus(): String? {
        val mover = mover() ?: return null
        suspend fun driveName(id: String?): String =
            locations()?.locations?.value?.firstOrNull { it.id == id }?.let { nameOf(it) } ?: getString(Lang.settings_storage_cache_location_internal)
        mover.progress.value?.let { p ->
            // 还在列要搬哪些时没有数
            if (p.total == 0) return getString(Lang.tv_background_task_cache_move, driveName(p.target))
            return getString(
                Lang.settings_storage_cache_move_progress,
                p.done + p.failed, p.total, p.bytesDone.bytes.toString(), p.bytesTotal.bytes.toString(),
            )
        }
        mover.waiting.value?.let { w ->
            return getString(Lang.tv_background_task_cache_move_waiting, driveName(w.target.location)) +
                    " · ${w.doneUnits}/${w.doneUnits + w.units} · ${w.doneBytes.bytes} / ${(w.doneBytes + w.bytes).bytes}"
        }
        val pending = setup()?.leftovers()?.total ?: return null
        if (pending.isEmpty) return null
        return getString(Lang.settings_storage_cache_move_pending, pending.units, pending.bytes.bytes.toString())
    }

    private suspend fun startMove(): String {
        val mover = mover() ?: return getString(Lang.settings_storage_cache_move_nothing)
        if (mover.progress.value != null || mover.waiting.value != null) return moveStatus().orEmpty()
        val target = selected()
        val location = locations()?.locations?.value?.firstOrNull { it.id == target }
        if (location == null || location.unusable != null) return getString(Lang.settings_storage_cache_move_target_unavailable)
        val setup = setup() ?: return getString(Lang.settings_storage_cache_move_nothing)
        val pending = setup.leftovers().total
        if (pending.isEmpty) return getString(Lang.settings_storage_cache_move_nothing)
        setup.moveLeftovers()
        return getString(Lang.cache_move_started, pending.units, pending.bytes.bytes.toString())
    }

    private suspend fun labelOf(choice: MediaCacheLocationChoice): String {
        val location = choice.location ?: return getString(Lang.settings_storage_cache_location_disconnected)
        val free = location.freeBytes
        val total = location.totalBytes
        if (free == null || total == null) return nameOf(location)
        return nameOf(location) + " · " + getString(Lang.settings_storage_cache_location_space, free.bytes.toString(), total.bytes.toString())
    }

    private suspend fun nameOf(location: MediaCacheLocation): String =
        if (location.id == null || location.label.isBlank()) getString(Lang.settings_storage_cache_location_internal) else location.label
}
