/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.clouddrive

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.him188.ani.app.data.models.preference.CloudDriveAccount
import me.him188.ani.app.data.models.preference.CloudDriveAccounts
import me.him188.ani.app.data.models.preference.CloudDriveAddedShares
import me.him188.ani.app.data.persistent.MemoryDataStore
import me.him188.ani.app.data.repository.media.MediaSourceInstanceRepository
import me.him188.ani.app.data.repository.media.MediaSourceInstanceRepositoryImpl
import me.him188.ani.app.data.repository.media.MediaSourceSaves
import me.him188.ani.app.data.repository.user.Settings
import me.him188.ani.app.domain.mediasource.instance.MediaSourceSave
import me.him188.ani.datasources.api.source.MediaSourceConfig
import me.him188.ani.datasources.api.source.serializeArguments

/**
 * 测试用的网盘协议: 一个虚构的网盘 (域名都在 `drive.test` 下), 用到引擎的每一项操作.
 *
 * 接口 (都带公共参数 `client=tv` 与请求头 `Referer`):
 * - 文件: `GET /api/files/search` `GET /api/files/list` `POST /api/files/download` `POST /api/files/play`
 *   `POST /api/files/folder` `POST /api/files/delete`, 后台任务 `GET /api/tasks/{taskId}`
 * - 分享 (不带登录 Cookie): `POST /api/share/open` `GET /api/share/list`; 转存 `POST /api/share/save`
 * - 账号: `GET /api/account/tier` `GET /api/account/profile`
 * - 扫码 (另一个域名, 不带公共参数): `/qr/start` → `/qr/poll` → `/qr/exchange`
 *
 * 响应外壳 `{"code":0,"status":"...","message":"..."}`: `code` 不是 0 算出错, 4507 是空间不够; `status` 为 `unauthenticated` 算登录失效.
 * 登录 Cookie 必须有 `sid`, `sig` 由服务端轮换.
 */
internal object TestDrive {
    const val ID = "testdrive"
    const val ROOT = "root"
    const val SHARE_ROOT = "0"
    const val USER_AGENT = "TestDrive/1.0"
    const val REFERER = "https://www.drive.test/"
    const val PRO_CONNECTIONS = 4
    const val DEFAULT_CONNECTIONS = 16

    private fun request(
        method: String,
        path: String,
        query: Map<String, String> = emptyMap(),
        body: JsonObject? = null,
        cookie: Boolean = true,
    ) = DriveRequest(method = method, url = "https://{host}$path", query = query, body = body, cookie = cookie)

    private fun get(path: String, vararg query: Pair<String, String>) = request("GET", path, query.toMap())

    private fun post(path: String, vararg body: Pair<String, String>) =
        request("POST", path, body = buildJsonObject { body.forEach { (key, value) -> put(key, value) } })

