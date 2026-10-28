/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.android.activity

import android.content.Context
import me.him188.ani.app.data.models.preference.ThemeSettings
import me.him188.ani.app.ui.foundation.TV_DESIGN_CANVAS_HEIGHT_DP
import me.him188.ani.app.ui.foundation.TV_DESIGN_CANVAS_WIDTH_DP
import me.him188.ani.app.ui.foundation.tvUiScaleToFitDesignCanvas

/**
 * 首次引导开始前按屏幕真实大小定一次界面缩放.
 *
 * 有的盒子上报的 densityDpi 偏大: 画面的像素数是准的, 每英寸多少像素报大了, 应用拿到的画布就比电视界面设计的
 * [TV_DESIGN_CANVAS_WIDTH_DP] × [TV_DESIGN_CANVAS_HEIGHT_DP] 小, 版面溢出 (引导最后一步的「开始」被挤到屏幕外, 走不完引导).
 * 引导还没走完、界面缩放还是 100% (没手动调过) 时, 换成刚好让画布够这么大的那一档 ([tvUiScaleToFitDesignCanvas], 按设置里 10% 一档往小取,
 * 最小 [ThemeSettings.UI_SCALE_MIN]): 写进镜像, 本次 Activity 就按它建, 第一帧就是对的; 同时记下来等 [consumePending] 写回设置.
 * 写回之后设置里的「界面缩放」显示这一档, 用户照样能改.
 */
internal object AutoUiScale {
    private const val PREFERENCES_NAME = "ani_auto_ui_scale"
    private const val KEY_PENDING = "pending"

    /**
     * Activity 建之前调用 ([context] 是还没缩放的那个): [eligible] (引导还没走完) 且没调过缩放时, 画布不够就定一档并返回它, 否则 null.
     */
    fun decide(context: Context, eligible: Boolean): Float? {
        if (!eligible || UiScaleMirror.read(context) != 1f) return null
        val metrics = context.resources.displayMetrics
        val scale = tvUiScaleToFitDesignCanvas(metrics.widthPixels, metrics.heightPixels, metrics.density) ?: return null
        UiScaleMirror.write(context, scale)
        runCatching {
            context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE).edit().putFloat(KEY_PENDING, scale).commit()
        }
        return scale
    }

    /** [decide] 定下、还没写回设置的那一档; 取一次就清掉. */
    fun consumePending(context: Context): Float? = runCatching {
        val prefs = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_PENDING)) return@runCatching null
        val scale = prefs.getFloat(KEY_PENDING, 1f)
        prefs.edit().remove(KEY_PENDING).commit()
        scale
    }.getOrNull()
}
