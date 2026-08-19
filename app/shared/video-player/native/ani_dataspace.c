/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

/*
 * 读写视频 Surface producer 端的 sticky dataspace。
 *
 * 为什么存在: Shield (NVIDIA ROM) 的 h264 硬解管线不理会 MediaFormat 里的色彩信息,
 * 视频层 dataspace 没人设置, 留着垃圾, transfer 位撞上 ST2084 就变假 HDR。
 * 这里从 app 侧直接写 Surface 的 sticky dataspace 字段 —— ACodec 在 app 进程内经同一个
 * Surface 对象 queueBuffer, 每帧都会带上这个值。详见 SurfaceDataSpace.kt / ColorInfoRepair.kt。
 *
 * 写入顺序 (nativeSetBuffersDataSpace):
 * 1. 先试公开的 ANativeWindow_setBuffersDataSpace (API 28+, dlsym 拿)。注意它带一份 UI
 *    色彩空间白名单 (libnativewindow isDataSpaceValid), 视频的 BT709 limited (0x10c10000)
 *    在 Android 11 上会被 -EINVAL 拒掉 —— 保留这步只是为了未来版本放宽白名单时走正门。
 * 2. 失败再走 window->perform(window, NATIVE_WINDOW_SET_BUFFERS_DATASPACE, ds), 即系统
 *    内联函数 native_window_set_buffers_data_space 的同一条路, ACodec 内部用的就是它,
 *    没有白名单。ANativeWindow 的这段 struct 布局是从 Android 1.x 稳定至今的 ABI (所有
 *    GL 驱动都按它编译), 按 AOSP system/window.h 抄最小前缀, 并用 magic + version 双重
 *    校验兜底: 不匹配就拒绝调用, 宁可功能失效也不跳错地址。
 *
 * 读取 (nativeGetBuffersDataSpace): 公开的 ANativeWindow_getBuffersDataSpace 是 API 28
 * (实测 Shield Android 11 存在, 符号住在 libnativewindow.so, 经 libandroid 的依赖树
 * dlsym 可得)。拿不到符号返回 -100 —— 调用方要按"读不到"处理, 不能当 0。
 *
 * 预编译产物提交在 ../src/androidMain/jniLibs/<abi>/libani_dataspace.so,
 * 重新构建用 ./build.ps1 (只需要 NDK, 不需要在 Gradle 里配 externalNativeBuild)。
 */

#include <jni.h>
#include <dlfcn.h>
#include <stdint.h>
#include <android/native_window_jni.h>

struct ani_native_base {
    int magic;    // ANDROID_NATIVE_WINDOW_MAGIC = '_wnd'
    int version;  // Surface 构造时置为 sizeof(ANativeWindow), 覆盖到 perform 之后
    void *reserved[4];
    void (*incRef)(struct ani_native_base *);
    void (*decRef)(struct ani_native_base *);
};

struct ani_native_window {
    struct ani_native_base common;
    const uint32_t flags;
    const int minSwapInterval;
    const int maxSwapInterval;
    const float xdpi;
    const float ydpi;
    intptr_t oem[4];
    int (*setSwapInterval)(struct ani_native_window *, int);
    int (*dequeueBuffer_DEPRECATED)(struct ani_native_window *, void **);
    int (*lockBuffer_DEPRECATED)(struct ani_native_window *, void *);
    int (*queueBuffer_DEPRECATED)(struct ani_native_window *, void *);
    int (*query)(const struct ani_native_window *, int, int *);
    int (*perform)(struct ani_native_window *, int, ...);
};

// AOSP: ANDROID_NATIVE_MAKE_CONSTANT('_','w','n','d') = ('_'<<24)|('w'<<16)|('n'<<8)|'d'
#define ANI_ANDROID_NATIVE_WINDOW_MAGIC \
    ((((int)'_') << 24) | (((int)'w') << 16) | (((int)'n') << 8) | ((int)'d'))
#define ANI_NATIVE_WINDOW_SET_BUFFERS_DATASPACE 19

// ANativeWindow_* 符号实际住在 libnativewindow.so, libandroid 只是把它带进依赖树
// (bionic 的 dlsym(handle) 会搜整棵依赖树)。两个都试, 防某些 ROM 链接关系不同。
static void *ani_dlsym_window(const char *symbol) {
    static void *libandroid = NULL;
    static void *libnativewindow = NULL;
    static int tried = 0;
    if (!tried) {
        tried = 1;
        // RTLD_DEFAULT 不一定搜得到 (取决于加载方命名空间), 显式 dlopen
        libandroid = dlopen("libandroid.so", RTLD_NOW);
        libnativewindow = dlopen("libnativewindow.so", RTLD_NOW);
    }
    void *addr = libandroid != NULL ? dlsym(libandroid, symbol) : NULL;
    if (addr == NULL && libnativewindow != NULL) {
        addr = dlsym(libnativewindow, symbol);
    }
    return addr;
}

JNIEXPORT jint JNICALL
Java_me_him188_ani_app_videoplayer_media_SurfaceDataSpace_nativeSetBuffersDataSpace(
        JNIEnv *env, jclass clazz, jobject surface, jint dataSpace) {
    (void) clazz;
    ANativeWindow *window = ANativeWindow_fromSurface(env, surface);
    if (window == NULL) {
        return -101; // Surface 已失效
    }
    jint result;
    int32_t (*public_set)(ANativeWindow *, int32_t) =
            (int32_t (*)(ANativeWindow *, int32_t)) ani_dlsym_window(
                    "ANativeWindow_setBuffersDataSpace");
    result = (public_set != NULL) ? public_set(window, dataSpace) : -100;
    if (result != 0) {
        // 公开 API 的白名单拒了 (视频 dataspace 在 Android 11 上必拒), 走 ACodec 同款内部通路
        struct ani_native_window *w = (struct ani_native_window *) window;
        if (w->common.magic != ANI_ANDROID_NATIVE_WINDOW_MAGIC ||
            w->common.version < (int) sizeof(struct ani_native_window) ||
            w->perform == NULL) {
            result = -102; // 不是预期的 ANativeWindow 布局, 不敢动
        } else {
            result = w->perform(w, ANI_NATIVE_WINDOW_SET_BUFFERS_DATASPACE, (int) dataSpace);
        }
    }
    ANativeWindow_release(window);
    return result;
}

JNIEXPORT jint JNICALL
Java_me_him188_ani_app_videoplayer_media_SurfaceDataSpace_nativeGetBuffersDataSpace(
        JNIEnv *env, jclass clazz, jobject surface) {
    (void) clazz;
    int32_t (*public_get)(ANativeWindow *) =
            (int32_t (*)(ANativeWindow *)) ani_dlsym_window(
                    "ANativeWindow_getBuffersDataSpace");
    if (public_get == NULL) {
        return -100; // API < 28 或符号缺失, 调用方按"读不到"处理
    }
    ANativeWindow *window = ANativeWindow_fromSurface(env, surface);
    if (window == NULL) {
        return -101;
    }
    jint result = public_get(window);
    ANativeWindow_release(window);
    return result;
}
