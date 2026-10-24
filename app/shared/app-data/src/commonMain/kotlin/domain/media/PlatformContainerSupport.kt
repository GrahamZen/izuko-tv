/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media

import me.him188.ani.datasources.api.Media
import me.him188.ani.datasources.api.topic.ResourceLocation
import me.him188.ani.utils.platform.currentPlatform
import me.him188.ani.utils.platform.isIos

/**
 * 文件名 [fileName] (可以带着前面的文件夹, 如 `文件夹 / 第01话.mkv`) 的容器格式这台设备的播放器放不了.
 * 只有 iOS 有这个问题: AVPlayer 只认 MP4 / MOV / M4V 与 HLS; 安卓与桌面的播放器都能放. 认不出扩展名时算放得了.
 */
fun isContainerUnplayableOnThisPlatform(fileName: String): Boolean =
    currentPlatform().isIos() && avPlayerCannotOpen(fileName)

/**
 * 按文件放的资源 (网盘、直链等 [ResourceLocation.HttpStreamingFile], 标题末尾是文件名) 这台设备的播放器放不了.
 * 选源时按「播放器不支持」排除: 不自动选、默认不列; 打开「显示已排除的资源」手动选它时, 网盘的资源退到转码流 (见 CloudDriveMediaResolver).
 */
fun Media.isFileContainerUnplayableOnThisPlatform(): Boolean =
    currentPlatform().isIos() && avPlayerCannotOpenFile()

/** [isFileContainerUnplayableOnThisPlatform] 里不看平台的那部分. */
internal fun Media.avPlayerCannotOpenFile(): Boolean =
    download is ResourceLocation.HttpStreamingFile && avPlayerCannotOpen(originalTitle)

/** AVPlayer 打不开 [fileName] 这种容器 (按扩展名). */
internal fun avPlayerCannotOpen(fileName: String): Boolean =
    fileName.substringAfterLast('.', "").trim().lowercase() in AVPLAYER_UNSUPPORTED_EXTENSIONS

private val AVPLAYER_UNSUPPORTED_EXTENSIONS = setOf(
    "mkv", "webm", "avi", "flv", "wmv", "rm", "rmvb", "ts", "m2ts", "mts", "vob", "mpg", "mpeg",
)
