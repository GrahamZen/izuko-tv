"""从真机 ART 使用记录生成 app/android/src/main/baseline-prof.txt (我们自己代码的 baseline profile 规则).

用法: uv run python scripts/baseline-profile/gen-rules.py <记录.prof.txt> <录制时装的 APK> [输出文件] [包级阈值, 默认 0.3]

记录怎么来 (见 docs/contributing/building.md 的「Baseline profile」):
  adb shell cmd package compile -m verify -f <包名>    # 回到未编译状态, 解释执行时 ART 才会记下用到的方法
  (冷启动后把主要页面都走一遍)
  adb shell cmd package dump-profiles <包名>
  adb pull /data/misc/profman/<包名>-primary.prof.txt

规则: 用到过 = 记录里的 热点 / 启动 / 启动后 方法.
- 我们的代码 (me.him188.*): 某包里用到过的字节码 ≥ 阈值 ⇒ 整包一条通配规则; 否则用到过的类 (连同内部类) 各一条.
  R8 合成类 (me.him188.ani.r8, 记录里是 R8 之后的名字) 不写: 通配在 R8 之前按原类名展开, 合成类由 R8 按规则里的原方法带上.
- TV 界面 (类名以 Tv 开头) 一律收进来: 录制走不到所有 TV 页面, 这部分体积小.
- 库不生成规则: Compose 等库自带 baseline profile, 其余由系统按设备上的实际使用记录编译.
"""
import re
import struct
import sys
import zipfile
from collections import defaultdict
from pathlib import Path

PRIM = {"V": "void", "Z": "boolean", "B": "byte", "S": "short", "C": "char", "I": "int", "J": "long", "F": "float",
        "D": "double"}
TV_RULE = "HSPLme/him188/ani/**/Tv*;->**(**)**"
R8_PACKAGE = "me.him188.ani.r8"
DEFAULT_OUT = Path(__file__).resolve().parents[2] / "app/android/src/main/baseline-prof.txt"


def java_type(desc):
    dims = len(desc) - len(desc.lstrip("["))
    base = desc[dims:]
    return (PRIM.get(base) or base[1:-1].replace("/", ".")) + "[]" * dims


def uleb(buf, off):
    result = shift = 0
    while True:
        b = buf[off]
        off += 1
        result |= (b & 0x7F) << shift
        if b < 0x80:
            return result, off
        shift += 7


def read_dex_sizes(apk):
    """(类, 方法名, 参数类型) -> 字节码大小; 类 -> 字节码大小."""
    method_size, class_size = {}, defaultdict(int)
    with zipfile.ZipFile(apk) as z:
        for dex_name in sorted(n for n in z.namelist() if re.fullmatch(r"classes\d*\.dex", n)):
            buf = z.read(dex_name)
            (_, string_ids_off, _, type_ids_off, _, proto_ids_off, _, _, _, method_ids_off,
             class_defs_size, class_defs_off) = struct.unpack_from("<12I", buf, 0x38)
            cache = {}

            def s(idx):
                v = cache.get(idx)
                if v is None:
                    off = struct.unpack_from("<I", buf, string_ids_off + idx * 4)[0]
                    _, off = uleb(buf, off)
                    v = cache[idx] = buf[off:buf.index(0, off)].decode("utf-8", "replace")
                return v

            def t(idx):
                return s(struct.unpack_from("<I", buf, type_ids_off + idx * 4)[0])

            def params_of(idx):
                params_off = struct.unpack_from("<I", buf, proto_ids_off + idx * 12 + 8)[0]
                if not params_off:
                    return ()
                n = struct.unpack_from("<I", buf, params_off)[0]
                return tuple(java_type(t(struct.unpack_from("<H", buf, params_off + 4 + 2 * k)[0])) for k in range(n))

            for i in range(class_defs_size):
                class_idx, _, _, _, _, _, data_off, _ = struct.unpack_from("<8I", buf, class_defs_off + i * 32)
                cls = java_type(t(class_idx))
                class_size[cls] += 0
                if not data_off:
                    continue
                off = data_off
                counts = []
                for _ in range(4):
                    v, off = uleb(buf, off)
                    counts.append(v)
                for _ in range(counts[0] + counts[1]):
                    _, off = uleb(buf, off)
                    _, off = uleb(buf, off)
                for count in counts[2:]:
                    midx = 0
                    for _ in range(count):
                        d, off = uleb(buf, off)
                        midx += d
                        _, off = uleb(buf, off)
                        code_off, off = uleb(buf, off)
                        if not code_off:
                            continue
                        size = struct.unpack_from("<I", buf, code_off + 12)[0] * 2
                        _, proto_idx, name_idx = struct.unpack_from("<HHI", buf, method_ids_off + midx * 8)
                        method_size[(cls, s(name_idx), params_of(proto_idx))] = size
                        class_size[cls] += size
    return method_size, class_size


def read_used_methods(profile):
    """profman 导出的文本记录里 热点 / 启动 / 启动后 三段的方法."""
    line_re = re.compile(r"^\s*(\S+) (.+?)\.([^.(]+)\((.*?)\)(?:\[|,|\s*$)")
    used = set()
    section = False
    for raw in open(profile, encoding="utf-8", errors="replace"):
        st = raw.strip()
        if st.startswith(("hot methods:", "startup methods:", "post startup methods:")):
            section = True
        elif st.startswith(("classes:", "base.apk", "===")):
            section = False
        elif section:
            m = line_re.match(raw)
            if m:
                used.add((m.group(2), m.group(3), tuple(p.strip() for p in m.group(4).split(",") if p.strip())))
    return used


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    profile, apk = sys.argv[1], sys.argv[2]
    out = Path(sys.argv[3]) if len(sys.argv) > 3 else DEFAULT_OUT
    threshold = float(sys.argv[4]) if len(sys.argv) > 4 else 0.3

    method_size, class_size = read_dex_sizes(apk)
    used = read_used_methods(profile)
    print(f"记录里用到过的方法 {len(used)}, 对得上 APK 的 {sum(k in method_size for k in used)}")

    def pkg_of(cls):
        return cls.rsplit(".", 1)[0] if "." in cls else ""

    def outer_of(cls):
        return cls.split("$", 1)[0]

    pkg_total, pkg_used = defaultdict(int), defaultdict(int)
    used_classes = defaultdict(set)
    for cls, size in class_size.items():
        pkg_total[pkg_of(cls)] += size
    for k in used:
        pkg_used[pkg_of(k[0])] += method_size.get(k, 0)
        used_classes[pkg_of(k[0])].add(k[0])

    rules = []
    for p in sorted(q for q in pkg_used if q.startswith("me.him188.") and q != R8_PACKAGE):
        if pkg_used[p] >= threshold * pkg_total[p]:
            rules.append(f"HSPL{p.replace('.', '/')}/*;->**(**)**")
        else:
            for o in sorted({outer_of(c) for c in used_classes[p]}):
                d = o.replace(".", "/")
                rules.append(f"HSPL{d};->**(**)**")
                rules.append(f"HSPL{d}$**;->**(**)**")
    rules.append(TV_RULE)
    out.write_text("\n".join(rules) + "\n", encoding="utf-8", newline="\n")
    print(f"写入 {out}: {len(rules)} 条规则")


if __name__ == "__main__":
    main()
