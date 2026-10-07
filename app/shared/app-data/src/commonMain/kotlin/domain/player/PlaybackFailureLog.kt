/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.player

import androidx.compose.runtime.Composable
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import me.him188.ani.app.domain.media.player.data.MediaDataProvider
import me.him188.ani.app.domain.media.resolver.EpisodeMetadata
import me.him188.ani.app.domain.media.resolver.MediaResolver
import me.him188.ani.datasources.api.Media
import me.him188.ani.datasources.api.source.MediaSourceKind
import me.him188.ani.utils.platform.currentTimeMillis
import org.openani.mediamp.source.MediaData
import kotlin.coroutines.cancellation.CancellationException

/**
 * 这一集的播放失败记录, 给 Web 控制台播放卡上的「失败报告」看 (失败过才有入口).
 *
 * 一集一份 (换集重新开始, 见 [start]), 每次失败一条: 哪个资源、哪一步、播放器上报的原因与真正的异常.
 * 换集时上一集失败过就留着 ([previousReport]): 自动换源、太短的视频播完都可能带着换集, 换过去以后还要看得到前一集为什么失败.
 * 失败由 [me.him188.ani.app.domain.player.extension.PlaybackFailureReportExtension] 记; 加载状态里大多不带异常,
 * 解析时的异常由 [recordingFailures] 包着的解析器另外记下, 记失败时按资源取回.
 */
object PlaybackFailureLog {
    enum class Stage {
        /** 解析资源 (取播放地址、网盘转存等). */
        RESOLVE,

        /** 播放器打开. */
        OPEN,

        /** 播起来之后. */
        PLAYBACK,
    }

    /**
     * 播放器上报的原因 (同播放页上的提示). [HTTP_ERROR] 是服务器回了 4xx / 5xx, 状态码见 [Entry.httpStatus];
     * [TOO_SHORT] 是播完的视频短得不像正片 (公告、广告片), 时长见 [Entry.mediaDurationMillis].
     */
    enum class Reason { RESOLUTION_TIMED_OUT, NETWORK, HTTP_ERROR, NO_MATCHING_FILE, UNSUPPORTED, PLAYER_ERROR, TOO_SHORT, UNKNOWN }

    class Entry(
        val timeMillis: Long,
        val stage: Stage,
        val reason: Reason,
        /** 异常里的 HTTP 错误状态码 (见 [httpErrorStatus]); 没有为 null. */
        val httpStatus: Int?,
        val mediaSourceId: String,
        val mediaTitle: String,
        val mediaKind: MediaSourceKind,
        /** 出错的主机名: 异常里提到的 (解析不出的域名、连不上的地址) 优先, 其次是资源地址的; 都没有为 null. */
        val host: String?,
        /** 异常链, 每层一行「类名: 消息」(网址去掉参数); 没拿到异常时为空. */
        val causes: List<String>,
        /** 完整堆栈 (网址去掉参数), 最长 [MAX_STACK_CHARS]; 没拿到异常时为 null. */
        val stackTrace: String?,
        /** 视频时长 (毫秒), 只在 [Reason.TOO_SHORT] 时有. */
        val mediaDurationMillis: Long? = null,
    )

    class Report(val subjectId: Int, val episodeId: Int, val entries: List<Entry>)

    private class ResolveFailure(val timeMillis: Long, val error: Throwable)

    private val lock = SynchronizedObject()
    private val _report = MutableStateFlow<Report?>(null)
    private val _previousReport = MutableStateFlow<Report?>(null)

    /** 当前这一集的记录; 还没开始播过为 null. */
    val report: StateFlow<Report?> = _report.asStateFlow()

    /** 换集前那一集的记录, 它失败过才有. */
    val previousReport: StateFlow<Report?> = _previousReport.asStateFlow()

    private val resolveFailures = MutableStateFlow<Map<String, ResolveFailure>>(emptyMap())

