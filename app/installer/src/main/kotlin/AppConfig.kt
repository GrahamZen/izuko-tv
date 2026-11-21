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
import android.os.Handler
import android.os.Looper
import java.io.ByteArrayOutputStream
import java.security.InvalidAlgorithmParameterException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

/**
 * 加密的应用配置 (交流群这类可能要换的东西), 与应用同一份: 本仓库 `lists` 分支的 `app-config.txt`.
 * 密文 `v1.<nonce>.<密文>` (两段 base64url, 不补 `=`), AES-256-GCM, 关联数据 [PURPOSE], 明文逐行 `名字=值`;
 * 格式见 `scripts/sealed_text.py`, 与应用的 SealedText 同一个. 钥匙编进包里 ([BuildConfig.APP_CONFIG_KEY]).
 *
 * 某一项配置里有就用它, 没有用编包时写进去的 (见 [ChatConfig]). 每个进程取一次, 取到能解开的存下 (下次启动先用它),
 * 取不到用存下的 —— 换群不用重发助手.
 */
internal object AppConfig {
    const val PURPOSE = "izuko-app-config/v1"
    private const val FILE_NAME = "app-config.txt"
    private const val PREFS = "app_config"
    private const val KEY_TOKEN = "token"

    // 用到时才建: 本机跑的单测里没有安卓的主线程
    private val main by lazy { Handler(Looper.getMainLooper()) }

    @Volatile
    private var fetched: Map<String, String>? = null

    @Volatile
    private var fetching = false

    /** 取到时在主线程调; 界面建出来时设、销毁时清 (界面重建过, 交给新的那个). */
    @Volatile
    var listener: ((Map<String, String>) -> Unit)? = null

    /** 现在用的: 这个进程里取到的, 没取到过用上次存下的; 都没有为空. */
    fun current(context: Context): Map<String, String> = fetched ?: saved(context)

    /** 这个进程里还没取过就在后台取一次; 取到能解开的存下, 在主线程交给 [listener]. */
    fun refresh(context: Context) {
        val key = BuildConfig.APP_CONFIG_KEY
        synchronized(this) {
            if (fetched != null || fetching || key.isEmpty()) return
            fetching = true
        }
        val app = context.applicationContext
        thread(name = "app-config") {
            try {
                for (url in repoFileUrls(BuildConfig.REPOSITORY, LISTS_BRANCH, FILE_NAME)) {
                    val token = try {
                        Http.getText(url, connectTimeoutMs = 5_000, readTimeoutMs = 8_000).trim()
                    } catch (e: Exception) {
                        InstallFlow.log("App config: $url failed (${e.describe()})")
                        continue
                    }
                    val config = open(token, key)?.let(::parse)
                    if (config == null) {
                        InstallFlow.log("App config: $url is not a config this build can open")
                        continue
                    }
                    InstallFlow.log("App config from $url: ${config.keys.sorted()}")
                    app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_TOKEN, token).apply()
                    fetched = config
                    main.post { listener?.invoke(config) }
                    return@thread
                }
            } finally {
                fetching = false
            }
        }
    }

    private fun saved(context: Context): Map<String, String> {
        val token = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TOKEN, null) ?: return emptyMap()
        return open(token, BuildConfig.APP_CONFIG_KEY)?.let(::parse).orEmpty()
    }

    /** 解开 [token]; 格式不对、钥匙不对、用途不对、被改过时为 `null`. */
    fun open(token: String, key: String): String? {
        val parts = token.trim().split('.')
        if (parts.size != 3 || parts[0] != "v1") return null
        val keyBytes = base64UrlDecode(key) ?: return null
        val nonce = base64UrlDecode(parts[1]) ?: return null
        val sealed = base64UrlDecode(parts[2]) ?: return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val secret = SecretKeySpec(keyBytes, "AES")
            try {
                cipher.init(Cipher.DECRYPT_MODE, secret, GCMParameterSpec(128, nonce))
            } catch (e: InvalidAlgorithmParameterException) {
                // Android 4.4 的实现可能只认 IvParameterSpec (标签长度默认 128 位)
                cipher.init(Cipher.DECRYPT_MODE, secret, IvParameterSpec(nonce))
            }
            cipher.updateAAD(PURPOSE.toByteArray(Charsets.UTF_8))
            cipher.doFinal(sealed).toString(Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    /** 明文逐行 `名字=值` (按第一个 `=` 拆); 同名的取最后一行. */
    fun parse(plain: String): Map<String, String> = plain.lineSequence()
        .mapNotNull { line -> line.indexOf('=').takeIf { it > 0 }?.let { line.substring(0, it).trim() to line.substring(it + 1).trim() } }
        .toMap()

    private const val BASE64_URL = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    /** base64url 解码 (补不补 `=` 都认); 有别的字符为 `null`. `java.util.Base64` 要 API 26, 这里自己解. */
    fun base64UrlDecode(text: String): ByteArray? {
        val out = ByteArrayOutputStream(text.length * 3 / 4)
        var buffer = 0
        var bits = 0
        for (c in text.trim().trimEnd('=')) {
            val value = BASE64_URL.indexOf(c)
            if (value < 0) return null
            buffer = (buffer shl 6) or value
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((buffer shr bits) and 0xFF)
            }
        }
        return out.toByteArray()
    }
}

/** 交流群: 加密配置里有就用它, 没有用编包时写进去的 ([BuildConfig] 的 `CHAT_*`). 名字同应用 (local.properties 属性去掉 `ani.`). */
internal data class ChatConfig(val qqId: String, val qqLink: String, val telegramLink: String) {
    companion object {
        fun of(config: Map<String, String>): ChatConfig {
            fun value(name: String, bundled: String) = config[name]?.takeIf { it.isNotBlank() } ?: bundled
            return ChatConfig(
                qqId = value("chat.qq.id", BuildConfig.CHAT_QQ_ID),
                qqLink = value("chat.qq.link", BuildConfig.CHAT_QQ_LINK),
                telegramLink = value("chat.telegram.link", BuildConfig.CHAT_TELEGRAM_LINK),
            )
        }
    }
}
