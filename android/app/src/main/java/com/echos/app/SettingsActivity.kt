package com.echos.app

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText

/** 设置页：服务地址 / 监听地址与端口 / ECH / DOH / TOKEN / 模式开关。 */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val editDomain = findViewById<TextInputEditText>(R.id.editDomain)
        val editListenAddr = findViewById<TextInputEditText>(R.id.editListenAddr)
        val editListenPort = findViewById<TextInputEditText>(R.id.editListenPort)
        val editEch = findViewById<TextInputEditText>(R.id.editEch)
        val editDoh = findViewById<TextInputEditText>(R.id.editDoh)
        val editToken = findViewById<TextInputEditText>(R.id.editToken)
        val switchGlobal = findViewById<MaterialSwitch>(R.id.switchGlobal)
        val switchVpn = findViewById<MaterialSwitch>(R.id.switchVpn)
        val btnSave = findViewById<MaterialButton>(R.id.btnSave)

        ConfigStore.load(this)?.let { cfg ->
            editDomain.setText(cfg.domain)
            editListenAddr.setText(cfg.listenAddr)
            editListenPort.setText(cfg.listenPort.toString())
            editEch.setText(cfg.ech)
            editDoh.setText(cfg.doh)
            editToken.setText(cfg.token)
            switchGlobal.isChecked = cfg.global
            switchVpn.isChecked = cfg.vpn
        }

        btnSave.setOnClickListener {
            val old = ConfigStore.load(this)
            val s = ConfigStore.Server(
                domain = editDomain.text?.toString()?.trim() ?: "",
                ech = editEch.text?.toString()?.trim() ?: "cloudflare-ech.com",
                doh = editDoh.text?.toString()?.trim() ?: "https://dns.alidns.com/dns-query",
                token = editToken.text?.toString()?.trim() ?: "",
                listenAddr = editListenAddr.text?.toString()?.trim() ?: "127.0.0.1",
                listenPort = editListenPort.text?.toString()?.toIntOrNull() ?: 30000,
                global = switchGlobal.isChecked,
                vpn = switchVpn.isChecked,
                cards = old?.cards ?: listOf(ConfigStore.EntryCard("", 443)),
                activeCard = old?.activeCard ?: 0
            )
            try {
                ConfigStore.save(this, s)
                Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
                finish()
            } catch (e: Exception) {
                Toast.makeText(this, "保存失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
