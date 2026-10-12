/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.remote

/**
 * 播放卡全屏的「同步播放」(地址与判断见 [RemoteMirror]): 手机能直接播电视这一集的地址时, 全屏进的是一个播真视频的
 * `<video>`, 位置、暂停、倍速跟着电视走; 全屏 / 小窗里的操作照旧转给电视。播不了时全屏照旧进静音载体
 * (见 RemoteControlPage 的 createCarrier), 按钮下面写一行原因。
 *
 * - **同步照「一起看」**: 电视为主, 差 [DRIFT_S] 秒以上才跳 (跳一次要重新缓冲), 暂停与倍速直接跟。位置读的是播放卡那边
 *   经 stateGuard 压过的那份 (`mirrorBridge.playback`), 刚在手机上拖完、电视还没跟上时不会被拽回去。声音不处理。
 * - **iPhone 的全屏播放器认死进去时那一份 src** (载体踩过, 见 ensureCarrier): 全屏 / 小窗里电视换了片源或集, 只能退出来
 *   让用户重进; 元数据没到也进不了全屏, 所以播放卡开着时就预先加载 (`preload = metadata`)。
 * - **与载体不同时出声**: 进全屏前把载体按停, 全屏 / 小窗期间载体那边的自动续播都让路 (`mirrorPresenting`),
 *   退出后由载体自己的 keepMediaAudioAlive 接回去。
 * - 电视在播本机上下完的文件 (缓存) 时, 地址是电视自己发这个文件的 (见 RemoteFileStream); 浏览器播不了这个格式
 *   (如 iPhone 上的 mkv) 就直接说不支持。
 * - 地址里可能带鉴权参数, 所以回报电视日志 (clientLog) 时只写主机名。
 */
