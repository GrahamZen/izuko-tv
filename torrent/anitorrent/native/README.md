# anitorrent 原生库 (重编版)

安卓用的 anitorrent 原生库与 Java 绑定不用 Maven Central 上的 `org.openani.anitorrent:*:0.2.0`, 而用 `repo/` 里重编的
`0.2.0-izuko6`。替换规则在 `build-logic/src/main/kotlin/anitorrentNative.kt`, 本地仓库在 `settings.gradle.kts` 里登记。
桌面端的原生库仍是上游 0.2.0。

## 改了什么

源码 = [open-ani/anitorrent](https://github.com/open-ani/anitorrent) v0.2.0 (`05756d03`) + `anitorrent.patch`,
libtorrent v2.0.10 + `libtorrent.patch`, 许可证同上游 (anitorrent GPL-3.0, libtorrent BSD)。

- **写盘回调** (`write_back_listener_t` / `write_back_disk_io`): BT 下载与在线源下载一样「写够一段就落盘, 落完再收」。
  libtorrent 写盘只进内核的页缓存, 网速比磁盘快时没落盘的数据越积越多; 电视的 `/data` 是 ext4 `data=ordered`,
  这时整机的 fsync 都要排队, 蓝牙遥控器失灵、画面停住。包装层把每个文件的写入报给 Kotlin
  (`torrent/anitorrent/src/commonMain/kotlin/TorrentWriteBack.kt`, 用 `utils/io` 的 `WriteBackCounter` 与 `syncFileContents`),
  要落盘时压住写完成的回调、告诉连接「磁盘忙」, 连接暂停读取, 落完再放开。等落盘超过 10 秒原生层自己放开。
- **整季包里单独删一集** (`torrent_handle_t::discard_file`): 完全落在这个文件里的分块让 libtorrent 忘掉
  (libtorrent 补丁加的 `torrent_handle::forget_pieces`: 不再上传给别人, 拒掉已收到的请求, 再想要时重新下), 再把文件里这一段
  变成空洞, 空间当场还给系统; 首尾与相邻集共用的分块不动。能打洞 (`fallocate` PUNCH_HOLE) 就打洞, 安卓的 sdcardfs 不支持时
  把文件重写成只留首尾数据的稀疏文件再换回原处; 换文件期间包装层的读写都等着 (`file_replace_mutex`),
  相邻集正在下的首尾分块不会写进被换掉的旧文件。直接删文件不行: libtorrent 仍以为有这些分块, 别人来要时读不到,
  整个种子会报错停下。忘掉分块时要拒掉对方排着的请求, libtorrent 补丁顺带修了 `peer_connection::reject_piece`:
  v2.0.10 删掉一条请求后还会多跳一格, 末尾位置也只在开头取一次, 对方排着这一块的请求时越界崩溃。
- **`session_t::apply_settings` 生效**: 上游只填了设置没交给 libtorrent, 运行中改限速等设置要等 BT 服务重启。

libtorrent 仍是 v2.0.10、与上游同样的编译选项 (导出的 libtorrent 符号与上游包一致), 和胶水库一起重编;
C++ 运行库改为 `c++_shared`, 用应用里已有的 `libc++_shared.so`。按 API 24 编: 低于 24 时 libtorrent 用 netlink 枚举网卡,
Android 11 起应用不许绑 netlink, 结果一个监听端口、一条连接都建不起来。

## 重编

改了补丁或要换编译选项时:

```bash
NDK=/path/to/android-ndk-r27c NINJA=/path/to/ninja SWIG=/path/to/swig-4.2.1 torrent/anitorrent/native/build.sh
```

需要 NDK r27c、CMake ≥ 3.21、ninja、SWIG 4.2.1、JDK (javac)、git、uv。脚本从上游克隆、打补丁、核对生成的绑定、编四个 ABI,
产物 (aar + 绑定 jar + pom) 写回 `repo/`。改 C++ 时在打好补丁的 anitorrent 仓库里改, 重新生成绑定
(参数见 `build.sh`), 再用 `git diff 05756d03` 重新导出 `anitorrent.patch`; libtorrent 的改动在 `$WORK/libtorrent` 里改,
用 `git diff` 导出 `libtorrent.patch`。
