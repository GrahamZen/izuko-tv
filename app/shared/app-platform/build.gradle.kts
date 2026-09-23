/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

import com.android.build.gradle.ProguardFiles.getDefaultProguardFile

plugins {
    id("ani.kmp-compose")
    alias(libs.plugins.kotlin.plugin.serialization)

    // alias(libs.plugins.kotlinx.atomicfu)
    idea
    id("ani.build-config")
}

val dandanplayAppId = getPropertyOrNull("ani.dandanplay.app.id") ?: ""
val dandanplayAppSecret = getPropertyOrNull("ani.dandanplay.app.secret") ?: ""
val tmdbApiToken = getPropertyOrNull("ani.tmdb.api.token") ?: ""

// 直连 bangumi 的 OAuth 应用 (fork 自己注册的). 回调地址 `ani://bangumi-oauth-callback`.
// 没配置时为空串 —— 登录页会直接告诉用户"这个构建没带凭据", 而不是跳到一个必然报错的授权页.
val bangumiOauthClientId = getPropertyOrNull("ani.bangumi.oauth.client.id") ?: ""
val bangumiOauthClientSecret = getPropertyOrNull("ani.bangumi.oauth.client.secret") ?: ""
val sentryDsn = getPropertyOrNull("ani.sentry.dsn") ?: ""
val analyticsKey = getPropertyOrNull("ani.analytics.key") ?: ""
val distroChannel = getPropertyOrNull("ani.distro.channel") ?: "default"

// 本项目的 GitHub 仓库 (见 gradle.properties). 应用里的项目链接、UA 与镜像清单都按它拼地址.
val projectRepository = getProperty("ani.repository")
// 更新时只装 release 里这个前缀的 APK (见 gradle.properties). 跳板包也认它: 它的"更新"就是装落地版.
val updateAssetPrefix = getProperty("ani.update.asset.prefix")

// 迁移分支: `-Pani.android.migrationBridge=true` 出**跳板包** (旧 applicationId, 老用户像平常一样升级到它,
// 由它引导安装落地版); 不带这个参数出的是**落地版** (新包名, 首次启动接管旧包的数据, 然后自己更新到最新版).
// 两个包都发布在 ani.migration.repository 里 (见 gradle.properties).
val migrationBridge = (getPropertyOrNull("ani.android.migrationBridge") ?: "false").toBooleanStrict()
val migrationRepository = getPropertyOrNull("ani.migration.repository") ?: projectRepository
val migrationLandingVersion = getProperty("ani.migration.landing.version")

// 检查更新、下载安装包的仓库: 跳板包去放落地版的仓库, 落地版去本项目的仓库找最新版.
// 发版前真机走一遍时用 -P 或 local.properties 的 ani.update.repository 指到测试仓库.
val updateRepository = getPropertyOrNull("ani.update.repository")
    ?: if (migrationBridge) migrationRepository else projectRepository

kotlin {
    android {
        namespace = "me.him188.ani.app.platform"
        // TODO AGP Migration: Test package optimization
        optimization {
            minify = false
            keepRules.apply {
                files(
                    getDefaultProguardFile("proguard-android-optimize.txt", layout.buildDirectory),
                    *sharedAndroidProguardRules(),
                )
            }
        }
    }

    sourceSets.commonMain.dependencies {
        api(projects.utils.platform)
        api(projects.app.shared.appLang)
        api(libs.kotlinx.coroutines.core)
        api(projects.danmaku.danmakuApi)
        api(libs.kotlinx.collections.immutable)

        api(libs.compose.lifecycle.viewmodel.compose)
        api(libs.compose.lifecycle.runtime.compose)
        api(libs.compose.navigation.compose)
        api(libs.compose.navigation.runtime)
        api(libs.compose.navigation3.runtime)
        api(libs.kotlinx.serialization.json)
        api(libs.compose.material3.adaptive.core)
        api(libs.compose.material3.adaptive.layout)
        api(libs.compose.material3.adaptive.navigation0)

        api(libs.koin.core)
        api(projects.utils.analytics)
    }
    sourceSets.commonTest.dependencies {
        implementation(projects.utils.uiTesting)
        implementation(libs.turbine)
    }
    sourceSets.androidMain.dependencies {
        api(projects.utils.buildConfig)
    }
    sourceSets.desktopMain.dependencies {
        api(libs.jna)
        api(libs.jna.platform)
    }
    sourceSets.iosMain.dependencies {
        // Workaround for CMP bug since 1.8.0. Removing this will cause IDE sync failure and may break ios build.
        api("androidx.performance:performance-annotation:1.0.0-alpha01")
    }
}

