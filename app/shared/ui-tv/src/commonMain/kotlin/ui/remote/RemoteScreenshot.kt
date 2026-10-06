/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.remote

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.text.intl.Locale
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import me.him188.ani.app.data.models.preference.TvTitleLogoLanguage
import me.him188.ani.app.data.network.TmdbImageService
import me.him188.ani.app.data.network.TmdbTitleLogo
import me.him188.ani.app.data.network.toTmdbLanguage
import me.him188.ani.app.data.repository.user.SettingsRepository
import me.him188.ani.app.ui.foundation.lan.LanHttpRequest
import me.him188.ani.app.ui.foundation.lan.LanHttpResponse
import me.him188.ani.app.ui.subject.episode.tv.TvPlayerScreenshots
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import org.koin.mp.KoinPlatform
import java.io.ByteArrayOutputStream
import kotlin.random.Random
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Web 控制台播放卡上的「截图」: 电视截下此刻的画面、字幕、弹幕三层 (见 [TvPlayerScreenshots]), 手机网页按勾选叠加、加水印 (logo、番名与集数、
 * 播放时间) 后下载 (见 SHOT_SCRIPT). 合成放在手机上: 改选项当场出预览, 不来回请求电视.
 *
 * - `POST api/player/screenshot`: 截一张, 回各层的地址与水印用的信息;
 * - `GET api/player/screenshot/<id>/<层>.png`: 取某一层 (frame / subtitles / danmaku), 截图后在后台编码, 编好之前请求等着;
 *   `logo.png` 是这部番的标题 logo, 截图时就拉下来 (见 [fetchLogo]);
 * - `GET api/player/screenshot/logos`: 这部番在 TMDB 上各种语言的全部 logo (手机上「添加水印 → 查看 TMDB 上的全部 logo」);
 * - `api/player/screenshot/presets…`: 水印预设, 见 [RemoteScreenshotPresets];
 * - `GET api/player/screenshot/icon.png`: 应用图标 (水印选「Izuko TV」时用).
 *
 * 只留最近一张: 再截一张或过了 [KEEP] 就丢掉.
 */
