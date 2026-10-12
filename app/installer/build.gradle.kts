/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

/*
 * 安装助手: 与 Izuko TV 正式版同包名、同签名、versionCode 1 的小应用. 用户先装它, 它按设备挑对安装包
 * (系统版本决定正式包 / 7.1 兼容包, ABI 决定架构), 从 GitHub 或加速镜像下载、校验, 再用 PackageInstaller 装上 ——
 * 装上的正式版是同包名的升级, 直接把助手换掉.
 *
 * 只发布一次 (固定的 `installer` release, 见 scripts/publish-installer.sh): 最新版本号、下载镜像都是运行时现查的.
 * 不依赖仓库里的任何共享模块, 也不用 AndroidX / Compose: 包要小, minSdk 要低到所有电视都解析得了.
 */

plugins {
    id("ani.android-application")
}

android {
    namespace = "io.github.grahamzen.izuko.installer"
    compileSdk = getIntProperty("android.compile.sdk")
    defaultConfig {
        // 必须与正式版 (app/android 的 tv 变体) 完全相同: 装正式版时才是覆盖升级
        applicationId = "io.github.grahamzen.anime.tv"
        // 4.4: 低于 7.1 的设备装不了 Izuko, 但助手要能装上把原因说清楚 —— 不少运营商盒子与老电视还是 4.4,
        // minSdk 再高, 那些设备上助手自己就报「解析软件包时出现问题」. 4.4 以下的设备只剩极少数, 不再往下降
        minSdk = 19
        targetSdk = getIntProperty("android.compile.sdk")
        // 比任何正式版都小 (正式版是 android.version.code), 正式版装上来才算升级
        versionCode = 1
        versionName = "installer-1"
        // minSdk < 21 时 AGP 把矢量图 (横幅、图标) 按各密度渲染成 PNG; 电视 1080p 是 xhdpi, 只出这一档, 包小一半多
        vectorDrawables {
            generatedDensities?.clear()
            generatedDensities?.add("xhdpi")
        }
        buildConfigField("String", "REPOSITORY", "\"${getProperty("ani.repository")}\"")
        buildConfigField("String", "ASSET_PREFIX", "\"${getProperty("ani.update.asset.prefix")}\"")
        // 正式包与 7.1 兼容包的 minSdk: 设备低于前者装兼容包, 低于后者哪个都装不了
        buildConfigField("int", "NORMAL_MIN_SDK", getProperty("android.min.sdk"))
        buildConfigField("int", "LEGACY_MIN_SDK", LEGACY_ANDROID_MIN_SDK.toString())
    }
    signingConfigs {
        // 与 app/android 同一套属性: 签名必须与正式版相同, 否则正式版装不上来
        kotlin.runCatching { getProperty("signing_release_storeFileFromRoot") }.getOrNull()?.let {
            create("release") {
                storeFile = rootProject.file(it)
                storePassword = getProperty("signing_release_storePassword")
                keyAlias = getProperty("signing_release_keyAlias")
                keyPassword = getProperty("signing_release_keyPassword")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.findByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
        debug {
            // 调试包换包名, 免得与设备上的正式版冲突
            applicationIdSuffix = ".installer"
        }
    }
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    // 全仓的 JVM 测试都跑在 JUnit 5 上 (ani.base 给测试任务设了 useJUnitPlatform)
    testImplementation(kotlin("test-junit5"))
    testImplementation(libs.junit5.jupiter.api)
    testRuntimeOnly(libs.junit5.jupiter.engine)
}
