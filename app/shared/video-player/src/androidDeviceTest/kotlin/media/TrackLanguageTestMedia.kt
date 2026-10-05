/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.videoplayer.media

import java.util.Base64

/**
 * 2 秒的小 MKV (7083 字节), 轨道:
 * - 音轨: `国语` (chi, 标了默认), 没起名的日语 (jpn)
 * - 字幕 (SRT): `English` (eng, 标了默认), `繁體中文` (chi), `简体中文` (chi)
 *
 * 生成: `ffmpeg -f lavfi -i color=black:s=64x36:r=5 -f lavfi -i sine=f=440:r=8000 -f lavfi -i sine=f=880:r=8000 -i en.srt -i tc.srt -i sc.srt
 * -t 2 -map 0:v -map 1:a -map 2:a -map 3 -map 4 -map 5 -c:v libx264 -preset ultrafast -crf 51 -pix_fmt yuv420p -c:a aac -b:a 8k -ac 1 -c:s srt
 * -metadata:s:a:0 language=chi -metadata:s:a:0 title=国语 -disposition:a:0 default -metadata:s:a:1 language=jpn -disposition:a:1 0
 * -metadata:s:s:0 language=eng -metadata:s:s:0 title=English -disposition:s:0 default -metadata:s:s:1 language=chi -metadata:s:s:1 title=繁體中文
 * -disposition:s:1 0 -metadata:s:s:2 language=chi -metadata:s:s:2 title=简体中文 -disposition:s:2 0 tracks.mkv`
 */
internal object TrackLanguageTestMedia {
    val bytes: ByteArray by lazy { Base64.getDecoder().decode(BASE64.joinToString("")) }

