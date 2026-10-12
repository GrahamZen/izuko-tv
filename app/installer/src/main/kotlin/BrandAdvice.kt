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
 * 系统拦下安装时, 按电视品牌给出能走的路. 不少品牌只认自家的安装渠道 (TCL 的极速安装、酷开的应用圈) 或带包名黑名单 (小米、海信),
 * 别的应用发起的安装 (包括助手) 会被拦; 那时只能换它们认的方式装. 依据见 memory reference-tv-brand-apk-install-issues.
 */
object BrandAdvice {
    /** 按 [manufacturer] 与 [brand] (Build 里的原值) 认品牌, 返回建议的字符串资源; 认不出给通用的. */
    fun forDevice(manufacturer: String?, brand: String?): Int {
        val name = "${manufacturer.orEmpty()} ${brand.orEmpty()}".lowercase()
        return when {
            "xiaomi" in name || "redmi" in name -> R.string.advice_xiaomi
            "tcl" in name || "ffalcon" in name || "thunderbird" in name -> R.string.advice_tcl
            "hisense" in name || "vidda" in name -> R.string.advice_hisense
            "skyworth" in name || "coocaa" in name -> R.string.advice_skyworth
            "changhong" in name -> R.string.advice_changhong
            "konka" in name -> R.string.advice_konka
            "huawei" in name || "honor" in name -> R.string.advice_huawei
            "letv" in name || "leeco" in name || "lemobile" in name -> R.string.advice_letv
            else -> R.string.advice_generic
        }
    }
}
