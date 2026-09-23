/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

import com.google.gms.googleservices.GoogleServicesPlugin.MissingGoogleServicesStrategy

plugins {
    id("ani.android-application")
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.kotlin.plugin.compose)
    alias(libs.plugins.kotlinx.atomicfu)
    alias(libs.plugins.google.gms.google.services)
    idea
}

val archs = getPropertyOrNull("ani.android.abis")
    ?.split(',')
    ?.map { it.trim() }
    ?.filter { it.isNotEmpty() }
    ?.takeIf { it.isNotEmpty() }
    ?.let { abis ->
        if (abis.size == 1 && abis.first() == "all") {
            listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        } else {
            abis
        }
    }
    ?: listOf("arm64-v8a")

android {
    namespace = "me.him188.ani.android"
    compileSdk = getIntProperty("android.compile.sdk")
    defaultConfig {
        // 加后缀就能出一个**与正式包共存**的包 (`-Pani.android.appIdSuffix=perfbase`):
        // A/B 对比时两个包各装各的、各 AOT 一次, 不用来回覆盖安装 (每轮省五到八分钟);
        // 数据也各自独立, 想要"全新安装"的冷启动场景直接 pm clear 那个包, 不碰正式包的登录与设置.
        // 默认空 = 正式包不受影响.
        // **分发包名与 Kotlin 的 namespace 是两回事**: namespace 仍是 me.him188.ani.android
        // (它只决定 R 类与类的全限定名, 用户看不到). 这里换掉的是装到设备上、应用商店认的那个标识.
        // 取的是中性词而不是产品名: 显示名 (app_name) 随时能改, applicationId 一旦发布就改不动了.
        // 迁移期的跳板包仍用旧 applicationId, 这样老用户那边才算"升级"而不是装新应用.
        // 见 app-platform/build.gradle.kts 的 migrationBridge.
        val bridge = (getPropertyOrNull("ani.android.migrationBridge") ?: "false").toBooleanStrict()
        applicationId = (if (bridge) "me.him188.ani" else "io.github.grahamzen.anime") +
                (getPropertyOrNull("ani.android.appIdSuffix") ?: "")
        // 迁移过程中新旧两个应用会同时装着, 桌面上同名的话用户分不清该留哪个、卸哪个
        manifestPlaceholders["appLabel"] = if (bridge) "@string/app_name_migration_bridge" else "@string/app_name"
        // 迁移时新旧两个包要互相看得见 (AndroidManifest 的 <queries>): 按同一个后缀拼出两边的电视包名,
        // 加了后缀的对比/测试包也能走通迁移
        val migrationAppIdSuffix = getPropertyOrNull("ani.android.appIdSuffix") ?: ""
        manifestPlaceholders["legacyTvPackage"] = "me.him188.ani$migrationAppIdSuffix.tv"
        manifestPlaceholders["currentTvPackage"] = "io.github.grahamzen.anime$migrationAppIdSuffix.tv"
        minSdk = androidMinSdk
        targetSdk = getIntProperty("android.compile.sdk")
        versionCode = getIntProperty("android.version.code")
        versionName = project.version.toString()
        ndk {
            // Specifies the ABI configurations of your native
            // libraries Gradle should build and package with your app.
            abiFilters.clear()
            //noinspection ChromeOsAbiSupport
            abiFilters += archs
        }
    }
    splits {
        abi {
            isEnable = true
            reset()
            //noinspection ChromeOsAbiSupport
            include(*archs.toTypedArray())
            isUniversalApk = true // 额外构建一个
        }
    }
    signingConfigs {
        kotlin.runCatching { getProperty("signing_release_storeFileFromRoot") }.getOrNull()?.let {
            create("release") {
                storeFile = rootProject.file(it)
                storePassword = getProperty("signing_release_storePassword")
                keyAlias = getProperty("signing_release_keyAlias")
                keyPassword = getProperty("signing_release_keyPassword")
            }
        }
        kotlin.runCatching { getProperty("signing_release_storeFile") }.getOrNull()?.let {
            create("release") {
                storeFile = file(it)
                storePassword = getProperty("signing_release_storePassword")
                keyAlias = getProperty("signing_release_keyAlias")
                keyPassword = getProperty("signing_release_keyPassword")
            }
        }
    }
    packaging {
        jniLibs {
            // FFmpeg is launched as a process, so the native binary must be extracted to a filesystem path.
            useLegacyPackaging = true
        }
        resources {
            merges.add("META-INF/DEPENDENCIES") // log4j
            pickFirsts.add("META-INF/LICENSE.md")
            pickFirsts.add("META-INF/LICENSE-notice.md")

        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            signingConfig = signingConfigs.findByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                *sharedAndroidProguardRules(),
            )
        }
        debug {
            applicationIdSuffix = getLocalProperty("ani.android.debug.applicationIdSuffix") ?: ".debug2"
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    flavorDimensions += "distribution"
    flavorDimensions += "formFactor"
    productFlavors {
        create("default") {
            dimension = "distribution"
        }
        /*
         * 形态维度: 手机/平板 与 Android TV 出两个独立 APK.
         *
         * TV 变体多出的东西全部按 flavor 隔离, phone 变体一行不受影响:
         * - src/izukoTv/AndroidManifest.xml: LEANBACK 启动器入口 / banner / 屏保服务 / EPG 权限
         * - src/izukoTv/kotlin: 形态接缝实现 + 主屏频道 + 屏保 (见 src/phone 下的同名接缝)
         * - tvImplementation: 只有 TV 变体打包遥控器界面与 tvprovider
         */
        create("phone") {
            dimension = "formFactor"
        }
        create("tv") {
            isDefault = true
            dimension = "formFactor"
            applicationIdSuffix = ".tv"
            versionNameSuffix = "-tv"
        }
    }
    // tv 形态的源码根目录是 src/izukoTv 而不是默认的 src/tv: 上游的 Android TV 客户端也有一个同名的 tv flavor,
    // 它的入口 (MainActivity / TvAniApplication) 放在 src/tv, 同名目录会被这里的 tv 变体一起编译
    sourceSets.named("tv") { setRoot("src/izukoTv") }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        // 只在兼容包上开: minSdk 低于 26 时系统里没有 java.time (kotlinx-datetime 等在用), 需要 D8 回填.
        // 这是模块级设置, 开了就是所有用户一起换成回填实现, 所以正式包坚决不开. 见 buildLegacyAndroidApp.
        isCoreLibraryDesugaringEnabled = buildLegacyAndroidApp
    }
}

dependencies {
    implementation(projects.app.shared)
    implementation(projects.app.shared.application)
    // TV (遥控器) 专属界面与主屏频道 API: 只进 tv 变体, phone 包里一个类都没有
    "tvImplementation"(projects.app.shared.uiTv)
    "tvImplementation"(libs.androidx.tvprovider)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.material)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.browser)

//    implementation(libs.log4j.core)
//    implementation(libs.log4j.slf4j.impl)

    implementation(libs.ktor.client.core)
    implementation(libs.mediamp.ffmpeg)

    // 必须与 isCoreLibraryDesugaringEnabled 同进同退: 只挂依赖不开开关, AGP 会直接报错.
    if (buildLegacyAndroidApp) {
        coreLibraryDesugaring(libs.android.desugar.jdk.libs)
    }
}

idea {
    module {
        excludeDirs.add(file(".cxx"))
    }
}

googleServices {
    missingGoogleServicesStrategy = (getLocalProperty("ani.enable.firebase") ?: "false").toBooleanStrict()
        .let {
            if (it) MissingGoogleServicesStrategy.ERROR else MissingGoogleServicesStrategy.IGNORE
        }
}
