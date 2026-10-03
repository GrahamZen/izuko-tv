/*
 * Copyright (C) 2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.videoplayer.media

import android.content.Context
import android.system.Os
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import java.io.File

/**
 * 给 libass 用的 fontconfig 一份带缓存目录的配置.
 *
 * libass 每建一个渲染器就初始化一次 fontconfig. 安卓上没有 `/etc/fonts`, 它退回内置的配置, 那份配置里的缓存目录都写不了,
 * 于是每次都把系统字体从头扫一遍: Shield 上 1~3 秒, 开播时建渲染器扫一次, 补完内嵌字体换渲染器又扫一次.
 * 这份配置的字体目录与内置的一样 (`/system/fonts`), 只多一个应用缓存里的缓存目录: 第一次扫完写下缓存, 之后 (包括应用重启) 直接读.
 *
 * 配置经环境变量 `FONTCONFIG_FILE` 交给 libass 里打包的 fontconfig. 改环境变量与别的线程读环境变量不能同时发生,
 * 所以在进程里第一次建 libass 对象之前设好 ([ensure]).
 */
internal object LibassFontconfig {
    private var configured = false

    @Synchronized
    fun ensure(context: Context) {
        if (configured) return
        configured = true
        try {
            val cacheDir = File(context.cacheDir, "fontconfig").apply { mkdirs() }
            val configFile = File(File(context.filesDir, "fontconfig").apply { mkdirs() }, "fonts.conf")
            val config = """
                |<?xml version="1.0"?>
                |<fontconfig>
                |  <dir>$SYSTEM_FONTS</dir>
                |  <cachedir>${cacheDir.absolutePath}</cachedir>
                |</fontconfig>
                |""".trimMargin()
            if (!configFile.isFile || configFile.readText() != config) configFile.writeText(config)
            Os.setenv("FONTCONFIG_FILE", configFile.absolutePath, true)
            logger.info { "libass fontconfig caches system fonts in $cacheDir" }
        } catch (e: Exception) {
            // 设不上就照旧: 每建一个渲染器扫一遍系统字体
            logger.warn { "Failed to set up the libass fontconfig cache: $e" }
        }
    }

    /** 与 libass 里打包的 fontconfig 不带配置时扫的目录相同. */
    private const val SYSTEM_FONTS = "/system/fonts"

    private val logger = logger<LibassFontconfig>()
}
