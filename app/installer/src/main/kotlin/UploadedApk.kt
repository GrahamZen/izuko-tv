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
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * 手机传来的文件能不能交给系统装: 是安装包、架构与系统版本这台电视装得了、包名是 Izuko TV、签名与正式发布的相同.
 * 局域网里谁都连得上助手的小服务 (地址里的随机令牌挡住没扫到码的人), 不认的包不交给系统安装界面;
 * 架构、版本不对的在这里说清楚, 比系统安装界面报「解析错误」好懂.
 */
object UploadedApk {
    sealed interface Result {
        data class Ok(val version: String) : Result

        /** @property message 给人看的原因 (电视的语言), 电视与手机网页上都显示 */
        data class Rejected(val message: String) : Result
    }

    fun check(context: Context, file: File): Result {
        if (!hasApkMagic(file)) return Result.Rejected(context.getString(R.string.upload_reject_not_apk))
        val abis = nativeAbis(file) ?: return Result.Rejected(context.getString(R.string.upload_reject_unparsable))
        val device = deviceAbis()
        if (abis.isNotEmpty() && device.none { it in abis }) {
            return Result.Rejected(context.getString(R.string.upload_reject_abi, abis.joinToString(", "), device.joinToString(", ")))
        }
        val info = runCatching { context.packageManager.getPackageArchiveInfo(file.path, SIGNATURE_FLAGS) }.getOrNull()
            ?: return Result.Rejected(context.getString(R.string.upload_reject_unparsable))
        if (info.packageName != BuildConfig.TARGET_PACKAGE) {
            return Result.Rejected(context.getString(R.string.upload_reject_package, info.packageName))
        }
        if (Build.VERSION.SDK_INT >= 24) {
            val minSdk = info.applicationInfo?.minSdkVersion ?: 0
            if (minSdk > Build.VERSION.SDK_INT) {
                return Result.Rejected(
                    context.getString(
                        R.string.upload_reject_too_new,
                        AndroidVersions.name(minSdk, ""),
                        AndroidVersions.name(Build.VERSION.SDK_INT, Build.VERSION.RELEASE),
                    ),
                )
            }
        }
        // 有的系统读不出未安装包的签名: 读不出就交给系统 (同包名覆盖安装时系统自己核签名)
        val certificates = certificateDigests(info)
        if (certificates.isNotEmpty() && BuildConfig.RELEASE_CERT_SHA256 !in certificates) {
            return Result.Rejected(context.getString(R.string.upload_reject_signature))
        }
        // 电视变体的版本号带 -tv (如 1.0.7-tv), 界面上与下载的写法 (1.0.7) 一致
        return Result.Ok(info.versionName.orEmpty().removeSuffix("-tv"))
    }

    private fun hasApkMagic(file: File): Boolean = runCatching {
        val head = ByteArray(4)
        RandomAccessFile(file, "r").use { it.read(head) } == head.size &&
            head.contentEquals(byteArrayOf(0x50, 0x4B, 0x03, 0x04))
    }.getOrDefault(false)

    /** 包里带原生库的架构 (`lib/<abi>/`); 没有原生库为空; 不是完整的 zip 为 `null`. */
    private fun nativeAbis(file: File): Set<String>? = runCatching {
        ZipFile(file).use { zip ->
            zip.entries().asSequence()
                .map { it.name }
                .filter { it.startsWith("lib/") && it.count { c -> c == '/' } >= 2 }
                .map { it.split('/')[1] }
                .toSet()
        }
    }.getOrNull()

    /** 9、10 上未安装的包只在带 GET_SIGNATURES 时取证书 (signingInfo 是空的), 两个都带. */
    @Suppress("DEPRECATION")
    private val SIGNATURE_FLAGS =
        if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES else PackageManager.GET_SIGNATURES

    @Suppress("DEPRECATION")
    private fun certificateDigests(info: PackageInfo): Set<String> {
        val signatures = (if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else null)
            ?.takeIf { it.isNotEmpty() }
            ?: info.signatures
            ?: return emptySet()
        return signatures.map { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }
        }.toSet()
    }
}
