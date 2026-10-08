package com.poweroff.client

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText

/** 连接设置页：已保存配置时启动后直接进入关机页面 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val cfg = Config(this)
        val editMode = intent.getBooleanExtra(EXTRA_EDIT, false)

        if (!editMode && cfg.isComplete) {
            startActivity(Intent(this, PowerActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_main)
        val etAddress = findViewById<TextInputEditText>(R.id.etAddress)
        val etUser = findViewById<TextInputEditText>(R.id.etUser)
        val etPass = findViewById<TextInputEditText>(R.id.etPass)

        etAddress.setText(cfg.address.ifEmpty { "192.168.1.1" })
        etUser.setText(cfg.username)
        etPass.setText(cfg.password)

        findViewById<MaterialButton>(R.id.btnEnter).setOnClickListener {
            val address = etAddress.text.toString().trim()
            val user = etUser.text.toString().trim()
            val pass = etPass.text.toString()

            if (RouterClient.normalize(address) == null) {
                etAddress.error = getString(R.string.err_address)
                return@setOnClickListener
            }
            if (user.isEmpty()) {
                etUser.error = getString(R.string.err_username)
                return@setOnClickListener
            }

            cfg.address = address
            cfg.username = user
            cfg.password = pass

            startActivity(Intent(this, PowerActivity::class.java))
            finish()
        }
    }

    companion object {
        const val EXTRA_EDIT = "edit"
    }
}
