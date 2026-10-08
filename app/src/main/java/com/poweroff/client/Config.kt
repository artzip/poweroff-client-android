package com.poweroff.client

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** 保存路由器地址、用户名、密码（使用 Android Keystore 加密存储） */
class Config(context: Context) {

    private val prefs: SharedPreferences = create(context.applicationContext)

    var address: String
        get() = prefs.getString("address", "") ?: ""
        set(v) = prefs.edit().putString("address", v).apply()

    var username: String
        get() = prefs.getString("username", "root") ?: "root"
        set(v) = prefs.edit().putString("username", v).apply()

    var password: String
        get() = prefs.getString("password", "") ?: ""
        set(v) = prefs.edit().putString("password", v).apply()

    /** 是否自动使用 WiFi 网关作为路由器地址（默认开启） */
    var autoGateway: Boolean
        get() = prefs.getBoolean("auto_gateway", true)
        set(v) = prefs.edit().putBoolean("auto_gateway", v).apply()

    /** 已配置：用户名已填写，且（自动获取网关 或 已填写地址）。OpenWrt 允许空密码 */
    val isComplete: Boolean
        get() = username.isNotBlank() && (autoGateway || address.isNotBlank())

    companion object {
        private const val FILE = "router_cfg"

        private fun create(c: Context): SharedPreferences = try {
            open(c)
        } catch (e: Exception) {
            // 密钥损坏时（如还原备份），清除后重建
            c.deleteSharedPreferences(FILE)
            open(c)
        }

        private fun open(c: Context): SharedPreferences {
            val key = MasterKey.Builder(c)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            return EncryptedSharedPreferences.create(
                c, FILE, key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }
    }
}
