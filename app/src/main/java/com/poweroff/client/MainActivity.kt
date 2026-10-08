package com.poweroff.client

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.WindowManager
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText

/** 连接设置页：已保存配置时启动后直接进入关机页面 */
class MainActivity : AppCompatActivity() {

    private var monitor: WifiGateway? = null
    private var currentGw: String? = null
    private var programmatic = false
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var etAddress: TextInputEditText
    private lateinit var swAuto: MaterialSwitch
    private lateinit var tvGateway: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val cfg = Config(this)
        val editMode = intent.getBooleanExtra(EXTRA_EDIT, false)

        if (!editMode && cfg.isComplete) {
            startActivity(Intent(this, PowerActivity::class.java))
            finish()
            return
        }

        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(R.layout.activity_main)
        etAddress = findViewById(R.id.etAddress)
        swAuto = findViewById(R.id.swAuto)
        tvGateway = findViewById(R.id.tvGateway)
        val etUser = findViewById<TextInputEditText>(R.id.etUser)
        val etPass = findViewById<TextInputEditText>(R.id.etPass)

        swAuto.isChecked = cfg.autoGateway
        setAddress(RouterClient.display(cfg.address).ifEmpty { "192.168.1.1" })
        etUser.setText(cfg.username)
        etPass.setText(cfg.password)
        pasteOnly(etPass)

        // 手动修改地址 → 自动关闭“自动获取”；重新打开开关会再次填入网关
        etAddress.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (!programmatic && swAuto.isChecked) swAuto.isChecked = false
            }
        })
        swAuto.setOnCheckedChangeListener { _, on -> if (on) fillFromGateway() }

        // 监听 WiFi 切换，自动刷新网关地址
        monitor = WifiGateway(this, false) { gw ->
            currentGw = gw
            updateHint()
            if (swAuto.isChecked) fillFromGateway()
        }.also { it.start() }
        handler.postDelayed({ updateHint() }, 1500)

        findViewById<MaterialButton>(R.id.btnEnter).setOnClickListener {
            val auto = swAuto.isChecked
            val raw = etAddress.text.toString().trim()
            val user = etUser.text.toString().trim()
            val pass = etPass.text.toString()

            if (!(auto && raw.isEmpty()) && RouterClient.normalize(raw) == null) {
                etAddress.error = getString(R.string.err_address)
                return@setOnClickListener
            }
            if (user.isEmpty()) {
                etUser.error = getString(R.string.err_username)
                return@setOnClickListener
            }

            cfg.autoGateway = auto
            cfg.address = raw
            cfg.username = user
            cfg.password = pass

            startActivity(Intent(this, PowerActivity::class.java))
            finish()
        }
    }

    /** 密码框只保留“粘贴”，去掉复制/剪切/分享等其它菜单项 */
    private fun pasteOnly(edit: TextInputEditText) {
        val callback = object : ActionMode.Callback {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                for (i in menu.size() - 1 downTo 0) {
                    val id = menu.getItem(i).itemId
                    if (id != android.R.id.paste && id != android.R.id.pasteAsPlainText) {
                        menu.removeItem(id)
                    }
                }
                return menu.size() > 0
            }

            override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = false
            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean = false
            override fun onDestroyActionMode(mode: ActionMode) {}
        }
        edit.customSelectionActionModeCallback = callback
        edit.customInsertionActionModeCallback = callback
    }

    private fun setAddress(text: String) {
        programmatic = true
        etAddress.setText(text)
        programmatic = false
    }

    private fun fillFromGateway() {
        val gw = currentGw ?: return
        val base = RouterClient.replaceHost(etAddress.text.toString(), gw)
        setAddress(RouterClient.display(base))
    }

    private fun updateHint() {
        val gw = currentGw
        tvGateway.text = if (gw != null) getString(R.string.gateway_current, gw)
        else getString(R.string.gateway_none)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        monitor?.stop()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_EDIT = "edit"
    }
}
