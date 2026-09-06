package com.echos.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 配置持久化：设置页（共享参数）+ 主界面多线路卡片。 */
object ConfigStore {
    private const val FILE = "server_config.json"

    /** 一条线路：优选IP(域名) + 服务端口。 */
    data class EntryCard(
        val ips: String,   // 优选 IP（域名），逗号分隔，可空（走域名解析）
        val port: Int      // 服务端口
    )

    data class Server(
        val domain: String,      // 服务地址（域名，设置页）
        val ech: String,         // ECH 公钥查询域名
        val doh: String,         // ECH DoH 服务器
        val token: String,       // 身份令牌
        val listenAddr: String,  // 监听地址
        val listenPort: Int,     // 本地 SOCKS5 端口（HTTP 自动 +1）
        val global: Boolean,     // true=全局 false=规则分流
        val vpn: Boolean,        // VPN 全局接管
        val cards: List<EntryCard>,
        val activeCard: Int      // 当前使用线路下标
    ) {
        val active: EntryCard?
            get() = cards.getOrNull(activeCard)

        fun isValid(): Boolean =
            domain.isNotBlank() &&
                active != null && active!!.port in 1..65535 &&
                listenPort in 1024..65535
    }

    fun load(ctx: Context): Server? {
        val file = ctx.getFileStreamPath(FILE)
        if (!file.exists()) return null
        return try {
            ctx.openFileInput(FILE).bufferedReader().use { r ->
                val o = JSONObject(r.readText())

                // 旧版兼容：addr = "域名:端口"
                val domain: String
                val legacyPort: Int
                if (o.has("domain")) {
                    domain = o.getString("domain")
                    legacyPort = 443
                } else {
                    val addr = o.optString("addr", "")
                    val idx = addr.lastIndexOf(':')
                    domain = if (idx > 0) addr.substring(0, idx) else addr
                    legacyPort = if (idx > 0) addr.substring(idx + 1).toIntOrNull() ?: 443 else 443
                }

                val cards = mutableListOf<EntryCard>()
                val arr = o.optJSONArray("cards")
                if (arr != null && arr.length() > 0) {
                    for (i in 0 until arr.length()) {
                        val c = arr.getJSONObject(i)
                        cards.add(EntryCard(c.optString("ips", ""), c.optInt("port", 443)))
                    }
                } else {
                    cards.add(EntryCard(o.optString("ips", ""), legacyPort))
                }

                Server(
                    domain = domain,
                    ech = o.optString("ech", "cloudflare-ech.com"),
                    doh = o.optString("doh", "https://dns.alidns.com/dns-query"),
                    token = o.optString("token", ""),
                    listenAddr = o.optString("listenAddr", "127.0.0.1"),
                    listenPort = o.optInt("listenPort", o.optInt("port", 30000)),
                    global = o.optBoolean("global", true),
                    vpn = o.optBoolean("vpn", true),
                    cards = cards,
                    activeCard = o.optInt("activeCard", 0)
                )
            }
        } catch (e: Exception) {
            null
        }
    }

    fun save(ctx: Context, s: Server) {
        val o = JSONObject()
        o.put("domain", s.domain)
        o.put("ech", s.ech)
        o.put("doh", s.doh)
        o.put("token", s.token)
        o.put("listenAddr", s.listenAddr)
        o.put("listenPort", s.listenPort)
        o.put("global", s.global)
        o.put("vpn", s.vpn)
        val cardsArr = JSONArray()
        s.cards.forEach { c ->
            cardsArr.put(JSONObject().put("ips", c.ips).put("port", c.port))
        }
        o.put("cards", cardsArr)
        o.put("activeCard", s.activeCard)
        ctx.openFileOutput(FILE, Context.MODE_PRIVATE).use { out ->
            out.write(o.toString().toByteArray())
        }
    }

    /** 生成内核命令行参数（不含二进制路径），配置非法返回 null。 */
    fun buildArgs(s: Server): List<String>? {
        val card = s.active ?: return null
        if (s.domain.isBlank() || card.port !in 1..65535) return null
        val listen = s.listenAddr.ifBlank { "127.0.0.1" }
        val args = mutableListOf(
            "-l", "socks5://$listen:${s.listenPort},http://$listen:${s.listenPort + 1}",
            "-f", "wss://${s.domain}:${card.port}",
            "-n", "2",
            "-ech", s.ech,
            "-dns", s.doh,
            "-default", if (s.global) "all" else "proxy"
        )
        if (card.ips.isNotBlank()) args += listOf("-ip", card.ips)
        if (s.token.isNotBlank()) args += listOf("-token", s.token)
        return args
    }

    fun default(): Server = Server(
        domain = "", ech = "cloudflare-ech.com",
        doh = "https://dns.alidns.com/dns-query", token = "",
        listenAddr = "127.0.0.1", listenPort = 30000,
        global = true, vpn = true,
        cards = listOf(EntryCard("", 443)), activeCard = 0
    )
}