    /**
     * 开始播 [subjectId] 的 [episodeId]: 换了集就开一份新的, 原来那一集失败过就挪进 [previousReport].
     * 换回 [previousReport] 那一集时把它的记录接着用.
     */
    fun start(subjectId: Int, episodeId: Int): Unit = synchronized(lock) {
        val current = _report.value
        if (current != null && current.subjectId == subjectId && current.episodeId == episodeId) return
        val previous = _previousReport.value
        val resumed = previous?.takeIf { it.subjectId == subjectId && it.episodeId == episodeId }
        _previousReport.value = current?.takeIf { it.entries.isNotEmpty() } ?: previous?.takeIf { it !== resumed }
        _report.value = resumed ?: Report(subjectId, episodeId, emptyList())
    }

    /** 记一次失败; 不是当前这一集 (后台保留的另一个会话) 的不记. */
    fun record(
        subjectId: Int,
        episodeId: Int,
        media: Media,
        stage: Stage,
        reason: Reason,
        error: Throwable?,
        mediaDurationMillis: Long? = null,
    ) {
        val entry = Entry(
            timeMillis = currentTimeMillis(),
            stage = stage,
            reason = reason,
            httpStatus = error?.httpErrorStatus(),
            mediaSourceId = media.mediaSourceId,
            mediaTitle = media.originalTitle,
            mediaKind = media.kind,
            host = error?.let(::hostInError) ?: hostOf(media.download.uri),
            causes = error?.let(::causeLines).orEmpty(),
            stackTrace = error?.let { redact(it.stackTraceToString()).take(MAX_STACK_CHARS) },
            mediaDurationMillis = mediaDurationMillis,
        )
        synchronized(lock) {
            val report = _report.value
            if (report != null && report.subjectId == subjectId && report.episodeId == episodeId) {
                _report.value = Report(subjectId, episodeId, (report.entries + entry).takeLast(MAX_ENTRIES))
            }
        }
    }

    /** 解析 [mediaId] 时抛出了 [error], 见 [recordingFailures]. */
    fun noteResolveFailure(mediaId: String, error: Throwable) {
        val now = currentTimeMillis()
        resolveFailures.update { notes ->
            (notes.filterValues { now - it.timeMillis < RESOLVE_NOTE_TTL_MILLIS } + (mediaId to ResolveFailure(now, error)))
        }
    }

    /** 取走 [mediaId] 刚才解析失败时的异常; 没有或已过时为 null. */
    fun takeResolveFailure(mediaId: String): Throwable? {
        var taken: ResolveFailure? = null
        resolveFailures.update { notes ->
            taken = notes[mediaId]
            notes - mediaId
        }
        return taken?.takeIf { currentTimeMillis() - it.timeMillis < RESOLVE_NOTE_TTL_MILLIS }?.error
    }

    internal fun causeLines(error: Throwable): List<String> {
        val lines = mutableListOf<String>()
        var current: Throwable? = error
        while (current != null && lines.size < MAX_CAUSE_DEPTH) {
            val name = current::class.simpleName ?: "Throwable"
            val message = current.message?.let(::redact)?.take(MAX_MESSAGE_CHARS)
            lines += if (message.isNullOrBlank()) name else "$name: $message"
            current = current.cause?.takeIf { it !== current }
        }
        return lines
    }

    /** 去掉网址的参数与锚点: 里面常有签名、令牌. */
    internal fun redact(text: String): String = URL_QUERY.replace(text) { it.groupValues[1] + "?…" }

    internal fun hostOf(uri: String): String? = URL_HOST.find(uri)?.groupValues?.get(1)?.takeIf { it.isNotEmpty() }

    /**
     * 异常链里提到的出错主机: 先找解析不出的域名、连不上的主机, 再找消息里的第一个网址.
     * 本机地址不算 (经本地 HLS 代理播放时播放器报的是代理的地址).
     */
    internal fun hostInError(error: Throwable): String? {
        val messages = generateSequence(error) { current -> current.cause?.takeIf { it !== current } }
            .take(MAX_CAUSE_DEPTH).mapNotNull { it.message }.toList()
        return sequenceOf(UNRESOLVED_HOST, CONNECT_FAILED_HOST, URL_IN_TEXT)
            .flatMap { pattern -> messages.asSequence().flatMap { pattern.findAll(it) }.map { it.groupValues[1] } }
            .firstOrNull { it.isNotEmpty() && it != "localhost" && !it.startsWith("127.") }
    }

