/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.platform

/**
 * 改分发包名前后的 applicationId 前缀, 与 app/android/build.gradle.kts 里的 `applicationId` 一致.
 *
 * 按前缀换算而不是写死整个包名: 变体后缀 (`.tv`) 与 debug 后缀 (`.debug2`) 要原样带过去,
 * 否则 debug 包之间对不上, 迁移这条路平时就没法验.
 */
object DistributionApplicationIds {
    /** 改名前的前缀. 迁移跳板包仍用它, 老用户那边才算"升级"而不是装了个新应用. */
    const val LEGACY_PREFIX = "me.him188.ani"

    /** 现在的前缀. */
    const val CURRENT_PREFIX = "io.github.grahamzen.anime"

    /** 新包 [currentPackage] 对应的旧包名. */
    fun legacyOf(currentPackage: String): String = LEGACY_PREFIX + currentPackage.removePrefix(CURRENT_PREFIX)

    /** 跳板包 [legacyPackage] 要迁去的新包名. */
    fun currentOf(legacyPackage: String): String = CURRENT_PREFIX + legacyPackage.removePrefix(LEGACY_PREFIX)

    /**
     * 把路径里属于旧包的那一段 (`/<旧包名>/`, 整段匹配) 换成新包名; 路径不在旧包目录下时返回 `null`.
     *
     * 用于接管设置时改写存下来的目录, 比如缓存目录 `…/Android/data/<旧包名>/files/Movies`:
     * 新包对旧包的专属目录没有权限, 原样接过来就写不进去.
     */
    fun rewritePathForCurrent(path: String, legacyPackage: String, currentPackage: String): String? {
        val segment = "/$legacyPackage/"
        val padded = "$path/"
        if (segment !in padded) return null
        return padded.replace(segment, "/$currentPackage/").removeSuffix("/")
    }
}
