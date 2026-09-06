package com.echos.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText

class MainActivity : AppCompatActivity() {

    private lateinit var editAddr: TextInputEditText
    private lateinit var editPort: TextInputEditText
    private lateinit var editDoh: TextInputEditText
    private lateinit var editEch: TextInputEditText
    private lateinit var editIps: TextInputEditText
    private lateinit var editToken: TextInputEditText
    private lateinit var switchGlobal: MaterialSwitch
    private lateinit var btnStart: MaterialButton
    private lateinit var btnStop: MaterialButton
    private lateinit var statusView: TextView
    private lateinit var logView: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var lastLogSize = -1

    private val tick = object : Runnable {
        override fun run() {
            val lines = ProxyService.logLines()
            if (lines.size != lastLogSize) {
                lastLogSize = lines.size
                logView.text = lines.joinToString("\n")
            }
            val running = ProxyService.isRunning
            statusView.text = if (running) getString(R.string.running) else getString(R.string.stopped)
            btnStart.isEnabled = !running
            btnStop.isEnabled = running
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        editAddr = findViewById(R.id.editAddr)
        editPort = findViewById(R.id.editPort)
        editDoh = findViewById(R.id.editDoh)
        editEch = findViewById(R.id.editEch)
        editIps = findViewById(R.id.editIps)
        editToken = findViewById(R.id.editToken)
        switchGlobal = findViewById(R.id.switchGlobal)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        statusView = findViewById(R.id.statusView)
        logView = findViewById(R.id.logView)

        ConfigStore.load(this)?.let { fill(it) }

        btnStart.setOnClickListener {
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
        }

        btnStop.setOnClickListener {
            startService(
                Intent(this, ProxyService::class.java).setAction(ProxyService.ACTION_STOP)
            )
        }

        if (Build.VERSION.SDK_INT >= 33) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
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

    private fun fill(s: ConfigStore.Server) {
        editAddr.setText(s.addr)
        editPort.setText(s.port.toString())
        editDoh.setText(s.doh)
        editEch.setText(s.ech)
        editIps.setText(s.ips)
        editToken.setText(s.token)
        switchGlobal.isChecked = s.global
    }

    private fun current(): ConfigStore.Server = ConfigStore.Server(
        addr = editAddr.text?.toString()?.trim() ?: "",
        port = editPort.text?.toString()?.toIntOrNull() ?: 30000,
        doh = editDoh.text?.toString()?.trim() ?: "https://dns.alidns.com/dns-query",
        ech = editEch.text?.toString()?.trim() ?: "cloudflare-ech.com",
        ips = editIps.text?.toString()?.trim() ?: "",
        token = editToken.text?.toString()?.trim() ?: "",
        global = switchGlobal.isChecked,
    )

    private fun saveConfig() {
        try {
            ConfigStore.save(this, current())
        } catch (_: Exception) {
        }
    }
}
