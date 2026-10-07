/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.clouddrive

import io.ktor.client.request.HttpRequestData
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.him188.ani.app.domain.mediasource.directapi.JsonNode
import me.him188.ani.datasources.api.source.MediaSourceConfig
import me.him188.ani.datasources.api.source.deserializeArgumentsOrNull
import me.him188.ani.datasources.api.source.serializeArguments
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 协议本身: 模板替换、文件条目的字段、显示名与档位、分享链接、资源的占位地址.
 */
class CloudDriveProtocolTest {
    private val protocol = TestDrive.protocol

    // region 模板

    private val template = DriveTemplate(
        mapOf(
            "ids" to JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b"))),
            "name" to JsonPrimitive("新 文件夹"),
            "page" to JsonPrimitive(2),
        ),
    )

    @Test
    fun `text templates join list variables with commas and keep unknown variables`() {
        assertEquals(
            "https://api.drive.test/list?ids=a,b&page=2&name=新 文件夹&x={unknown}",
            template.text("https://api.drive.test/list?ids={ids}&page={page}&name={name}&x={unknown}"),
        )
        assertEquals("no variables", template.text("no variables"))
    }

    @Test
    fun `json templates put whole-string variables in as json values`() {
        val body = buildJsonObject {
            put("ids", "{ids}")
            put("page", "{page}")
            put("label", "名字: {name} ({page})")
            put("count", 1)
            put("flag", true)
            put(
                "nested",
                buildJsonArray {
                    add("{ids}")
                    add(buildJsonObject { put("name", "{name}") })
                },
            )
            put("keep", "{unknown}")
        }
        val expected = Json.parseToJsonElement(
            """{"ids":["a","b"],"page":2,"label":"名字: 新 文件夹 (2)","count":1,"flag":true,"nested":[["a","b"],{"name":"新 文件夹"}],"keep":"{unknown}"}""",
        )
        assertEquals(expected, template.json(body))
    }

    @Test
    fun `list variables in a query parameter are sent joined by commas`() = runTest {
        val viaQuery = protocol.copy(
            api = protocol.api.copy(
                deleteFiles = DriveValueOp(
                    DriveRequest(url = "https://{host}/api/files/delete", query = mapOf("ids" to "{fileIds}", "request" to "{requestId}")),
                    value = "data.task",
                ),
            ),
        )
        val requests = mutableListOf<HttpRequestData>()
        val service = testDriveService(accountsOf(TestDrive.loggedIn), viaQuery) { request ->
            requests += request
            reply("""{"code":0,"data":{}}""")
        }
        service.api.deleteFiles(listOf("f1", "f2"))
        service.api.deleteFiles(listOf("f3"))
        assertEquals(listOf("f1,f2", "f3"), requests.map { it.url.parameters["ids"] })
        assertEquals("tv", requests.first().url.parameters["client"])
        // 每个请求一个新的请求 id
        val ids = requests.map { it.url.parameters["request"] }
        assertTrue(ids.all { !it.isNullOrBlank() && it != "{requestId}" }, "$ids")
        assertNotEquals(ids[0], ids[1])
    }

    // endregion

    // region 文件条目

    private fun parse(json: String, fields: DriveFileFields = protocol.file) =
        CloudDriveApi.parseFile(JsonNode(Json.parseToJsonElement(json)), fields)

    @Test
    fun `file entries are read with the field paths of the protocol`() {
        val file = parse(
            """{"id":"f1","name":"Show - 01.mkv","parent":"d1","isDir":false,"size":123,"mtime":1790437896202,"height":1080,"kind":"video","token":"t1"}""",
        )!!
        assertEquals("f1", file.fid)
        assertEquals("Show - 01.mkv", file.fileName)
        assertEquals("d1", file.parentFid)
        assertFalse(file.dir)
        assertEquals(123, file.size)
        assertEquals(1790437896202, file.updatedAt)
        assertEquals(1080, file.videoHeight)
        assertTrue(file.isVideo)
        assertEquals("t1", file.shareToken)

        // 文件夹不算视频; 没有的字段取默认值
        val folder = parse("""{"id":"d1","name":"Show","isDir":true,"kind":"video"}""")!!
        assertTrue(folder.dir)
        assertFalse(folder.isVideo)
        assertEquals("", folder.parentFid)
        assertEquals(0, folder.size)
        // 类别不是视频的不算
        assertFalse(parse("""{"id":"s1","name":"Show - 01.ass","kind":"file"}""")!!.isVideo)
        // 没有 id 的不要
        assertNull(parse("""{"name":"orphan.mkv","kind":"video"}"""))
    }

