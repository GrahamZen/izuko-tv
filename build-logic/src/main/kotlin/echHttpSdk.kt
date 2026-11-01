/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.ArchiveOperations
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.withType
import org.gradle.work.DisableCachingByDefault
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import java.net.URI
import java.security.MessageDigest
import javax.inject.Inject

/**
 * iOS 加密直连 (ECH) 用的 libcurl + BoringSSL + zlib 静态库: ech-research/libechhttp-darwin-build 的预编译包 (MIT, Kazumi 的 ech_http 也用它),
 * 按版本与 SHA-256 钉死. 系统的 TLS (URLSession / Network.framework) 不能给别的域名的 ECH 配置, 所以自带一份.
 */
private object EchHttpSdk {
    const val VERSION = "v0.1.2"

    /** Kotlin 目标名 → 预编译包的目标与 SHA-256 (取自 release 附件的 digest). */
    val targets = mapOf(
        "iosArm64" to ("ios-arm64-device" to "3308c1e54cab207ed0c2af06ba7b1c28e82b41ebbe8234d16692bd35a4827d56"),
        "iosSimulatorArm64" to ("ios-arm64-simulator" to "dbf60eb43270d40d4ac5c34f83d76faf7ec137ff8ec870c98aaa065cc0dc7d4d"),
    )

    fun url(target: String) =
        "https://github.com/ech-research/libechhttp-darwin-build/releases/download/$VERSION/libechhttp-deps-$VERSION-$target.zip"
}

/**
 * 给 iOS 目标的 main 编译接上 `src/nativeInterop/cinterop/echhttp.def`: 先下载并校验预编译包, 头文件与静态库从解压目录取,
 * 静态库打进 klib, 链接 framework 与测试可执行文件时一起链上.
 */
fun Project.configureEchHttpInterop() {
    if (!enableIos) return

    configure<KotlinMultiplatformExtension> {
        targets.withType<KotlinNativeTarget>().configureEach {
            val (sdkTarget, sha256) = EchHttpSdk.targets[name] ?: return@configureEach
            val sdkDirectory = layout.buildDirectory.dir("echhttp-sdk/$sdkTarget")
            val download = tasks.register<DownloadEchHttpSdkTask>("downloadEchHttpSdk${name.replaceFirstChar { it.uppercase() }}") {
                url.set(EchHttpSdk.url(sdkTarget))
                this.sha256.set(sha256)
                outputDirectory.set(sdkDirectory)
            }
            compilations.getByName("main").cinterops.create("echhttp") {
                definitionFile.set(file("src/nativeInterop/cinterop/echhttp.def"))
                includeDirs(sdkDirectory.map { it.dir("include") })
                extraOpts("-libraryPath", sdkDirectory.get().asFile.resolve("lib").absolutePath)
                tasks.named(interopProcessingTaskName) { dependsOn(download) }
            }
        }
    }
}

@DisableCachingByDefault(because = "Downloads a pinned archive; the extracted directory is the cache")
abstract class DownloadEchHttpSdkTask : DefaultTask() {
    @get:Input
    abstract val url: Property<String>

    /** 压缩包的 SHA-256 (小写十六进制). 对不上就失败, 不解压. */
    @get:Input
    abstract val sha256: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @get:Inject
    abstract val archiveOperations: ArchiveOperations

    @get:Inject
    abstract val fileSystemOperations: FileSystemOperations

    @TaskAction
    fun download() {
        val archive = temporaryDir.resolve("sdk.zip")
        URI(url.get()).toURL().openStream().use { input -> archive.outputStream().use { input.copyTo(it) } }
        val actual = MessageDigest.getInstance("SHA-256").digest(archive.readBytes()).joinToString("") { "%02x".format(it) }
        check(actual == sha256.get()) { "SHA-256 mismatch for ${url.get()}: expected ${sha256.get()}, got $actual" }
        fileSystemOperations.sync {
            from(archiveOperations.zipTree(archive))
            into(outputDirectory)
            includeEmptyDirs = false
        }
        archive.delete()
    }
}
