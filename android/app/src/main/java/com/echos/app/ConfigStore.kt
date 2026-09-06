package com.echos.app

import android.content.Context
import org.json.JSONObject

/** 服务器配置的持久化。存 JSON 到应用私有目录，重启不丢。 */
object ConfigStore {
    private const val FILE = "server_config.json"

    data class Server(
        val addr: String,      // 域名:端口
        val port: Int,         // 本地 SOCKS5 端口（HTTP 自动 +1）
        val doh: String,       // ECH DoH 服务器
        val ech: String,       // ECH 公钥查询域名
        val ips: String,       // 优选 IP，逗号分隔
        val token: String,     // 身份令牌
        val global: Boolean,   // true=全局 false=规则分流
    )

    fun load(ctx: Context): Server? {
        val file = ctx.getFileStreamPath(FILE)
        if (!file.exists()) return null
        return try {
            ctx.openFileInput(FILE).bufferedReader().use { r ->
                val o = JSONObject(r.readText())
                Server(
                    addr = o.getString("addr"),
                    port = o.getInt("port"),
                    doh = o.getString("doh"),
                    ech = o.getString("ech"),
                    ips = o.optString("ips", ""),
                    token = o.optString("token", ""),
                    global = o.optBoolean("global", true),
                )
            }
        } catch (e: Exception) {
            null
        }
    }

    fun save(ctx: Context, s: Server) {
        val o = JSONObject()
        o.put("addr", s.addr)
        o.put("port", s.port)
        o.put("doh", s.doh)
        o.put("ech", s.ech)
        o.put("ips", s.ips)
        o.put("token", s.token)
        o.put("global", s.global)
        ctx.openFileOutput(FILE, Context.MODE_PRIVATE).use { out ->
            out.write(o.toString().toByteArray())
        }
    }
}
