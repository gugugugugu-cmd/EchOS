//go:build android

// Android 平台的 DNS 引导补丁。
//
// Go 的纯 Go 解析器依赖 /etc/resolv.conf，但 Android 上没有这个文件
// （Android 的 DNS 配置由 netd 管理，不暴露给普通进程）。CGO 又是禁用的
// （CGO_ENABLED=0 交叉编译），没有 cgo 解析器兜底 —— 结果是所有默认域名
// 解析都会失败，表现为：
//   - DoH 服务器域名（dns.alidns.com）解析不了 → ECH 公钥查询失败
//   - 未配置 -ip 时服务地址域名解析不了 → WebSocket 连不上
//
// 修复方式：检测到 /etc/resolv.conf 不存在时，把 net.DefaultResolver
// 换成走公共 DNS 的自定义解析器。只影响 android 平台构建，其他平台零改动。
// 客户端给 -ip（优选IP）后服务连接不走这里，但 DoH 与分流解析仍受益。
package main

import (
	"context"
	"net"
	"os"
	"time"
)

const androidBootstrapDNS = "223.5.5.5:53" // 阿里公共 DNS，国内外可达

func init() {
	if _, err := os.Stat("/etc/resolv.conf"); err == nil {
		return // 有 resolv.conf 就不动默认行为
	}
	dialer := &net.Dialer{Timeout: 5 * time.Second}
	net.DefaultResolver = &net.Resolver{
		PreferGo: true,
		Dial: func(ctx context.Context, network, _ string) (net.Conn, error) {
			return dialer.DialContext(ctx, network, androidBootstrapDNS)
		},
	}
}