    private val BASE64 = listOf(
        "GkXfo6NChoEBQveBAULygQRC84EIQoKIbWF0cm9za2FCh4EEQoWBAhhTgGcBAAAAAAAbdxFNm3TAv4Sz6pYGTbuLU6uEFUmpZlOsgaFNu4tTq4QWVK5rU6yB",
        "7027jFOrhBJUw2dTrIIDAU27jFOrhBxTu2tTrIIbJ+wBAAAAAAAAUwAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
        "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAFUmpZsm/hCpqgXwq17GDD0JATYCMTGF2ZjYyLjMuMTAwV0GMTGF2ZjYyLjMuMTAwc6SQv4bwWRQV",
        "X/90Dq9fuDkU3kSJiECgoAAAAAAAFlSua0IMv4Ttdl8UrgEAAAAAAACC14EBc8WIjPComrQG1CycgQAitZyDdW5kiIEAho9WX01QRUc0L0lTTy9BVkODgQEj",
        "44OEC+vCAOCQsIFAuoEkmoECVbCEVbmBAVXugQDsAQAAAAAAAAIAAGOipwFCwAr/4QAXZ0LACtoR/nwEQAAAAwBAAAADAoPEiagBAAVozgGXIK4BAAAAAAAA",
        "UdeBAnPFiHEE6q3K2je5nIEAU26G5Zu96K+tIrWcg2NoaYaFQV9BQUNWqoQHoSAAg4EC4ZGfgQG1iEC/QAAAAAAAYmSBIFXugQBjooUViFblAK4BAAAAAAAA",
        "S9eBA3PFiJbf1VOgSzkdnIEAIrWcg2pwboiBAIaFQV9BQUNWqoQHoSAAg4EC4ZGfgQG1iEC/QAAAAAAAYmSBIFXugQBjooUViFblAK4BAAAAAAAANteBBHPF",
        "iCk0jVGnfEVlnIEAU26HRW5nbGlzaCK1nINlbmeGi1NfVEVYVC9VVEY4g4ERVe6BAK4BAAAAAAAAPteBBXPFiKyLvv4EH0nznIEAU26M57mB6auU5Lit5paH",
        "IrWcg2NoaYiBAIaLU19URVhUL1VURjiDgRFV7oEArgEAAAAAAAA+14EGc8WIvrck4jGczyicgQBTboznroDkvZPkuK3mlocitZyDY2hpiIEAhotTX1RFWFQv",
        "VVRGOIOBEVXugQASVMNnQjC/hKju7dpzc59jwIBnyJlFo4dFTkNPREVSRIeMTGF2ZjYyLjMuMTAwc3PXY8CLY8WIjPComrQG1CxnyKJFo4dFTkNPREVSRIeV",
        "TGF2YzYyLjExLjEwMCBsaWJ4MjY0Z8ihRaOIRFVSQVRJT05Eh5MwMDowMDowMi4wMDAwMDAwMDAAc3PTY8CLY8WIcQTqrcraN7lnyJ5Fo4dFTkNPREVSRIeR",
        "TGF2YzYyLjExLjEwMCBhYWNnyKFFo4hEVVJBVElPTkSHkzAwOjAwOjAyLjEyODAwMDAwMABzc9NjwItjxYiW39VToEs5HWfInkWjh0VOQ09ERVJEh5FMYXZj",
        "NjIuMTEuMTAwIGFhY2fIoUWjiERVUkFUSU9ORIeTMDA6MDA6MDIuMTI4MDAwMDAwAHNz02PAi2PFiCk0jVGnfEVlZ8ieRaOHRU5DT0RFUkSHkUxhdmM2Mi4x",
        "MS4xMDAgc3J0Z8ihRaOIRFVSQVRJT05Eh5MwMDowMDowMi4wMDAwMDAwMDAAc3PTY8CLY8WIrIu+/gQfSfNnyJ5Fo4dFTkNPREVSRIeRTGF2YzYyLjExLjEw",
        "MCBzcnRnyKFFo4hEVVJBVElPTkSHkzAwOjAwOjAyLjAwMDAwMDAwMABzc9NjwItjxYi+tyTiMZzPKGfInkWjh0VOQ09ERVJEh5FMYXZjNjIuMTEuMTAwIHNy",
        "dGfIoUWjiERVUkFUSU9ORIeTMDA6MDA6MDIuMDAwMDAwMDAwAB9DtnVV6r+EQvSci+eBAKNA84IAAIDeAgBMYXZjNjIuMTEuMTAwAAI4pVLTRu6z9o+3xJV3",
        "LRIkSJEkg2radq2natp2rjO1cZ2raZ6dsU7YrDtW07VxnauM5VlOVbTlW02LE47E5VlIQIQIQIRJRkoyqMqlMlaU11yuru3MDdilAlAwMDAwMbNyyyyyyym5",
        "ZZZZZZTcssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssss",
        "ssssssssssssssssssssssssssssssssssssvKNA/oMAAIDeAgBMYXZjNjIuMTEuMTAwAAIopVmRLCoRSS6V/ailZl8553erSSRJEkFtWzbVs21bOYsw5izD",
        "mLMPZXZPZXZPZXdOxti7G2Ls7Yuzti8XcW8XcW6O0bo7RujtGhISEhISEhISEhISEhISEhISEpQkJmmOJZpppmdnaRpEdHor44LpH/ntSkoFyZlmSgSgQooo",
        "oooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooo",
        "ooooooooooooooooooouo0JxgQAAgAAAAlMGBf//T9xF6b3m2Ui3lizYINkj7u94MjY0IC0gY29yZSAxNjUgcjMyMjMgMDQ4MGNiMCAtIEguMjY0L01QRUct",
        "NCBBVkMgY29kZWMgLSBDb3B5bGVmdCAyMDAzLTIwMjUgLSBodHRwOi8vd3d3LnZpZGVvbGFuLm9yZy94MjY0Lmh0bWwgLSBvcHRpb25zOiBjYWJhYz0wIHJl",
        "Zj0xIGRlYmxvY2s9MDowOjAgYW5hbHlzZT0wOjAgbWU9ZGlhIHN1Ym1lPTAgcHN5PTEgcHN5X3JkPTEuMDA6MC4wMCBtaXhlZF9yZWY9MCBtZV9yYW5nZT0x",
        "NiBjaHJvbWFfbWU9MSB0cmVsbGlzPTAgOHg4ZGN0PTAgY3FtPTAgZGVhZHpvbmU9MjEsMTEgZmFzdF9wc2tpcD0xIGNocm9tYV9xcF9vZmZzZXQ9MCB0aHJl",
        "YWRzPTEgbG9va2FoZWFkX3RocmVhZHM9MSBzbGljZWRfdGhyZWFkcz0wIG5yPTAgZGVjaW1hdGU9MSBpbnRlcmxhY2VkPTAgYmx1cmF5X2NvbXBhdD0wIGNv",
        "bnN0cmFpbmVkX2ludHJhPTAgYmZyYW1lcz0wIHdlaWdodHA9MCBrZXlpbnQ9MjUwIGtleWludF9taW49NSBzY2VuZWN1dD0wIGludHJhX3JlZnJlc2g9MCBy",
        "Yz1jcmYgbWJ0cmVlPTAgY3JmPTUxLjAgcWNvbXA9MC42MCBxcG1pbj0wIHFwbWF4PTY5IHFwc3RlcD00IGlwX3JhdGlvPTEuNDAgYXE9MACAAAAAEmWIhDom",
        "KAAVk5OTrrrrrrrrwKNA2oIAgIABAJzZOR1EuhlEvBVkvn2+f/4v/P9euONatJ/0/T/8P/P8eeOtXd1n/T8/+n/z9dcca1Zr/0/P/p/9/rjjjjUvFl0UgcBl",
        "0ck4mmZsMVzTTL2NjZHZs2RLHIfrwXYIHCUYTrOdFVRjGoNHRRHQboHUKDdBp1UUHEatiIs2EfLct2ydC6Nr7GyKLYUvyJHBWXMs52I885znUpUalKnnnnnn",
        "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",
    )
}
