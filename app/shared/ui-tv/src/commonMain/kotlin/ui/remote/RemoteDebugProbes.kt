/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.remote

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import me.him188.ani.app.videoplayer.diagnostics.PlayerProbes

/**
 * `GET api/settings/debug/probes`: 把播放链路上那些「只能看屏确认」的判定读出来, 网页「设置 → 常规 → 调试」里那张只读卡片
 * 与 `console.sh probes` 读的就是它。只在 debug 包里给 (门控同 [RemoteDebugSettings.enabled]); 与调试开关不同, 这张卡片是常驻的,
 * 不随「用完删掉」一起删。
 *
 * - `hls`: 每次 HLS 列表过去广告的结果 —— 总段数、删了几段几秒, 或者没动它的原因。
 *   判据改动 (或换成上游那套按 PTS 连续性判的) 之后, 拿同一个片源前后对一遍就知道有没有回归,
 *   不用靠眼睛看正片里还有没有插播。
 * - `video`: 当前解码器名、要不要给 Surface 写 dataspace 兜底 (NVIDIA)、补齐后的色彩三项齐不齐。
 *   「颜色不对 / 假 HDR」原来只能看屏, 有这三项就能先用数据筛一遍。
 */
internal object RemoteDebugProbes {
    fun probes(): JsonObject {
        if (!RemoteDebugSettings.enabled) return result(false, tr("只有 debug 包能看调试探针"))
        return snapshot()
    }

    private fun snapshot(): JsonObject = buildJsonObject {
        put("ok", true)
        putJsonArray("hls") {
            for (d in PlayerProbes.hls) addJsonObject {
                put("host", d.host)
                put("totalSegments", d.totalSegments)
                put("removedSegments", d.removedSegments)
                put("removedSeconds", d.removedSeconds)
                put("skippedReason", d.skippedReason)
                put("atMillis", d.atMillis)
            }
        }
        val video = PlayerProbes.videoPipeline
        if (video == null) {
            put("video", null as String?)
        } else {
            putJsonObject("video") {
                put("decoder", video.decoderName)
                put("nvidiaWorkaround", video.nvidiaWorkaround)
                put("codecs", video.codecs)
                put("colorSpace", video.colorSpace)
                put("colorRange", video.colorRange)
                put("colorTransfer", video.colorTransfer)
                // 三项缺一就是假 HDR 的成因 (见 ColorInfoRepair)
                put("colorComplete", video.colorComplete)
                put("atMillis", video.atMillis)
            }
        }
    }

    private fun result(ok: Boolean, message: String): JsonObject = buildJsonObject {
        put("ok", ok)
        put("message", message)
    }
}
