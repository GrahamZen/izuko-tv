/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.clouddrive

import me.him188.ani.datasources.api.source.MediaSourceLocation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CloudDriveLocationTest {
    @Test
    fun `local folders are files on this device`() {
        assertEquals(MediaSourceLocation.Local, LocalFolderDrive.newProtocol("Seagate").mediaLocation)
    }

    @Test
    fun `WebDAV on the home network is LAN and WebDAV on the internet stays online`() {
        fun location(url: String) = WebDavSources.newProtocol("NAS", url).mediaLocation
        assertEquals(MediaSourceLocation.Lan, location("http://192.168.1.10:5005/dav"))
        assertEquals(MediaSourceLocation.Lan, location("http://10.0.0.2/"))
        assertEquals(MediaSourceLocation.Lan, location("https://172.20.1.1/webdav"))
        assertEquals(MediaSourceLocation.Lan, location("http://nas.local:5005"))
        assertEquals(MediaSourceLocation.Lan, location("http://nas:5005"))
        assertEquals(MediaSourceLocation.Lan, location("http://[fd00::12]:5005/"))
        assertEquals(MediaSourceLocation.Online, location("https://dav.jianguoyun.com/dav/"))
        assertEquals(MediaSourceLocation.Online, location("http://172.32.0.1/"))
        assertEquals(MediaSourceLocation.Online, location("http://8.8.8.8/"))
    }

    @Test
    fun `cloud drives from subscriptions are online`() {
        assertEquals(MediaSourceLocation.Online, CloudDriveProtocol(id = "drive", name = "Drive").mediaLocation)
    }

    @Test
    fun `LAN hosts`() {
        listOf("localhost", "127.0.0.1", "169.254.3.4", "192.168.0.1", "router.lan", "nas.home.arpa", "box.internal", "::1", "fe80::1", "[fd12::3]")
            .forEach { assertTrue(isLanHost(it), it) }
        listOf("example.com", "100.64.1.2", "192.169.0.1", "2001:db8::1", "256.1.1.1", "")
            .forEach { assertFalse(isLanHost(it), it) }
    }
}
