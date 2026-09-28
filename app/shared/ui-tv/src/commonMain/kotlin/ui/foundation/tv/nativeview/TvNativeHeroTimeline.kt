/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv.nativeview

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.animation.LinearInterpolator
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * 海报墙的 **hero 态** (探索 / 追番 / 搜索三页共用): 卡片墙上按确定, 先切成 hero 的样子 —— 背景图与简介出现, 聚焦行移到简介正下方,
 * 上面的行淡出, 番名淡出; 再按确定才进详情页. 进详情页的放大转场从 hero 背景图起步, 卡片墙上没有这张图只能交叉淡入, 先切过来就能放大进去、
 * 返回时缩回来. 按返回变回卡片墙; 从详情页返回停在 hero 态, 再按一次返回才回卡片墙.
 *
 * 进出按一条时间线走, 页面底色与背景图从不同时在变 (底色见 TvPosterWallTone: 卡片墙深灰, hero 态近黑). 照 Android TV
 * 版 Apple TV App 的做法: 先背景后文字, 旧的先走, 淡入淡出一律线性 —— 缓动曲线把变化挤在中间一小段, 整屏换色看着是一闪. 背景图的羽化是
 * 在图上叠画 hero 底色的渐变, 图只在底色已经是那个色的时候露面, 图边任何时候都融得进去. 四段进度:
 * - [above]: 聚焦行上面的几行与卡片番名淡没 (探索页轮播露着的那截也随它淡);
 * - [tone]: 整屏底色压成 hero 的底 (只在深色主题下有, 浅色 hero 底与卡片墙同色);
 * - [content]: 背景图; [text]: 标题与简介.
 *
 * 进: above 0→1 (150ms) 与 tone 0→1 (深 300 / 浅 150ms) 同时起, tone 走完再走 content (400ms), text 在 content 起步 100ms 后走 300ms
 * (content 已经不是 0 时不等). 出: text (150ms) 与 content (250ms) 同时收, content 收完再 above (250ms) 与 tone (深 350 / 浅 250ms) 一起回.
 * 全部线性, 时长按剩余路程折算 (半路反向从当前值接着走). [animated] = false (视觉效果流畅档) 时直接到位.
 */
class TvNativeHeroTimeline(private val onUpdate: (TvNativeHeroTimeline) -> Unit) {
    var active: Boolean = false
        private set
    var above = 0f
        private set
    var tone = 0f
        private set
    var content = 0f
        private set
    var text = 0f
        private set

    /** 深色主题 (浅色下 tone 不压黑, 时长更短). */
    var dark: Boolean = true

    /** hero 态看得见 (有一段还没收完): 背景图 / hero 文字要画. */
    val visible: Boolean get() = active || tone > 0f || content > 0f || text > 0f

    private val running = ArrayList<ValueAnimator>()
    private var sequence = 0

    /** 进 / 出 hero 态. [animated] = false 时四段直接到位 (页面重建时恢复在 hero 态也用它). */
    fun setActive(active: Boolean, animated: Boolean) {
        if (this.active == active && !animated) {
            snapAll(if (active) 1f else 0f)
            return
        }
        if (this.active == active) return
        this.active = active
        cancelAll()
        val seq = ++sequence
        if (!animated) {
            snapAll(if (active) 1f else 0f)
            return
        }
        if (active) {
            animate(Track.Above, 1f, TV_HERO_ABOVE_OUT_MILLIS)
            val toneMillis = if (dark) TV_HERO_TONE_IN_MILLIS else TV_HERO_TONE_IN_MILLIS_LIGHT
            animate(Track.Tone, 1f, toneMillis) {
                if (seq != sequence) return@animate
                val textDelay = if (content > 0f) 0L else TV_HERO_TEXT_DELAY_MILLIS
                animate(Track.Content, 1f, TV_HERO_CONTENT_IN_MILLIS)
                animate(Track.Text, 1f, TV_HERO_TEXT_IN_MILLIS, delay = textDelay)
            }
        } else {
            animate(Track.Text, 0f, TV_HERO_TEXT_OUT_MILLIS)
            animate(Track.Content, 0f, TV_HERO_CONTENT_OUT_MILLIS) {
                if (seq != sequence) return@animate
                animate(Track.Above, 0f, TV_HERO_ABOVE_IN_MILLIS)
                animate(Track.Tone, 0f, if (dark) TV_HERO_TONE_OUT_MILLIS else TV_HERO_TONE_OUT_MILLIS_LIGHT)
            }
        }
    }

    private enum class Track { Above, Tone, Content, Text }

    private fun get(track: Track) = when (track) {
        Track.Above -> above
        Track.Tone -> tone
        Track.Content -> content
        Track.Text -> text
    }

    private fun set(track: Track, value: Float) {
        when (track) {
            Track.Above -> above = value
            Track.Tone -> tone = value
            Track.Content -> content = value
            Track.Text -> text = value
        }
    }

    private fun animate(track: Track, to: Float, fullMillis: Long, delay: Long = 0L, then: (() -> Unit)? = null) {
        val from = get(track)
        val distance = abs(to - from).coerceIn(0f, 1f)
        val millis = (fullMillis * distance).roundToLong()
        // 浅色主题下 tone 没有可看的变化 (同色), 照样按时长走, 后面的段按原来的先后接上
        if (millis <= 0L && delay <= 0L) {
            set(track, to)
            onUpdate(this)
            then?.invoke()
            return
        }
        val animator = ValueAnimator.ofFloat(from, to).apply {
            duration = millis
            startDelay = delay
            interpolator = LinearInterpolator()
            addUpdateListener {
                set(track, it.animatedValue as Float)
                onUpdate(this@TvNativeHeroTimeline)
            }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    running.remove(animation)
                    if (!cancelled) then?.invoke()
                }
            })
        }
        running.add(animator)
        animator.start()
    }

    private fun cancelAll() {
        running.toList().forEach { it.cancel() }
        running.clear()
    }

    private fun snapAll(value: Float) {
        cancelAll()
        above = value
        tone = value
        content = value
        text = value
        onUpdate(this)
    }
}

// 各段时长, 先后见 TvNativeHeroTimeline 的说明
private const val TV_HERO_ABOVE_OUT_MILLIS = 150L
private const val TV_HERO_TONE_IN_MILLIS = 300L
private const val TV_HERO_TONE_IN_MILLIS_LIGHT = 150L
private const val TV_HERO_CONTENT_IN_MILLIS = 400L
private const val TV_HERO_TEXT_DELAY_MILLIS = 100L
private const val TV_HERO_TEXT_IN_MILLIS = 300L
private const val TV_HERO_TEXT_OUT_MILLIS = 150L
private const val TV_HERO_CONTENT_OUT_MILLIS = 250L
private const val TV_HERO_ABOVE_IN_MILLIS = 250L
private const val TV_HERO_TONE_OUT_MILLIS = 350L
private const val TV_HERO_TONE_OUT_MILLIS_LIGHT = 250L