    val protocol = CloudDriveProtocol(
        id = ID,
        name = "测试网盘",
        names = mapOf("en" to "Test Drive"),
        iconUrl = "https://www.drive.test/favicon.ico",
        websiteUrl = "https://www.drive.test/",
        http = DriveHttpConfig(
            hosts = listOf("api-a.drive.test", "api-b.drive.test", "api-c.drive.test"),
            userAgent = USER_AGENT,
            headers = mapOf(HttpHeaders.Referrer to REFERER),
            query = mapOf("client" to "tv"),
            rotatingCookies = listOf("sig"),
        ),
        envelope = DriveEnvelope(
            code = "code",
            okCodes = listOf("0"),
            status = "status",
            authStatuses = listOf("unauthenticated"),
            message = "message",
            capacityLimitCodes = listOf("4507"),
        ),
        file = DriveFileFields(
            id = "id",
            name = "name",
            parentId = "parent",
            isDir = "isDir",
            dirValues = listOf("true"),
            size = "size",
            updatedAt = "mtime",
            videoHeight = "height",
            category = "kind",
            videoCategories = listOf("video"),
            shareToken = "token",
        ),
        rootFolderId = ROOT,
        api = DriveApiConfig(
            tier = DriveValueOp(get("/api/account/tier"), value = "data.tier"),
            nickname = DriveValueOp(get("/api/account/profile"), value = "data.nickname"),
            search = DriveListOp(
                get("/api/files/search", "q" to "{keyword}", "page" to "{page}", "size" to "{pageSize}", "order" to "dir_first"),
                items = "data.items",
                total = "data.total",
                pageSize = 50,
            ),
            listFolder = DriveListOp(
                get("/api/files/list", "parent" to "{folderId}", "page" to "{page}", "size" to "{pageSize}"),
                items = "data.items",
                total = "data.total",
                pageSize = 50,
            ),
            download = DriveDownloadOp(post("/api/files/download", "ids" to "{fileIds}"), items = "data", url = "url"),
            transcoded = DriveTranscodedOp(
                post("/api/files/play", "id" to "{fileId}"),
                items = "data.streams",
                url = "url",
                height = "height",
                accessible = "available",
            ),
            createFolder = DriveValueOp(post("/api/files/folder", "name" to "{name}", "parent" to "{parentId}"), value = "data.id"),
            deleteFiles = DriveValueOp(post("/api/files/delete", "ids" to "{fileIds}"), value = "data.task"),
            task = DriveTaskOp(
                get("/api/tasks/{taskId}", "retry" to "{retry}"),
                status = "data.state",
                done = listOf("done"),
                failed = listOf("failed"),
                message = "data.error",
                resultIds = "data.result.ids",
                maxPolls = 5,
                intervalMillis = 500,
            ),
            shareToken = DriveShareTokenOp(
                post("/api/share/open", "share" to "{shareId}", "passcode" to "{passcode}").copy(cookie = false),
                token = "data.token",
                title = "data.title",
            ),
            listShare = DriveListOp(
                get(
                    "/api/share/list",
                    "share" to "{shareId}", "token" to "{shareToken}", "parent" to "{folderId}", "page" to "{page}", "size" to "{pageSize}",
                ).copy(cookie = false),
                items = "data.items",
                total = "data.total",
                pageSize = 50,
            ),
            saveFromShare = DriveValueOp(
                post(
                    "/api/share/save",
                    "share" to "{shareId}", "token" to "{shareToken}", "ids" to "{fileIds}", "fileTokens" to "{fileTokens}",
                    "target" to "{folderId}", "from" to "{shareRootId}",
                ),
                value = "data.task",
            ),
        ),
        login = DriveLoginConfig(
            requiredCookies = listOf("sid"),
            cookieHint = "在网页版登录后复制 Cookie",
            qr = DriveQrLogin(
                start = DriveRequest(
                    url = "https://login.drive.test/qr/start",
                    query = mapOf("request" to "{requestId}"),
                    cookie = false,
                    common = false,
                ),
                token = "data.token",
                status = "state",
                okStatuses = listOf("ok"),
                content = "https://login.drive.test/qr?token={token}",
                poll = DriveRequest(url = "https://login.drive.test/qr/poll", query = mapOf("token" to "{token}"), cookie = false, common = false),
                confirmedStatuses = listOf("confirmed"),
                expiredStatuses = listOf("expired"),
                ticket = "data.ticket",
                exchange = DriveRequest(
                    url = "https://login.drive.test/qr/exchange",
                    query = mapOf("ticket" to "{ticket}"),
                    cookie = false,
                    common = false,
                ),
                nickname = "data.nickname",
                appName = "测试网盘",
                timeoutSeconds = 300,
                pollIntervalSeconds = 1,
            ),
        ),
        playback = DrivePlaybackConfig(
            headers = mapOf(HttpHeaders.Cookie to "{cookie}", HttpHeaders.Referrer to REFERER, HttpHeaders.UserAgent to "{userAgent}"),
            parallelConnections = DEFAULT_CONNECTIONS,
        ),
        share = DriveShareConfig(
            linkPattern = """(?:https?://)?share\.drive\.test/s/([0-9A-Za-z]+)(?:\?pwd=([0-9A-Za-z]+))?""",
            url = "https://share.drive.test/s/{shareId}",
            rootFolderId = SHARE_ROOT,
        ),
        links = DriveLinkTemplates(file = "https://www.drive.test/file/{fileId}", folder = "https://www.drive.test/folder/{folderId}"),
        tiers = listOf(
            DriveTier(match = "^PRO(_TRIAL)?$", label = "专业版", labels = mapOf("en" to "Pro"), parallelConnections = PRO_CONNECTIONS),
            DriveTier(match = "^BASIC$", label = "基础版", labels = mapOf("en" to "Basic")),
        ),
    )

    /** 这个网盘以前由专门的代码支持时留下的设置键与数据源类型. */
    val legacy = DriveLegacyData(
        accountKey = "legacyDriveAccount",
        addedSharesKey = "legacyDriveAddedShares",
        driveFactoryId = "legacy-drive",
        addedSharesFactoryId = "legacy-drive-added-shares",
        shareSearchFactoryId = "legacy-drive-share-search",
    )

    /** 登录着的账号. */
    val loggedIn = CloudDriveAccount(cookie = "sid=p1; sig=u1; uid=42")
}

internal class MemorySettings<T>(initial: T) : Settings<T> {
    val state = MutableStateFlow(initial)
    override val flow: Flow<T> get() = state
    override suspend fun set(value: T) {
        state.value = value
    }
}

