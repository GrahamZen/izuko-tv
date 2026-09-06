/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.settings.tv

import android.graphics.Bitmap
import android.graphics.Color
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import me.him188.ani.app.navigation.SettingsTab
import me.him188.ani.app.ui.foundation.Res
import me.him188.ani.app.ui.foundation.a
import me.him188.ani.app.ui.foundation.tv.nativeview.TvNativeTestHost
import me.him188.ani.app.ui.foundation.tv.nativeview.testTextStyle
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.settings_tab_appearance
import me.him188.ani.app.ui.lang.settings_tab_player
import me.him188.ani.app.ui.lang.settings_tab_proxy
import me.him188.ani.app.ui.lang.settings_tab_theme
import me.him188.ani.app.ui.lang.settings_theme_title
import me.him188.ani.app.ui.lang.tv_settings_off
import me.him188.ani.app.ui.lang.tv_settings_on
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 设置页的按键导航: 方向键、确定、返回全由视图自己判 (见 [TvSettingsView]). 这里直接挂视图 (不经 Compose), 宿主照 [TvSettingsState]
 * 的规则改值、进出选项层, 再按清单重建内容交回视图.
 */
class TvSettingsViewTest {
    private data class Fake(val enabled: Boolean = false, val level: Int = 2, val other: Boolean = false, val hideOther: Boolean = false)

    private val fake = TvSettingKey.Stored<Fake>("fake") { error("not used") }
    private val list = TvSettingKey.Live<List<String>>("list") { error("not used") }

    private val catalog = tvSettingsCatalog {
        category(SettingsTab.APPEARANCE, Lang.settings_tab_appearance) {
            header(Lang.settings_theme_title)
            toggle(
                fake, Lang.settings_theme_title,
                qr = { TvQr("https://example.com/qr", caption = tvText("cap")) },
                read = { it.enabled },
                write = { copy(enabled = it) },
            )
            choice(
                fake, Lang.settings_theme_title,
                options = { (1..12).toList() },
                label = { tvText("L$it") },
                read = { it.level },
                write = { copy(level = it) },
            )
            header(Lang.settings_theme_title)
            toggle(fake, Lang.settings_theme_title, visible = { !it.hideOther }, read = { it.other }, write = { copy(other = it) })
        }
        category(SettingsTab.THEME, Lang.settings_tab_theme) {
            toggle(fake, Lang.settings_theme_title, read = { it.other }, write = { copy(other = it) })
            action(Lang.settings_theme_title, image = TvImage(Res.drawable.a)) { emptyList() }
            group(Lang.settings_theme_title) {
                toggle(fake, Lang.settings_theme_title, read = { it.other }, write = { copy(other = it) })
            }
        }
        legacy(SettingsTab.PROXY, Lang.settings_tab_proxy)
        category(SettingsTab.PLAYER, Lang.settings_tab_player) {
            toggle(fake, Lang.settings_theme_title, read = { it.other }, write = { copy(other = it) })
            entries(
                listOf(list),
                entries = { it[list].orEmpty() },
                key = { it },
                title = { tvText(it) },
                move = { emptyList() },
            ) { emptyList() }
        }
    }

    private val host = TvNativeTestHost()
    private lateinit var view: TvSettingsView
    private var value = Fake()
    private var nav = TvSettingsNav()
    private var order = listOf("a", "b", "c")
    private val clicked = mutableListOf<String>()
    private val reordered = mutableListOf<List<String>>()

    private val toggleId = "APPEARANCE.1"
    private val choiceId = "APPEARANCE.2"
    private val otherId = "APPEARANCE.4"
    private val imageId = "THEME.1"
    private val groupId = "THEME.2"

    private fun rebuild() {
        val resolver = TvTextResolver { res ->
            when (res) {
                Lang.tv_settings_on -> "on"
                Lang.tv_settings_off -> "off"
                else -> "t"
            }
        }
        view.submit(buildTvSettingsPage(catalog, TvSettingsValues(mapOf(fake to value, list to order)), nav, resolver))
    }

