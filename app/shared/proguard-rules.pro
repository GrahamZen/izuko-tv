-dontwarn javax.annotation.Nullable
-dontwarn javax.annotation.ParametersAreNonnullByDefault


# Keep API interfaces
-keep class org.openapitools.** {
	*;
}

-keep class ** extends me.him188.ani.datasources.api.subject.SubjectProvider {}
-keep class ** extends me.him188.ani.datasources.api.source.MediaSource {}
-keep class ** extends me.him188.ani.datasources.api.source.MediaSourceFactory {}

# PlayerStatsOverlay.android.kt 反射读取 ExoPlayer 内部字段 (网速估计, 实际解码器名)
-keepclassmembers class androidx.media3.exoplayer.ExoPlayerImpl {
    androidx.media3.exoplayer.upstream.BandwidthMeter bandwidthMeter;
}
-keepclassmembers class androidx.media3.exoplayer.mediacodec.MediaCodecRenderer {
    androidx.media3.exoplayer.mediacodec.MediaCodecInfo codecInfo;
}

# Torrent4j
-keep class org.libtorrent4j.swig.libtorrent_jni {*;}
-keep class me.him188.ani.app.ui.settings.tabs.** {*;} # 否则设置页切换 tab 会 crash, #367
-keep class me.him188.ani.app.navigation.** {*;} # 否则启动 APP 时会 crash
-keep class me.him188.ani.app.ui.subject.cache.** {*;} # 否则点击缓存管理会 crash


# logback-android
-keepclassmembers class ch.qos.logback.classic.pattern.* { <init>(); }
# The following rules should only be used if you plan to keep
# the logging calls in your released app.
-keepclassmembers class ch.qos.logback.** { *; } #java.io.IOException: Failed to load asset path /data/app/~~2FXqiqIwzpvJbysP7TCLHQ==/me.him188.ani-fqpPfM4QmpABXA7iaUY_Cw==/base.apk
-keepclassmembers class org.slf4j.impl.** { *; }
# TODO 上面两条看起会少 optimize 非常多东西, 可以考虑优化下
-keep class ch.qos.logback.classic.android.LogcatAppender
-keep class ch.qos.logback.core.rolling.RollingFileAppender
-keep class ch.qos.logback.core.rolling.TimeBasedRollingPolicy
#-keepattributes *Annotation* # logback-android 推荐添加, 但测试可以不用添加这个
-dontwarn javax.mail.**


# anitorrent
-keep class org.openani.anitorrent.binding.** { *; }

# ffmpeg native
-keep class org.openani.mediamp.ffmpeg.JvmFFmpegProcess { *; }

# onnxruntime
-keep class ai.onnxruntime.** { *; } # onnxruntime4j_jni constructs Java values through FindClass/GetMethodID

# Android AIDL for torrent service.
-keepnames class me.him188.ani.app.domain.torrent.I* { *; }
-keepnames class me.him188.ani.app.domain.torrent.parcel.** { *; }

-keepattributes LineNumberTable,SourceFile
-renamesourcefileattribute SourceFile
-keepnames class me.him188.ani.** { *; }
-keepnames class !com.google.common.**, !com.google.thirdparty.**, ** { *; } # Keep all names as this only increases pacakge size by a few MBs, but significantly helps with debugging.

# Guava 例外: 有的电视 ROM 在 BOOTCLASSPATH 里带了老版本 Guava (例如 /system/framework/libsetting.jar).
# 类加载双亲优先, APK 里的 com.google.common 被整个遮蔽, media3 调新方法 (ImmutableMap.Builder.buildOrThrow)
# 直接 NoSuchMethodError, 一打开播放器就崩. 放开 Guava 的类名, 让 R8 改名并挪进下面这个包, 与 ROM 那份再无关系.
# 成员名照旧保留: Guava 内部按字段名反射 (AbstractFuture 的 AtomicReferenceFieldUpdater 等).
-keepclassmembernames class com.google.common.** { *; }
-keepclassmembernames class com.google.thirdparty.** { *; }
# 所有改了名的类 (Guava 与 R8 合成的类) 放进我们独有的包, 不落在默认包里 (默认包里的短类名同样可能被 ROM 撞上).
-repackageclasses me.him188.ani.r8
