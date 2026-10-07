/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.player.tracks

/**
 * 字幕 / 音轨的语言, 从轨道名 (`简体中文`, `CHS`, `繁日双语`, `Japanese`) 与容器里标的语言码 (`chi`, `zh-Hant`, `jpn`) 认出来.
 *
 * @param base 主语言: `zh` / `ja` / `en`, 或其它 ISO 639 码
 * @param script 中文的简繁: `Hans` / `Hant`; 不是中文或分不出时为 null
 * @param withJapanese 中日双语 (`简日双语`)
 */
data class TrackLanguage(
    val base: String,
    val script: String? = null,
    val withJapanese: Boolean = false,
) {
    /** 记住选择用的键: `zh-Hans`, `zh-Hant+ja`, `zh`, `ja`, `en` … */
    val key: String
        get() = buildString {
            append(base)
            if (script != null) append('-').append(script)
            if (withJapanese) append("+ja")
        }

    companion object {
        const val HANS = "Hans"
        const val HANT = "Hant"

        /** [key] 的反操作; 认不出返回 null. */
        fun parseKey(key: String): TrackLanguage? {
            val withJapanese = key.endsWith("+ja")
            val main = key.removeSuffix("+ja")
            val base = main.substringBefore('-')
            if (base.isEmpty() || !base.all { it.isLetter() }) return null
            val script = main.substringAfter('-', "").takeIf { it.isNotEmpty() }
            return TrackLanguage(base, script, withJapanese)
        }

        /**
         * 一条轨道的语言: 名字优先 (名字分得出简繁); 名字只说了「中文」时用语言码补简繁, 名字认不出时只看语言码.
         */
        fun of(label: String?, code: String?): TrackLanguage? {
            val fromLabel = label?.let { fromTags(it) }
            val fromCode = fromCode(code)
            return when {
                fromLabel == null -> fromCode
                fromLabel.base == "zh" && fromLabel.script == null && fromCode?.base == "zh" && fromCode.script != null ->
                    fromLabel.copy(script = fromCode.script)

                else -> fromLabel
            }
        }

        /** 轨道名或文件名里的语言标记; 一个标记也没有时返回 null. */
        fun fromTags(text: String): TrackLanguage? {
            val tags = LanguageTags.of(text)
            return when {
                tags.sc && tags.tc -> TrackLanguage("zh", withJapanese = tags.ja)
                tags.sc -> TrackLanguage("zh", HANS, withJapanese = tags.ja)
                tags.tc -> TrackLanguage("zh", HANT, withJapanese = tags.ja)
                tags.zh -> TrackLanguage("zh", withJapanese = tags.ja)
                tags.ja -> TrackLanguage("ja")
                tags.en -> TrackLanguage("en")
                else -> null
            }
        }

        /** 容器里标的语言码 (ISO 639-1 / 639-2 / BCP 47); 空的与 `und` 返回 null. */
        fun fromCode(code: String?): TrackLanguage? {
            val parts = code?.trim()?.lowercase()?.split('-', '_')?.filter { it.isNotEmpty() } ?: return null
            val primary = parts.firstOrNull() ?: return null
            val rest = parts.drop(1)
            return when (primary) {
                "zh", "chi", "zho", "cmn" -> TrackLanguage(
                    "zh",
                    when {
                        rest.any { it == "hans" || it == "cn" || it == "sg" } -> HANS
                        rest.any { it == "hant" || it == "tw" || it == "hk" || it == "mo" } -> HANT
                        else -> null
                    },
                )

                "ja", "jpn" -> TrackLanguage("ja")
                "en", "eng" -> TrackLanguage("en")
                "und", "mis", "mul", "zxx" -> null
                else -> primary.takeIf { it.length in 2..3 && it.all { c -> c in 'a'..'z' } }?.let { TrackLanguage(it) }
            }
        }
    }
}

/**
 * 名字里的语言标记: `sc`, `chs&jpn`, `zh-Hant`, `Simplified Chinese`, `简日双语`, `日本語`.
 * 字幕文件名 (见 DriveSidecarSubtitles) 与轨道名共用.
 */
internal class LanguageTags(
    val sc: Boolean,
    val tc: Boolean,
    val zh: Boolean,
    val ja: Boolean,
    val en: Boolean,
) {
    companion object {
        fun of(text: String): LanguageTags {
            var sc = false
            var tc = false
            var zh = false
            var ja = false
            var en = false
            for (token in TOKEN_SEPARATOR.split(text.lowercase())) {
                when {
                    token.isEmpty() -> {}
                    token in SC_TOKENS -> sc = true
                    token in TC_TOKENS -> tc = true
                    token in ZH_TOKENS -> zh = true
                    token in JA_TOKENS -> ja = true
                    token in EN_TOKENS -> en = true
                    token in SC_JA_TOKENS -> {
                        sc = true
                        ja = true
                    }

                    token in TC_JA_TOKENS -> {
                        tc = true
                        ja = true
                    }

                    token in ZH_WORDS -> zh = true
                    token in JA_WORDS -> ja = true
                    // 中文写的标记 (`简日双语`, `繁体`); 只认全由这几个字组成的, 免得把标题里的字 (`日常`) 当成标记
                    token.all { it in CJK_TAG_CHARS } -> {
                        if ('简' in token || '簡' in token) sc = true
                        if ('繁' in token) tc = true
                        if ('中' in token) zh = true
                        if ('日' in token) ja = true
                        if ('英' in token) en = true
                    }
                }
            }
            return LanguageTags(sc, tc, zh, ja, en)
        }

        private val TOKEN_SEPARATOR = Regex("""[^\p{L}\p{N}]+""")

        private val SC_TOKENS = setOf("sc", "chs", "gb", "gbk", "hans", "zhs", "cn", "chn", "sim", "simplified", "schinese")
        private val TC_TOKENS = setOf("tc", "cht", "big5", "hant", "zht", "tw", "hk", "trad", "traditional", "tchinese")
        private val ZH_TOKENS = setOf("zh", "chi", "zho", "chinese", "mandarin")
        private val JA_TOKENS = setOf("jp", "ja", "jpn", "jap", "japanese")
        private val EN_TOKENS = setOf("en", "eng", "english")
        private val SC_JA_TOKENS = setOf("jpsc", "scjp", "chsjp", "jpchs", "chsjpn", "gbjp", "jpgb")
        private val TC_JA_TOKENS = setOf("jptc", "tcjp", "chtjp", "jpcht", "chtjpn", "big5jp", "jpbig5")

        /** 轨道名里常见、但不全由 [CJK_TAG_CHARS] 组成的整词 */
        private val ZH_WORDS = setOf("国语", "國語", "普通话", "普通話", "华语", "華語", "汉语", "漢語")
        private val JA_WORDS = setOf("日本語", "日本语", "日语原声", "日語原聲")
        private const val CJK_TAG_CHARS = "简簡繁体體中文字日语語双雙英"
    }
}