    @BeforeTest
    fun setUp() {
        host.launch()
        host.onMain {
            view = TvSettingsView(host.activity, testStyle(), "设置")
            view.imageLoader = { _, done -> done(Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)) }
            view.listener = object : TvSettingsViewListener {
                override fun onRailFocused(categoryId: String) {
                    nav = TvSettingsNav(categoryId)
                    rebuild()
                }

                override fun onRowClicked(rowId: String) {
                    clicked += rowId
                    val option = TvSettingsRowIds.parseOption(rowId)
                    when {
                        option != null -> {
                            value = value.copy(level = option.second + 1)
                            nav = nav.copy(drill = emptyList())
                        }

                        rowId == toggleId -> value = value.copy(enabled = !value.enabled)
                        rowId == choiceId -> nav = nav.copy(drill = listOf(choiceId))
                        rowId == groupId -> nav = nav.copy(drill = listOf(groupId))
                    }
                    rebuild()
                }

                override fun onBackInRight(): Boolean {
                    if (nav.drill.isEmpty()) return false
                    nav = nav.copy(drill = emptyList())
                    rebuild()
                    return true
                }

                override fun onPositionChanged(categoryId: String, rowId: String?) = Unit

                override fun onRowsReordered(rowId: String, order: List<String>) {
                    val keys = order.map { TvSettingsRowIds.parseEntry(it)!!.second }
                    reordered += keys
                    this@TvSettingsViewTest.order = keys
                    rebuild()
                }
            }
            host.root.addView(view, FrameLayout.LayoutParams(1920, 1080))
            rebuild()
            view.requestEntryFocus()
        }
    }

    @AfterTest
    fun tearDown() = host.close()

    private fun focusedRow(): TvSettingsRow? = (view.findFocus() as? TvSettingsRowView)?.row

    private fun waitFocus(id: String) =
        host.waitUntil("焦点到 $id (现在 ${focusedRow()?.id})") { focusedRow()?.id == id }

    private fun enterRight() {
        waitFocus("cat:APPEARANCE")
        host.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        waitFocus(toggleId)
    }

    @Test
    fun `lands on the category and up and down switch the list`() {
        waitFocus("cat:APPEARANCE")
        host.press(KeyEvent.KEYCODE_DPAD_DOWN)
        waitFocus("cat:THEME")
        assertEquals("THEME", nav.categoryId)
        host.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        waitFocus("THEME.0")
    }

    @Test
    fun `confirm on a toggle flips it in place`() {
        enterRight()
        host.press(KeyEvent.KEYCODE_DPAD_CENTER)
        host.waitUntil("开关变成开") { focusedRow()?.value == "on" }
        assertEquals(toggleId, host.onMain { focusedRow()?.id })
    }

    /** 说明栏的二维码视图 (在主线程上调, 如 waitUntil 的条件里). */
    private fun qrView(): TvSettingsQrView =
        (0 until view.childCount).map { view.getChildAt(it) }.filterIsInstance<TvSettingsQrView>().single()

    @Test
    fun `a row with a qr code shows it in the info column`() {
        enterRight()
        host.waitUntil("说明栏画出二维码") { qrView().let { it.visibility == View.VISIBLE && it.shownContent == "https://example.com/qr" } }
        host.press(KeyEvent.KEYCODE_DPAD_DOWN)
        waitFocus(choiceId)
        host.waitUntil("没有码的行不画") { qrView().visibility == View.GONE }
    }

    private fun imageView(): TvSettingsImageView =
        (0 until view.childCount).map { view.getChildAt(it) }.filterIsInstance<TvSettingsImageView>().single()

    private fun enterTheme() {
        waitFocus("cat:APPEARANCE")
        host.press(KeyEvent.KEYCODE_DPAD_DOWN)
        waitFocus("cat:THEME")
        host.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        waitFocus("THEME.0")
    }

    @Test
    fun `a row with an image shows it above the info title`() {
        enterTheme()
        host.waitUntil("没有图的行不画") { imageView().visibility == View.GONE }
        host.press(KeyEvent.KEYCODE_DPAD_DOWN)
        waitFocus(imageId)
        host.waitUntil("说明栏画出图") { imageView().let { it.visibility == View.VISIBLE && it.hasImage && it.height > 0 } }
    }

    @Test
    fun `entering a group lists its items and back returns to the group row`() {
        enterTheme()
        host.press(KeyEvent.KEYCODE_DPAD_DOWN)
        waitFocus(imageId)
        host.press(KeyEvent.KEYCODE_DPAD_DOWN)
        waitFocus(groupId)
        host.press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFocus("$groupId.0")
        assertEquals(listOf(groupId), nav.drill)
        assertTrue(host.onMain { view.handleBack() })
        waitFocus(groupId)
        assertTrue(nav.drill.isEmpty())
    }

    @Test
    fun `losing the focused row moves focus to the landing row`() {
        enterRight()
        host.press(KeyEvent.KEYCODE_DPAD_DOWN)
        waitFocus(choiceId)
        host.press(KeyEvent.KEYCODE_DPAD_DOWN)
        waitFocus(otherId)
        host.onMain {
            value = value.copy(hideOther = true)
            rebuild()
        }
        // 持焦的那一行没了: 落到这一屏的落点 (第一条能停的行), 焦点不出中栏
        waitFocus(toggleId)
    }

    @Test
    fun `down skips headers`() {
        enterRight()
        host.press(KeyEvent.KEYCODE_DPAD_DOWN)
        waitFocus(choiceId)
        host.press(KeyEvent.KEYCODE_DPAD_DOWN)
        waitFocus(otherId)
        // 到底不动
        host.press(KeyEvent.KEYCODE_DPAD_DOWN)
        waitFocus(otherId)
    }

    @Test
    fun `choosing an option writes it and returns to the item`() {
        enterRight()
        host.press(KeyEvent.KEYCODE_DPAD_DOWN)
        waitFocus(choiceId)
        host.press(KeyEvent.KEYCODE_DPAD_CENTER)
        // 落在当前值上
        waitFocus(TvSettingsRowIds.option(choiceId, 1))
        host.press(KeyEvent.KEYCODE_DPAD_DOWN)
        waitFocus(TvSettingsRowIds.option(choiceId, 2))
        host.press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFocus(choiceId)
        assertEquals(3, value.level)
        host.waitUntil("值变成 L3") { focusedRow()?.value == "L3" }
    }

    @Test
    fun `back leaves the options then the list then lets the page close`() {
        enterRight()
        host.press(KeyEvent.KEYCODE_DPAD_DOWN)
        waitFocus(choiceId)
        host.press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFocus(TvSettingsRowIds.option(choiceId, 1))
        assertTrue(host.onMain { view.handleBack() })
        waitFocus(choiceId)
        assertEquals(2, value.level)
        assertTrue(host.onMain { view.handleBack() })
        waitFocus("cat:APPEARANCE")
        assertFalse(host.onMain { view.handleBack() })
    }

    @Test
    fun `far options land in view when entering the choice`() {
        host.onMain {
            value = value.copy(level = 12)
            rebuild()
        }
        enterRight()
        host.press(KeyEvent.KEYCODE_DPAD_DOWN)
        waitFocus(choiceId)
        host.press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFocus(TvSettingsRowIds.option(choiceId, 11))
    }

    @Test
    fun `legacy category opens through its single row`() {
        waitFocus("cat:APPEARANCE")
        host.press(KeyEvent.KEYCODE_DPAD_DOWN)
        waitFocus("cat:THEME")
        host.press(KeyEvent.KEYCODE_DPAD_DOWN)
        waitFocus("cat:PROXY")
        host.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        waitFocus("legacy:PROXY")
        host.press(KeyEvent.KEYCODE_DPAD_CENTER)
        host.waitUntil("点了打开原来的设置页") { clicked.lastOrNull() == "legacy:PROXY" }
    }

    @Test
    fun `left in the list returns to the active category`() {
        enterRight()
        host.press(KeyEvent.KEYCODE_DPAD_LEFT)
        waitFocus("cat:APPEARANCE")
    }

    private val entriesId = "PLAYER.1"

    private fun entry(key: String) = TvSettingsRowIds.entry(entriesId, key)

    /** 左栏走到「播放器」那一类, 进中栏, 落到列表第一行. */
    private fun enterList() {
        waitFocus("cat:APPEARANCE")
        repeat(3) { host.press(KeyEvent.KEYCODE_DPAD_DOWN) }
        waitFocus("cat:PLAYER")
        host.press(KeyEvent.KEYCODE_DPAD_RIGHT)
        waitFocus("PLAYER.0")
        host.press(KeyEvent.KEYCODE_DPAD_DOWN)
        waitFocus(entry("a"))
    }

    private fun longPressConfirm() {
        host.keyDown(KeyEvent.KEYCODE_DPAD_CENTER)
        host.keyDown(KeyEvent.KEYCODE_DPAD_CENTER, repeatCount = 1)
        host.keyUp(KeyEvent.KEYCODE_DPAD_CENTER)
    }

    @Test
    fun `long press picks a row up and confirm drops it in its new place`() {
        enterList()
        longPressConfirm()
        host.press(KeyEvent.KEYCODE_DPAD_DOWN)
        waitFocus(entry("a"))
        host.press(KeyEvent.KEYCODE_DPAD_DOWN)
        // 到组的末尾停住
        host.press(KeyEvent.KEYCODE_DPAD_DOWN)
        waitFocus(entry("a"))
        assertTrue(reordered.isEmpty(), "reported before dropping")
        host.press(KeyEvent.KEYCODE_DPAD_CENTER)
        host.waitUntil("放下后报新顺序") { reordered.isNotEmpty() }
        assertEquals(listOf("b", "c", "a"), reordered.single())
        waitFocus(entry("a"))
        // 放下那一下不算点击
        assertTrue(clicked.none { it == entry("a") })
    }

    @Test
    fun `moving stays inside its group and back drops it`() {
        enterList()
        longPressConfirm()
        // 上面是开关, 不在同一组: 不动
        host.press(KeyEvent.KEYCODE_DPAD_UP)
        waitFocus(entry("a"))
        host.press(KeyEvent.KEYCODE_DPAD_DOWN)
        waitFocus(entry("a"))
        assertTrue(host.onMain { view.handleBack() })
        host.waitUntil("返回放下") { reordered.isNotEmpty() }
        assertEquals(listOf("b", "a", "c"), reordered.single())
        // 已经放下了: 再上下就是普通的走焦点
        host.press(KeyEvent.KEYCODE_DPAD_UP)
        waitFocus(entry("b"))
    }

    @Test
    fun `short press on a movable row is a click`() {
        enterList()
        host.press(KeyEvent.KEYCODE_DPAD_CENTER)
        host.waitUntil("点了第一行") { clicked.lastOrNull() == entry("a") }
        assertTrue(reordered.isEmpty())
    }

    private fun testStyle(): TvSettingsStyle = TvSettingsStyle(
        paddingStartPx = 96,
        paddingEndPx = 96,
        paddingTopPx = 64,
        paddingBottomPx = 48,
        titleGapPx = 36,
        railWidthPx = 400,
        infoWidthFraction = 0.28f,
        columnGapPx = 56,
        rowGapPx = 8,
        rowHeightPx = 92,
        headerTopGapPx = 28,
        headerBottomGapPx = 8,
        cornerPx = 20f,
        rowPaddingHPx = 32,
        trailingGapPx = 32,
        swatchSizePx = 36,
        swatchGapPx = 24,
        drillTitleBottomGapPx = 12,
        infoTitleGapPx = 20,
        focusBleedPx = 16,
        qrSizePx = 300,
        qrGapPx = 16,
        infoImageSizePx = 144,
        pageTitle = testTextStyle(48f, 60),
        railTitle = testTextStyle(32f, 44),
        rowTitle = testTextStyle(32f, 44),
        rowValue = testTextStyle(28f, 40),
        header = testTextStyle(24f, 32),
        drillTitle = testTextStyle(28f, 40),
        infoTitle = testTextStyle(32f, 44),
        infoBody = testTextStyle(28f, 40),
        activeBg = 0x1AFFFFFF,
        focusedBg = Color.WHITE,
        qrBg = Color.WHITE,
        qrFg = Color.BLACK,
        text = Color.WHITE,
        textSecondary = 0x99FFFFFF.toInt(),
        focusedText = Color.BLACK,
        focusedTextSecondary = 0xCC000000.toInt(),
        focusScale = 1.03f,
        marqueeRepeat = 0,
        focusInMillis = 0,
        focusOutMillis = 0,
        animated = false,
    )
}
