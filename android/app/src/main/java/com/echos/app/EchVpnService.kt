package com.echos.app

import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.io.File

/**
 * VPN 全局接管：VpnService 建立 TUN，hev-socks5-tunnel（JNI）把全部流量
 * 转 SOCKS5(127.0.0.1:1080) → 内核 → WSS+ECH → Worker。
 *
 * 关键点：addDisallowedApplication(自身 UID) —— 内核子进程与本 App 同 UID，
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
                stopVpn()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                startForegroundCompat()
                if (!isVpnRunning) startVpn()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    private fun startForegroundCompat() {
        val notif = NotificationCompat.Builder(this, ProxyService.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("EchOS VPN 运行中")
            .setContentText("全部流量经 ECH 隧道转发")
            .setOngoing(true)
            .setContentIntent(
                android.app.PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java),
                    android.app.PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                ProxyService.NOTIF_ID + 1, notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(ProxyService.NOTIF_ID + 1, notif)
        }
    }

    private fun startVpn() {
        val cfg = ConfigStore.load(this) ?: run {
            ProxyService.log("[VPN] 配置为空")
            return
        }
        val socksPort = cfg.port

        // TUN：全局路由 + mapdns（fake-IP DNS）
        val builder = Builder()
            .setSession("EchOS VPN")
            .setMtu(8500)
            .addAddress(TUN_ADDR, 32)
            .addAddress(TUN_V6, 128)
            .addDnsServer(TUN_DNS)
            .addRoute("0.0.0.0", 0)
            .addRoute("::", 0)
        try {
            // 核心：自身包名的流量（内核 WSS/DoH、hev 的控制连接）绕过 TUN
            builder.addDisallowedApplication(packageName)
        } catch (e: Exception) {
            ProxyService.log("[VPN] 排除自身失败: ${e.message}")
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

        // hev 接管 fd（内部会 close）
        val fd = pfd.detachFd()
        tunFd = null
        val ok = try {
            tproxy.TProxyStartService(conf.absolutePath, fd)
        } catch (e: Throwable) {
            ProxyService.log("[VPN] hev 启动异常: ${e.message}")
            false
        }
        if (!ok) {
            ProxyService.log("[VPN] hev 启动失败")
            try { ParcelFileDescriptor.fromFd(fd).close() } catch (_: Exception) {}
            stopSelf()
            return
        }
        isVpnRunning = true
        ProxyService.log("[VPN] TUN 已建立，全局接管生效（SOCKS5 127.0.0.1:$socksPort）")
    }

    private fun stopVpn() {
        if (!isVpnRunning) return
        isVpnRunning = false
        try {
            tproxy.TProxyStopService()
        } catch (_: Throwable) {
        }
        ProxyService.log("[VPN] 全局接管已停止")
    }
}
