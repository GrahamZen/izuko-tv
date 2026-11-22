/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.plugin.cocoapods.CocoapodsExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.TestExecutable

plugins {
    id("ani.kmp-compose")
    alias(libs.plugins.kotlin.plugin.serialization)

    // alias(libs.plugins.kotlinx.atomicfu)
    alias(libs.plugins.sentry.kotlin.multiplatform)
}

kotlin {
    android {
        namespace = "me.him188.ani.app.application"
    }
    sourceSets.commonMain.dependencies {
        api(projects.app.shared.appPlatform)
        api(projects.app.shared.uiFoundation)
        api(projects.app.shared)
        api(libs.kotlinx.coroutines.core)
        implementation(libs.atomicfu)
    }
    sourceSets.commonTest.dependencies {
        implementation(projects.utils.uiTesting)
    }
    sourceSets.androidMain.dependencies {
        // FileKit 的非 Compose 对话框 (图片查看器保存) 需要在 Activity 里初始化
        implementation(libs.filekit.dialogs)
    }
    sourceSets.iosMain.dependencies {
        implementation(libs.mediamp.ffmpeg)
        // 电视界面 (见 InstallTvTouchPageVariants)
        implementation(projects.app.shared.uiTv)
    }
}

kotlin {
    if (enableIos && buildIosFramework && getOs() == Os.MacOS) {
        // onnxruntime 的两个 pod 从本地目录装 (来源与核对见 onnxruntime-pods/README.md): ObjC 封装随仓库,
        // C 库由 fetchOnnxruntimeC 下载、核对后解到这里
        val onnxruntimeCDir = layout.buildDirectory.dir("onnxruntime-c").get().asFile
        val fetchOnnxruntimeC = tasks.register<Exec>("fetchOnnxruntimeC") {
            description = "Downloads, verifies and unpacks the onnxruntime-c pod (see onnxruntime-pods/README.md)"
            commandLine(
                "bash", layout.projectDirectory.file("onnxruntime-pods/fetch-onnxruntime-c.sh").asFile.absolutePath,
                onnxruntimeCDir.absolutePath,
                layout.buildDirectory.dir("onnxruntime-c-download").get().asFile.absolutePath,
            )
        }
        tasks.matching { it.name.startsWith("podInstallSynthetic") }.configureEach { dependsOn(fetchOnnxruntimeC) }

        // Sentry requires cocoapods for its dependencies
        extensions.configure<CocoapodsExtension> {
            // https://kotlinlang.org/docs/native-cocoapods.html#configure-existing-project
            framework {
                baseName = "application"
                isStatic = false
                @OptIn(ExperimentalKotlinGradlePluginApi::class)
                transitiveExport = false
                export(projects.app.shared.appPlatform)
            }
            pod("onnxruntime-objc") {
                source = path(layout.projectDirectory.dir("onnxruntime-pods/onnxruntime-objc").asFile)
            }
            pod("onnxruntime-c") {
                source = path(onnxruntimeCDir)
                linkOnly = true
            }
            // iOS Firebase SDKs are linked from the host Podfile
        }

        val httpDownloaderProject = project(":utils:http-downloader")
        listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
            val capitalizedTargetName = target.name.replaceFirstChar { it.uppercase() }
            val sliceName = when (target.name) {
                "iosArm64" -> "ios-arm64"
                "iosSimulatorArm64" -> "ios-arm64-simulator"
                else -> error("Unsupported Apple target: ${target.name}")
            }
            val frameworkSearchPath = httpDownloaderProject.layout.buildDirectory.dir(
                "mediamp-ffmpeg/apple-runtime/MediampFFmpegKit.xcframework/$sliceName",
            )
            val frameworkSearchPathValue = frameworkSearchPath.get().asFile.absolutePath

            val onnxSliceName = when (target.name) {
                "iosArm64" -> "ios-arm64"
                "iosSimulatorArm64" -> "ios-arm64_x86_64-simulator"
                else -> error("Unsupported Apple target: ${target.name}")
            }
            val onnxSearchPathValue = onnxruntimeCDir.resolve("onnxruntime.xcframework/$onnxSliceName").absolutePath

            target.binaries.configureEach {
                linkerOpts("-F$frameworkSearchPathValue", "-framework", "MediampFFmpegKit")
                // -lc++ 与 -weak_framework CoreML 对应 onnxruntime-c.podspec 的 libraries / weak_frameworks
                linkerOpts(
                    "-F$onnxSearchPathValue", "-framework", "onnxruntime",
                    "-lc++", "-weak_framework", "CoreML",
                )
            }
            // App 包内嵌 MediampFFmpegKit, 测试可执行文件没有 bundle, 需从解压目录加载这个动态 framework
            target.binaries.withType<TestExecutable>().configureEach {
                linkerOpts("-rpath", frameworkSearchPathValue)
            }

            tasks.matching { task ->
                task.name.startsWith("link") && task.name.endsWith(capitalizedTargetName)
            }.configureEach {
                dependsOn(":utils:http-downloader:extractMediampFfmpegAppleRuntime")
                // 上面的 -F 指向该任务产出的 Pods 目录
                dependsOn("podInstallSyntheticIos")
            }
        }
    }
}
