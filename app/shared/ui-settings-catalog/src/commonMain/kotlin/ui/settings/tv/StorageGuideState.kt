/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.settings.tv

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import me.him188.ani.app.domain.media.cache.library.MediaStorageSetup

/** 「缓存放在哪」引导的各步, 按这个顺序走 (授权只在选了片库又没权限时有) */
internal enum class StorageGuideStep { Drive, Mode, Permission, Existing, Confirm }

/**
 * 「缓存放在哪」引导选了什么、走到哪一步 (界面见 [StorageGuideDialog]). 只是选择, 按「完成」([choice] 等交给 [MediaStorageSetup.apply]) 才生效,
 * 中途退出什么都不改. 返回键按走过的步退回去 ([back]).
 *
 * @param initial 现在的设置, 各步先选好它
 * @param librarySupported 平台支持片库 (安卓 11 起); 不支持时「方式」那一步只能选应用目录
 */
internal class StorageGuideState(initial: MediaStorageSetup.Choice, val librarySupported: Boolean) {
    /** 打开引导时片库开着 (关掉时「已有的缓存」那一步问移回还是留在文件夹里) */
    val wasLibrary: Boolean = initial.library

    var step: StorageGuideStep by mutableStateOf(StorageGuideStep.Drive)
        private set
    var location: String? by mutableStateOf(initial.location)
        private set
    var library: Boolean by mutableStateOf(initial.library && librarySupported)
        private set

    /** 「已有的缓存」: 都移过去 */
    var moveExisting: Boolean by mutableStateOf(true)
        private set

    /** 「已有的缓存」: 关掉片库时片库里的留在文件夹里 (不移回应用目录) */
    var keepLibraryInFolder: Boolean by mutableStateOf(false)
        private set

    private val history = mutableStateListOf<StorageGuideStep>()

    val choice: MediaStorageSetup.Choice get() = MediaStorageSetup.Choice(location, library)

    /** 这次是关掉片库 (「已有的缓存」那一步问片库里的怎么办) */
    val turningLibraryOff: Boolean get() = wasLibrary && !library

    /** 头上画的几步: 授权只在走到了、或选了片库要去授权时出现 */
    val visibleSteps: List<StorageGuideStep>
        get() = listOfNotNull(
            StorageGuideStep.Drive,
            StorageGuideStep.Mode,
            StorageGuideStep.Permission.takeIf { step == it || it in history },
            StorageGuideStep.Existing,
        )

    fun chooseDrive(id: String?) {
        location = id
        go(StorageGuideStep.Mode)
    }

    /** 选了方式; 选片库又没权限时先去授权 */
    fun chooseMode(library: Boolean, granted: Boolean) {
        this.library = library && librarySupported
        go(if (this.library && !granted) StorageGuideStep.Permission else StorageGuideStep.Existing)
    }

    /** 权限给下来了 (在授权那一步) */
    fun permissionGranted() {
        if (step == StorageGuideStep.Permission) go(StorageGuideStep.Existing)
    }

    /** 不给权限, 改放应用目录 */
    fun skipPermission() {
        library = false
        go(StorageGuideStep.Existing)
    }

    /** 算出来没有要搬的 (在「已有的缓存」那一步): 直接到确认, 返回时跳过它 */
    fun nothingToMove() {
        if (step != StorageGuideStep.Existing) return
        moveExisting = true
        keepLibraryInFolder = false
        step = StorageGuideStep.Confirm
    }

    fun chooseExisting(move: Boolean, keepLibraryInFolder: Boolean = false) {
        moveExisting = move
        this.keepLibraryInFolder = keepLibraryInFolder
        go(StorageGuideStep.Confirm)
    }

    /** 退回上一步; 已经在第一步时返回 `false` (由调用方关掉引导) */
    fun back(): Boolean {
        val previous = history.removeLastOrNull() ?: return false
        step = previous
        return true
    }

    private fun go(next: StorageGuideStep) {
        history += step
        step = next
    }
}