internal fun accountsOf(account: CloudDriveAccount, driveId: String = TestDrive.ID): MemorySettings<CloudDriveAccounts> =
    MemorySettings(CloudDriveAccounts.Default.with(driveId, account))

/** 测试网盘的账号. */
internal val MemorySettings<CloudDriveAccounts>.account: CloudDriveAccount get() = state.value.of(TestDrive.ID)

internal fun MockRequestHandleScope.reply(
    body: String,
    setCookies: List<String> = emptyList(),
    status: HttpStatusCode = HttpStatusCode.OK,
): HttpResponseData = respond(
    body,
    status,
    headersOf(
        HttpHeaders.ContentType to listOf("application/json"),
        HttpHeaders.SetCookie to setCookies,
    ),
)

internal fun mockClient(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): HttpClient =
    HttpClient(MockEngine { request -> handler(request) }) { expectSuccess = false }

internal fun testDriveService(
    accounts: Settings<CloudDriveAccounts>,
    protocol: CloudDriveProtocol = TestDrive.protocol,
    handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
): CloudDriveService = CloudDriveService(protocol, accounts, mockClient(handler))

/** 请求的 JSON 体. */
internal fun HttpRequestData.jsonBody(): JsonObject = Json.parseToJsonElement((body as TextContent).text).jsonObject

internal fun JsonObject.strings(key: String): List<String> = getValue(key).jsonArray.map { it.jsonPrimitive.content }

internal fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

/** 请求的方法与路径, 用来按顺序核对请求. */
internal fun HttpRequestData.route(): String = "${method.value} ${url.encodedPath}"

/** 按测试网盘的字段写出一个文件条目. */
internal fun DriveFile.toJson(): String = buildJsonObject {
    put("id", fid)
    put("name", fileName)
    put("parent", parentFid)
    put("isDir", dir)
    put("size", size)
    put("mtime", updatedAt)
    put("height", videoHeight)
    put("kind", if (isVideo) "video" else if (dir) "folder" else "file")
    put("token", shareToken)
}.toString()

/** 列文件的响应. */
internal fun listJson(files: List<DriveFile>, total: Int = files.size): String =
    """{"code":0,"data":{"items":[${files.joinToString(",") { it.toJson() }}],"total":$total}}"""

internal fun listJson(vararg files: DriveFile): String = listJson(files.toList())

/** 取直链的响应: [ids] 各自的直链 `https://dl.drive.test/<id>`, [files] 里有的带上名字与所在文件夹. */
internal fun downloadJson(ids: List<String>, files: Map<String, DriveFile> = emptyMap()): String = buildJsonObject {
    put("code", 0)
    put(
        "data",
        buildJsonArray {
            for (id in ids) {
                add(
                    buildJsonObject {
                        put("id", id)
                        files[id]?.let {
                            put("name", it.fileName)
                            put("parent", it.parentFid)
                        }
                        put("url", "https://dl.drive.test/$id")
                    },
                )
            }
        },
    )
}.toString()

internal fun dir(id: String, name: String, parent: String = "") = DriveFile(id, fileName = name, parentFid = parent, dir = true)

internal fun video(id: String, name: String, parent: String = "", size: Long = 500L * 1024 * 1024, token: String = "t-$id") =
    DriveFile(id, fileName = name, parentFid = parent, size = size, isVideo = true, shareToken = token)

/**
 * 找字幕有超时上限 (withTimeoutOrNull), 而模拟的 HTTP 跑在真线程上: 留在 runTest 的虚拟时钟里, 一等请求就算超时了.
 */
internal fun realTimeTest(block: suspend CoroutineScope.() -> Unit) = runTest { withContext(Dispatchers.Default, block) }

/**
 * 分享的内容, 既能直接当 [DriveShareBrowser] 用, 也能按 [TestDrive.protocol] 的分享接口回 HTTP 请求 ([respond]).
 *
 * @param folders 分享 id → (文件夹 id → 里面的文件), 根是 [TestDrive.SHARE_ROOT]
 */
