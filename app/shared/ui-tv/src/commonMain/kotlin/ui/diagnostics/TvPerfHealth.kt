/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.diagnostics

import android.app.Activity
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.StatFs
import androidx.annotation.RequiresApi
import android.view.Display
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import me.him188.ani.app.ui.remote.tr
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「设备体检」: 一次性读设备与应用的状态 (几秒内完成), 给出结论. 见 [TvPerfDiagnostics].
 *
 * 重点在「为什么会卡 / 为什么被杀」能直接看出来的几样: 存储剩余 (Shield 存储满了会整机卡和 ANR)、系统可用内存、
 * 应用有没有编译成机器码 (刚装 / 刚更新时慢 3~5 倍)、界面是不是按 4K 渲染、最近几次进程退出的原因
 * (被系统因内存回收 / ANR / 崩溃, Android 11 起系统才给)、温控.
 */
internal object TvPerfHealth {
    private const val MB = 1024L * 1024L
    private const val REASON_FREEZER = 14
    private const val REASON_PACKAGE_STATE_CHANGE = 15
    private const val REASON_PACKAGE_UPDATED = 16

    fun run(context: Context, activity: Activity?): JsonObject {
        val findings = ArrayList<PerfFinding>()
        val am = context.getSystemService(ActivityManager::class.java)
        val mem = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
        val totalMb = mem.totalMem / MB
        val availMb = mem.availMem / MB
        val display = activity?.let { currentDisplay(it) }
        val decor = activity?.window?.decorView
        val windowWidth = decor?.width ?: 0
        val windowSize = if (decor != null) "${decor.width}x${decor.height}" else "?"
        val runtime = Runtime.getRuntime()
        val javaUsedMb = (runtime.totalMemory() - runtime.freeMemory()) / MB
        val javaMaxMb = runtime.maxMemory() / MB
        val storage = runCatching { StatFs(context.filesDir.path) }.getOrNull()
        val freeMb = storage?.let { it.availableBytes / MB }
        val compiled = compileState(context)
        val thermal = if (Build.VERSION.SDK_INT >= 29) context.getSystemService(PowerManager::class.java)?.currentThermalStatus ?: 0 else 0
        val exits = exitReasons(am, context.packageName)

        if (freeMb != null) {
            when {
                freeMb < 500 -> findings += PerfFinding(PerfFinding.Level.BAD, tr("存储只剩 {0} MB：剩得太少时整台电视都会卡，甚至应用无响应。清理一下缓存或卸载不用的应用。", freeMb))
                freeMb < 1500 -> findings += PerfFinding(PerfFinding.Level.WARN, tr("存储剩余 {0} MB，偏少；低于 500 MB 时整台电视会明显变卡。", freeMb))
            }
        }
        if (mem.lowMemory || availMb < maxOf(300L, totalMb / 10)) {
            findings += PerfFinding(PerfFinding.Level.WARN, tr("系统可用内存只剩 {0} MB（共 {1} MB）：后台的应用会被回收，Izuko 切到后台后也容易被关掉。", availMb, totalMb))
        }
        when (compiled.level) {
            0 -> findings += PerfFinding(PerfFinding.Level.WARN, tr("应用还没有编译成机器码（刚安装或刚更新时是这样），这时会慢好几倍；电视空闲一段时间后系统会自动编译。"))
            1 -> findings += PerfFinding(PerfFinding.Level.INFO, tr("应用只编译了常用的部分（按使用记录），不常用的界面第一次打开会慢一些。"))
        }
        if (windowWidth >= 3000) {
            findings += PerfFinding(PerfFinding.Level.WARN, tr("界面按 {0} 渲染：4K 界面的 GPU 负担是 1080p 的 4 倍。卡的话把电视的输出分辨率改为 1080p，或把视觉效果调成「流畅」。", windowSize))
        }
        if (javaMaxMb > 0 && javaUsedMb * 100 / javaMaxMb >= 85) {
            findings += PerfFinding(PerfFinding.Level.WARN, tr("Java 堆接近上限（{0}/{1} MB），GC 会越来越频繁。", javaUsedMb, javaMaxMb))
        }
        if (thermal >= 2) {
            findings += PerfFinding(PerfFinding.Level.WARN, tr("电视正在因为发热降频（温控等级 {0}）。", thermal))
        }
        val recent = exits.filter { System.currentTimeMillis() - it.timestamp < 3L * 24 * 3600 * 1000 }
        val lowMemoryKills = recent.filter { it.reason == ApplicationExitInfo.REASON_LOW_MEMORY }
        if (lowMemoryKills.isNotEmpty()) {
            val last = lowMemoryKills.first()
            findings += PerfFinding(
                PerfFinding.Level.WARN,
                tr("最近三天被系统因内存不足关掉 {0} 次（最近一次 {1}，当时{2}）。", lowMemoryKills.size, time(last.timestamp), importanceText(last.importance)),
            )
        }
        recent.count { it.reason == ApplicationExitInfo.REASON_ANR }.takeIf { it > 0 }?.let {
            findings += PerfFinding(PerfFinding.Level.WARN, tr("最近三天出现 {0} 次应用无响应（ANR）。", it))
        }
        recent.count { it.reason == ApplicationExitInfo.REASON_CRASH || it.reason == ApplicationExitInfo.REASON_CRASH_NATIVE }.takeIf { it > 0 }?.let {
            findings += PerfFinding(PerfFinding.Level.WARN, tr("最近三天崩溃 {0} 次。", it))
        }
        if (findings.none { it.level == PerfFinding.Level.BAD || it.level == PerfFinding.Level.WARN }) {
            findings += PerfFinding(PerfFinding.Level.OK, tr("设备状态正常，没有发现会拖慢 Izuko 的问题。"))
        }

        return buildJsonObject {
            putJsonArray("findings") {
                for (f in findings.sortedBy { it.level.ordinal }) addJsonObject {
                    put("level", f.level.name.lowercase(Locale.ROOT))
                    put("text", f.text)
                }
            }
            putJsonObject("device") {
                put("model", "${Build.MANUFACTURER} ${Build.MODEL}")
                if (Build.VERSION.SDK_INT >= 31) put("soc", "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}")
                put("hardware", Build.HARDWARE)
                put("android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                put("abi", Build.SUPPORTED_ABIS.joinToString())
                put("cores", Runtime.getRuntime().availableProcessors())
                put("cpuMaxMhz", cpuMaxFreqs())
                put("gpu", runCatching { glRenderer() }.getOrElse { "?" })
                put("ramMb", totalMb)
                put("lowRamDevice", am?.isLowRamDevice ?: false)
                put("memoryClassMb", am?.memoryClass ?: 0)
                put("largeMemoryClassMb", am?.largeMemoryClass ?: 0)
            }
            putJsonObject("display") {
                put("window", windowSize)
                display?.let { d ->
                    put("mode", "${d.mode.physicalWidth}x${d.mode.physicalHeight}@${PerfAnalysis.fmt1(d.mode.refreshRate.toDouble())}Hz")
                    put("supported", d.supportedModes.joinToString { "${it.physicalWidth}x${it.physicalHeight}@${PerfAnalysis.fmt1(it.refreshRate.toDouble())}" })
                }
                put("density", context.resources.displayMetrics.densityDpi)
            }
            putJsonObject("app") {
                put("compiled", compiled.text)
                put("odexMb", compiled.odexMb)
            }
            putJsonObject("memory") {
                put("availMb", availMb)
                put("totalMb", totalMb)
                put("thresholdMb", mem.threshold / MB)
                put("lowMemory", mem.lowMemory)
                put("javaUsedMb", javaUsedMb)
                put("javaMaxMb", javaMaxMb)
                put("nativeMb", Debug.getNativeHeapAllocatedSize() / MB)
                processMemory()
            }
            putJsonObject("storage") {
                put("freeMb", freeMb)
                put("totalMb", storage?.let { it.totalBytes / MB })
            }
            put("thermalStatus", thermal)
            putJsonArray("exits") {
                for (e in exits.take(10)) addJsonObject {
                    put("time", time(e.timestamp))
                    put("reason", reasonText(e.reason))
                    put("state", importanceText(e.importance))
                    put("process", e.processName)
                    put("pssMb", e.pssKb / 1024)
                    put("description", e.description)
                }
            }
            putJsonObject("readable") {
                put("threadStats", File("/proc/self/task").list()?.isNotEmpty() == true)
                put("cpuFreq", readSmall("/sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq") != null)
                put("memoryPressure", readSmall("/proc/pressure/memory") != null)
            }
        }
    }

    private fun JsonObjectBuilder.processMemory() {
        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)
        fun stat(name: String) = (info.getMemoryStat(name)?.toLongOrNull() ?: 0L) / 1024
        putJsonObject("processMb") {
            put("pss", stat("summary.total-pss"))
            put("java", stat("summary.java-heap"))
            put("native", stat("summary.native-heap"))
            put("graphics", stat("summary.graphics"))
            put("code", stat("summary.code"))
            put("other", stat("summary.private-other"))
        }
    }

