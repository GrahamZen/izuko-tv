/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

@file:OptIn(ExperimentalKotlinGradlePluginApi::class)

import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSetTree

plugins {
    // 全仓通用约定 (opt-ins / jvm target / 测试源集配置). 上游把根脚本的 allprojects/subprojects
    // 解散成了这个约定插件, 不显式应用就会**静默**丢掉那些配置; 顺带它还把 build-logic 带上
    // 本脚本的 classpath, 下面的 getIntProperty 才解析得到 (included build 不像 buildSrc 那样泄漏 classpath).
    id("ani.base")
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.plugin.compose)
    alias(libs.plugins.jetbrains.compose)
}

apply(plugin = "de.mannodermaus.android-junit5")

/*
 * 电视版界面 (遥控器与平板触屏两种输入), Android TV 包与 iOS 共用.
 *
 * 目标只有 android 与 iOS (打开 `ani.enable.ios` 时), 没有 desktop, 因此不套用 `ani.kmp-compose`
 * (那个约定会配出 desktop). 页面的「大脑」(数据、落点、返回分层、菜单) 与 Compose 画面在 commonMain;
 * 只有安卓才有的东西在 androidMain: 原生 View 画面 (leanback 卡片墙、横滑行), Web 控制台, 播放器, 性能诊断等.
 * 两边分开的地方用 expect/actual 接 (安卓给原生 View, iOS 给 Compose 版).
 *
 * android target 的那部分配置 (compileSdk / 测试源集) 只能在这里重复一遍:
 * `ani.base` 的测试配置对所有含 android target 的模块都要求 androidHostTest 与
 * androidDeviceTest 存在.
 *
 * 依赖方向: ui-tv -> 各共享界面模块, 共享模块不得反向依赖本模块: 电视版页面只能由应用入口
 * (Android 的 tv 变体、iOS 入口) 装配, 控件级差异只能靠 AniUiBehavior 这类数据开关表达.
 */
kotlin {
    if (enableIos) {
        iosArm64()
        iosSimulatorArm64()
    }
    android {
        namespace = "me.him188.ani.app.tv"
        compileSdk = getIntProperty("android.compile.sdk")
        minSdk = androidMinSdk
        androidResources.enable = true

        // 同 ani.kmp-library: :utils:build-config 按分发渠道分了 default / tv 两个变体, 库模块一律取 default
        localDependencySelection {
            productFlavorDimension(AniAndroidFlavors.DIMENSION) {
                selectFrom.set(listOf(AniAndroidFlavors.DEFAULT))
            }
        }

        withHostTestBuilder {
            sourceSetTreeName = KotlinSourceSetTree.test.name
        }

        withDeviceTestBuilder {
            sourceSetTreeName = KotlinSourceSetTree.test.name
        }.configure {
            targetSdk {
                release(androidMinSdk)
            }
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            instrumentationRunnerArguments["runnerBuilder"] = "de.mannodermaus.junit5.AndroidJUnit5Builder"
            instrumentationRunnerArguments["package"] = "me.him188"
            execution = "HOST"
        }

        packaging {
            resources {
                pickFirsts.add("META-INF/LICENSE.md")
                pickFirsts.add("META-INF/LICENSE-notice.md")
            }
        }
    }

    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    sourceSets.commonMain.dependencies {
        implementation(projects.utils.platform)
        implementation(libs.kotlinx.serialization.json) // Web 控制台的 JSON 接口 (ui/remote)
        // app:shared 是顶层界面聚合模块 (播放页 ViewModel / 主页导航), TV 页面直接复用它的状态层.
        // 方向是 ui-tv -> app:shared, 不得反向.
        api(projects.app.shared)
        api(projects.app.shared.uiFoundation)
        api(projects.app.shared.uiSubject)
        api(projects.app.shared.uiExploration)
        api(projects.app.shared.uiMediaselect)
        api(projects.app.shared.uiSettings)
        api(projects.app.shared.uiSettingsCatalog) // 设置页的清单 (多平台模块), 本模块只画原生三栏
        api(projects.app.shared.uiDownload)
        api(projects.app.shared.uiComment)
        api(projects.app.shared.appData)
        api(projects.app.shared.videoPlayer)
        api(projects.danmaku.danmakuUi)
        implementation(libs.koin.core)
        implementation(libs.atomicfu)
    }
    sourceSets.androidMain.dependencies {
        // 进度条缩略图取帧 (见 TvFramePreviewSource): media3 的 ExperimentalFrameExtractor,
        // 用来替代 mediamp 那份打不开 HLS 的 MediaMetadataRetriever 实现
        implementation(libs.androidx.media3.transformer)
        implementation(libs.androidx.media3.exoplayer)
        // 原生页面 (View) 的行 / 列, 见 ui/foundation/tv/nativeview
        implementation(libs.androidx.leanback.grid)
    }
    sourceSets.commonTest.dependencies {
        implementation(projects.utils.testing)
    }
}