    private val URL_QUERY = Regex("""(\b[a-zA-Z][a-zA-Z0-9+.-]*://[^\s?#"'<>]+)[?#][^\s"'<>]*""")
    private val URL_HOST = Regex("""^[a-zA-Z][a-zA-Z0-9+.-]*://(?:[^@/?#]*@)?([^/?#:]+)""")
    private val UNRESOLVED_HOST = Regex("""Unable to resolve host "([^"]+)"""")
    private val CONNECT_FAILED_HOST = Regex("""Failed to connect to /?([^/\s:]+)""")
    private val URL_IN_TEXT = Regex("""\bhttps?://(?:[^@/?#\s]*@)?([^/?#:\s"]+)""")

    private const val MAX_ENTRIES = 30
    private const val MAX_CAUSE_DEPTH = 8
    private const val MAX_MESSAGE_CHARS = 500
    private const val MAX_STACK_CHARS = 12_000
    private const val RESOLVE_NOTE_TTL_MILLIS = 30_000L
}

/**
 * 是不是连不上网 (解析不出域名、连接失败或超时) 造成的: 播放器上报「网络错误」而不是「未知错误」.
 * 按 ExoPlayer 的错误码名与 `java.net` 的异常类名认 (commonMain 里看不见这些类; 平台类不会被混淆).
 */
fun Throwable.isNetworkFailure(): Boolean {
    var current: Throwable? = this
    var depth = 0
    while (current != null && depth++ < 8) {
        val message = current.message.orEmpty()
        if (NETWORK_ERROR_CODES.any { message.contains(it) }) return true
        if (current::class.simpleName in NETWORK_EXCEPTIONS) return true
        current = current.cause?.takeIf { it !== current }
    }
    return false
}

private val NETWORK_ERROR_CODES = listOf("ERROR_CODE_IO_NETWORK_CONNECTION_FAILED", "ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT")
private val NETWORK_EXCEPTIONS = setOf(
    "UnknownHostException", "ConnectException", "SocketTimeoutException", "NoRouteToHostException", "PortUnreachableException",
)

/**
 * 服务器回的 HTTP 错误状态码 (400~599): 连上了但被拒绝 (防盗链 403、资源没了 404) 或服务器出错, 不是网络问题.
 * 按消息认: ExoPlayer 的 `Response code: 403`, Ktor 的 `…) invalid: 403 Forbidden` / `Server error(…: 502 Bad Gateway`,
 * 以及 `HTTP 403` 这类写法. 没有为 null.
 */
fun Throwable.httpErrorStatus(): Int? {
    var current: Throwable? = this
    var depth = 0
    while (current != null && depth++ < 8) {
        val message = current.message.orEmpty()
        HTTP_STATUS_PATTERNS.firstNotNullOfOrNull { it.find(message)?.groupValues?.get(1)?.toIntOrNull() }
            ?.takeIf { it in 400..599 }
            ?.let { return it }
        current = current.cause?.takeIf { it !== current }
    }
    return null
}

private val HTTP_STATUS_PATTERNS = listOf(
    Regex("""Response code: (\d{3})\b"""),
    Regex("""\) invalid: (\d{3})\b"""),
    Regex("""Server error\(.*?: (\d{3}) """),
    Regex("""\bHTTP (\d{3})\b"""),
)

/** 解析失败时把异常记进 [PlaybackFailureLog] ([PlaybackFailureLog.noteResolveFailure]), 其余照原样交给 [this]. */
fun MediaResolver.recordingFailures(): MediaResolver = FailureRecordingMediaResolver(this)

private class FailureRecordingMediaResolver(private val delegate: MediaResolver) : MediaResolver {
    override fun supports(media: Media): Boolean = delegate.supports(media)

    @Composable
    override fun ComposeContent() {
        delegate.ComposeContent()
    }

    override suspend fun resolve(media: Media, episode: EpisodeMetadata): MediaDataProvider<MediaData> {
        try {
            return delegate.resolve(media, episode)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            PlaybackFailureLog.noteResolveFailure(media.mediaId, e)
            throw e
        }
    }
}