    @Test
    fun `video duration is read when the protocol names its field`() {
        val json = """{"id":"f1","name":"Show - 01.mkv","kind":"video","size":1443789299,"duration":1420}"""
        assertEquals(1420, parse(json, protocol.file.copy(duration = "duration"))!!.durationSeconds)
        // 协议没写时长字段时是 0
        assertEquals(0, parse(json)!!.durationSeconds)
    }

    @Test
    fun `file entries follow the protocol for folder values - timestamps and video detection`() {
        val fields = protocol.file.copy(isDir = "type", dirValues = listOf("folder", "album"), updatedAtSeconds = true, category = "")
        assertTrue(parse("""{"id":"d1","name":"x","type":"album"}""", fields)!!.dir)
        assertFalse(parse("""{"id":"f1","name":"x.mkv","type":"file"}""", fields)!!.dir)
        // 秒级时间戳换成毫秒
        assertEquals(1790437896000, parse("""{"id":"f1","name":"x.mkv","mtime":1790437896}""", fields)!!.updatedAt)
        // 没有类别字段时按扩展名认视频
        assertTrue(parse("""{"id":"f1","name":"Show - 01.MKV"}""", fields)!!.isVideo)
        assertTrue(parse("""{"id":"f2","name":"Show - 01.mp4"}""", fields)!!.isVideo)
        assertFalse(parse("""{"id":"f3","name":"Show - 01.ass"}""", fields)!!.isVideo)
        assertFalse(parse("""{"id":"d2","name":"Show.mkv","type":"folder"}""", fields)!!.isVideo)
    }

    // endregion

    // region 显示名、档位、支持的功能

    @Test
    fun `display names follow the language tag`() {
        assertEquals("Test Drive", protocol.displayName("en"))
        assertEquals("Test Drive", protocol.displayName("en-US"))
        assertEquals("Test Drive", protocol.displayName("en_GB"))
        assertEquals("测试网盘", protocol.displayName("zh-CN"))
        assertEquals("测试网盘", protocol.displayName("ja"))
    }

    @Test
    fun `tiers are matched in order by regex`() {
        assertEquals("专业版", protocol.tierOf("PRO")?.displayLabel("zh-Hans"))
        assertEquals("Pro", protocol.tierOf("PRO_TRIAL")?.displayLabel("en"))
        assertEquals("基础版", protocol.tierOf("BASIC")?.label)
        assertNull(protocol.tierOf("SUPER_PRO"))
        assertNull(protocol.tierOf(""))
        // 写坏的正则当作对不上
        val broken = protocol.copy(tiers = listOf(DriveTier(match = "(", label = "坏的")) + protocol.tiers)
        assertEquals("专业版", broken.tierOf("PRO")?.label)
    }

    @Test
    fun `features follow the operations the protocol has`() {
        assertEquals("testdrive-drive", protocol.driveMediaSourceId)
        assertTrue(protocol.supportsSearch)
        assertTrue(protocol.supportsTranscoded)
        assertTrue(protocol.supportsShares)
        assertTrue(protocol.supportsSaving)

        val minimal = CloudDriveProtocol(id = "mini", name = "迷你")
        assertFalse(minimal.supportsSearch)
        assertFalse(minimal.supportsTranscoded)
        assertFalse(minimal.supportsShares)
        assertFalse(minimal.supportsSaving)
        // 没有分享链接格式就不能用分享
        assertFalse(protocol.copy(share = protocol.share.copy(linkPattern = "")).supportsShares)
        assertFalse(protocol.copy(api = protocol.api.copy(task = null)).supportsSaving)
    }