internal class FakeShares(
    val folders: MutableMap<String, Map<String, List<DriveFile>>> = mutableMapOf(),
    val titles: Map<String, String> = emptyMap(),
    val unavailable: MutableSet<String> = mutableSetOf(),
    private val defaultTitle: (String) -> String = { "分享 $it" },
) : DriveShareBrowser {
    /** 打开过的分享与用的提取码. */
    val opened = mutableListOf<Pair<String, String>>()

    /** 列过的 (分享, 文件夹). */
    val listed = mutableListOf<Pair<String, String>>()

    override val rootFolderId: String get() = TestDrive.SHARE_ROOT
    override val needsFileToken: Boolean get() = true

    private fun title(shareId: String) = titles[shareId] ?: defaultTitle(shareId)

    override suspend fun open(shareId: String, passcode: String): String {
        opened += shareId to passcode
        if (shareId in unavailable) throw CloudDriveShareUnavailableException("404", "分享不存在")
        return title(shareId)
    }

    override suspend fun listFolder(shareId: String, passcode: String, folderId: String): List<DriveFile> {
        listed += shareId to folderId
        if (shareId in unavailable) throw CloudDriveShareUnavailableException("404", "分享不存在")
        return folders[shareId]?.get(folderId).orEmpty()
    }

    /** 回分享接口的请求; 不是分享接口时为 null. */
    fun respond(scope: MockRequestHandleScope, request: HttpRequestData): HttpResponseData? = when {
        request.method == HttpMethod.Post && request.url.encodedPath == "/api/share/open" -> {
            val shareId = request.jsonBody().string("share")
            if (shareId in unavailable) {
                scope.reply("""{"code":404,"message":"分享不存在"}""")
            } else {
                scope.reply(
                    buildJsonObject {
                        put("code", 0)
                        put("data", buildJsonObject { put("token", "tk-$shareId"); put("title", title(shareId)) })
                    }.toString(),
                )
            }
        }

        request.url.encodedPath == "/api/share/list" -> {
            val shareId = request.url.parameters["share"].orEmpty()
            if (shareId in unavailable || request.url.parameters["token"] != "tk-$shareId") {
                scope.reply("""{"code":404,"message":"分享不存在"}""")
            } else {
                scope.reply(listJson(folders[shareId]?.get(request.url.parameters["parent"].orEmpty()).orEmpty()))
            }
        }

        else -> null
    }
}

/** 以前版本按设置键存的数据. */
internal class FakeLegacyPreferences(initial: Map<String, String> = emptyMap()) : LegacyPreferences {
    val values = initial.toMutableMap()
    override suspend fun read(key: String): String? = values[key]
    override suspend fun remove(key: String) {
        values -= key
    }
}

/** 「网盘」数据源的保存记录, 参数里带着协议 [protocol]. */
internal fun driveSave(
    protocol: CloudDriveProtocol = TestDrive.protocol,
    instanceId: String = "drive-${protocol.id}",
    isEnabled: Boolean = true,
) = MediaSourceSave(
    instanceId = instanceId,
    mediaSourceId = protocol.driveMediaSourceId,
    factoryId = CloudDriveMediaSource.FactoryId,
    isEnabled = isEnabled,
    config = MediaSourceConfig(
        serializedArguments = MediaSourceConfig.serializeArguments(CloudDriveArguments.serializer(), CloudDriveArguments(protocol = protocol)),
    ),
)

internal class TestRegistry(
    val registry: CloudDriveRegistry,
    val instances: MediaSourceInstanceRepository,
    val accounts: MemorySettings<CloudDriveAccounts>,
    val addedShares: MemorySettings<CloudDriveAddedShares>,
    val legacy: FakeLegacyPreferences,
)

/**
 * 从保存的数据源 [saves] 建网盘列表, 各网盘的请求都发给 [client]. 读数据源的协程跑在 [TestScope.backgroundScope] 里, 测试结束时停掉.
 */
internal fun TestScope.testRegistry(
    saves: List<MediaSourceSave> = listOf(driveSave()),
    accounts: MemorySettings<CloudDriveAccounts> = MemorySettings(CloudDriveAccounts.Default),
    addedShares: MemorySettings<CloudDriveAddedShares> = MemorySettings(CloudDriveAddedShares.Default),
    legacy: FakeLegacyPreferences = FakeLegacyPreferences(),
    client: () -> HttpClient = { mockClient { error("unexpected request ${it.url}") } },
): TestRegistry {
    val instances = MediaSourceInstanceRepositoryImpl(MemoryDataStore(MediaSourceSaves(saves)))
    val registry = CloudDriveRegistry(
        instances = instances,
        accounts = accounts,
        addedShares = addedShares,
        legacyPreferences = legacy,
        tmdbSubjectMap = null,
        parentCoroutineContext = backgroundScope.coroutineContext,
        httpClient = client,
    )
    return TestRegistry(registry, instances, accounts, addedShares, legacy)
}

/**
 * 等网盘列表读完保存的数据源. 读协议与认领旧数据在同一轮里做, 认领引起的数据源列表变化会再触发一轮; 让出几次, 等这些都处理完.
 *
 * 网盘列表的协程跑在 [TestScope.backgroundScope] 里, `advanceUntilIdle` 不会替它推进, 只能挂起等.
 */
internal suspend fun TestRegistry.awaitDrives(): List<CloudDriveService> {
    val drives = registry.drives.filterNotNull().first()
    repeat(3) { yield() }
    return drives
}
