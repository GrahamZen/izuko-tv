/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package io.github.grahamzen.izuko.installer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.StatFs
import android.provider.Settings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 设备支持的 ABI, 首选在前. 5.0 以前没有 SUPPORTED_ABIS, 读老的两个字段. */
fun deviceAbis(): List<String> =
    if (Build.VERSION.SDK_INT >= 21) {
        Build.SUPPORTED_ABIS.toList()
    } else {
        @Suppress("DEPRECATION")
        listOf(Build.CPU_ABI, Build.CPU_ABI2).filter { !it.isNullOrBlank() && it != "unknown" }
    }

/**
 * 失败时显示的诊断信息: 判断「为什么装不上」要的全部设备信息, 让用户拍下这一屏就能报障 (电视上截图、导日志都难).
 * 每行一对 (名称, 值), 名称是字符串资源.
 */
object DeviceReport {
    fun lines(context: Context): List<Pair<Int, String>> {
        val lines = mutableListOf<Pair<Int, String>>()
        lines += R.string.report_installer to BuildConfig.VERSION_NAME
        lines += R.string.report_android to android(context)
        lines += R.string.report_device to listOf(Build.MANUFACTURER, Build.BRAND, Build.MODEL).joinToString(" / ")
        lines += R.string.report_codename to listOf(Build.DEVICE, Build.PRODUCT, Build.HARDWARE).joinToString(" / ")
        lines += R.string.report_build to Build.DISPLAY.orEmpty().take(MAX_VALUE_LENGTH)
        lines += R.string.report_abis to abiSummary(context)
        lines += R.string.report_storage to storage(context)
        lines += R.string.report_unknown_sources to unknownSources(context)
        lines += R.string.report_system_installer to systemInstaller(context)
        lines += R.string.report_installed_by to installedBy(context)
        InstallFlow.suggestedPackage()?.let { lines += R.string.report_package to it }
        if (InstallFlow.uploadedFromPhone) {
            lines += R.string.report_source to context.getString(R.string.report_source_phone)
        } else {
            InstallFlow.lastSourceHost?.let { lines += R.string.report_source to it }
        }
        InstallAttempts.previous(context)?.let { lines += R.string.report_last_attempt to InstallAttempts.describe(context, it) }
        lines += R.string.report_time to SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(Date())
        return lines
    }

    /** 按 API 级别写版本号; 系统自报的对不上 (运营商盒子常把它改高) 就一并写出来, 排查时一眼看得出. */
    private fun android(context: Context): String {
        val sdk = Build.VERSION.SDK_INT
        val release = Build.VERSION.RELEASE
        val name = AndroidVersions.name(sdk, release)
        return if (release.isBlank() || AndroidVersions.releaseMatches(sdk, release)) {
            "Android $name (API $sdk)"
        } else {
            context.getString(R.string.report_android_claimed, name, sdk, release)
        }
    }

    private fun abiSummary(context: Context): String {
        val abis = deviceAbis().joinToString(", ")
        // 64 位芯片配 32 位系统的电视很多 (装 arm64 的包必失败), 只看这个列表才准
        val bits = if (Build.VERSION.SDK_INT >= 21 && Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()) 64 else 32
        return context.getString(R.string.report_abis_value, abis, bits)
    }

    private fun storage(context: Context): String = runCatching {
        val stat = StatFs(context.filesDir.path)
        context.getString(R.string.report_storage_value, megabytes(stat.availableBytes), megabytes(stat.totalBytes))
    }.getOrElse { it.describe() }

    /** 助手现在能不能装应用 (「安装未知应用」的授权); 读不到时为 `null`. */
    fun installAllowed(context: Context): Boolean? = runCatching { readInstallAllowed(context) }.getOrNull()

    private fun readInstallAllowed(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 26) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            // 8.0 以前是全局开关
            @Suppress("DEPRECATION")
            Settings.Secure.getInt(context.contentResolver, Settings.Secure.INSTALL_NON_MARKET_APPS, 0) == 1
        }

    private fun unknownSources(context: Context): String = runCatching {
        context.getString(if (readInstallAllowed(context)) R.string.report_allowed else R.string.report_not_allowed)
    }.getOrElse { it.describe() }

    /** 打开安装包时系统交给谁: 厂商改过的安装器 (小米、TCL 等) 一眼能看出来. */
    private fun systemInstaller(context: Context): String = runCatching {
        // 7.0 以前的安装器只认 file://, 之后的只认 content://
        val info = listOf("content://x/izuko.apk", "file:///x/izuko.apk").firstNotNullOfOrNull { uri ->
            val intent = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(uri), ApkProvider.MIME_TYPE)
            context.packageManager.resolveActivity(intent, 0)?.activityInfo
        } ?: return context.getString(R.string.report_none)
        "${info.packageName} ${packageVersion(context, info.packageName)}".trim()
    }.getOrElse { it.describe() }

    /** 助手是谁装上的 (文件管理器、应用市场、ADB …), 没记录时为空. */
    private fun installedBy(context: Context): String = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getInstallerPackageName(context.packageName)
            ?: context.getString(R.string.report_none)
    }.getOrElse { it.describe() }

    private fun packageVersion(context: Context, packageName: String): String = runCatching {
        context.packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
    }.getOrDefault("")

    private fun megabytes(bytes: Long) = "${bytes / 1024 / 1024} MB"

    private const val MAX_VALUE_LENGTH = 60
}
