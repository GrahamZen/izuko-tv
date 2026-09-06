/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.tv.nativeview

import android.graphics.Bitmap
import android.widget.FrameLayout
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 按住方向键连走时卡片先不急着换 ([TvNativeCoverDefer]): 连发期间重新绑定的普通海报先顶着上一个条目 (番名不换), 拿到焦点的那张当场换番名,
 * 封面上本来就是这一张的当场整张换上;
 * 松手后整张换上; 连发期间离屏、松手后才挂回来的卡在挂回来时补上. 卡片的番名从 contentDescription 读 (与番名同时换).
 */
class TvNativeCoverDeferTest {
    private val host = TvNativeTestHost()
    private lateinit var card: TvNativeCardView

    @BeforeTest
    fun setUp() {
        host.launch()
        host.onMain {
            card = TvNativeCardView(host.activity, testWallStyle())
            host.root.addView(card, FrameLayout.LayoutParams(300, 500))
            card.bind(cover("A"), host.sketch)
        }
    }

    @AfterTest
    fun tearDown() {
        host.onMain { TvNativeCoverDefer.setDeferring(false) }
        host.close()
    }

    /** 本地不存在的文件: 加载失败也不碰网络, 只为让卡片算「有封面的普通海报」. */
    private fun cover(title: String) = TvNativeCard(imageUrl = "file:///nonexistent/$title.jpg", title = title)

    @Test
    fun `rebinding while repeating keeps the old card until release`() {
        host.onMain {
            TvNativeCoverDefer.setDeferring(true)
            card.bind(cover("B"), host.sketch)
            assertEquals("A", card.contentDescription, "连发期间先顶着上一个条目")
            TvNativeCoverDefer.setDeferring(false)
            assertEquals("B", card.contentDescription, "松手后整张换上")
        }
    }

    @Test
    fun `focused card takes its new title right away`() {
        host.onMain {
            TvNativeCoverDefer.setDeferring(true)
            card.bind(cover("B"), host.sketch)
            card.requestFocus()
            assertEquals("B", card.contentDescription, "拿到焦点的那张当场换番名")
        }
    }

    @Test
    fun `card reattached after release catches up`() {
        host.onMain {
            TvNativeCoverDefer.setDeferring(true)
            card.bind(cover("B"), host.sketch)
            host.root.removeView(card)
            TvNativeCoverDefer.setDeferring(false)
            assertEquals("A", card.contentDescription, "离屏的卡松手时不动")
            host.root.addView(card, FrameLayout.LayoutParams(300, 500))
            assertEquals("B", card.contentDescription, "挂回来时补上")
        }
    }

    @Test
    fun `rebinding without repeating applies right away`() {
        host.onMain {
            card.bind(cover("B"), host.sketch)
            assertEquals("B", card.contentDescription)
        }
    }

    /** 本地真图: 加载得成, 用来走「封面已上屏」那几条路. */
    private fun realCoverUrl(name: String): String {
        val file = File(host.activity.cacheDir, "$name.png")
        file.outputStream().use { Bitmap.createBitmap(40, 60, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it) }
        return "file://" + file.absolutePath
    }

    @Test
    fun `rebinding to the cover already shown applies right away`() {
        val url = realCoverUrl("shown")
        host.onMain { card.bind(TvNativeCard(imageUrl = url, title = "C1"), host.sketch) }
        host.waitUntil("封面上屏") { card.shownCoverUrl == url }
        host.onMain {
            TvNativeCoverDefer.setDeferring(true)
            card.bind(TvNativeCard(imageUrl = url, title = "C2"), host.sketch)
            assertEquals("C2", card.contentDescription, "封面上本来就是这一张: 不用等松手")
        }
    }

    @Test
    fun `rebinding the shown cover keeps it without reloading`() {
        val url = realCoverUrl("rebind")
        host.onMain { card.bind(TvNativeCard(imageUrl = url, title = "D1"), host.sketch) }
        host.waitUntil("封面上屏") { card.shownCoverUrl == url }
        host.onMain {
            // 整份重绑 (分页到了新一页) 时这张图可能早被挤出内存缓存: 重载就会先清成底色
            host.sketch.memoryCache.clear()
            card.bind(TvNativeCard(imageUrl = url, title = "D2"), host.sketch)
            assertEquals("D2", card.contentDescription)
            assertEquals(url, card.shownCoverUrl, "封面上本来就是这一张: 不重载")
        }
    }

    @Test
    fun `rebinding the same url with obscure changed reloads the cover`() {
        val url = realCoverUrl("obscure")
        host.onMain { card.bind(TvNativeCard(imageUrl = url, title = "E1"), host.sketch) }
        host.waitUntil("封面上屏") { card.shownCoverUrl == url }
        host.onMain {
            card.bind(TvNativeCard(imageUrl = url, title = "E1", obscure = true), host.sketch)
            assertEquals(null, card.shownCoverUrl, "打码与否画出来不一样: 重载")
        }
    }
}