    @Test
    fun `protocol survives the data source arguments`() {
        val arguments = CloudDriveArguments(protocol = protocol)
        val config = MediaSourceConfig(serializedArguments = MediaSourceConfig.serializeArguments(CloudDriveArguments.serializer(), arguments))
        assertEquals(arguments, config.deserializeArgumentsOrNull(CloudDriveArguments.serializer()))

        // 订阅里只写了必要字段的也能读
        val minimal = MediaSourceConfig(serializedArguments = Json.parseToJsonElement("""{"protocol":{"id":"mini","name":"迷你","extra":1}}"""))
        val decoded = minimal.deserializeArgumentsOrNull(CloudDriveArguments.serializer())!!
        assertEquals(CloudDriveProtocol(id = "mini", name = "迷你"), decoded.protocol)
        assertEquals("迷你", CloudDriveMediaSource.infoOf(decoded).displayName)
        assertEquals("自己的名字", CloudDriveMediaSource.infoOf(decoded.copy(name = "自己的名字")).displayName)
    }

    @Test
    fun `tiers of a drive source are keyed by the id its media carry`() {
        // 订阅给实例分配随机 id, 网盘源的资源却带协议给的固定 id: 层级要记在后者上才查得到
        assertEquals("testdrive-drive", CloudDriveMediaSource.reportedMediaSourceId(CloudDriveArguments(protocol = protocol), "random-uuid"))
        assertEquals("random-uuid", CloudDriveMediaSource.reportedMediaSourceId(CloudDriveShareSearchArguments.Example, "random-uuid"))
    }

    // endregion

    // region 分享链接

    private val links = DriveShareLinks(protocol)

    @Test
    fun `extracting links only takes passcodes written in the link`() {
        val text = "正片${'$'}https://share.drive.test/s/abc123#花絮${'$'}https://share.drive.test/s/def456?pwd=x9z1 别家 https://other.example/s/1 提取码：zzzz"
        assertEquals(listOf(DriveShareLink("abc123", ""), DriveShareLink("def456", "x9z1")), links.extract(text))
        // 粘贴的文字里链接后面的提取码归它
        assertEquals(listOf(DriveShareLink("abc123", "k7Qp")), links.parse("https://share.drive.test/s/abc123 提取码：k7Qp"))
        assertEquals(listOf(DriveShareLink("abc123", "")), links.extract("https://share.drive.test/s/abc123 提取码：k7Qp"))
        // 重复的只留第一个
        assertEquals(
            listOf(DriveShareLink("abc123", "")),
            links.extract("https://share.drive.test/s/abc123 https://share.drive.test/s/abc123?pwd=1111"),
        )
    }

    @Test
    fun `no or broken link pattern finds no links`() {
        val none = DriveShareLinks(protocol.copy(share = protocol.share.copy(linkPattern = "")))
        assertEquals(emptyList(), none.parse("https://share.drive.test/s/abc123"))
        assertEquals(emptyList(), none.extract("https://share.drive.test/s/abc123"))
        val broken = DriveShareLinks(protocol.copy(share = protocol.share.copy(linkPattern = "(")))
        assertEquals(emptyList(), broken.parse("https://share.drive.test/s/abc123"))
    }

    // endregion

    // region 占位地址

    private val placeholders = DrivePlaceholders(protocol)

    private val shareRef = DriveShareFileRef("share1", "x9z1", "id#1", "tok/+=", "葬送的芙莉莲 S01E01 [1080p].mkv", 123456)

    @Test
    fun `own file placeholders round trip`() {
        assertEquals("https://www.drive.test/file/f1", placeholders.fileUri("f1"))
        assertEquals("f1", placeholders.fileIdOf(placeholders.fileUri("f1")))
        assertNull(placeholders.fileIdOf("https://www.drive.test/file/"))
        assertNull(placeholders.fileIdOf("https://www.drive.test/folder/d1"))
        assertEquals("https://www.drive.test/folder/d1", placeholders.folderUrl("d1"))
        assertEquals("https://share.drive.test/s/share1", placeholders.shareUrl("share1"))
    }

