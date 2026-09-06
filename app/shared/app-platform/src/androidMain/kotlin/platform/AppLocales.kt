/*
 * Copyright (C) 2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.platform

import android.app.LocaleManager
import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 应用内语言 (独立于系统语言). 空列表表示跟随系统.
 *
 * - Android 13+: 交给系统 [LocaleManager]. 系统负责保存, 变更时重建 Activity 并切换整个进程的语言.
 * - Android 12L 及以下: 系统没有应用内语言. 这里用 SharedPreferences 保存 (`attachBaseContext` 时要同步读),
 *   每个窗口创建时经 [wrap] 覆盖 Configuration 的语言, 并设置进程默认语言 ——
 *   Compose 资源按 `Locale.current` / `Locale.getDefault()` 选文案, 不看 Configuration.
 *
 * 不经 `AppCompatDelegate.setApplicationLocales`: 它在 13+ 从 AppCompatActivity 的 delegate 取 [LocaleManager],
 * 在 12L 及以下由 delegate 保存与应用, 而应用的 Activity 是 ComponentActivity, 两条路都是空操作.
 */
object AppLocales {
    private const val PREFERENCES_NAME = "ani_app_locales"
    private const val KEY_LANGUAGE_TAGS = "languageTags"

    private val defaultLocalesKeeperRegistered = AtomicBoolean(false)

    fun get(context: Context): LocaleList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return context.getSystemService(LocaleManager::class.java)?.applicationLocales
                ?: LocaleList.getEmptyLocaleList()
        }
        return readStored(context)
    }

    /**
     * 设置应用内语言. 12L 及以下会重建 [context] 所在的 Activity 使其生效 (13+ 由系统重建).
     */
    fun set(context: Context, locales: LocaleList) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(LocaleManager::class.java)?.applicationLocales = locales
            return
        }
        // 同步落盘: 紧接着重建的 Activity 要在 attachBaseContext 里读到新值
        preferences(context).edit().putString(KEY_LANGUAGE_TAGS, locales.toLanguageTags()).commit()
        setDefaultLocales(if (locales.isEmpty) systemLocales(context) else locales)
        context.findActivity()?.recreate()
    }

    /**
     * 让 [base] 按应用内语言取资源, 并把进程默认语言设为应用内语言.
     * 在 Activity 的 `attachBaseContext`, 以及不经 Activity 自建界面的窗口 (如屏保) 里调用.
     * 13+ 或跟随系统时原样返回.
     */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
        keepDefaultLocales(base)
        val locales = readStored(base)
        if (locales.isEmpty) return base
        setDefaultLocales(locales)
        val config = Configuration(base.resources.configuration).apply {
            setLocales(locales)
            setLayoutDirection(locales[0])
        }
        return base.createConfigurationContext(config)
    }

    /**
     * 进程配置变化 (系统语言、深色模式、分辨率等) 时, 系统会把进程默认语言重置为系统语言.
     * Activity 重建时 [wrap] 会再设一次, 但在那之前 (以及 Activity 在后台、推迟重建时)
     * 界面之外的代码 (如网页控制台) 也在读它, 所以在进程级回调里立即改回.
     */
    private fun keepDefaultLocales(context: Context) {
        if (!defaultLocalesKeeperRegistered.compareAndSet(false, true)) return
        val app = context.applicationContext ?: context
        app.registerComponentCallbacks(
            object : ComponentCallbacks {
                override fun onConfigurationChanged(newConfig: Configuration) {
                    val locales = readStored(app)
                    if (!locales.isEmpty) setDefaultLocales(locales)
                }

                @Deprecated("Deprecated in Java")
                override fun onLowMemory() = Unit
            },
        )
    }

    private fun readStored(context: Context): LocaleList {
        val tags = runCatching {
            preferences(context).getString(KEY_LANGUAGE_TAGS, null)
        }.getOrNull()
        return if (tags.isNullOrBlank()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tags)
    }

    /**
     * 系统为本应用选出的语言列表 (首项是应用资源里最匹配的系统语言), 与进程启动时系统设的默认语言一致.
     */
    private fun systemLocales(context: Context): LocaleList =
        (context.applicationContext ?: context).resources.configuration.locales

    private fun setDefaultLocales(locales: LocaleList) {
        if (!locales.isEmpty) LocaleList.setDefault(locales)
    }

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
}
