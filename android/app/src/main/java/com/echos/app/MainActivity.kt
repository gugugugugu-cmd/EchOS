package com.echos.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textfield.TextInputEditText

class MainActivity : AppCompatActivity() {

    private lateinit var cardsContainer: LinearLayout
    private lateinit var cardsScroll: ScrollView
    private lateinit var btnStart: MaterialButton
    private lateinit var btnStop: MaterialButton
    private lateinit var statusView: TextView
    private lateinit var logView: TextView

    private val cards = mutableListOf(ConfigStore.EntryCard("", 443))
    private var activeCard = 0

    private val handler = Handler(Looper.getMainLooper())
    private var lastLogSize = -1

    private val vpnPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                startVpn()
            } else {
                ProxyService.log("[VPN] 授权被拒绝，仅本地代理模式运行")
            }
        }

    private val settingsLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            // 设置页返回后，若代理在跑则重启内核以应用新设置
            restartIfRunning()
        }

    private val tick = object : Runnable {
        override fun run() {
            val lines = ProxyService.logLines()
            if (lines.size != lastLogSize) {
                lastLogSize = lines.size
                logView.text = lines.joinToString("\n")
            }
            val running = ProxyService.isRunning
            val vpn = EchVpnService.isVpnRunning
            statusView.text = when {
                running && vpn -> "运行中 · VPN 全局接管"
                running -> "运行中 · 本地代理"
                vpn -> "仅 VPN（内核未运行）"
                else -> getString(R.string.stopped)
            }
            btnStart.isEnabled = !running && !vpn
            btnStop.isEnabled = running || vpn
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        cardsContainer = findViewById(R.id.cardsContainer)
        cardsScroll = findViewById(R.id.cardsScroll)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        statusView = findViewById(R.id.statusView)
        logView = findViewById(R.id.logView)

        findViewById<View>(R.id.btnAddCard).setOnClickListener {
            addCard(ConfigStore.EntryCard("", 443))
        }
        findViewById<View>(R.id.btnSettings).setOnClickListener {
            settingsLauncher.launch(Intent(this, SettingsActivity::class.java))
        }

        ConfigStore.load(this)?.let { cfg ->
            if (cfg.cards.isNotEmpty()) {
                cards.clear()
                cards.addAll(cfg.cards)
            }
            activeCard = cfg.activeCard.coerceIn(0, cards.size - 1)
        }
        rebuildCards()

        btnStart.setOnClickListener { onStartClicked() }
        btnStop.setOnClickListener {
            startService(
                Intent(this, EchVpnService::class.java).setAction(EchVpnService.ACTION_STOP)
            )
            startService(
                Intent(this, ProxyService::class.java).setAction(ProxyService.ACTION_STOP)
            )
        }

        if (Build.VERSION.SDK_INT >= 33) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1
            )
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(tick)
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        saveConfig()
        super.onPause()
    }

    // ==================== 线路卡片 ====================

    private fun rebuildCards() {
        cardsContainer.removeAllViews()
        cards.forEachIndexed { i, _ -> addCardView(i) }
        highlightActive()
    }

    private fun addCardView(index: Int) {
        val v = LayoutInflater.from(this)
            .inflate(R.layout.item_entry_card, cardsContainer, false)
        val card = v.findViewById<MaterialCardView>(R.id.cardRoot)
        val title = v.findViewById<TextView>(R.id.cardTitle)
        val editIps = v.findViewById<TextInputEditText>(R.id.editIps)
        val editPort = v.findViewById<TextInputEditText>(R.id.editPort)
        val delete = v.findViewById<ImageButton>(R.id.cardDelete)
        val activeTag = v.findViewById<TextView>(R.id.cardActive)

        val e = cards[index]
        editIps.setText(e.ips)
        editPort.setText(e.port.toString())
        title.text = "线路 ${index + 1}"

        fun commit() {
            val i = cardsContainer.indexOfChild(v)
            if (i < 0 || i >= cards.size) return
            cards[i] = cards[i].copy(
                ips = editIps.text?.toString()?.trim() ?: "",
                port = editPort.text?.toString()?.toIntOrNull() ?: 443
            )
            saveConfig()
        }
        editIps.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) commit() }
        editPort.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) commit() }

        // 点卡片空白处切换使用线路
        card.setOnClickListener {
            val i = cardsContainer.indexOfChild(v)
            if (i >= 0 && i != activeCard) {
                activeCard = i
                highlightActive()
                saveConfig()
                restartIfRunning()
            }
        }

        delete.setOnClickListener {
            val i = cardsContainer.indexOfChild(v)
            if (cards.size <= 1) {
                Toast.makeText(this, "至少保留一个线路卡片", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            cards.removeAt(i)
            cardsContainer.removeView(v)
            if (activeCard >= cards.size) activeCard = cards.size - 1
            renumber()
            highlightActive()
            saveConfig()
        }
        cardsContainer.addView(v)
    }

    private fun addCard(e: ConfigStore.EntryCard) {
        cards.add(e)
        addCardView(cards.size - 1)
        highlightActive()
        saveConfig()
        cardsScroll.post { cardsScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun renumber() {
        for (i in 0 until cardsContainer.childCount) {
            cardsContainer.getChildAt(i)
                .findViewById<TextView>(R.id.cardTitle).text = "线路 ${i + 1}"
        }
    }

    private fun highlightActive() {
        for (i in 0 until cardsContainer.childCount) {
            val card = cardsContainer.getChildAt(i)
                .findViewById<MaterialCardView>(R.id.cardRoot)
            val active = i == activeCard
            card.strokeWidth = if (active) 3 else 1
            card.strokeColor = ColorStateList.valueOf(
                if (active) Color.parseColor("#0B57D0") else Color.parseColor("#E0E0E0")
            )
            card.findViewById<TextView>(R.id.cardActive).visibility =
                if (active) View.VISIBLE else View.GONE
        }
    }

    // ==================== 配置 ====================

    private fun saveConfig() {
        val old = ConfigStore.load(this)
        val s = ConfigStore.Server(
            domain = old?.domain ?: "",
            ech = old?.ech ?: "cloudflare-ech.com",
            doh = old?.doh ?: "https://dns.alidns.com/dns-query",
            token = old?.token ?: "",
            listenAddr = old?.listenAddr ?: "127.0.0.1",
            listenPort = old?.listenPort ?: 30000,
            global = old?.global ?: true,
            vpn = old?.vpn ?: true,
            cards = cards.toList(),
            activeCard = activeCard.coerceIn(0, (cards.size - 1).coerceAtLeast(0))
        )
        try {
            ConfigStore.save(this, s)
        } catch (_: Exception) {
        }
    }

    // ==================== 启动 / 停止 ====================

    private fun onStartClicked() {
        val cfg = ConfigStore.load(this) ?: ConfigStore.default()
        val card = cfg.active
        if (cfg.domain.isBlank() || card == null || card.port !in 1..65535) {
            Toast.makeText(
                this, "请先在「⚙ 设置」填写服务地址，并确保当前线路端口有效",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        saveConfig()
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1
            )
        }
        ProxyService.clearLogs()
        lastLogSize = -1
        ContextCompat.startForegroundService(
            this, Intent(this, ProxyService::class.java)
                .setAction(ProxyService.ACTION_START)
        )
        if (cfg.vpn) {
            val prepare = VpnService.prepare(this)
            if (prepare != null) {
                vpnPermissionLauncher.launch(prepare)
            } else {
                startVpn()
            }
        }
    }

    private fun startVpn() {
        ContextCompat.startForegroundService(
            this, Intent(this, EchVpnService::class.java)
                .setAction(EchVpnService.ACTION_START)
        )
    }

    private fun restartIfRunning() {
        if (!ProxyService.isRunning) return
        startService(
            Intent(this, ProxyService::class.java).setAction(ProxyService.ACTION_STOP)
        )
        handler.postDelayed({
            ContextCompat.startForegroundService(
                this, Intent(this, ProxyService::class.java)
                    .setAction(ProxyService.ACTION_START)
            )
        }, 600)
    }
}