    @Test
    fun `share file placeholders round trip`() {
        val uri = placeholders.shareFileUri(shareRef)
        assertTrue(uri.startsWith("https://share.drive.test/s/share1#"), uri)
        assertEquals(shareRef, placeholders.parseShareFile(uri))
        // 普通的分享链接与自己网盘的占位地址都不是
        assertNull(placeholders.parseShareFile("https://share.drive.test/s/share1"))
        assertNull(placeholders.parseShareFile(placeholders.fileUri("f1")))
        assertNull(placeholders.fileIdOf(uri))
        // 记下所在文件夹的; 没有这一项的地址照常解析
        val inFolder = shareRef.copy(folderId = "dir/1")
        assertEquals(inFolder, placeholders.parseShareFile(placeholders.shareFileUri(inFolder)))
        assertEquals("", placeholders.parseShareFile(uri)!!.folderId)
    }

    @Test
    fun `placeholders keep the layout written in the protocol`() {
        // 地址写在页面锚点里的布局: 已经存下来的资源地址照样认得
        val anchored = DrivePlaceholders(
            protocol.copy(
                links = DriveLinkTemplates(file = "https://www.drive.test/#/file/{fileId}"),
                share = protocol.share.copy(url = "https://www.drive.test/s/{shareId}"),
            ),
        )
        assertEquals("https://www.drive.test/#/file/f1", anchored.fileUri("f1"))
        assertEquals("abc", anchored.fileIdOf("https://www.drive.test/#/file/abc"))
        val shareUri = anchored.shareFileUri(shareRef)
        assertTrue(shareUri.startsWith("https://www.drive.test/s/share1#"), shareUri)
        assertEquals(shareRef, anchored.parseShareFile(shareUri))
        assertNull(anchored.fileIdOf(shareUri))
        assertNull(anchored.parseShareFile("https://www.drive.test/#/file/abc"))
        // 没写文件夹地址时用网站地址
        assertEquals("https://www.drive.test/", anchored.folderUrl("d1"))

        // 变量后面还有路径的模板
        val suffixed = DrivePlaceholders(
            protocol.copy(
                links = DriveLinkTemplates(file = "https://www.drive.test/file/{fileId}/preview"),
                share = protocol.share.copy(url = "https://share.drive.test/s/{shareId}/view"),
            ),
        )
        assertEquals("https://www.drive.test/file/f1/preview", suffixed.fileUri("f1"))
        assertEquals("f1", suffixed.fileIdOf("https://www.drive.test/file/f1/preview"))
        assertNull(suffixed.fileIdOf("https://www.drive.test/file/f1"))
        val suffixedShare = suffixed.shareFileUri(shareRef)
        assertTrue(suffixedShare.startsWith("https://share.drive.test/s/share1/view#"), suffixedShare)
        assertEquals(shareRef, suffixed.parseShareFile(suffixedShare))
    }

    @Test
    fun `placeholders without templates stay on an invalid domain of the drive`() {
        val bare = DrivePlaceholders(CloudDriveProtocol(id = "mini", name = "迷你"))
        assertEquals("https://mini.drive.invalid/file/f1", bare.fileUri("f1"))
        assertEquals("f1", bare.fileIdOf(bare.fileUri("f1")))
        assertEquals(shareRef, bare.parseShareFile(bare.shareFileUri(shareRef)))
        assertTrue(bare.shareFileUri(shareRef).startsWith("https://mini.drive.invalid/s/share1#"))
        // 别的网盘的地址不认
        assertNull(bare.fileIdOf(placeholders.fileUri("f1")))
        assertNull(bare.parseShareFile(placeholders.shareFileUri(shareRef)))
        assertNull(placeholders.fileIdOf(bare.fileUri("f1")))
        assertNull(placeholders.parseShareFile(bare.shareFileUri(shareRef)))
    }

    // endregion
}
