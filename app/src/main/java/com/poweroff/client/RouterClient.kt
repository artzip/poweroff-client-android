package com.poweroff.client

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/** 通过 LuCI 登录接口获取会话 Cookie（兼容旧版与新版 LuCI） */
object RouterClient {

    class LoginResult(val ok: Boolean, val cookies: List<String>, val error: String?)

    /** 规范化地址：自动补 http://，去掉末尾斜杠 */
    fun normalize(input: String): String? {
        var s = input.trim().trimEnd('/')
        if (s.isEmpty()) return null
        if (!s.contains("://")) s = "http://$s"
        return try {
            val u = URL(s)
            if (u.host.isNullOrEmpty()) null else s
        } catch (e: Exception) {
            null
        }
    }

    /** 保留已保存地址的协议和端口，仅把主机替换为新的网关地址 */
    fun replaceHost(saved: String, host: String): String {
        val n = normalize(saved) ?: return "http://$host"
        return try {
            val u = URL(n)
            val port = if (u.port != -1) ":${u.port}" else ""
            "${u.protocol}://$host$port"
        } catch (e: Exception) {
            "http://$host"
        }
    }

    /** 输入框显示用：省略默认的 http:// */
    fun display(base: String): String = base.removePrefix("http://")

    fun login(base: String, user: String, pass: String): LoginResult {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL("$base/cgi-bin/luci").openConnection() as HttpURLConnection
            if (conn is HttpsURLConnection) trustAll(conn)
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 6000
            conn.readTimeout = 8000
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")

            val body = "luci_username=${enc(user)}&luci_password=${enc(pass)}"
            conn.outputStream.use { it.write(body.toByteArray()) }

            val code = conn.responseCode
            val cookies = conn.headerFields
                .filterKeys { it != null && it.equals("Set-Cookie", ignoreCase = true) }
                .values.flatten()

            if (code in 200..399 && cookies.any { it.trim().startsWith("sysauth") }) {
                LoginResult(true, cookies, null)
            } else if (code == 403 || code == 200) {
                LoginResult(false, emptyList(), "login")
            } else {
                LoginResult(false, emptyList(), "http $code")
            }
        } catch (e: Exception) {
            LoginResult(false, emptyList(), "connect")
        } finally {
            conn?.disconnect()
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** 路由器常使用自签名证书：仅用于用户自己配置的路由器地址 */
    private fun trustAll(c: HttpsURLConnection) {
        val tm = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        })
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, tm, SecureRandom())
        c.sslSocketFactory = ctx.socketFactory
        c.hostnameVerifier = HostnameVerifier { _, _ -> true }
    }
}
