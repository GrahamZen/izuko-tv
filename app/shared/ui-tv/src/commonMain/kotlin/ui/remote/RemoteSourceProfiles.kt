/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.remote

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import me.him188.ani.app.domain.foundation.HttpClientProvider
import me.him188.ani.app.domain.foundation.ScopedHttpClientUserAgent
import me.him188.ani.app.domain.foundation.get
import me.him188.ani.app.domain.media.fetch.MediaSourceManager
import me.him188.ani.app.domain.media.probe.MediaStreamProbe
import me.him188.ani.app.domain.media.resolver.EpisodeMetadata
import me.him188.ani.app.domain.media.resolver.MediaResolver
import me.him188.ani.app.domain.mediasource.instance.MediaSourceInstance
import me.him188.ani.app.domain.mediasource.profile.SourceObservation
import me.him188.ani.app.domain.mediasource.profile.SourceProfiles
import me.him188.ani.app.ui.foundation.lan.LanHttpRequest
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.source.MatchKind
import me.him188.ani.datasources.api.source.MediaFetchRequest
import me.him188.ani.datasources.api.source.MediaSourceKind
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.platform.currentTimeMillis
import org.koin.mp.KoinPlatform
import java.net.URLDecoder
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds

/**
 * 设置标签「数据源」页的数据源画像 (见 [SourceProfiles]): 每个源下面一行实际表现与建议层级, 以及「深度测试」 ——
 * 拿一部各站都有的番 ([SAMPLE]) 搜一次、解析出第一集的视频地址、探测分辨率与码率, 结果也记进画像 (标为测试).
 *
 * 深度测试在后台跑 (控制台一次只处理一个请求, 搜索加解析可能要一两分钟): `POST api/sources/deeptest` 开始, `GET` 查进度.
 * 要 WebView 嗅探的源离开播放页解析不了 (WebView 挂在播放页上), 记下「要 WebView」, 码率等真正播放时再记.
 * BT 源只测搜索 (解析就会开始下载).
 */
internal object RemoteSourceProfiles {
    const val DEEP_TEST_PATH = "api/sources/deeptest"

    private val logger = logger<RemoteSourceProfiles>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val jobs = MutableStateFlow<Map<String, JsonObject>>(emptyMap())

    private val SEARCH_TIMEOUT = 60.seconds
    private val RESOLVE_TIMEOUT = 40.seconds
    private val PROBE_TIMEOUT = 30.seconds

    /** 葬送的芙莉莲 第 1 集: 各站几乎都有. */
    private val SAMPLE = MediaFetchRequest(
        subjectId = "400602",
        episodeId = "1227087",
        subjectNameCN = "葬送的芙莉莲",
        subjectNames = listOf("葬送的芙莉莲", "葬送のフリーレン", "Sousou no Frieren"),
        episodeSort = EpisodeSort(1),
        episodeName = "冒险结束",
    )

    /** 数据源列表里每个源的 `profile` (没有记录时不写) 与 `webViewDemoted` (要 WebView, 选源时降了一级). */
    fun putProfile(builder: JsonObjectBuilder, mediaSourceId: String, demoted: Set<String>) = with(builder) {
        if (mediaSourceId in demoted) put("webViewDemoted", true)
        val s = SourceProfiles.summary(mediaSourceId) ?: return@with
        putJsonObject("profile") {
            put("plays", s.plays)
            put("tests", s.tests)
            put("failures", s.failures)
            s.width?.let { put("width", it) }
            s.height?.let { put("height", it) }
            s.codec?.let { put("codec", it) }
            s.kbps?.let { put("kbps", it) }
            s.webView?.let { put("webView", it) }
            put("hlsPlays", s.hlsPlays)
            put("adPlays", s.adPlays)
            s.startupMillis?.let { put("startupMs", it) }
            put("searches", s.searches)
            s.searchMillis?.let { put("searchMs", it) }
            put("searchFailures", s.searchFailures)
            put("lastAt", s.lastAt)
            s.suggestedTier?.let { put("suggestedTier", it) }
            putJsonArray("reasons") { s.reasons.forEach { add(it.name) } }
        }
    }

    fun demotedIds(): Set<String> = runBlocking { SourceProfiles.webViewSources.first() }

    /** `api/sources/deeptest`: POST `id` 开始, GET `?id=` 查进度 (没测过 `state: none`). */
    fun handle(request: LanHttpRequest): JsonObject? {
        val id = if (request.method == "POST") request.field("id") else request.queryParam("id").orEmpty()
        if (request.method == "POST") start(id)
        else if (request.method != "GET") return null
        return jobs.value[id] ?: buildJsonObject { put("state", "none") }
    }

