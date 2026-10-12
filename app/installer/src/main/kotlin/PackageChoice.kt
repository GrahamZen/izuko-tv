/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package io.github.grahamzen.izuko.installer

/**
 * 按设备挑安装包: 系统版本定正式包还是 7.1 兼容包, ABI 定架构.
 * 发布包的命名见 fork-release.yml: `<前缀>-<版本>-<架构>.apk` 与 `<前缀>-<版本>-legacy-<架构>.apk`.
 */
object PackageChoice {
    /** 正式包出的架构 (universal 另算). */
    private val NORMAL_ABIS = listOf("arm64-v8a", "armeabi-v7a", "x86_64")

    /** 兼容包只出 ARM 两种与 universal. */
    private val LEGACY_ABIS = listOf("arm64-v8a", "armeabi-v7a")

    const val UNIVERSAL = "universal"

    /** universal 里带的架构 (CI 按 `ani.android.abis=all` 编, 两族都是这三种). */
    private val UNIVERSAL_ABIS = listOf("arm64-v8a", "armeabi-v7a", "x86_64")

    sealed interface Result {
        /** 系统低于兼容包的 [minSdk], 哪个包都装不了. */
        data class TooOld(val sdk: Int, val minSdk: Int) : Result

        /** 设备的架构 (如纯 x86) 哪个包都不带, 下了也装不上. */
        data class UnsupportedAbi(val abis: List<String>) : Result

        /**
         * @property legacy 要装 7.1 兼容包
         * @property suffixes 文件名里版本号之后那段, 按优先顺序: 本机架构的专包在前, universal 兜底在后
         */
        data class Packages(val legacy: Boolean, val suffixes: List<String>) : Result
    }

    /**
     * universal 只作兜底: 有的盒子 (腾讯极光 7S) 的安装器拒装带 64 位库的 universal, 而本机架构的专包装得上;
     * 本机 ABI 列表里一个出包的都没有时 universal 是唯一的选择.
     */
    fun choose(sdk: Int, abis: List<String>, normalMinSdk: Int, legacyMinSdk: Int): Result {
        if (sdk < legacyMinSdk) return Result.TooOld(sdk, legacyMinSdk)
        if (abis.none { it in UNIVERSAL_ABIS }) return Result.UnsupportedAbi(abis)
        val legacy = sdk < normalMinSdk
        val offered = if (legacy) LEGACY_ABIS else NORMAL_ABIS
        // 按设备自己的偏好顺序取第一个有包的: x86 电视首选 x86 (不出包), 但它能跑 armeabi-v7a
        val exact = abis.firstOrNull { it in offered }
        return Result.Packages(legacy, listOfNotNull(exact, UNIVERSAL).map { if (legacy) "legacy-$it" else it })
    }

    fun assetName(prefix: String, version: String, suffix: String) = "$prefix-$version-$suffix.apk"

    /**
     * 本应用的版本线: 主版本号小于 6. 6 及以上是改分发包名之前的旧版本, 与本应用同一个仓库, 要排除
     * (同 UpdateChecker 的 UPDATE_LINE_MAJOR_EXCLUSIVE).
     */
    fun isInVersionLine(version: String): Boolean =
        version.substringBefore('.').toIntOrNull()?.let { it < 6 } ?: false
}
