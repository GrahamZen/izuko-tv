/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.remote

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.him188.ani.app.domain.media.player.data.AniSystemFileMediaData
import me.him188.ani.app.ui.foundation.lan.LanHttpRequest
import me.him188.ani.app.ui.foundation.lan.LanHttpResponse
import org.openani.mediamp.ExperimentalMediampApi
import org.openani.mediamp.source.MediaData
import org.openani.mediamp.source.SystemFileMediaData
import org.openani.mediamp.source.UriMediaData
import java.io.File
import java.net.InetAddress
import java.net.URI
import java.net.URLEncoder

/**
 * 播放卡全屏的「同步播放」(见 [MIRROR_SCRIPT]): 手机自己拿电视这一集的播放地址播, 进度跟着电视走; 视频数据不经电视.
 *
 * 本接口只回地址与「值不值得让手机试」的判断。手机浏览器的 `<video>` 带不了自定义请求头 (Cookie、Referer、UA
 * 都设不了; 控制台页面是 no-referrer, 取流时不带 Referer), 所以:
 * - 电视在播本机上下完的文件 (缓存、本机文件): 由电视按需发这个文件 ([FILE_PATH], 见 [RemoteFileStream]); 浏览器播不了这个格式
 *   (如 iPhone 上的 mkv) 时网页上直接说不支持;
 * - 边下边播的 BT 与经电视本机代理播的 (HLS 去广告): 手机拿不到;
 * - 要带 Cookie / Authorization 的 (网盘): 浏览器带不了;
 * - 其余都交给手机试 —— 容器、编码、防盗链认不认空 Referer, 只有手机真去加载才知道。
 */
internal object RemoteMirror {
    const val PATH = "api/player/mirror"

    /** `FILE_PATH/<key>/<文件名>`: 电视此刻在播的本机文件; key 对不上 (换了媒体) 就 404, 只发正在播的那一个 */
    const val FILE_PATH = "api/player/mirror/file"

    fun serveFile(player: RemotePlayerHandle?, request: LanHttpRequest): LanHttpResponse {
        val key = request.path.removePrefix("$FILE_PATH/").substringBefore('/')
        val data = player?.vm?.player?.mediaData?.value
        val file = localFileOf(data)
        if (file == null || key.isEmpty() || mirrorKeyOf(data) != key) return LanHttpResponse.status(404, "Not Found")
        return RemoteFileStream.serve(file, request)
    }

    /** `SUB_PATH/<key>/<序号>.vtt`: 电视在播的本机文件带的外挂字幕, 转成 WebVTT (见 [RemoteSubtitles]) */
    const val SUB_PATH = "api/player/mirror/sub"

    fun serveSub(player: RemotePlayerHandle?, request: LanHttpRequest): LanHttpResponse {
        val rest = request.path.removePrefix("$SUB_PATH/")
        val key = rest.substringBefore('/')
        val data = player?.vm?.player?.mediaData?.value
        val index = RemoteSubtitles.parseVttName(rest.substringAfter('/')) ?: return LanHttpResponse.status(404, "Not Found")
        if (key.isEmpty() || mirrorKeyOf(data) != key) return LanHttpResponse.status(404, "Not Found")
        val sub = RemoteSubtitles.localOf(data).firstOrNull { it.index == index } ?: return LanHttpResponse.status(404, "Not Found")
        return RemoteSubtitles.serveVtt(sub)
    }

    fun handle(player: RemotePlayerHandle?, request: LanHttpRequest): LanHttpResponse? {
        if (request.method != "GET") return null
        val data = player?.vm?.player?.mediaData?.value
        val verdict = if (player == null) {
            MirrorVerdict.Unavailable(MirrorVerdict.REASON_NOT_PLAYING, pending = false)
        } else {
            judgeMirror(data)
        }
        val obj = verdict.toJson()
        // 本机文件带的外挂字幕 (缓存时存下的网盘字幕等): 浏览器挂成字幕轨, 电视选着的那条设成默认
        val subs = if (verdict is MirrorVerdict.Playable && verdict.local) RemoteSubtitles.localOf(data) else emptyList()
        if (subs.isEmpty()) return json(obj)
        val state = player?.subtitleState
        val selected = RemoteSubtitles.selectedOf(subs, state?.selected, known = state != null)
        return json(
            JsonObject(
                obj + mapOf(
                    "subs" to JsonArray(subs.map { it.toJson("$SUB_PATH/${verdict.key}") }),
                    "subSel" to JsonPrimitive(selected?.index ?: -1),
                ),
            ),
        )
    }

