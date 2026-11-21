// 逐个检查加密直连 (ECH) 的 Cloudflare 地址: 改仓库根目录 bangumi-ech-addresses.json 之前先跑一遍.
//
//	go run scripts/ech/check-addresses.go 172.67.134.140 104.21.6.61 2606:4700:20::681a:817
//
// 从阿里 DoH 取 crypto.cloudflare.com 的公共 ECH 配置 (应用用的也是它), 对每个地址以 api.bgm.tv 为内层域名握手,
// 打印握手是否接受 ECH、Cloudflare /cdn-cgi/trace 里的 sni (encrypted 才是真加密了) 与 h (请求到了 bangumi).
// 要 Go 1.23 以上 (标准库从这一版起支持 ECH 客户端). 测的是本机的网络: 在海外跑只能证明地址接 ECH,
// 用户那边连不连得上看应用里网络体检的「加密直连的各个地址」.
package main

import (
	"bufio"
	"crypto/tls"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"regexp"
	"strings"
	"time"
)

func echConfig() ([]byte, error) {
	resp, err := http.Get("https://dns.alidns.com/resolve?name=crypto.cloudflare.com&type=65")
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	var body struct {
		Answer []struct{ Data string `json:"data"` } `json:"Answer"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&body); err != nil {
		return nil, err
	}
	re := regexp.MustCompile(`ech="?([A-Za-z0-9+/]+=*)`)
	for _, a := range body.Answer {
		if m := re.FindStringSubmatch(a.Data); m != nil {
			return base64.StdEncoding.DecodeString(m[1])
		}
	}
	return nil, fmt.Errorf("no ech in DoH answer")
}

func check(addr string, cfg []byte) string {
	start := time.Now()
	conn, err := net.DialTimeout("tcp", net.JoinHostPort(addr, "443"), 6*time.Second)
	if err != nil {
		return "connect failed: " + err.Error()
	}
	defer conn.Close()
	conn.SetDeadline(time.Now().Add(10 * time.Second))
	tc := tls.Client(conn, &tls.Config{ServerName: "api.bgm.tv", EncryptedClientHelloConfigList: cfg, NextProtos: []string{"http/1.1"}})
	if err := tc.Handshake(); err != nil {
		return fmt.Sprintf("handshake failed after %dms: %v", time.Since(start).Milliseconds(), err)
	}
	accepted := tc.ConnectionState().ECHAccepted
	fmt.Fprintf(tc, "GET /cdn-cgi/trace HTTP/1.1\r\nHost: api.bgm.tv\r\nConnection: close\r\n\r\n")
	b, _ := io.ReadAll(bufio.NewReader(tc))
	trace := map[string]string{}
	for _, l := range strings.Split(string(b), "\n") {
		if kv := strings.SplitN(strings.TrimSpace(l), "=", 2); len(kv) == 2 {
			trace[kv[0]] = kv[1]
		}
	}
	return fmt.Sprintf("ech=%v sni=%s h=%s colo=%s %dms", accepted, trace["sni"], trace["h"], trace["colo"], time.Since(start).Milliseconds())
}

func main() {
	cfg, err := echConfig()
	if err != nil {
		fmt.Println("ech config:", err)
		os.Exit(1)
	}
	for _, a := range os.Args[1:] {
		fmt.Printf("%-24s %s\n", a, check(a, cfg))
	}
}