    private class Compiled(val level: Int, val text: String, val odexMb: Long)

    /**
     * 有没有编译成机器码: 看进程映射里本应用的 .odex 有多大. 没有 / 很小 = 只校验过 (解释 + JIT, 慢几倍),
     * 中等 = 按使用记录编译了常用部分 (speed-profile), 很大 = 全部编译 (speed). 系统没给查询接口, 这是近似.
     */
    private fun compileState(context: Context): Compiled {
        val appDir = File(context.applicationInfo.sourceDir).parent ?: return Compiled(-1, "?", 0)
        val odex = runCatching {
            File("/proc/self/maps").useLines { lines ->
                lines.map { it.substringAfterLast(' ') }
                    .firstOrNull { it.startsWith(appDir) && it.endsWith(".odex") }
            }
        }.getOrNull()
        val mb = odex?.let { File(it).length() / MB } ?: 0
        return when {
            odex == null || mb < 2 -> Compiled(0, tr("未编译（只校验）"), mb)
            mb < 30 -> Compiled(1, tr("部分编译（按使用记录）"), mb)
            else -> Compiled(2, tr("已全部编译"), mb)
        }
    }

    /** 进程退出记录 (系统从 Android 11 起才给, 更早的电视上为空). */
    private class ExitRecord(
        val timestamp: Long,
        val reason: Int,
        val importance: Int,
        val processName: String,
        val pssKb: Long,
        val description: String?,
    )

