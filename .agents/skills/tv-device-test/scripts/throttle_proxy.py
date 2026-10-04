"""
本机限速 HTTP 代理 (给真机测「播放中卡住缓冲」用): 支持 CONNECT (https) 与普通 http 转发,
服务器 → 客户端方向按 rate.txt 里的字节/秒限速 (0 或空 = 不限), 每 0.5 秒重读, 所有连接共用一个额度.
可选的 block.txt 每行一个域名: 连这些域名一律拒绝, 写进去时已经连着的也当场断开 (测「某个接口取不到」, 如只拦 api.bgm.tv
让分集取不到、条目照常); 写成 `hold <域名>` 则不拒绝, 只把这个域名回来的数据扣住, 删掉这行才放行 (测「某个接口慢」, 要在应用的
读超时 (30 秒) 之内放行). 同样每 0.5 秒重读.
用法: uv run python -I throttle_proxy.py <端口> <rate.txt 路径> [block.txt 路径]
"""
import asyncio
import os
import sys
import time

PORT = int(sys.argv[1])
RATE_FILE = sys.argv[2]
BLOCK_FILE = sys.argv[3] if len(sys.argv) > 3 else None

rate = 0
lock = asyncio.Lock()
next_free = 0.0
blocked = set()
held = set()
# 域名 -> 正连着的 (客户端, 上游) writer, 域名被拦时断开
open_tunnels = {}


async def watch_rate():
    global rate, blocked, held
    while True:
        try:
            text = open(RATE_FILE, encoding="utf-8").read().strip()
            new = int(text) if text else 0
        except (OSError, ValueError):
            new = 0
        if new != rate:
            print(f"{time.strftime('%H:%M:%S')} rate -> {new} B/s", flush=True)
            rate = new
        if BLOCK_FILE:
            try:
                entries = {l.strip() for l in open(BLOCK_FILE, encoding="utf-8") if l.strip()}
            except OSError:
                entries = set()
            holds = {e.split(None, 1)[1].strip() for e in entries if e.startswith("hold ")}
            hosts = {e for e in entries if not e.startswith("hold ")}
            if holds != held:
                print(f"{time.strftime('%H:%M:%S')} held -> {sorted(holds)}", flush=True)
                held = holds
            if hosts != blocked:
                print(f"{time.strftime('%H:%M:%S')} blocked -> {sorted(hosts)}", flush=True)
                blocked = hosts
                for host in hosts:
                    for writers in open_tunnels.pop(host, []):
                        for w in writers:
                            w.close()
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


async def pipe(reader, writer, limited, host):
    try:
        while True:
            data = await reader.read(16384)
            if not data:
                break
            if limited:
                while host in held:
                    await asyncio.sleep(0.1)
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
    host = target.rsplit(":", 1)[0] if method == "CONNECT" else target.split("://", 1)[-1].split("/", 1)[0].split(":")[0]
    if host in blocked:
        print(f"{time.strftime('%H:%M:%S')} blocked {method} {target}", flush=True)
        client_writer.write(b"HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
        client_writer.close()
        return
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
    writers = (client_writer, up_writer)
    open_tunnels.setdefault(host, []).append(writers)
    try:
        await asyncio.gather(
            pipe(client_reader, up_writer, limited=False, host=host),
            pipe(up_reader, client_writer, limited=True, host=host),
        )
    finally:
        tunnels = open_tunnels.get(host, [])
        if writers in tunnels:
            tunnels.remove(writers)


async def main():
    server = await asyncio.start_server(handle, "127.0.0.1", PORT)
    print(f"throttle proxy on 127.0.0.1:{PORT}, rate file {RATE_FILE}", flush=True)
    async with server:
        await asyncio.gather(server.serve_forever(), watch_rate())


asyncio.run(main())
