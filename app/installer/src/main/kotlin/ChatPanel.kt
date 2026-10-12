/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package io.github.grahamzen.izuko.installer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/**
 * 界面右边的交流群: QQ 群与 Telegram 两个码上下排, 码右边写名字 (QQ 再写群号与入群问题的答案); 下载、安装、出错时都在 ——
 * 等下载的那一阵正好扫码, 装不上也能进群问. 这一栏定宽, 文字在栏里换行, 不把左边的内容挤窄.
 * 群只给装了应用的人看, 链接不写进源码, 构建时注入 (见 build.gradle.kts 的 CHAT_*); 都没配时返回 null, 不放这一栏.
 */
internal fun buildChatPanel(context: Context, muted: Int): View? {
    val qqLink = BuildConfig.CHAT_QQ_LINK
    val telegramLink = BuildConfig.CHAT_TELEGRAM_LINK
    if (qqLink.isBlank() && telegramLink.isBlank()) return null
    val density = context.resources.displayMetrics.density
    fun dp(value: Int) = (value * density).toInt()
    fun text(sizeSp: Float, color: Int, value: String) = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(color)
        text = value
    }

    val panel = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    panel.addView(text(17f, Color.WHITE, context.getString(R.string.chat_title)))
    panel.addView(text(13f, muted, context.getString(R.string.chat_hint)), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
    var codes = 0
    fun addCode(link: String, label: String, lines: List<String>) {
        val sizePx = dp(QR_SIZE_DP)
        val bitmap = qrBitmap(link, sizePx) ?: return
        val box = sizePx + dp(QR_QUIET_ZONE_DP) * 2
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(
            ImageView(context).apply {
                setImageBitmap(bitmap)
                // 浅底深码: 白色圆角底板当扫码需要的留白
                background = GradientDrawable().apply {
                    setColor(Color.WHITE)
                    cornerRadius = dp(8).toFloat()
                }
                val pad = dp(QR_QUIET_ZONE_DP)
                setPadding(pad, pad, pad, pad)
            },
            LinearLayout.LayoutParams(box, box),
        )
        val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(text(15f, Color.WHITE, label))
        for (line in lines) texts.addView(text(12f, muted, line), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(16) })
        panel.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(if (codes == 0) 16 else QR_GAP_DP) })
        codes++
    }
    if (qqLink.isNotBlank()) {
        addCode(
            qqLink,
            context.getString(R.string.chat_qq_short),
            listOfNotNull(
                BuildConfig.CHAT_QQ_ID.takeIf { it.isNotBlank() }?.let { context.getString(R.string.chat_qq_id, it) },
                context.getString(R.string.chat_qq_answer, BuildConfig.REPOSITORY.substringBefore('/')),
            ),
        )
    }
    if (telegramLink.isNotBlank()) addCode(telegramLink, context.getString(R.string.chat_telegram), emptyList())
    if (codes == 0) return null
    panel.layoutParams = LinearLayout.LayoutParams(dp(PANEL_WIDTH_DP), LinearLayout.LayoutParams.WRAP_CONTENT)
    return panel
}

/**
 * 把交流群一栏 ([buildChatPanel]) 拼在 [content] 右边, 两栏都垂直居中; 没有这一栏时原样返回 [content].
 * [content] 右边原来的留白挪到两栏外面, 中间只留一道间隔.
 */
internal fun attachChatPanel(context: Context, content: LinearLayout, muted: Int): View {
    val panel = buildChatPanel(context, muted) ?: return content
    val gap = (PANEL_GAP_DP * context.resources.displayMetrics.density).toInt()
    val endPadding = content.paddingRight
    content.setPadding(content.paddingLeft, content.paddingTop, gap, content.paddingBottom)
    return LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, 0, endPadding, 0)
        addView(content, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(panel, panel.layoutParams ?: LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }
}

/** 黑码白底的位图; 编码失败 (链接太长之类) 返回 null, 这个码就不放. */
internal fun qrBitmap(content: String, sizePx: Int): Bitmap? = runCatching {
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, mapOf(EncodeHintType.MARGIN to 0))
    val width = matrix.width
    val height = matrix.height
    val pixels = IntArray(width * height) { i -> if (matrix[i % width, i / width]) Color.BLACK else Color.WHITE }
    Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
}.getOrNull()

private const val QR_SIZE_DP = 104
private const val QR_QUIET_ZONE_DP = 8
private const val QR_GAP_DP = 16
private const val PANEL_WIDTH_DP = 300
private const val PANEL_GAP_DP = 48
