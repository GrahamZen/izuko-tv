"""
本机限速 HTTP 代理 (给真机测「播放中卡住缓冲」用): 支持 CONNECT (https) 与普通 http 转发,
服务器 → 客户端方向按 rate.txt 里的字节/秒限速 (0 或空 = 不限), 每 0.5 秒重读, 所有连接共用一个额度.
用法: uv run python -I throttle_proxy.py <端口> <rate.txt 路径>
"""
import asyncio
import os
import sys
import time

PORT = int(sys.argv[1])
RATE_FILE = sys.argv[2]

rate = 0
lock = asyncio.Lock()
next_free = 0.0


async def watch_rate():
    global rate
    while True:
        try:
            text = open(RATE_FILE, encoding="utf-8").read().strip()
            new = int(text) if text else 0
        except (OSError, ValueError):
            new = 0
        if new != rate:
            print(f"{time.strftime('%H:%M:%S')} rate -> {new} B/s", flush=True)
            rate = new
        await asyncio.sleep(0.5)


async def throttle(n):
    """按全局额度给 n 字节排队: 限速时所有连接加起来不超过 rate."""
    global next_free
    if rate <= 0:
        return
    async with lock:
        now = time.monotonic()
        start = max(now, next_free)
        next_free = start + n / rate
        wait = next_free - now
    if wait > 0:
        await asyncio.sleep(wait)


async def pipe(reader, writer, limited):
    try:
        while True:
            data = await reader.read(16384)
            if not data:
                break
            if limited:
                await throttle(len(data))
            writer.write(data)
            await writer.drain()
    except (ConnectionError, asyncio.CancelledError, OSError):
        pass
    finally:
        try:
            writer.close()
        except Exception:
            pass


async def handle(client_reader, client_writer):
    try:
        head = await client_reader.readuntil(b"\r\n\r\n")
    except Exception:
        client_writer.close()
        return
    lines = head.decode("latin-1").split("\r\n")
    method, target, version = lines[0].split(" ", 2)
    try:
        if method == "CONNECT":
            host, port = target.rsplit(":", 1)
            up_reader, up_writer = await asyncio.open_connection(host, int(port))
            client_writer.write(b"HTTP/1.1 200 Connection Established\r\n\r\n")
            await client_writer.drain()
        else:
            # 普通 http: 绝对地址改成路径, 每个请求一条上游连接
            rest = target.split("://", 1)[1]
            hostport, _, path = rest.partition("/")
            host, _, port = hostport.partition(":")
            up_reader, up_writer = await asyncio.open_connection(host, int(port or 80))
            headers = [l for l in lines[1:] if l and not l.lower().startswith(("proxy-", "connection:"))]
            request = f"{method} /{path} {version}\r\n" + "\r\n".join(headers) + "\r\nConnection: close\r\n\r\n"
            up_writer.write(request.encode("latin-1"))
            await up_writer.drain()
    except Exception as e:
        print(f"upstream {target} failed: {e}", flush=True)
        client_writer.close()
        return
    await asyncio.gather(
        pipe(client_reader, up_writer, limited=False),
        pipe(up_reader, client_writer, limited=True),
    )


async def main():
    server = await asyncio.start_server(handle, "127.0.0.1", PORT)
    print(f"throttle proxy on 127.0.0.1:{PORT}, rate file {RATE_FILE}", flush=True)
    async with server:
        await asyncio.gather(server.serve_forever(), watch_rate())


asyncio.run(main())