    private fun MirrorVerdict.toJson(): JsonObject = buildJsonObject {
        put("ok", true)
        put("key", key)
        when (val v = this@toJson) {
            is MirrorVerdict.Playable -> {
                put("playable", true)
                put("url", v.url)
                put("kind", v.kind)
                put("host", v.host)
                // 电视自己发的本机文件: 浏览器播不了时网页直接说不支持这个格式
                put("local", v.local)
                // 只给名字, 给手机日志标明这条原本带了哪些头 (值里可能有 Cookie)
                putJsonArray("headers") { v.headerNames.forEach { add(it) } }
            }

            is MirrorVerdict.Unavailable -> {
                put("playable", false)
                put("reason", tr(v.reason))
                // 电视还没装上媒体: 手机过一会儿再问
                put("pending", v.pending)
            }
        }
    }

    private fun json(obj: JsonObject): LanHttpResponse =
        LanHttpResponse.bytes(obj.toString().toByteArray(), "application/json; charset=utf-8")
}

/** 电视这一集的播放地址能不能交给手机播, 见 [judgeMirror]. */
internal sealed interface MirrorVerdict {
    /** 判的是哪一份媒体, 同 [mirrorKeyOf] */
    val key: String

    /**
     * @param url 网上的原地址, 或电视发本机文件的相对地址 ([local])
     * @param kind 按地址后缀猜的容器: `hls` / `mp4` / `mkv` / `ts` / `flv` / `webm` / `other`
     */
    data class Playable(
        val url: String,
        override val key: String,
        val kind: String,
        val host: String,
        val headerNames: List<String>,
        val local: Boolean = false,
    ) : MirrorVerdict

    /** @param reason 给 [tr] 的原文 */
    data class Unavailable(val reason: String, val pending: Boolean, override val key: String = "") : MirrorVerdict

    companion object {
        const val REASON_NOT_PLAYING = "电视还没开始播放"
        const val REASON_LOCAL = "这个片源还在电视上边下边播（如 BT），缓存下完后手机才能播"
        const val REASON_LOCAL_PROXY = "电视在经本机代理播这个片源（如 HLS 去广告），手机拿不到原地址"
        const val REASON_CREDENTIALS = "这个片源要带登录信息（如网盘），手机浏览器带不了"
    }
}

/**
 * 播放器里此刻是哪一份媒体: 换源、换集、重新解析就变; 没装媒体时为空。播放状态每轮都带着它 (见 RemotePlayerHandle.playbackJson),
 * 手机据此知道该重新问地址, 以及全屏里放的那份是不是已经不是电视在播的了 —— 换集时位置先归零, 完整状态晚一轮才到,
 * 只看那份的话会先把旧视频跳到 0 秒再退出。
 */
internal fun mirrorKeyOf(data: MediaData?): String = when (data) {
    null -> ""
    is UriMediaData -> Integer.toHexString(data.uri.hashCode())
    else -> "x" + Integer.toHexString(System.identityHashCode(data))
}

/** 电视在播的是本机上一个完整的文件 (下完的缓存、本机文件) 时是它; 边下边播的 BT 等不算 */
@OptIn(ExperimentalMediampApi::class)
internal fun localFileOf(data: MediaData?): File? {
    val path = when (data) {
        is AniSystemFileMediaData -> data.delegate.file
        is SystemFileMediaData -> data.file
        else -> return null
    }
    return File(path.toString())
}

internal fun judgeMirror(data: MediaData?): MirrorVerdict {
    if (data == null) return MirrorVerdict.Unavailable(MirrorVerdict.REASON_NOT_PLAYING, pending = true)
    val key = mirrorKeyOf(data)
    localFileOf(data)?.let { file ->
        return MirrorVerdict.Playable(
            url = "${RemoteMirror.FILE_PATH}/$key/" + URLEncoder.encode(file.name, "UTF-8").replace("+", "%20"),
            key = key,
            kind = mirrorKindOf(file.name),
            host = "tv",
            headerNames = emptyList(),
            local = true,
        )
    }
    if (data !is UriMediaData) return MirrorVerdict.Unavailable(MirrorVerdict.REASON_LOCAL, pending = false, key)
    val uri = runCatching { URI(data.uri) }.getOrNull()
    val scheme = uri?.scheme?.lowercase()
    val host = uri?.host
    if (uri == null || (scheme != "http" && scheme != "https") || host.isNullOrEmpty()) {
        return MirrorVerdict.Unavailable(MirrorVerdict.REASON_LOCAL, pending = false, key)
    }
    if (isLoopbackHost(host)) return MirrorVerdict.Unavailable(MirrorVerdict.REASON_LOCAL_PROXY, pending = false, key)
    if (data.headers.keys.any { it.equals("Cookie", ignoreCase = true) || it.equals("Authorization", ignoreCase = true) }) {
        return MirrorVerdict.Unavailable(MirrorVerdict.REASON_CREDENTIALS, pending = false, key)
    }
    return MirrorVerdict.Playable(
        url = data.uri,
        key = key,
        kind = mirrorKindOf(uri.path.orEmpty()),
        host = host,
        headerNames = data.headers.keys.sorted(),
    )
}

