"""放在公开地方、只给装了应用的人看的密文 (加密的应用配置、加密的通知) 的共用部分: 加解密、读写 local.properties 里的钥匙.

格式: `v1.<nonce>.<密文>`, base64url 不补 `=`; AES-256-GCM, 关联数据是用途 (PURPOSE_*), 同一把钥匙加密的两种东西互相顶替不了.
钥匙: local.properties 的 `ani.config.key` (CI 的 secret APP_CONFIG_KEY), 也编进应用.
与 app-data 的 SealedText、build-logic/src/main/kotlin/appConfig.kt 同一个格式, 改了一起改.
要 cryptography: `uv run --no-project --with cryptography python scripts/<脚本>.py …`.
"""
import base64
import os
import pathlib

from cryptography.hazmat.primitives.ciphers.aead import AESGCM

ROOT = pathlib.Path(__file__).resolve().parent.parent
PROPS = ROOT / "local.properties"
VERSION = "v1"
PURPOSE_APP_CONFIG = b"izuko-app-config/v1"
PURPOSE_NOTICES = b"izuko-notices/v1"


def b64e(b: bytes) -> str:
    return base64.urlsafe_b64encode(b).decode().rstrip("=")


def b64d(s: str) -> bytes:
    return base64.urlsafe_b64decode(s + "=" * (-len(s) % 4))


def seal(plain: str, key: str, purpose: bytes, nonce: bytes = None) -> str:
    nonce = nonce or os.urandom(12)
    return f"{VERSION}.{b64e(nonce)}.{b64e(AESGCM(b64d(key)).encrypt(nonce, plain.encode(), purpose))}"


def open_token(token: str, key: str, purpose: bytes):
    """解开得到明文; 格式不对、钥匙不对、用途不对、被改过时为 None."""
    parts = token.strip().split(".")
    if len(parts) != 3 or parts[0] != VERSION:
        return None
    try:
        return AESGCM(b64d(key)).decrypt(b64d(parts[1]), b64d(parts[2]), purpose).decode()
    except Exception:
        return None


def lines(plain: str) -> list:
    """明文的逐行 `名字=值` (按第一个 `=` 拆), 名字可以重复, 照原文顺序."""
    return [tuple(x.strip() for x in line.split("=", 1)) for line in plain.splitlines() if "=" in line and line.index("=") > 0]


def read_props() -> dict:
    out = {}
    if PROPS.exists():
        for line in PROPS.read_text(encoding="utf-8").splitlines():
            if "=" in line and not line.lstrip().startswith("#"):
                k, v = line.split("=", 1)
                out[k.strip()] = v.strip()
    return out


def write_prop(name: str, value: str) -> None:
    """改或加 local.properties 的一行, 其余原样 (换行符照原文件)."""
    raw = PROPS.read_bytes() if PROPS.exists() else b""
    nl = b"\r\n" if b"\r\n" in raw else b"\n"
    rows = raw.split(nl) if raw else []
    if rows and rows[-1] == b"":
        rows.pop()
    entry = f"{name}={value}".encode()
    for i, row in enumerate(rows):
        if row.split(b"=", 1)[0].strip() == name.encode():
            rows[i] = entry
            break
    else:
        rows.append(entry)
    PROPS.write_bytes(nl.join(rows) + nl)


def read_key() -> str:
    key = read_props().get("ani.config.key", "")
    if not key:
        raise SystemExit("local.properties 里没有 ani.config.key (scripts/app-config.py keygen, 或从备份抄回来)")
    return key
