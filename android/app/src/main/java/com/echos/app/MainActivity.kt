package com.echos.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView

class MainActivity : AppCompatActivity() {

    private lateinit var cardsContainer: LinearLayout
    private lateinit var btnStart: MaterialButton
    private lateinit var btnStop: MaterialButton
    private lateinit var statusView: TextView

    private val handler = Handler(Looper.getMainLooper())

    private val vpnPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                startVpn()
            } else {
                ProxyService.log("[VPN] 授权被拒绝，仅本地代理模式运行")
            }
        }

    private val tick = object : Runnable {
        override fun run() {
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
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        statusView = findViewById(R.id.statusView)

        findViewById<View>(R.id.btnAddCard).setOnClickListener {
            val idx = ConfigStore.addCard(this, ConfigStore.EntryCard("", 443))
            openCardEditor(idx)
        }
        findViewById<View>(R.id.btnLogs).setOnClickListener {
            startActivity(Intent(this, LogActivity::class.java))
        }
        findViewById<View>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

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
        rebuildCards()
        handler.post(tick)
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        super.onPause()
    }

    // ==================== 线路卡片 ====================

    private fun rebuildCards() {
        cardsContainer.removeAllViews()
        val cfg = ConfigStore.load(this) ?: ConfigStore.default()
        cfg.cards.forEachIndexed { i, card ->
            val v = layoutInflater.inflate(R.layout.item_entry_card, cardsContainer, false)
            val cardRoot = v.findViewById<MaterialCardView>(R.id.cardRoot)
            val rowText = v.findViewById<TextView>(R.id.cardText)
            val activeTag = v.findViewById<TextView>(R.id.cardActive)

            rowText.text = card.display()
            val active = i == cfg.activeCard
            cardRoot.strokeWidth = if (active) 3 else 1
            cardRoot.strokeColor =
                if (active) Color.parseColor("#0B57D0") else Color.parseColor("#E0E0E0")
            activeTag.visibility = if (active) View.VISIBLE else View.GONE

            cardRoot.setOnClickListener { openCardEditor(i) }
            cardsContainer.addView(v)
        }
    }

    private fun openCardEditor(index: Int) {
        startActivity(
            Intent(this, CardEditActivity::class.java).putExtra("index", index)
        )
    }

    // ==================== 启动 / 停止 ====================

    private fun onStartClicked() {
        val cfg = ConfigStore.load(this) ?: ConfigStore.default()
        val card = cfg.active
        if (cfg.domain.isBlank() || card == null || card.port !in 1..65535) {
            Toast.makeText(
                this, "请先在「设置」填写服务地址，并确保当前线路端口有效",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1
            )
        }
        ProxyService.clearLogs()
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
}
