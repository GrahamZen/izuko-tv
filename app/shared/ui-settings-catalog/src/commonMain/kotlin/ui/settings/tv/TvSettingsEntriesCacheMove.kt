/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.settings.tv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import me.him188.ani.app.domain.media.cache.location.MediaCacheLocations
import me.him188.ani.app.domain.media.cache.location.MediaCacheMover
import me.him188.ani.app.domain.usecase.GlobalKoin
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.cache_move_started
import me.him188.ani.app.ui.lang.settings_storage_cache_move
import me.him188.ani.app.ui.lang.settings_storage_cache_move_confirm
import me.him188.ani.app.ui.lang.settings_storage_cache_move_confirm_text
import me.him188.ani.app.ui.lang.settings_storage_cache_move_confirm_title
import me.him188.ani.app.ui.lang.settings_storage_cache_move_description
import me.him188.ani.app.ui.lang.settings_storage_cache_move_none
import me.him188.ani.app.ui.lang.settings_storage_cache_move_nothing
import me.him188.ani.app.ui.lang.settings_storage_cache_move_pending
import me.him188.ani.app.ui.lang.settings_storage_cache_move_progress
import me.him188.ani.app.ui.lang.settings_storage_cache_move_stop
import me.him188.ani.app.ui.lang.settings_storage_cache_move_stop_text
import me.him188.ani.app.ui.lang.settings_storage_cache_move_stop_title
import me.him188.ani.app.ui.lang.settings_storage_cache_move_target_unavailable
import me.him188.ani.utils.coroutines.IO_
import me.him188.ani.datasources.api.topic.FileSize.Companion.bytes
import org.jetbrains.compose.resources.getString
import kotlin.time.Duration.Companion.seconds

/** 搬家的进度 ([progress], 没在搬时为 null) 与各位置上待搬的缓存 ([pending], 按位置 id) */
internal class TvCacheMoveStatus(
    val progress: MediaCacheMover.Progress?,
    val pending: Map<String?, MediaCacheMover.Pending>,
)

/**
 * 搬家的状态, 页面开着时每秒刷新进度. 待搬的要扫一遍各缓存的文件大小, 不在搬时每 10 秒、搬完那一刻与插拔盘时重算.
 * 没有这项实现的平台一直没有值, 「移动已有缓存」不显示.
 */
internal val mediaCacheMoveKey = TvSettingKey.Live<TvCacheMoveStatus>("mediaCacheMove") {
    val mover = GlobalKoin.getOrNull<MediaCacheMover>()
    val locations = GlobalKoin.getOrNull<MediaCacheLocations>()
    if (mover == null || locations == null) {
        emptyFlow()
    } else {
        flow {
            var pending = emptyMap<String?, MediaCacheMover.Pending>()
            var pendingFor: List<String?>? = null
            var sinceRecount = 0
            while (true) {
                val progress = mover.progress.value
                val ids = locations.locations.value.map { it.id }
                if (progress == null && (ids != pendingFor || sinceRecount >= 10)) {
                    pending = mover.pending(ids)
                    pendingFor = ids
                    sinceRecount = 0
                }
                // 搬的期间待搬的会变, 搬完重算
                if (progress != null) pendingFor = null
                emit(TvCacheMoveStatus(progress, pending))
                sinceRecount++
                delay(1.seconds)
            }
        }.flowOn(Dispatchers.IO_)
    }
}

/**
 * 「移动已有缓存」: 把不在「缓存位置」选的盘上的缓存都搬过去 (见 [MediaCacheMover]). 在搬时按它是停止.
 * 有不止一个位置 (或还有待搬的) 时才显示.
 */
internal fun TvSettingsItemsBuilder.cacheMoveItem() {
    val mediaCache = TvNetworkSettingKeys.mediaCache
    val locations = mediaCacheLocationsKey
    val move = mediaCacheMoveKey
    action(
        Lang.settings_storage_cache_move, Lang.settings_storage_cache_move_description,
        keys = listOf(mediaCache, locations, move),
        value = { v -> moveValueText(v[move], v[mediaCache]?.defaultLocation) },
        visible = { v ->
            v[locations].orEmpty().size > 1 ||
                    v[move]?.let { it.progress != null || it.pending.values.any { p -> p.units > 0 } } == true
        },
    ) { v ->
        val target = v[mediaCache]?.defaultLocation
        listOf(TvSettingsEdit.Run { env -> onMoveClicked(env, target) })
    }
}

private fun moveValueText(status: TvCacheMoveStatus?, target: String?): TvText? {
    if (status == null) return null
    status.progress?.let { p ->
        return tvText(
            Lang.settings_storage_cache_move_progress,
            p.done + p.failed + p.skipped, p.total, p.bytesDone.bytes.toString(), p.bytesTotal.bytes.toString(),
        )
    }
    val pending = status.pending[target] ?: return null
    return if (pending.units > 0) {
        tvText(Lang.settings_storage_cache_move_pending, pending.units, pending.bytes.bytes.toString())
    } else {
        tvText(Lang.settings_storage_cache_move_none)
    }
}

private suspend fun onMoveClicked(env: TvSettingsEnv, target: String?) {
    val mover = GlobalKoin.get<MediaCacheMover>()
    if (mover.isRunning) {
        val confirm = TvSettingsConfirm(
            title = tvText(Lang.settings_storage_cache_move_stop_title),
            text = tvText(Lang.settings_storage_cache_move_stop_text),
            confirmLabel = tvText(Lang.settings_storage_cache_move_stop),
            destructive = true,
        )
        env.ask(confirm) { mover.cancel() }
        return
    }
    val location = GlobalKoin.get<MediaCacheLocations>().locations.value.firstOrNull { it.id == target }
    if (location == null || location.unusable != null) {
        env.toast(getString(Lang.settings_storage_cache_move_target_unavailable))
        return
    }
    val pending = mover.pending(target)
    if (pending.units == 0) {
        env.toast(getString(Lang.settings_storage_cache_move_nothing))
        return
    }
    val confirm = TvSettingsConfirm(
        title = tvText(Lang.settings_storage_cache_move_confirm_title, pending.units, location.nameText()),
        text = tvText(Lang.settings_storage_cache_move_confirm_text, pending.bytes.bytes.toString()),
        confirmLabel = tvText(Lang.settings_storage_cache_move_confirm),
    )
    env.ask(confirm) {
        // 在后台搬, 搬完由 TvLowSpaceToasts 提示结果
        if (mover.start(target)) env.toast(getString(Lang.cache_move_started, pending.units, pending.bytes.bytes.toString()))
    }
}
