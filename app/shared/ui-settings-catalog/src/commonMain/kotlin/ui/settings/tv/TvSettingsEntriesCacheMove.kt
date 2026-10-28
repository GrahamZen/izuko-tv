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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import me.him188.ani.app.domain.media.cache.library.MediaLibraryPublisher
import me.him188.ani.app.domain.media.cache.library.MediaStorageSetup
import me.him188.ani.app.domain.media.cache.location.MediaCacheLocations
import me.him188.ani.app.domain.media.cache.location.MediaCacheMover
import me.him188.ani.app.domain.usecase.GlobalKoin
import me.him188.ani.app.ui.foundation.getStringOffMain
import me.him188.ani.app.ui.foundation.tv.TvActionPanelRequests
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.settings_storage_cache_move
import me.him188.ani.app.ui.lang.settings_storage_cache_move_confirm
import me.him188.ani.app.ui.lang.settings_storage_cache_move_confirm_text
import me.him188.ani.app.ui.lang.settings_storage_cache_move_confirm_title
import me.him188.ani.app.ui.lang.settings_storage_cache_move_description
import me.him188.ani.app.ui.lang.settings_storage_cache_move_nothing
import me.him188.ani.app.ui.lang.settings_storage_cache_move_pending
import me.him188.ani.app.ui.lang.settings_storage_cache_move_target_unavailable
import me.him188.ani.utils.coroutines.IO_
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.datasources.api.topic.FileSize.Companion.bytes
import kotlin.time.Duration.Companion.seconds

/** 正在搬或在等停播 ([busy], 这时进度在动作面板顶上) 与按现在的设置还在别的地方的缓存 ([leftovers]) */
internal class TvCacheMoveStatus(
    val busy: Boolean,
    val leftovers: MediaStorageSetup.Group,
)

/**
 * 搬家的状态, 页面开着时每秒看一次. 还在别的地方的要扫一遍各缓存的文件大小, 不在搬时每 10 秒、搬完那一刻与插拔盘时重算.
 * 没有这项实现的平台一直没有值, 「移动已有缓存」不显示.
 */
internal val mediaCacheMoveKey = TvSettingKey.Live<TvCacheMoveStatus>("mediaCacheMove") {
    val mover = GlobalKoin.getOrNull<MediaCacheMover>()
    val setup = GlobalKoin.getOrNull<MediaStorageSetup>()
    val locations = GlobalKoin.getOrNull<MediaCacheLocations>()
    if (mover == null || setup == null || locations == null) {
        emptyFlow()
    } else {
        flow {
            var leftovers = MediaStorageSetup.Group.EMPTY
            var countedFor: List<String?>? = null
            var sinceRecount = 0
            while (true) {
                val busy = mover.progress.value != null || mover.waiting.value != null
                val ids = locations.locations.value.map { it.id }
                if (!busy && (ids != countedFor || sinceRecount >= 10)) {
                    leftovers = setup.leftovers().total
                    countedFor = ids
                    sinceRecount = 0
                }
                // 搬的期间还在别处的会变, 搬完重算
                if (busy) countedFor = null
                emit(TvCacheMoveStatus(busy, leftovers))
                sinceRecount++
                delay(1.seconds)
            }
        }.flowOn(Dispatchers.IO_)
    }
}

/**
 * 「移动已有缓存」: 按「缓存放在哪」的设置还在别的地方的缓存 (引导里选了只放新的、搬的时候那块盘没接上、取消了的) 搬过去
 * ([MediaStorageSetup.moveLeftovers]). 有这样的缓存、又没在搬也没在等停播时才显示: 搬的进度、停止与取消都在动作面板顶上
 * (开始搬时弹一次, 见 [showMoveInActionPanel]), 这里不重复.
 */
internal fun TvSettingsItemsBuilder.cacheMoveItem() {
    val mediaCache = TvNetworkSettingKeys.mediaCache
    val locations = mediaCacheLocationsKey
    val move = mediaCacheMoveKey
    action(
        Lang.settings_storage_cache_move, Lang.settings_storage_cache_move_description,
        keys = listOf(mediaCache, locations, move),
        value = { v -> moveValueText(v[move]) },
        visible = { v -> v[move]?.let { !it.busy && !it.leftovers.isEmpty } == true },
    ) { v ->
        val target = v[mediaCache]?.defaultLocation
        listOf(TvSettingsEdit.Run { env -> onMoveClicked(env, target) })
    }
}

private fun moveValueText(status: TvCacheMoveStatus?): TvText? {
    if (status == null) return null
    val leftovers = status.leftovers
    if (leftovers.isEmpty) return null
    return tvText(Lang.settings_storage_cache_move_pending, leftovers.units, leftovers.bytes.bytes.toString())
}

private suspend fun onMoveClicked(env: TvSettingsEnv, target: String?) {
    val mover = GlobalKoin.get<MediaCacheMover>()
    // 这一行在这时不显示; 刚好在消失前按下去的, 带去看动作面板
    if (mover.isRunning || mover.waiting.value != null) {
        TvActionPanelRequests.showTasks("a move already going on")
        return
    }
    val location = GlobalKoin.get<MediaCacheLocations>().locations.value.firstOrNull { it.id == target }
    if (location == null || location.unusable != null) {
        env.toast(getStringOffMain(Lang.settings_storage_cache_move_target_unavailable))
        return
    }
    val setup = GlobalKoin.get<MediaStorageSetup>()
    val leftovers = setup.leftovers().total
    if (leftovers.isEmpty) {
        env.toast(getStringOffMain(Lang.settings_storage_cache_move_nothing))
        return
    }
    val confirm = TvSettingsConfirm(
        title = tvText(Lang.settings_storage_cache_move_confirm_title, leftovers.units, location.nameText()),
        text = tvText(Lang.settings_storage_cache_move_confirm_text, leftovers.bytes.bytes.toString()),
        confirmLabel = tvText(Lang.settings_storage_cache_move_confirm),
    )
    env.ask(confirm) {
        // 在后台搬, 进度在动作面板顶上, 搬完由 TvLowSpaceToasts 提示结果
        if (setup.moveLeftovers()) tvSettingsWriteScope.launch { showMoveInActionPanel() }
    }
}

/**
 * 在电视上开始移动之后弹一次动作面板看进度 (见 [TvActionPanelRequests]): 等面板顶上有了这件事再弹, 免得弹出来那一刻还空着、焦点落到别处.
 * 搬家一开始就有进度 (见 [MediaCacheMover.Progress]), 只放入片库时要等发布开始; 几秒内都没有 (一下就做完了, 结果由提示说) 就不弹.
 */
internal suspend fun showMoveInActionPanel() {
    val mover = GlobalKoin.getOrNull<MediaCacheMover>() ?: return
    val publishing = GlobalKoin.getOrNull<MediaLibraryPublisher>()?.progress ?: flowOf(null)
    val shown = withTimeoutOrNull(5.seconds) {
        combine(mover.progress, mover.waiting, publishing) { move, waiting, publish -> move != null || waiting != null || publish != null }
            .first { it }
    }
    if (shown != null) {
        TvActionPanelRequests.showTasks("moving caches")
    } else {
        logger.info { "Moving caches finished before it showed up in the action panel, not opening the panel" }
    }
}

private val logger = logger("TvSettingsEntriesCacheMove")