internal object RemoteScreenshot {
    private val logger = logger<RemoteScreenshot>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("RemoteScreenshot"))

    private class Shot(val id: String, val layers: Map<String, Deferred<ByteArray?>>, val logo: Deferred<LanHttpResponse?>?)

    @Volatile
    private var last: Shot? = null

    /** 处理 `api/player/screenshot` 下的请求; 路径或方法不认识返回 null. */
    fun handle(player: RemotePlayerHandle?, request: LanHttpRequest): LanHttpResponse? {
        val get = request.method == "GET" || request.method == "HEAD"
        val path = request.path.removePrefix(PATH)
        return when {
            path.isEmpty() && request.method == "POST" -> json(capture(player))
            path == "/logos" && get -> json(logos(player))
            // 水印预设, 见 RemoteScreenshotPresets
            path == "/presets" || path.startsWith("/presets/") -> RemoteScreenshotPresets.handle(path, request)?.let(::json)
            path == "/icon.png" && get -> iconBytes?.let { LanHttpResponse.bytes(it, "image/png", cacheControl = "private, max-age=86400") }
                ?: LanHttpResponse.status(404, "Not Found")
            path.startsWith("/") && get -> layer(path.removePrefix("/"))
            else -> null
        }
    }

    private fun capture(player: RemotePlayerHandle?): JsonObject {
        if (player == null) return result(false, tr("电视当前不在播放页"))
        if (player.background) return result(false, tr("电视上没有打开播放器，截不了图"))
        val shot = runBlocking { withTimeoutOrNull(CAPTURE_TIMEOUT) { TvPlayerScreenshots.capture() } }
            ?: return result(false, tr("电视上现在没有显示播放画面（可能休眠了，或者切到了别的应用），截不了图"))
        val width = shot.frame.width
        val height = shot.frame.height
        val layers = buildMap {
            put(LAYER_FRAME, encode(shot.frame))
            shot.subtitles?.let { put(LAYER_SUBTITLES, encode(it)) }
            shot.danmaku?.let { put(LAYER_DANMAKU, encode(it)) }
        }
        val logo = runBlocking { withTimeoutOrNull(LOGO_TIMEOUT) { titleLogo(player) } }?.let(::fetchLogo)
        val id = Random.nextLong().toULong().toString(36)
        val current = Shot(id, layers, logo)
        last = current
        scope.launch {
            delay(KEEP)
            if (last === current) last = null
        }
        logger.info { "Remote screenshot $id: ${width}x$height, layers=${layers.keys}" }

        val page = player.page
        val episode = page?.episodePresentation
        return buildJsonObject {
            put("ok", true)
            put("id", id)
            put("width", width)
            put("height", height)
            putJsonObject("layers") {
                for (name in layers.keys) put(name, "$PATH/$id/$name.png")
            }
            put("subjectId", player.vm.subjectId)
            put("title", page?.subjectPresentation?.title)
            if (episode != null) {
                put("episode", tr("第 {0} 话", episode.sort))
                put("episodeName", episode.title)
            }
            put("position", shot.positionMillis)
            if (logo != null) put("logo", "$PATH/$id/$LAYER_LOGO.png")
            put("icon", "$PATH/icon.png")
        }
    }

    /** 后台编码 PNG, 编完就释放位图 (4K 画面一张 33MB, 不能留着). */
    private fun encode(bitmap: Bitmap): Deferred<ByteArray?> = scope.async {
        try {
            ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        } catch (e: Exception) {
            logger.warn(e) { "Remote screenshot: failed to encode a layer" }
            null
        } finally {
            bitmap.recycle()
        }
    }

    private fun layer(path: String): LanHttpResponse {
        val id = path.substringBefore('/')
        val name = path.substringAfter('/').removeSuffix(".png")
        val shot = last?.takeIf { it.id == id } ?: return LanHttpResponse.status(404, "Not Found")
        if (name == LAYER_LOGO) {
            val logo = shot.logo ?: return LanHttpResponse.status(404, "Not Found")
            return runBlocking { withTimeoutOrNull(LOGO_FETCH_TIMEOUT) { logo.await() } } ?: LanHttpResponse.status(404, "Not Found")
        }
        val task = shot.layers[name] ?: return LanHttpResponse.status(404, "Not Found")
        val bytes = runBlocking { withTimeoutOrNull(ENCODE_TIMEOUT) { task.await() } }
            ?: return LanHttpResponse.status(503, "Service Unavailable")
        return LanHttpResponse.bytes(bytes, "image/png", cacheControl = "private, max-age=600")
    }

    /**
     * 截图时就把 logo 拉下来 (原图拉不到退到 w500): 手机上取的时候直接给, 不跟网页上同时在拉的封面、图标抢转发的名额
     * (见 [RemoteImageProxy] 的并发上限与失败记账). 两张都拉不到为 null, 手机上就没有「番剧 logo」这一项.
     */
    private fun fetchLogo(logo: TmdbTitleLogo): Deferred<LanHttpResponse?> = scope.async(Dispatchers.IO) {
        val response = listOf(logo.url(LOGO_WIDTH), logo.url(LOGO_FALLBACK_WIDTH)).firstNotNullOfOrNull { url ->
            RemoteImageProxy.serveTrusted(url).takeIf { it.status == 200 }
        }
        if (response == null) logger.warn { "Remote screenshot: failed to fetch the title logo ${logo.filePath}" }
        response
    }

    /**
     * 这部番的标题 logo (TMDB 图), 语言同电视上标题 logo 的设置; 没有时为 null. 不看「标题显示 logo」开没开: 那是电视界面的偏好,
     * 截图水印用不用由手机上选.
     */
    private suspend fun titleLogo(player: RemotePlayerHandle): TmdbTitleLogo? {
        val page = player.page ?: return null
        val tmdb = KoinPlatform.getKoin().get<TmdbImageService>()
        val language = logoLanguage()
        val subjectId = player.vm.subjectId
        return tmdb.peekTitleLogo(subjectId, language)
            ?: tmdb.getTitleLogo(subjectId, page.subjectPresentation.originalTitle, language)
    }

    /** 标题 logo 用哪种语言的: 同电视上的设置 (null = 作品原语言), 见 TvTitleLogoLanguage. */
    private suspend fun logoLanguage(): String? {
        val settings = KoinPlatform.getKoin().get<SettingsRepository>().themeSettings.flow.first()
        return if (settings.tvTitleLogoLanguage == TvTitleLogoLanguage.Original) {
            null
        } else {
            Locale.current.toTmdbLanguage().substringBefore('-').lowercase()
        }
    }

    /**
     * 这部番在 TMDB 上的全部 logo (各种语言, 同「标题 logo 不对」报告里列的那份, 见 TmdbImageService.getTitleLogoCandidates).
     * 图经 [RemoteImageProxy] 同源转发 (手机上要画进 canvas): `thumb` 是 w500, `src` 依次是原图、w500.
     */
    private fun logos(player: RemotePlayerHandle?): JsonObject {
        val page = player?.page ?: return result(false, tr("电视当前不在播放页"))
        val tmdb = KoinPlatform.getKoin().get<TmdbImageService>()
        val candidates = runBlocking {
            withTimeoutOrNull(LOGOS_TIMEOUT) {
                tmdb.getTitleLogoCandidates(player.vm.subjectId, page.subjectPresentation.originalTitle, logoLanguage())
            }
        } ?: return result(false, tr("没能从 TMDB 拿到这部番的 logo"))
        return buildJsonObject {
            put("ok", true)
            putJsonArray("logos") {
                for (option in candidates.logos) addJsonObject {
                    put("path", option.logo.filePath)
                    option.language?.let { put("lang", it) }
                    put("aspect", option.logo.aspectRatio)
                    put("thumb", RemoteImageProxy.proxied(option.logo.url(LOGO_FALLBACK_WIDTH)))
                    putJsonArray("src") {
                        add(RemoteImageProxy.proxied(option.logo.url(LOGO_WIDTH)))
                        add(RemoteImageProxy.proxied(option.logo.url(LOGO_FALLBACK_WIDTH)))
                    }
                }
            }
        }
    }

    /** 应用图标, 256px PNG; 取不到为 null. */
    private val iconBytes: ByteArray? by lazy {
        runCatching {
            val context = KoinPlatform.getKoin().get<Context>()
            val drawable = context.packageManager.getApplicationIcon(context.packageName)
            val bitmap = Bitmap.createBitmap(ICON_SIZE, ICON_SIZE, Bitmap.Config.ARGB_8888)
            drawable.setBounds(0, 0, ICON_SIZE, ICON_SIZE)
            drawable.draw(Canvas(bitmap))
            ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
                .also { bitmap.recycle() }
        }.onFailure { logger.warn(it) { "Remote screenshot: failed to render the app icon" } }.getOrNull()
    }

    private fun json(obj: JsonObject): LanHttpResponse =
        LanHttpResponse.bytes(obj.toString().toByteArray(), "application/json; charset=utf-8")

    private fun result(ok: Boolean, message: String): JsonObject = buildJsonObject {
        put("ok", ok)
        put("message", message)
    }

    const val PATH = "api/player/screenshot"
    private const val LAYER_FRAME = "frame"
    private const val LAYER_SUBTITLES = "subtitles"
    private const val LAYER_DANMAKU = "danmaku"
    private const val LAYER_LOGO = "logo"

    /** 截图本身在主线程上读回几张位图, 正常几十毫秒. */
    private val CAPTURE_TIMEOUT = 5.seconds

    /** 4K 画面编 PNG 在电视上要一两秒. */
    private val ENCODE_TIMEOUT = 30.seconds

    /** 标题 logo 本进程没查过时要去 TMDB 查一次; 等不到就先不给 (手机上照样能用应用图标). */
    private val LOGO_TIMEOUT = 3.seconds

    private val KEEP = 10.minutes
    private const val ICON_SIZE = 256

    /** logo 取原图那一档 (见 TmdbTitleLogo.url): 4K 截图上大号水印也不糊. */
    private const val LOGO_WIDTH = 1000

    /** 原图拉不到时退到的那一档 (w500). */
    private const val LOGO_FALLBACK_WIDTH = 500

    /** 列 TMDB 上全部 logo 时等多久 (本进程没查过对应的 TMDB 条目时要先搜). */
    private val LOGOS_TIMEOUT = 15.seconds

    /** 手机来取 logo 时最多等它拉完多久 (两档各有 5 秒上限, 见 RemoteImageProxy). */
    private val LOGO_FETCH_TIMEOUT = 12.seconds
}