//if (bangumiClientDesktopAppId == null || bangumiClientDesktopSecret == null) {
//    logger.warn("bangumi.oauth.client.desktop.appId or bangumi.oauth.client.desktop.secret is not set. Bangumi authorization will not work. Get a token from https://bgm.tv/dev/app and set them in local.properties.")
//}

/// BUILD CONFIG

buildConfig {
    packageName.set("me.him188.ani.app.platform")
    className.set("AniBuildConfig")
    outputDir.set(layout.buildDirectory.dir("generated/buildconfig"))

    // Desktop platform configuration
    fun BuildConfigPlatform.firebaseFields() {
        fun getProp(name: String): String {
            return if (enableFirebase) {
                getProperty(name).also {
                    check(it.isNotBlank()) { "Local property '$name' is not set. You must either set it or disable `ani.enable.firebase`." }
                }
            } else {
                ""
            }
        }
        stringField("firebaseGAAppId", getProp("firebase.ga.app.id"), isOverride = false)
//        stringField("firebaseApiKey", getProp("firebase.api.key"), isOverride = false)
        //            stringField("firebaseStorageBucket", getProperty("firebase.storage.bucket"), isOverride = false)
        //            stringField("firebaseProjectId", getProperty("firebase.project.id"), isOverride = false)
        //            stringField("firebaseGATrackingId", getProperty("firebase.ga.tracking.id"), isOverride = false)
        //            
        //            stringField("firebaseGAMeasurementId", getProperty("firebase.ga.measurement.id"), isOverride = false)
        stringField("firebaseGAApiSecret", getProp("firebase.ga.api.secret"), isOverride = false)

        booleanField("analyticsEnabled", enableFirebase)
    }

    platform("desktop") {
        stringField("versionName", project.version.toString())
        expressionField(
            "isDebug",
            "System.getenv(\"ANI_DEBUG\") == \"true\" || System.getProperty(\"ani.debug\") == \"true\"",
        )
        stringField("dandanplayAppId", dandanplayAppId)
        stringField("dandanplayAppSecret", dandanplayAppSecret)
        stringField("tmdbApiToken", tmdbApiToken)
        stringField("bangumiOauthClientId", bangumiOauthClientId)
        stringField("bangumiOauthClientSecret", bangumiOauthClientSecret)
        stringField("sentryDsn", sentryDsn)
        stringField("distroChannel", distroChannel)
        stringField("projectRepository", projectRepository)
        stringField("updateAssetPrefix", updateAssetPrefix)
        stringField("updateRepository", updateRepository)
        booleanField("isMigrationBridge", migrationBridge)
        booleanField("isMigrationLanding", !migrationBridge)
        stringField("migrationLandingVersion", migrationLandingVersion)

        firebaseFields()
    }

    // Android platform configuration
    platform("android") {
        stringField("versionName", project.version.toString())
        expressionField("isDebug", "me.him188.ani.buildconfig.AndroidBuildConfig.DEBUG")
        stringField("dandanplayAppId", dandanplayAppId)
        stringField("dandanplayAppSecret", dandanplayAppSecret)
        stringField("tmdbApiToken", tmdbApiToken)
        stringField("bangumiOauthClientId", bangumiOauthClientId)
        stringField("bangumiOauthClientSecret", bangumiOauthClientSecret)
        stringField("sentryDsn", sentryDsn)
        stringField("distroChannel", distroChannel)
        stringField("projectRepository", projectRepository)
        stringField("updateAssetPrefix", updateAssetPrefix)
        stringField("updateRepository", updateRepository)
        booleanField("isMigrationBridge", migrationBridge)
        booleanField("isMigrationLanding", !migrationBridge)
        stringField("migrationLandingVersion", migrationLandingVersion)

        booleanField("analyticsEnabled", enableFirebase)
    }

    // iOS platform configuration (only if enabled)
    if (enableIos) {
        platform("ios") {
            stringField("versionName", project.version.toString())
            booleanField("isDebug", false)
            stringField("dandanplayAppId", dandanplayAppId)
            stringField("dandanplayAppSecret", dandanplayAppSecret)
            stringField("tmdbApiToken", tmdbApiToken)
        stringField("bangumiOauthClientId", bangumiOauthClientId)
        stringField("bangumiOauthClientSecret", bangumiOauthClientSecret)
            stringField("sentryDsn", sentryDsn)

            val sentryEnabled = (getPropertyOrNull("ani.sentry.ios") ?: "true").toBooleanStrict()
            booleanField("sentryEnabled", sentryEnabled)
                stringField("distroChannel", distroChannel)

            firebaseFields()
        }
    }
}
