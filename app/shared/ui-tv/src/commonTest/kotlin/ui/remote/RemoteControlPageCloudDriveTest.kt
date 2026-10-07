/*
 * Copyright (C) 2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.remote

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.him188.ani.app.data.models.preference.CloudDriveAccount
import me.him188.ani.app.data.models.preference.CloudDrivePlaybackMode
import me.him188.ani.app.domain.mediasource.clouddrive.CloudDriveProtocol
import me.him188.ani.app.domain.mediasource.clouddrive.CloudDriveQrLoginState
import me.him188.ani.app.domain.mediasource.clouddrive.DriveLoginConfig
import me.him188.ani.app.domain.mediasource.clouddrive.DriveMobileOpen
import me.him188.ani.app.domain.mediasource.clouddrive.DriveQrLogin
import me.him188.ani.app.domain.mediasource.clouddrive.DriveRequest
import me.him188.ani.app.domain.mediasource.clouddrive.DriveTier
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 「数据源」页的网盘卡片: 卡片上的一切 (名字、扫码用的 App、手机唤起链接、Cookie 说明、档位) 都取自网盘的协议, 页面里不写任何具体网盘.
 *
 * 手机上「在 App 中确认」的按钮要用协议给的唤起链接打开二维码内容: 确认页常常只在网盘 App 内可用,
 * 直接链到二维码里的 https 链接, 手机的其他浏览器打开后完成不了登录.
 */
class RemoteControlPageCloudDriveTest {
    private val page = renderRemoteControlPage(
        initialTab = "player",
        searchFormHtml = "",
        requestSectionHtml = "",
    )

    private val qrLogin = DriveQrLogin(
        start = DriveRequest(url = "https://api.drive.test/qr/token"),
        token = "data.token",
        content = "https://login.drive.test/qr?t={token}",
        poll = DriveRequest(url = "https://api.drive.test/qr/poll"),
        appName = "测试网盘 App",
        mobileOpen = DriveMobileOpen(
            android = "testdrive://open?url={url}",
            ios = "testdrive-ios://open?url={url}",
            inAppUserAgent = "TestDriveApp/",
        ),
    )

    private val protocol = CloudDriveProtocol(
        id = "testdrive",
        name = "测试网盘",
        names = mapOf("en" to "Test Drive"),
        login = DriveLoginConfig(requiredCookies = listOf("sid"), cookieHint = "在 drive.test 登录后复制 Cookie", qr = qrLogin),
        tiers = listOf(DriveTier(match = "^VIP", label = "会员", labels = mapOf("en" to "Member"))),
    )

    private val account = CloudDriveAccount(
        cookie = "sid=1",
        nickname = "小明",
        tier = "VIP_YEAR",
        playbackMode = CloudDrivePlaybackMode.TRANSCODED,
    )

    private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content
    private fun JsonObject.bool(key: String) = getValue(key).jsonPrimitive.boolean

    @Test
    fun `confirm button opens the qr content through the protocol's app link`() {
        // 模板按平台取, {url} 换成编码后的二维码内容
        assertContains(page, "var t = m ? (ios ? m.ios : m.android) : '';")
        assertContains(page, "t.split('{url}').join(encodeURIComponent(link))")
        assertContains(page, "'<a class=\"primary\" href=\"' + esc(href) + '\"'")
        assertFalse(
            page.contains("href=\"' + esc(q.link) + '\""),
            "按钮直接链到二维码链接, 手机浏览器里完成不了登录",
        )
    }

    @Test
    fun `page opened inside the drive app is detected by the protocol's ua marker`() {
        assertContains(page, "var k = d.mobileOpen && d.mobileOpen.inAppUserAgent;")
        assertContains(page, "ua.toLowerCase().indexOf(k.toLowerCase()) >= 0")
    }

    @Test
    fun `drive card data comes from the protocol`() {
        val json = RemoteCloudDrive.driveJson(
            protocol = protocol,
            account = account,
            sourceName = "我的测试网盘",
            sourceEnabled = false,
            expired = false,
            qr = CloudDriveQrLoginState.WaitingForScan("https://login.drive.test/qr?t=abc", System.currentTimeMillis() + 60_000),
            languageTag = "en",
        )
        assertEquals("testdrive", json.string("id"))
        assertEquals("Test Drive", json.string("name"))
        assertEquals("testdrive-drive", json.string("sourceId"))
        assertEquals("我的测试网盘", json.string("sourceName"))
        assertFalse(json.bool("enabled"))
        assertTrue(json.bool("loggedIn"))
        assertEquals("小明", json.string("nickname"))
        assertEquals("Member", json.string("tier"))
        assertTrue(json.bool("transcode"))
        assertFalse(json.bool("supportsTranscoded"))
        assertTrue(json.bool("supportsQr"))
        assertEquals("测试网盘 App", json.string("appName"))
        assertEquals("在 drive.test 登录后复制 Cookie", json.string("cookieHint"))
        val open = json.getValue("mobileOpen").jsonObject
        assertEquals("testdrive://open?url={url}", open.string("android"))
        assertEquals("testdrive-ios://open?url={url}", open.string("ios"))
        assertEquals("TestDriveApp/", open.string("inAppUserAgent"))
        val qr = json.getValue("qr").jsonObject
        assertEquals("waiting", qr.string("state"))
        assertEquals("https://login.drive.test/qr?t=abc", qr.string("link"))
    }

    @Test
    fun `drive without qr login offers only the cookie`() {
        val plain = protocol.copy(login = DriveLoginConfig(), tiers = emptyList())
        val json = RemoteCloudDrive.driveJson(
            protocol = plain,
            account = CloudDriveAccount.Default,
            sourceName = null,
            sourceEnabled = true,
            expired = false,
            qr = null,
            languageTag = "zh-CN",
        )
        assertEquals("测试网盘", json.string("name"))
        // 找不到数据源时用网盘名
        assertEquals("测试网盘", json.string("sourceName"))
        assertFalse(json.bool("loggedIn"))
        assertFalse(json.bool("supportsQr"))
        assertEquals("", json.string("appName"))
        assertEquals("", json.string("tier"))
        assertNull(json["mobileOpen"])
        assertNull(json["qr"])
    }
}