    private fun exitReasons(am: ActivityManager?, packageName: String): List<ExitRecord> {
        if (am == null || Build.VERSION.SDK_INT < 30) return emptyList()
        return runCatching { exitReasons30(am, packageName) }.getOrDefault(emptyList())
    }

    @RequiresApi(30)
    private fun exitReasons30(am: ActivityManager, packageName: String): List<ExitRecord> =
        am.getHistoricalProcessExitReasons(packageName, 0, 20).map {
            ExitRecord(it.timestamp, it.reason, it.importance, it.processName, it.pss, it.description)
        }

    private fun reasonText(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_LOW_MEMORY -> tr("内存不足被系统关掉")
        ApplicationExitInfo.REASON_ANR -> tr("应用无响应（ANR）")
        ApplicationExitInfo.REASON_CRASH -> tr("崩溃")
        ApplicationExitInfo.REASON_CRASH_NATIVE -> tr("原生崩溃")
        ApplicationExitInfo.REASON_USER_REQUESTED -> tr("被强行停止（安装更新、清除数据或手动停止）")
        ApplicationExitInfo.REASON_EXIT_SELF -> tr("应用自己退出")
        ApplicationExitInfo.REASON_SIGNALED -> tr("被系统终止")
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> tr("占用资源过多被系统关掉")
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> tr("依赖的进程退出")
        ApplicationExitInfo.REASON_USER_STOPPED -> tr("被用户停止")
        ApplicationExitInfo.REASON_UNKNOWN -> tr("原因未知")
        ApplicationExitInfo.REASON_OTHER -> tr("其它")
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> tr("启动失败")
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> tr("权限变更")
        // 下面三个是 Android 13 加的, 按值写 (编译用的常量在旧系统上也只是数字)
        REASON_FREEZER -> tr("被系统冻结后关掉")
        REASON_PACKAGE_STATE_CHANGE -> tr("应用被停用或状态变化")
        REASON_PACKAGE_UPDATED -> tr("应用更新")
        else -> tr("其它（{0}）", reason)
    }

    private fun importanceText(importance: Int): String = when {
        importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> tr("在前台")
        importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE -> tr("可见")
        importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE -> tr("在后台运行服务")
        else -> tr("在后台")
    }

    private fun time(millis: Long): String = SimpleDateFormat("MM-dd HH:mm", Locale.ROOT).format(Date(millis))

    private fun cpuMaxFreqs(): String {
        val cores = Runtime.getRuntime().availableProcessors()
        val mhz = (0 until cores).map { i ->
            readSmall("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq")?.toLongOrNull()?.div(1000) ?: 0L
        }
        // 模拟器与部分电视读出来是 0
        return if (mhz.all { it <= 0 }) "?" else mhz.joinToString(",")
    }

    @Suppress("DEPRECATION")
    private fun currentDisplay(activity: Activity): Display? =
        if (Build.VERSION.SDK_INT >= 30) activity.display else activity.windowManager.defaultDisplay

    /** 临时建一个 1x1 的 EGL 上下文读 GPU 名字, 读完就拆 (不能 eglTerminate: 默认 display 是整个进程共用的). */
    private fun glRenderer(): String {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) return "?"
        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) return "?"
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        EGL14.eglChooseConfig(
            display,
            intArrayOf(EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE),
            0, configs, 0, 1, num, 0,
        )
        val config = configs[0] ?: return "?"
        val context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        val surface = EGL14.eglCreatePbufferSurface(display, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        try {
            if (!EGL14.eglMakeCurrent(display, surface, surface, context)) return "?"
            return "${GLES20.glGetString(GLES20.GL_RENDERER)} (${GLES20.glGetString(GLES20.GL_VERSION)})"
        } finally {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, surface)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglReleaseThread()
        }
    }

    private fun readSmall(path: String): String? = runCatching { File(path).readText().trim() }.getOrNull()
}
