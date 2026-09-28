/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BangumiCoverThumbnailTest {
    private val cover = "https://lain.bgm.tv/pic/cover/l/fe/45/6049_zy52O.jpg"

    private fun thumbnail(width: Int) = "https://lain.bgm.tv/r/$width/pic/cover/l/fe/45/6049_zy52O.jpg"

    @BeforeTest
    fun resetBefore() = resetBangumiCoverThumbnailsForTest()

    @AfterTest
    fun resetAfter() = resetBangumiCoverThumbnailsForTest()

    /** 图床只认这几档宽度, 别的宽度回 400 "invalid size format". */
    @Test
    fun `picks the smallest allowed width not below the display width`() {
        assertEquals(thumbnail(100), bangumiCoverThumbnailUrl(cover, 100))
        assertEquals(thumbnail(200), bangumiCoverThumbnailUrl(cover, 101))
        // 1080p 界面上的海报卡 (275px 宽)
        assertEquals(thumbnail(400), bangumiCoverThumbnailUrl(cover, 275))
        // 同一张卡在 4K 界面上 (550px 宽)
        assertEquals(thumbnail(600), bangumiCoverThumbnailUrl(cover, 550))
        assertEquals(thumbnail(1200), bangumiCoverThumbnailUrl(cover, 1200))
    }

    @Test
    fun `wider than the largest width uses the original`() {
        assertNull(bangumiCoverThumbnailUrl(cover, 1201))
        assertNull(bangumiCoverThumbnailUrl(cover, 0))
    }

    /** 数据里存的可能是镜像域名; 镜像原样透传这个路径, 域名保持不动. */
    @Test
    fun `keeps mirror hosts and scheme`() {
        assertEquals(
            "https://lain.bangumi.vip/r/400/pic/cover/l/fe/45/6049_zy52O.jpg",
            bangumiCoverThumbnailUrl("https://lain.bangumi.vip/pic/cover/l/fe/45/6049_zy52O.jpg", 275),
        )
        assertEquals(
            "http://lain.bgm.tv/r/400/pic/cover/l/fe/45/6049_zy52O.jpg",
            bangumiCoverThumbnailUrl("http://lain.bgm.tv/pic/cover/l/fe/45/6049_zy52O.jpg", 275),
        )
    }

    @Test
    fun `only rewrites large covers on the image host`() {
        listOf(
            "https://api.bgm.tv/v0/subjects/6049/image?type=large",
            "https://lain.bgm.tv/img/no_icon_subject.png",
            "https://lain.bgm.tv/pic/cover/c/fe/45/6049_zy52O.jpg",
            "https://lain.bgm.tv/pic/crt/l/af/91/9962_prsn_uzB8A.jpg",
            thumbnail(400),
            "https://image.tmdb.org/t/p/w500/abc.jpg",
            "https://example.com/lain.bgm.tv/pic/cover/l/fe/45/6049_zy52O.jpg",
            "file:///data/lain.bgm.tv/pic/cover/l/fe/45/6049_zy52O.jpg",
            "https://lain.bgm.tv",
        ).forEach { url ->
            assertNull(bangumiCoverThumbnailUrl(url, 275), url)
        }
    }

    @Test
    fun `maps a thumbnail back to its original`() {
        assertEquals(cover, bangumiCoverOriginalUrl(thumbnail(400)))
        assertEquals(
            "https://lain.bangumi.vip/pic/cover/l/fe/45/6049_zy52O.jpg",
            bangumiCoverOriginalUrl("https://lain.bangumi.vip/r/600/pic/cover/l/fe/45/6049_zy52O.jpg"),
        )
        assertNull(bangumiCoverOriginalUrl(cover))
        assertNull(bangumiCoverOriginalUrl("https://lain.bgm.tv/r/200/pic/crt/l/af/91/9962_prsn_uzB8A.jpg"))
    }

    /** 偶发一两次缩略图失败 (图床抖动) 不停用; 连续几次失败而原图能取到, 才当这条线路不支持. */
    @Test
    fun `stops rewriting after consecutive fallbacks only`() {
        noteBangumiCoverThumbnailFallback(thumbnailFailedButOriginalWorked = true)
        noteBangumiCoverThumbnailFallback(thumbnailFailedButOriginalWorked = true)
        noteBangumiCoverThumbnailFallback(thumbnailFailedButOriginalWorked = false)
        noteBangumiCoverThumbnailFallback(thumbnailFailedButOriginalWorked = true)
        noteBangumiCoverThumbnailFallback(thumbnailFailedButOriginalWorked = true)
        assertTrue(bangumiCoverThumbnailsEnabled())
        assertEquals(thumbnail(400), bangumiCoverThumbnailUrl(cover, 275))

        noteBangumiCoverThumbnailFallback(thumbnailFailedButOriginalWorked = true)
        assertFalse(bangumiCoverThumbnailsEnabled())
        assertNull(bangumiCoverThumbnailUrl(cover, 275))
    }
}