private fun isLoopbackHost(host: String): Boolean {
    val bare = host.removePrefix("[").removeSuffix("]")
    if (bare.equals("localhost", ignoreCase = true)) return true
    // 只认字面的 IP, 不做 DNS 查询
    val literal = bare.isNotEmpty() && (bare.all { it.isDigit() || it == '.' } || bare.contains(':'))
    return literal && runCatching { InetAddress.getByName(bare).isLoopbackAddress }.getOrDefault(false)
}

private fun mirrorKindOf(path: String): String {
    val p = path.lowercase()
    return when {
        p.endsWith(".m3u8") || p.endsWith(".m3u") -> "hls"
        p.endsWith(".mp4") || p.endsWith(".m4v") || p.endsWith(".mov") -> "mp4"
        p.endsWith(".mkv") -> "mkv"
        p.endsWith(".ts") -> "ts"
        p.endsWith(".flv") -> "flv"
        p.endsWith(".webm") -> "webm"
        else -> "other"
    }
}

/** 本功能网页与接口的文案 (见 [RemoteI18nTable]). */
internal fun mirrorTexts() = listOf(
    RemoteText("全屏（同步播放）", "Fullscreen (synced video)", "全屏（同步播放）", "全螢幕（同步播放）"),
    RemoteText(
        "全屏只显示控制：{0}",
        "Fullscreen shows controls only: {0}",
        "全屏只顯示控制：{0}",
        "全螢幕只顯示控制：{0}",
    ),
    RemoteText("电视还没开始播放", "The TV hasn't started playing yet", "電視還沒開始播放", "電視還沒開始播放"),
    RemoteText(
        "这个片源还在电视上边下边播（如 BT），缓存下完后手机才能播",
        "The TV is still downloading this source as it plays (e.g. BT). The phone can play it once the cache finishes",
        "這個片源還在電視上邊下邊播（如 BT），緩存下完後手機才能播",
        "這個片源還在電視上邊下邊播（如 BT），快取下完後手機才能播",
    ),
    RemoteText(
        "电视在经本机代理播这个片源（如 HLS 去广告），手机拿不到原地址",
        "The TV plays this through its own proxy (e.g. HLS ad filtering), so the phone can't get the original link",
        "電視在經本機代理播這個片源（如 HLS 去廣告），手機拿不到原地址",
        "電視在經本機代理播這個片源（如 HLS 去廣告），手機拿不到原網址",
    ),
    RemoteText(
        "这个片源要带登录信息（如网盘），手机浏览器带不了",
        "This source needs sign-in credentials (e.g. a cloud drive), which a phone browser can't send",
        "這個片源要帶登錄信息（如網盤），手機瀏覽器帶不了",
        "這個片源要帶登入資訊（如雲端硬碟），手機瀏覽器帶不了",
    ),
    RemoteText(
        "这个浏览器不能直接播 HLS",
        "This browser can't play HLS directly",
        "這個瀏覽器不能直接播 HLS",
        "這個瀏覽器不能直接播 HLS",
    ),
    RemoteText("手机播不了这个片源（{0}）", "The phone can't play this source ({0})", "手機播不了這個片源（{0}）", "手機播不了這個片源（{0}）"),
    RemoteText(
        "手机不支持播放这个格式（{0}）",
        "The phone doesn't support this format ({0})",
        "手機不支持播放這個格式（{0}）",
        "手機不支援播放這個格式（{0}）",
    ),
    RemoteText("格式不支持或被拒绝访问", "unsupported format or access denied", "格式不支持或被拒絕訪問", "格式不支援或被拒絕存取"),
    RemoteText("解码失败", "decoding failed", "解碼失敗", "解碼失敗"),
    RemoteText("加载被中断", "loading was interrupted", "加載被中斷", "載入被中斷"),
    RemoteText(
        "电视换了片源或剧集，点全屏重新进入",
        "The TV switched source or episode. Tap fullscreen to enter again",
        "電視換了片源或劇集，點全屏重新進入",
        "電視換了片源或劇集，點全螢幕重新進入",
    ),
)
