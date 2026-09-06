package com.echos.app

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton

/** 分应用代理：选择走代理（或排除）的应用列表。 */
class AppPickerActivity : AppCompatActivity() {

    private val selected = mutableSetOf<String>()
    private lateinit var adapter: AppListAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_picker)

        val mode = intent.getStringExtra("mode") ?: "allow"
        selected.addAll(intent.getStringArrayListExtra("selected") ?: emptyList())

        findViewById<TextView>(R.id.appPickerTitle).text =
            if (mode == "allow") "选择走代理的应用" else "选择不走代理的应用"

        val pm = packageManager
        val apps = pm.getInstalledApplications(0)
            .filter {
                (it.flags and ApplicationInfo.FLAG_ENABLED) != 0 &&
                    pm.getLaunchIntentForPackage(it.packageName) != null
            }
            .sortedBy { pm.getApplicationLabel(it).toString().lowercase() }
            .map { AppItem(pm.getApplicationLabel(it).toString(), it.packageName) }

        adapter = AppListAdapter(apps, selected)
        val rv = findViewById<RecyclerView>(R.id.appList)
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter

        findViewById<EditText>(R.id.appSearch).addTextChangedListener(
            object : android.text.TextWatcher {
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {
                    adapter.filter(s?.toString() ?: "")
                }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {}
            }
        )

        findViewById<MaterialButton>(R.id.btnAppDone).setOnClickListener {
            ConfigStore.setAppFilter(this, null, selected.toList())
            Toast.makeText(this, "已选择 ${selected.size} 个应用", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}

data class AppItem(val label: String, val pkg: String)

class AppListAdapter(
    private val all: List<AppItem>,
    private val selected: MutableSet<String>
) : RecyclerView.Adapter<AppListAdapter.VH>() {

    private var visible = all.toList()

    class VH(val root: com.google.android.material.checkbox.MaterialCheckBox) :
        RecyclerView.ViewHolder(root)

    fun filter(q: String) {
        visible = if (q.isBlank()) all.toList()
        else all.filter {
            it.label.contains(q, true) || it.pkg.contains(q, true)
        }
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH =
        VH(com.google.android.material.checkbox.MaterialCheckBox(parent.context))

    override fun getItemCount(): Int = visible.size

    override fun onBindViewHolder(h: VH, pos: Int) {
        val item = visible[pos]
        val cb = h.root
        cb.text = "${item.label}\n${item.pkg}"
        cb.isChecked = item.pkg in selected
        cb.setOnCheckedChangeListener(null)
        cb.isChecked = item.pkg in selected
        cb.setOnCheckedChangeListener { _, checked ->
            if (checked) selected.add(item.pkg) else selected.remove(item.pkg)
        }
    }
}