internal val MIRROR_SCRIPT = """
(function () {
  var bridge = window.mirrorBridge, hooks = window.remoteHooks;
  if (!bridge || !hooks) return;
  /** 差多少秒才跳 (同「一起看」的 3 秒): 跳一次要重新缓冲, 小的差错由它去 */
  var DRIFT_S = 3;
  var SELF_MS = 500;
  var info = null;            // 最近一次 api/player/mirror 的回复
  var askedFor = '', asking = false, askAgainAt = 0;
  var v = null;               // 播真视频的那个 video
  var loadedKey = '';         // v 上装的是哪条地址 (info.key)
  var ready = false;          // 元数据到了 (iPhone 上没到进不了全屏)
  var failure = '';           // 手机这边加载失败的原因
  var fs = false, pip = false, enteringUntil = 0;
  var presentedKey = '';      // 进全屏时放的是哪一份媒体 (播放状态里的 mediaKey, 见 RemoteMirror 的 mirrorKeyOf)
  var selfAt = 0, selfSeekAt = 0, rateHoldUntil = 0;
  /*
   * 开播时对齐 (见 align): 进全屏、电视从暂停接着播、跳过一次之后, 手机真正出画面时量一下差多少, 差得多就跳过去。
   * 跳过去要等数据, 这段时间电视又往前走了, 所以跳到电视前面一点: 跳到的地方手机已经缓冲好了的话几乎不用等, 只多跳
   * [SEEK_BUFFERED_S]; 要从网上取的, 多跳刚才落后的秒数 (手机取一次就要这么久)。跳完再量, 还差多少补到下一次上,
   * 最多跳 [ALIGN_MAX_JUMPS] 次。电视照常播, 不动它 (电视跳一下也要时间, 让它跟过来反而差得更多)。
   * 不用临时快放: iPhone 的原生全屏不认改倍速。
   */
  /** 电视报的位置每 0.1 秒更新一次 (mediamp 的位置轮询), 加上局域网单程约 50 毫秒 */
  var LATENCY_S = 0.1;
  var ALIGN_OK_S = 0.15;
  /** 跳到手机已经缓冲好的地方时往电视前面多跳的秒数 */
  var SEEK_BUFFERED_S = 0.1;
  /** iPhone / iPad 上的浏览器都是 WebKit, Mac 上的 Safari 也是: 都不播 [APPLE_UNPLAYABLE] 这几种容器 */
  var APPLE_WEBKIT = /iPhone|iPad|iPod/.test(navigator.userAgent) || (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1) ||
    (/Safari/.test(navigator.userAgent) && !/Chrome|Chromium|Edg|OPR|Firefox/.test(navigator.userAgent));
  var APPLE_UNPLAYABLE = ['mkv', 'ts', 'flv'];
  /** 电视发的本机文件多久还没有元数据就当成手机不支持 */
  var LOCAL_LOAD_TIMEOUT_MS = 10000;
  /** 开播对齐最多跳几次 */
  var ALIGN_MAX_JUMPS = 3;
  var sample = null;          // 最近一次电视的播放状态: { pos 秒, playing, rate, at 收到的时刻 }
  var alignPending = false, alignTimer = null, alignHoldUntil = 0;
  var alignJumps = 0, alignLead = 0;  // 这次开播已经跳了几次; 跳到电视前面多少秒

  function quiet(fn) {
    selfAt = Date.now();
    try { fn(); } finally { selfAt = Date.now(); }
  }
  function selfActed() { return Date.now() - selfAt < SELF_MS; }
  function presenting() { return fs || pip || Date.now() < enteringUntil; }
  function canEnter() {
    var p = bridge.playback();
    return !!(v && ready && !failure && info && info.playable && loadedKey === info.key && p && p.mediaKey === info.key);
  }
  window.mirrorPresenting = presenting;
  window.mirrorReady = canEnter;
  window.mirrorNote = function () {
    if (canEnter()) return '';
    if (failure) return T('全屏只显示控制：{0}', failure);
    if (info && !info.playable && !info.pending && info.reason) return T('全屏只显示控制：{0}', info.reason);
    return '';
  };

  function cardShown() {
    var b = document.getElementById('pb-fs');
    return !document.hidden && !!(b && b.offsetParent);
  }
  function errorText(code) {
    if (code === 1) return T('加载被中断');
    if (code === 2) return T('网络错误');
    if (code === 3) return T('解码失败');
    if (code === 4) return T('格式不支持或被拒绝访问');
    return 'code ' + code;
  }
  function describe(el) {
    return (isFinite(el.duration) ? el.duration.toFixed(1) + 's' : String(el.duration)) +
      ' ' + el.videoWidth + 'x' + el.videoHeight;
  }

  // ---- 地址: 播放器里换了媒体 (mediaKey 变了) 才问电视; 问的那一下电视又换了的话下一轮再问 ----
  function maybeAsk(p) {
    if (presenting() || asking || !cardShown()) return;
    var key = (p && p.mediaKey) || '';
    if (!key) {
      if (loadedKey) unload();
      info = null;
      askedFor = '';
      return;
    }
    if (key === askedFor && !(info && info.pending && Date.now() >= askAgainAt)) return;
    askedFor = key;
    asking = true;
    window.fetchT('api/player/mirror')
      .then(function (r) { return r.json(); })
      .then(function (r) {
        asking = false;
        info = r;
        askAgainAt = Date.now() + 3000;
        if (r.key !== askedFor) askedFor = '';
        apply();
      })
      .catch(function (e) {
        asking = false;
        askAgainAt = Date.now() + 5000;
        info = { playable: false, pending: true };
        console.warn('Mirror info failed', e);
      });
  }
  function apply() {
    var r = info;
    if (!r || !r.playable) {
      if (loadedKey) unload();
      if (r && !r.pending) clientLog('mirror unavailable: ' + r.reason);
      bridge.paint();
      return;
    }
    if (loadedKey === r.key) return;
    load(r);
  }
  function load(r) {
    if (!v) v = createVideo();
    ready = false;
    failure = '';
    loadedKey = r.key;
    if (r.kind === 'hls' && !v.canPlayType('application/vnd.apple.mpegurl')) {
      failure = T('这个浏览器不能直接播 HLS');
      clientLog('mirror skipped: no native HLS in this browser, host=' + r.host);
      bridge.paint();
      return;
    }
    // 苹果的浏览器不播这几种容器, 而且不一定报错 (有的 mkv 一直停在加载中): 不去试, 直接说
    if (APPLE_WEBKIT && APPLE_UNPLAYABLE.indexOf(r.kind) >= 0) {
      failure = T('手机不支持播放这个格式（{0}）', r.kind);
      clientLog('mirror skipped: ' + r.kind + ' does not play in this browser');
      bridge.paint();
      return;
    }
    clientLog('mirror loading: kind=' + r.kind + ' host=' + r.host + ' headers=' + (r.headers || []).join(',') +
      (r.subs && r.subs.length ? ' subs=' + r.subs.length : ''));
    setTracks(r);
    v.src = r.url;
    try { v.load(); } catch (e) { console.warn('Mirror load rejected', e); }
    // 电视自己发的文件在局域网里一两秒就有元数据; 迟迟不来也不报错的 (浏览器播不了又不说) 当成不支持
    if (r.local) {
      setTimeout(function () {
        if (loadedKey !== r.key || ready || failure) return;
        failure = T('手机不支持播放这个格式（{0}）', r.kind);
        clientLog('mirror LOAD TIMEOUT kind=' + r.kind + ' host=' + r.host);
        bridge.paint();
      }, LOCAL_LOAD_TIMEOUT_MS);
    }
    bridge.paint();
  }
  /*
   * 外挂字幕挂成字幕轨 (电视转好的 WebVTT, 见 RemoteSubtitles): iPhone 原生全屏里只显示这种; 电视选着的那条设成默认。
   */
  function setTracks(r) {
    var old = v.querySelectorAll('track');
    for (var i = 0; i < old.length; i++) v.removeChild(old[i]);
    ((r && r.subs) || []).forEach(function (s, n) {
      var t = document.createElement('track');
      t.kind = 'subtitles';
      t.src = s.vtt;
      t.label = s.label || T('字幕 {0}', n + 1);
      if (s.lang) t.srclang = s.lang;
      if (s.i === r.subSel) t.default = true;
      v.appendChild(t);
    });
  }
  function unload() {
    loadedKey = '';
    ready = false;
    failure = '';
    if (!v) return;
    setTracks(null);
    quiet(function () { v.pause(); });
    v.removeAttribute('src');
    try { v.load(); } catch (e) {}
  }

  function createVideo() {
    var el = document.createElement('video');
    el.preload = 'metadata';
    // 原生控件: 全屏播放器靠它才给进度条和那颗画中画按钮 (同载体)
    el.controls = true;
    el.setAttribute('playsinline', '');
    el.setAttribute('aria-hidden', 'true');
    // 不能 display:none (那样进不了全屏 / 小窗), 挪到屏幕外
    el.style.cssText = 'position:fixed;left:-9999px;top:0;width:2px;height:2px;opacity:0;pointer-events:none';
    el.addEventListener('loadedmetadata', function () {
      ready = true;
      var p = bridge.playback();
      clientLog('mirror ready: ' + describe(el) + ', tv ' + (p && p.duration ? (p.duration / 1000).toFixed(1) + 's' : '?') +
        ', kind=' + (info && info.kind) + ' host=' + (info && info.host));
      bridge.paint();
    });
    el.addEventListener('error', function () {
      // unload 摘掉 src 引出的那一下不算
      if (!el.getAttribute('src')) return;
      var err = el.error;
      ready = false;
      // 电视自己发的文件不会被拒绝访问, 4 只能是格式不支持 (如 iPhone 播 mkv)
      failure = info && info.local && err && err.code === 4
        ? T('手机不支持播放这个格式（{0}）', info.kind)
        : T('手机播不了这个片源（{0}）', errorText(err && err.code));
      clientLog('mirror LOAD ERROR code=' + (err && err.code) + ' kind=' + (info && info.kind) + ' host=' + (info && info.host) +
        (err && err.message ? ' (' + err.message + ')' : ''));
      if (presenting()) exitPresentation();
      bridge.paint();
    });
    // 全屏 / 小窗里按的暂停、播放、拖动、倍速 = 用户的意思, 转给电视 (与载体同一条路)
    el.addEventListener('pause', function () {
      if (selfActed() || !presenting()) return;
      var p = bridge.playback();
      if (p && p.playing) bridge.control('pause');
    });
    el.addEventListener('play', function () {
      if (selfActed() || !presenting()) return;
      var p = bridge.playback();
      if (p && !p.playing) bridge.control('play');
    });
    // 缓冲完、真正出画面: 开播时的那次对齐在这里做
    el.addEventListener('playing', function () { scheduleAlign(400); });
    el.addEventListener('seeked', function () {
      // 对齐的那一跳完了: 出画面后再量一次 (缓冲够了的话不会再有 playing 事件)
      if (alignPending) scheduleAlign(600);
      if (selfActed() || !presenting() || Date.now() - selfSeekAt < 1000) return;
      var p = bridge.playback(), dur = ((p && p.duration) || 0) / 1000;
      if (!(dur > 0)) return;
      bridge.seek(Math.round(Math.max(0, Math.min(dur, el.currentTime)) * 1000));
    });
    el.addEventListener('ratechange', function () {
      if (selfActed() || !presenting()) return;
      // 电视确认之前别被下一轮对表改回去
      rateHoldUntil = Date.now() + 3000;
      bridge.rate(el.playbackRate);
    });
    function modeChanged() {
      var on = el.webkitPresentationMode ? el.webkitPresentationMode === 'picture-in-picture'
        : document.pictureInPictureElement === el;
      if (on === pip) return;
      pip = on;
      clientLog('mirror pip ' + (on ? 'entered' : 'left'));
      if (!on) left();
      bridge.paint();
    }
    el.addEventListener('webkitpresentationmodechanged', modeChanged);
    el.addEventListener('enterpictureinpicture', modeChanged);
    el.addEventListener('leavepictureinpicture', modeChanged);
    el.addEventListener('webkitbeginfullscreen', function () {
      fs = true;
      enteringUntil = 0;
      clientLog('mirror fullscreen entered: ' + describe(el));
      follow(bridge.playback());
      bridge.paint();
    });
    el.addEventListener('webkitendfullscreen', function () {
      fs = false;
      left();
      bridge.paint();
    });
    document.addEventListener('fullscreenchange', function () {
      var on = document.fullscreenElement === el;
      if (on === fs) return;
      fs = on;
      if (on) {
        enteringUntil = 0;
        follow(bridge.playback());
      } else left();
      bridge.paint();
    });
    document.body.appendChild(el);
    return el;
  }
  /*
   * 全屏 / 小窗结束: 停下来, 载体那边下一轮自己接回去。**晚一点再看**: 在全屏里点画中画时, 全屏结束的事件可能比
   * 「进了小窗」先到, 这时停下来小窗里就是暂停的。
   */
  function left() {
    setTimeout(function () {
      if (!v || presenting()) return;
      if (v.webkitPresentationMode === 'picture-in-picture') { pip = true; return; }
      alignPending = false;
      alignHoldUntil = 0;
      if (alignTimer) { clearTimeout(alignTimer); alignTimer = null; }
      quiet(function () { v.pause(); });
      clientLog('mirror presentation left');
      bridge.paint();
    }, 400);
  }
  function exitPresentation() {
    if (!v) return;
    try {
      if (pip && v.webkitSetPresentationMode) v.webkitSetPresentationMode('inline');
      else if (pip && document.exitPictureInPicture) document.exitPictureInPicture();
      if (fs && v.webkitExitFullscreen) v.webkitExitFullscreen();
      else if (fs && document.exitFullscreen) document.exitFullscreen();
    } catch (e) { console.warn('Mirror exit failed', e); }
  }

  /**
   * 点「全屏」那一下 (用户手势里) 调: 进得去返回 true; 返回 false 时播放卡照旧进载体全屏。
   * iOS 要在手势的调用栈里、视频在播时进全屏, 所以 play() 之后同步就切 (同 enterCarrierFullscreen)。
   */
  window.mirrorEnterFullscreen = function () {
    if (!canEnter()) return false;
    var p = bridge.playback(), ok = true, at = tvNow();
    // 进全屏的事件到之前就算在呈现了: 载体那边这时就得让路
    enteringUntil = Date.now() + 3000;
    presentedKey = info.key;
    bridge.pauseCarrier();
    wantAlign();
    quiet(function () {
      try {
        if (at != null) {
          selfSeekAt = Date.now();
          v.currentTime = at;
        }
        v.playbackRate = (p && p.speed) || 1;
      } catch (e) { console.warn('Mirror position before fullscreen rejected', e); }
      var started = v.play();
      if (started && started.catch) started.catch(function (e) { console.warn('Mirror play rejected', e); });
      try {
        if (typeof v.webkitEnterFullscreen === 'function') v.webkitEnterFullscreen();
        else if (v.requestFullscreen) {
          v.requestFullscreen().catch(function (e) {
            console.warn('Mirror fullscreen rejected', e);
            enteringUntil = 0;
            quiet(function () { v.pause(); });
          });
        } else ok = false;
      } catch (e) {
        console.warn('Mirror fullscreen rejected', e);
        ok = false;
      }
    });
    if (!ok) {
      enteringUntil = 0;
      quiet(function () { v.pause(); });
      clientLog('mirror fullscreen rejected, using the carrier');
      return false;
    }
    return true;
  };

  /** 电视此刻的位置 (秒): 最近一次收到的位置按经过的时间与倍速往前推; 还没收到过时为 null */
  function tvNow() {
    if (!sample) return null;
    var t = sample.pos;
    if (sample.playing) t += ((Date.now() - sample.at) / 1000 + LATENCY_S) * sample.rate;
    return Math.max(0, t);
  }
  function noteSample(p) {
    if (p) sample = { pos: (p.position || 0) / 1000, playing: !!p.playing, rate: p.speed || 1, at: Date.now() };
  }
  /** 这是一次「开播」: 手机出画面后对齐一次 */
  function wantAlign() {
    alignPending = true;
    alignJumps = 0;
    alignLead = 0;
  }
  function scheduleAlign(ms) {
    if (!alignPending || alignTimer || !presenting()) return;
    // 出画面后稍等一下, 让 currentTime 走起来再量
    alignTimer = setTimeout(align, ms);
  }
  function align() {
    alignTimer = null;
    if (!alignPending || !v || !presenting() || !sample || !sample.playing || v.paused) return;
    // 跳过去还在缓冲: 等出画面再量
    if (v.seeking || v.readyState < 3) { scheduleAlign(300); return; }
    var d = tvNow() - v.currentTime;
    if (Math.abs(d) <= ALIGN_OK_S) {
      alignPending = false;
      clientLog('mirror aligned: off ' + d.toFixed(2) + 's' + (alignJumps ? ' after ' + alignJumps + ' jump(s)' : ''));
      return;
    }
    if (alignJumps >= ALIGN_MAX_JUMPS) {
      alignPending = false;
      clientLog('mirror align gave up: off ' + d.toFixed(2) + 's');
      return;
    }
    var t = tvNow();
    // 第一次: 跳到的地方缓冲好了只多跳一点, 要从网上取的多跳刚才落后的秒数; 之后: 上次往前多跳的再补上还差的
    alignLead = ++alignJumps === 1
      ? (isBuffered(t + SEEK_BUFFERED_S) ? SEEK_BUFFERED_S : Math.max(SEEK_BUFFERED_S, d))
      : alignLead + d;
    alignLead = Math.max(0, Math.min(alignLead, 2 * DRIFT_S));
    alignHoldUntil = Date.now() + (alignLead + 3) * 1000;
    jumpTo(t + alignLead, 'align: off ' + d.toFixed(2) + 's, jumping to ' + alignLead.toFixed(2) + 's ahead of the tv');
  }
  /** 手机在 [t] 秒那里已经有数据了 (再往后留半秒余量) */
  function isBuffered(t) {
    var b = v.buffered;
    for (var i = 0; i < b.length; i++) if (b.start(i) <= t && t + 0.5 <= b.end(i)) return true;
    return false;
  }
  function jumpTo(t, why) {
    selfSeekAt = Date.now();
    clientLog('mirror ' + why);
    quiet(function () {
      try { v.currentTime = t; } catch (e) { console.warn('Mirror seek rejected', e); }
    });
  }

  /** 全屏 / 小窗里跟着电视: 暂停与倍速直接跟, 位置差 DRIFT_S 秒以上才跳; 开播时另由 align 对齐一次 */
  function follow(p) {
    if (!v || !presenting() || !p) return;
    // 开播对齐跳过去还在缓冲的那几秒里别动它
    if (Date.now() < alignHoldUntil) return;
    var rate = p.speed || 1;
    if (Date.now() > rateHoldUntil && Math.abs(v.playbackRate - rate) > 0.001) {
      quiet(function () { v.playbackRate = rate; });
    }
    if (p.playing && v.paused) {
      // 电视接着播 = 一次开播
      wantAlign();
      quiet(function () {
        var started = v.play();
        if (started && started.catch) started.catch(function () {});
      });
    } else if (!p.playing && !v.paused) quiet(function () { v.pause(); });
    if (v.readyState < 1 || v.seeking) return;
    var drift = v.currentTime - tvNow();
    if (Math.abs(drift) > DRIFT_S) {
      wantAlign();
      jumpTo(tvNow(), 'resync: drift ' + drift.toFixed(1) + 's');
      return;
    }
    // 出画面的事件错过了 (比如开播时还没轮到对齐) 时从这里补上
    if (!v.paused && v.readyState >= 3) scheduleAlign(0);
  }

  // 播放卡每轮拿到 (经 stateGuard 的) 播放状态时调, 见 RemoteControlPage 的 hooks.playback
  window.mirrorOnPlayback = function (p) {
    noteSample(p);
    maybeAsk(p);
    if (!presenting()) return;
    // 全屏 / 小窗里换不了 src: 电视换了片源或集 (换的那一下 mediaKey 先变空) 就退出来, 让用户重进。
    // 要在 follow 之前: 换集时位置先归零, 先对表的话会把旧视频跳回 0 秒
    if (presentedKey && (p && p.mediaKey) !== presentedKey) {
      presentedKey = '';
      clientLog('mirror left: the tv switched media');
      exitPresentation();
      toast(T('电视换了片源或剧集，点全屏重新进入'));
      return;
    }
    follow(p);
  };
  hooks.unavailable.push(function () {
    info = null;
    askedFor = '';
    if (!presenting() && loadedKey) unload();
  });
})();
"""
