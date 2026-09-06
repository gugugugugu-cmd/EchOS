package com.echos.app

import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.content.ContextCompat
import java.io.File

/**
 * VPN 全局接管：VpnService 建立 TUN，hev-socks5-tunnel（JNI）把全部流量
 * 转 SOCKS5(127.0.0.1:1080) → 内核 → WSS+ECH → Worker。
 *
 * 关键点：addDisallowedApplication(自身包名) —— 内核子进程与本 App 同包名，
 * 其出站（WSS/DoH/UDP）天然绕过 TUN，规避自环。
 *
 * DNS 用 mapdns（fake-IP）：App 的 DNS 查询由 hev 应答假 IP，后续会话以
 * 「域名」进入 SOCKS5，内核侧分流（geosite）可用。
 */
class EchVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.echos.app.VPN_START"
        const val ACTION_STOP = "com.echos.app.VPN_STOP"

        const val TUN_ADDR = "198.18.0.1"
        const val TUN_DNS = "198.18.0.2"
        const val TUN_V6 = "fc00::1"

        @Volatile
        var isVpnRunning = false
            private set

        /** 可靠停止：先让服务清理 TUN，再取消 started 状态。 */
        fun stop(context: android.content.Context) {
            context.startService(Intent(context, EchVpnService::class.java).setAction(ACTION_STOP))
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                context.stopService(Intent(context, EchVpnService::class.java))
                context.getSystemService(android.app.NotificationManager::class.java)
                    .cancel(ProxyService.NOTIF_ID)
            }, 300)
        }
    }

    private val tproxy = hev.htproxy.TProxyService()
    private var tunFd: ParcelFileDescriptor? = null

    override fun onBind(intent: Intent?) = super.onBind(intent)

    override fun onCreate() {
        super.onCreate()
        ProxyService.createChannelStatic(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopVpnInternal()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                // 唯一前台通知由 ProxyService 持有；本服务只管理 VPN/TUN。
                // 代理服务已启动时，启动完成后刷新同一条通知。
                // 进程被系统重启后 isVpnRunning 可能丢失：hev 若仍在跑，先停再启
                val hevAlive = try {
                    tproxy.TProxyIsRunning()
                } catch (_: Throwable) {
                    false
                }
                if (hevAlive) stopVpnInternal()
                if (!isVpnRunning) startVpn()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopVpnInternal()
        super.onDestroy()
    }

    private fun startVpn() {
        val cfg = ConfigStore.load(this) ?: run {
            ProxyService.log("[VPN] 配置为空，请先填写并保存")
            return
        }
        if (!cfg.isValid()) {
            ProxyService.log("[VPN] 配置不完整（服务地址 / 线路端口 / 监听端口）")
            return
        }
        val socksPort = cfg.listenPort

        // TUN：全局路由 + mapdns（fake-IP DNS）
        val builder = Builder()
            .setSession("EchOS VPN")
            .setMtu(8500)
            .addAddress(TUN_ADDR, 32)
            .addAddress(TUN_V6, 128)
            .addDnsServer(TUN_DNS)
            .addRoute("0.0.0.0", 0)
            .addRoute("::", 0)
        // 分应用代理：allowlist 与 denylist 不能同时使用。
        when (cfg.appMode) {
            "allow" -> {
                // allowlist 模式下自身包名天然不在允许列表，因此自动绕过 TUN
                cfg.appList.filter { it != packageName }.forEach { pkg ->
                    try {
                        builder.addAllowedApplication(pkg)
                    } catch (e: Exception) {
                        ProxyService.log("[VPN] 跳过不存在的应用: $pkg")
                    }
                }
                ProxyService.log("[VPN] 分应用模式：仅 ${cfg.appList.count { it != packageName }} 个应用走代理")
            }
            "exclude" -> {
                // denylist 模式：自身包名必须始终加入排除列表，防止内核 WSS 自环
                try {
                    builder.addDisallowedApplication(packageName)
                } catch (e: Exception) {
                    ProxyService.log("[VPN] 排除自身失败: ${e.message}")
                }
                cfg.appList.filter { it != packageName }.forEach { pkg ->
                    try {
                        builder.addDisallowedApplication(pkg)
                    } catch (e: Exception) {
                        ProxyService.log("[VPN] 跳过不存在的应用: $pkg")
                    }
                }
                ProxyService.log("[VPN] 排除模式：${cfg.appList.count { it != packageName }} 个应用不走代理")
            }
            else -> {
                // 默认模式：仅本应用绕过 TUN，避免内核自身连接形成环路
                try {
                    builder.addDisallowedApplication(packageName)
                } catch (e: Exception) {
                    ProxyService.log("[VPN] 排除自身失败: ${e.message}")
                }
            }
        }
        val pfd = builder.establish()
        if (pfd == null) {
            ProxyService.log("[VPN] TUN 建立失败（权限或配置问题）")
            stopSelf()
            return
        }

        // hev 配置
        val conf = File(filesDir, "hev.yml")
        try {
            conf.writeText(
                """
                tunnel:
                  name: tun0
                  mtu: 8500
                  ipv4: $TUN_ADDR
                  ipv6: '$TUN_V6'

                socks5:
                  port: $socksPort
                  address: 127.0.0.1
                  udp: 'udp'

                mapdns:
                  address: $TUN_DNS
                  port: 53
                  network: 100.64.0.0
                  netmask: 255.192.0.0
                  cache-size: 10000

                misc:
                  log-file: '${File(filesDir, "hev.log").absolutePath}'
                  log-level: info
                """.trimIndent()
            )
        } catch (e: Exception) {
            ProxyService.log("[VPN] 写配置失败: ${e.message}")
            try { pfd.close() } catch (_: Exception) {}
            stopSelf()
            return
        }

        // hev 使用副本 fd，原始 pfd 由 EchVpnService 持有并负责关闭。
        // 这样 native 停止或异常时，不会留下系统 VPN 仍引用的 TUN fd。
        val nativePfd = try {
            ParcelFileDescriptor.dup(pfd.fileDescriptor)
        } catch (e: Exception) {
            ProxyService.log("[VPN] 复制 TUN fd 失败: ${e.message}")
            try { pfd.close() } catch (_: Exception) {}
            stopSelf()
            return
        }
        val nativeFd = nativePfd.detachFd()
        tunFd = pfd
        val ok = try {
            tproxy.TProxyStartService(conf.absolutePath, nativeFd)
        } catch (e: Throwable) {
            ProxyService.log("[VPN] hev 启动异常: ${e.message}")
            false
        }
        if (!ok) {
            ProxyService.log("[VPN] hev 启动失败")
            try { ParcelFileDescriptor.adoptFd(nativeFd).close() } catch (_: Exception) {}
            try { tunFd?.close() } catch (_: Exception) {}
            tunFd = null
            stopSelf()
            return
        }
        isVpnRunning = true
        ProxyService.log("[VPN] TUN 已建立，全局接管生效（SOCKS5 127.0.0.1:$socksPort）")
        ProxyService.refreshNotification(this)
    }

    /** 完整停止：停 hev 转发 + 关闭 TUN fd（拆除系统 VPN 接口）。 */
    private fun stopVpnInternal() {
        try {
            tproxy.TProxyStopService()
        } catch (_: Throwable) {
        }
        // hev 停止是异步的，稍等它把读循环退出再关 fd，避免 EBADF 报错噪音
        try {
            Thread.sleep(150)
        } catch (_: InterruptedException) {
        }
        try {
            tunFd?.close()
        } catch (_: Throwable) {
        }
        tunFd = null
        if (isVpnRunning) {
            isVpnRunning = false
            ProxyService.log("[VPN] 全局接管已停止")
        }
        if (ProxyService.isRunning) {
            ProxyService.refreshNotification(this)
        } else {
            getSystemService(android.app.NotificationManager::class.java)
                .cancel(ProxyService.NOTIF_ID)
        }
    }
}
