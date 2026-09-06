package com.echos.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * 前台服务：拉起 Go 内核（libxtun.so，即 x-tunnel 的 Android 交叉编译产物），
 * 转发日志，退出时回收进程。与 macOS 版「SwiftUI 壳 + x-tunnel 子进程」同构。
 */
class ProxyService : Service() {

    companion object {
        const val ACTION_START = "com.echos.app.action.START"
        const val ACTION_STOP = "com.echos.app.action.STOP"
        const val CHANNEL_ID = "proxy"
        const val NOTIF_ID = 1
        const val MAX_LOG_LINES = 400

        @Volatile
        var isRunning = false
            private set

        private val logBuffer = ArrayDeque<String>()

        fun logLines(): List<String> = synchronized(logBuffer) { logBuffer.toList() }

        fun log(line: String) {
            synchronized(logBuffer) {
                logBuffer.addLast(line)
                while (logBuffer.size > MAX_LOG_LINES) logBuffer.removeFirst()
            }
        }

        fun clearLogs() = synchronized(logBuffer) { logBuffer.clear() }
    }

    private var process: Process? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopProxy()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                if (!isRunning) startProxy()
                startForegroundCompat()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopProxy()
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, getString(R.string.notif_channel),
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    private fun startForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID, buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIF_ID, buildNotification())
        }
    }

    private fun startProxy() {
        val cfg = ConfigStore.load(this) ?: run {
            log("[App] 配置为空，请先填写并保存")
            return
        }
        if (cfg.addr.isBlank()) {
            log("[App] 服务器地址未填写")
            return
        }

        val bin = File(applicationInfo.nativeLibraryDir, "libxtun.so")
        if (!bin.exists()) {
            log("[App] 找不到内核二进制: ${bin.absolutePath}")
            return
        }

        val args = mutableListOf(
            bin.absolutePath,
            "-l", "socks5://127.0.0.1:${cfg.port},http://127.0.0.1:${cfg.port + 1}",
            "-f", "wss://${cfg.addr}",
            "-n", "2",
            "-ech", cfg.ech,
            "-dns", cfg.doh,
            "-default", if (cfg.global) "all" else "proxy",
        )
        if (cfg.ips.isNotBlank()) args += listOf("-ip", cfg.ips)
        if (cfg.token.isNotBlank()) args += listOf("-token", cfg.token)

        log("[App] 启动内核: ${args.drop(1).joinToString(" ")}")

        try {
            val pb = ProcessBuilder(args).redirectErrorStream(true)
            pb.environment()["HOME"] = filesDir.absolutePath
            val proc = pb.start()
            process = proc
            isRunning = true

            Thread {
                try {
                    BufferedReader(InputStreamReader(proc.inputStream)).useLines { lines ->
                        lines.forEach { log(it) }
                    }
                } catch (_: Exception) {
                }
                val code = proc.waitFor()
                isRunning = false
                log("[App] 内核已退出 (exit=$code)")
                updateNotification()
            }.start()
        } catch (e: Exception) {
            isRunning = false
            log("[App] 启动失败: ${e.message}")
        }
    }

    private fun stopProxy() {
        val p = process ?: return
        process = null
        try {
            p.destroy()
            if (!p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly()
            }
        } catch (_: Exception) {
        }
        if (isRunning) {
            isRunning = false
            log("[App] 代理已停止")
        }
    }

    private fun updateNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification())
    }
}