    private fun LanHttpRequest.field(name: String): String =
        formFieldList().lastOrNull { it.first == name }?.second.orEmpty()

    private fun LanHttpRequest.queryParam(name: String): String? =
        query.split('&').firstOrNull { it.substringBefore('=') == name }
            ?.substringAfter('=', "")
            ?.let { URLDecoder.decode(it, "UTF-8") }

    private fun start(instanceId: String) {
        if (jobs.value[instanceId]?.get("state")?.jsonPrimitive?.content == "running") return
        update(instanceId) { put("state", "running"); put("stage", "search") }
        scope.launch {
            val result = try {
                run(instanceId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.info { "Deep test of $instanceId failed: $e" }
                Progress().apply { error = "failed"; detail = describe(e) }
            }
            update(instanceId) { result.writeTo(this); put("state", "done") }
        }
    }

    private class Progress {
        var searchMs: Long? = null
        var count: Int? = null
        var title: String? = null
        var resolveMs: Long? = null
        var webView: Boolean? = null
        var probeMs: Long? = null
        var probe: MediaStreamProbe.Result? = null
        var error: String? = null
        var detail: String? = null

        fun writeTo(b: JsonObjectBuilder) = with(b) {
            searchMs?.let { put("searchMs", it) }
            count?.let { put("count", it) }
            title?.let { put("title", it) }
            resolveMs?.let { put("resolveMs", it) }
            webView?.let { put("webView", it) }
            probeMs?.let { put("probeMs", it) }
            probe?.let { p ->
                put("container", p.container)
                p.width?.let { put("width", it) }
                p.height?.let { put("height", it) }
                p.codec?.let { put("codec", it) }
                p.kbps?.let { put("kbps", it) }
            }
            error?.let { put("error", it) }
            detail?.let { put("detail", it) }
        }
    }

    /** 异常的说明: 消息为空时用类名 (仓库层的异常常不带消息). */
    private fun describe(e: Throwable): String? = e.message?.takeIf { it.isNotBlank() } ?: e::class.simpleName

    private fun update(instanceId: String, build: JsonObjectBuilder.() -> Unit) {
        jobs.update { it + (instanceId to buildJsonObject(build)) }
    }

    private suspend fun run(instanceId: String): Progress {
        val koin = KoinPlatform.getKoin()
        val instance: MediaSourceInstance = koin.get<MediaSourceManager>().allInstances.first().find { it.instanceId == instanceId }
            ?: return Progress().apply { error = "missing" }
        val source = instance.source
        val p = Progress()

        // 1. 搜索
        var t = currentTimeMillis()
        val matches = try {
            withTimeoutOrNull(SEARCH_TIMEOUT) { source.fetch(SAMPLE).results.take(50).toList() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 站点拒绝 (如 403) 等: 只报搜索失败, 不记进画像 (源上可能只是没这部番)
            return p.apply { searchMs = currentTimeMillis() - t; error = "search_failed"; detail = describe(e) }
        }
        p.searchMs = currentTimeMillis() - t
        if (matches == null) return p.apply { error = "search_timeout" }
        p.count = matches.size
        val match = matches.firstOrNull { it.kind == MatchKind.EXACT } ?: matches.firstOrNull()
            ?: return p.apply { error = "search_empty" }
        p.title = match.media.originalTitle
        update(instanceId) { put("state", "running"); put("stage", "resolve"); p.writeTo(this) }
        if (match.media.kind != MediaSourceKind.WEB) return p.apply { error = "not_web" }

        // 2. 解析出视频地址; 规则源走没走 WebView 由 RuleMediaSource 记下
        t = currentTimeMillis()
        val resolver = koin.get<MediaResolver>()
        val resolveError = try {
            val provider = withTimeoutOrNull(RESOLVE_TIMEOUT) { resolver.resolve(match.media, EpisodeMetadata("", EpisodeSort(1), EpisodeSort(1))) }
            if (provider == null) "resolve_timeout" else null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // WebView 挂在播放页上, 离开播放页时嗅探不了
            if (e.message?.contains("not attached") == true) "needs_webview" else (describe(e) ?: "resolve_failed")
        }
        p.resolveMs = currentTimeMillis() - t
        // 只认规则源自己报的 (RuleMediaSource 记); 网页抓取源本来就靠 WebView, 不参与降级
        val webView = SourceProfiles.takeWebView(source.mediaSourceId)
        p.webView = webView
        val resolved = SourceProfiles.resolvedOf(match.media.mediaId)
        if (resolveError != null || resolved == null) {
            val needsWebView = resolveError == "needs_webview"
            p.error = if (needsWebView) "needs_webview" else "resolve_failed"
            p.detail = resolveError?.takeIf { !needsWebView }
            // 要 WebView 不算失败 (只是这里测不了); 别的解析失败算这个源的一次失败
            SourceProfiles.record(
                source.mediaSourceId,
                SourceObservation(
                    at = currentTimeMillis(), ok = needsWebView, webView = webView,
                    reason = if (needsWebView) null else "RESOLVE_FAILED", test = true,
                ),
            )
            return p
        }
        update(instanceId) { put("state", "running"); put("stage", "probe"); p.writeTo(this) }

        // 3. 探测
        t = currentTimeMillis()
        val client = koin.get<HttpClientProvider>().get(ScopedHttpClientUserAgent.BROWSER)
        val probe = withTimeoutOrNull(PROBE_TIMEOUT) { client.use { MediaStreamProbe.probe(this, resolved.uri, resolved.headers) } }
        p.probeMs = currentTimeMillis() - t
        p.probe = probe
        if (probe == null) p.error = "probe_failed"
        SourceProfiles.record(
            source.mediaSourceId,
            SourceObservation(
                at = currentTimeMillis(),
                ok = probe != null,
                webView = webView,
                width = probe?.width,
                height = probe?.height,
                codec = probe?.codec,
                kbps = probe?.kbps,
                reason = if (probe == null) "PROBE_FAILED" else null,
                test = true,
            ),
        )
        return p
    }
}

/** 数据源画像那一行与「深度测试」的样式 (见 [RemoteSourceProfiles]). */
internal val SOURCE_PROFILE_STYLE = """
.src-prof { margin-top: 6px; font-size: 12px; color: var(--mute); line-height: 1.5; }
.src-prof b { font-weight: 600; color: var(--sub); }
.src-sug { display: inline-block; margin-left: 4px; padding: 0 6px; border-radius: 6px; background: var(--chip); color: var(--on-chip); }
.src-sug.diff { background: var(--p-soft); color: var(--p); font-weight: 600; }
.src-deep { margin: 8px 2px 0; font-size: 13px; color: var(--mute); line-height: 1.5; }
.src-deep.ok { color: #2e7d32; }
.src-deep.bad { color: var(--err); }
""".trimIndent()

/**
 * 数据源行里的画像一行 (`window.srcProfileHtml`) 与「深度测试」(`window.srcDeepTest`, 按钮由 `window.srcDeepButton` 给);
 * 由 SOURCES_SCRIPT 的 rowHtml 与点击处理各调一行.
 */
internal val SOURCE_PROFILE_SCRIPT = """
(function () {
  function resLabel(w, h) {
    w = w || 0; h = h || 0;
    if (w >= 3800 || h >= 2000) return '4K';
    if (w >= 1800 || h >= 1000) return '1080p';
    if (w >= 1200 || h >= 700) return '720p';
    return h ? h + 'p' : '';
  }
  function rate(kbps) { return kbps >= 1000 ? (kbps / 1000).toFixed(1) + ' Mbps' : kbps + ' kbps'; }
  function videoText(o) {
    var v = [];
    if (o.height || o.width) v.push(resLabel(o.width, o.height) + (o.codec ? ' ' + o.codec : ''));
    else if (o.codec) v.push(o.codec);
    if (o.kbps) v.push(rate(o.kbps));
    return v;
  }
  var REASONS = { ADS: T('有插播广告'), WEB_VIEW: T('要 WebView'), BELOW_1080P: T('不到 1080p'), FAILURES: T('失败多'), SEARCH_FAILS: T('搜索常失败') };
  /** 各源最近一次深度测试的结果 (列表重画后照样显示): id -> { cls, text } */
  var deep = {};
  window.srcProfileHtml = function (s) {
    var p = s.profile;
    var h = '';
    if (p) {
      var parts = videoText(p);
      if (p.webView != null) parts.push(p.webView ? T('要 WebView') : T('直连'));
      if (p.hlsPlays) parts.push(p.adPlays ? T('删到插播广告 {0} 次', p.adPlays) : T('没删到插播广告'));
      if (p.startupMs != null) parts.push(T('开播 {0} 秒', (p.startupMs / 1000).toFixed(1)));
      if (p.failures) parts.push(T('失败 {0} 次', p.failures));
      if (p.searchMs != null) parts.push(T('搜索 {0} 秒', (p.searchMs / 1000).toFixed(1)));
      if (p.searchFailures) parts.push(T('搜索失败 {0}/{1} 次', p.searchFailures, p.searches));
      var n = p.plays + p.tests + p.failures;
      h += '<b>' + (n ? T('画像（近 {0} 次）', n) : T('画像')) + '</b> ' + parts.map(esc).join(' · ');
      if (p.suggestedTier != null) {
        var current = s.tier != null ? s.tier : 2;
        var why = (p.reasons || []).map(function (r) { return REASONS[r] || r; }).join('、');
        h += '<span class="src-sug' + (p.suggestedTier !== current ? ' diff' : '') + '">' + T('建议 T{0}', p.suggestedTier) +
          (why ? '（' + esc(why) + '）' : '') + '</span>';
      }
    }
    if (s.webViewDemoted) h += (h ? '<br>' : '') + T('要 WebView 才拿得到视频，选源时降了一级');
    var d = deep[s.id];
    return (h ? '<div class="src-prof">' + h + '</div>' : '') + (d ? '<div class="src-deep' + d.cls + '">' + esc(d.text) + '</div>' : '');
  };
  window.srcDeepButton = function (s) {
    return '<button data-act="deep" class="ic">' + window.ICONS.speed + T('深度测试') + '</button>';
  };
  var ERRORS = {
    search_timeout: T('搜索超时'), search_empty: T('没搜到'), search_failed: T('搜索失败'), not_web: T('BT 源只测搜索'),
    needs_webview: T('要 WebView 嗅探，离开播放页测不了，播放时会补上画像'),
    resolve_failed: T('解析不出视频地址'), probe_failed: T('读不出视频信息'), missing: T('数据源不存在，请刷新'), failed: T('测试出错')
  };
  function deepText(d) {
    var parts = [];
    if (d.searchMs != null) parts.push(T('搜索 {0} 秒', (d.searchMs / 1000).toFixed(1)) + (d.count != null ? T('，{0} 条', d.count) : ''));
    if (d.resolveMs != null) parts.push(T('解析 {0} 秒', (d.resolveMs / 1000).toFixed(1)) + (d.webView != null ? '（' + (d.webView ? T('要 WebView') : T('直连')) + '）' : ''));
    var v = videoText(d);
    if (v.length) parts.push(v.join(' · ') + (d.container ? '（' + d.container + '）' : ''));
    if (d.error) parts.push((ERRORS[d.error] || d.error) + (d.detail ? '：' + d.detail : ''));
    if (d.state === 'running') parts.push(d.stage === 'resolve' ? T('正在解析…') : d.stage === 'probe' ? T('正在读视频信息…') : T('正在搜索…'));
    return parts.map(esc).join(' → ');
  }
  /** 「深度测试」: 开始后每 2 秒查一次, 结果留在这一行下面; 做完重拉列表, 画像那一行跟着更新. */
  window.srcDeepTest = function (item, s, btn) {
    btn.disabled = true;
    // 列表可能中途重画: 每次都按 id 重新找这一行
    function show(cls, text) {
      deep[s.id] = { cls: cls, text: text };
      var row = document.querySelector('#src-list .src-item[data-id="' + CSS.escape(s.id) + '"]');
      if (!row) return;
      var out = row.querySelector('.src-deep');
      if (!out) {
        out = document.createElement('div');
        row.querySelector('.src-btns').insertAdjacentElement('beforebegin', out);
      }
      out.className = 'src-deep' + cls;
      out.textContent = text;
    }
    function render(d) { show(d.state === 'done' ? (d.error && d.error !== 'needs_webview' ? ' bad' : ' ok') : '', deepText(d)); }
    function fail() { btn.disabled = false; show(' bad', T('测试出错')); }
    function poll() {
      window.getJson('api/sources/deeptest?id=' + encodeURIComponent(s.id)).then(function (d) {
        render(d);
        if (d.state === 'running') setTimeout(poll, 2000);
        else { btn.disabled = false; if (window.loadSources) window.loadSources(); }
      }).catch(fail);
    }
    show('', T('正在搜索…'));
    window.post('api/sources/deeptest', { id: s.id }).then(function (d) { render(d); setTimeout(poll, 2000); }).catch(fail);
  };
})();
""".trimIndent()
