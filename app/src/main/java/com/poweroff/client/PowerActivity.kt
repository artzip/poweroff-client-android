package com.poweroff.client

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar

/** 登录路由器并只显示 LuCI 的“关机”页面；自动模式下随 WiFi 切换更新路由器地址 */
class PowerActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var cfg: Config
    private lateinit var gateway: WifiGateway

    private var base = ""
    private var target = ""
    private var auto = false
    private var relogged = false
    private var generation = 0
    private val handler = Handler(Looper.getMainLooper())

    private val noWifi = Runnable {
        if (auto && gateway.gateway == null) showStatus(getString(R.string.status_no_wifi))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_power)

        cfg = Config(this)
        auto = cfg.autoGateway

        web = findViewById(R.id.webView)
        progress = findViewById(R.id.progress)
        status = findViewById(R.id.status)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.inflateMenu(R.menu.power_menu)
        toolbar.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.action_refresh -> { refresh(); true }
                R.id.action_settings -> { openSettings(); true }
                else -> false
            }
        }

        setupWebView()

        // 始终监听 WiFi：自动模式用于获取/更新网关；同时把网络绑定到 WiFi，保证能访问局域网
        gateway = WifiGateway(this, true) { gw -> if (auto) onGateway(gw) }
        gateway.start()

        if (auto) {
            progress.visibility = View.VISIBLE
            handler.postDelayed(noWifi, 1500)
        } else {
            val b = RouterClient.normalize(cfg.address)
            if (b == null) {
                openSettings()
                return
            }
            setBase(b)
            start()
        }
    }

    /** 自动模式：WiFi 网关变化（连接 / 切换 / 断开） */
    private fun onGateway(gw: String?) {
        handler.removeCallbacks(noWifi)
        if (gw == null) {
            base = ""
            generation++
            showStatus(getString(R.string.status_no_wifi))
            return
        }
        val nb = RouterClient.replaceHost(cfg.address, gw)
        cfg.address = nb
        setBase(nb)
        relogged = false
        start()
    }

    private fun setBase(b: String) {
        base = b
        target = "$b/cgi-bin/luci/admin/system/poweroff"
    }

    private fun refresh() {
        relogged = false
        if (base.isEmpty()) showStatus(getString(R.string.status_no_wifi)) else start()
    }

    private fun showStatus(msg: String) {
        web.visibility = View.INVISIBLE
        progress.visibility = View.GONE
        status.text = msg
        status.visibility = View.VISIBLE
    }

    private fun setupWebView() {
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.setSupportZoom(false)
        CookieManager.getInstance().setAcceptCookie(true)

        web.webViewClient = object : WebViewClient() {

            // 只允许停留在关机页面，禁止跳转到路由器其它页面
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                return !request.url.toString().startsWith(target)
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                view.visibility = View.INVISIBLE
                status.visibility = View.GONE
                progress.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (base.isEmpty()) return
                view.evaluateJavascript(HIDE_JS) {
                    // 若仍是登录页（会话失效），重新登录一次
                    view.evaluateJavascript(
                        "(function(){return !!document.querySelector('input[name=luci_password]');})()"
                    ) { r ->
                        if (r == "true") {
                            if (!relogged) {
                                relogged = true
                                start()
                            } else {
                                showStatus(getString(R.string.status_login))
                            }
                        } else if (base.isNotEmpty()) {
                            view.visibility = View.VISIBLE
                            progress.visibility = View.GONE
                        }
                    }
                }
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame && base.isNotEmpty()) {
                    showStatus(getString(R.string.status_connect, base))
                }
            }

            // 仅对用户配置/自动获取的路由器地址接受自签名 HTTPS 证书
            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                val host = Uri.parse(base).host
                if (host != null && host == Uri.parse(error.url).host) handler.proceed() else handler.cancel()
            }
        }
    }

    private fun start() {
        if (base.isEmpty()) return
        status.visibility = View.GONE
        progress.visibility = View.VISIBLE
        web.visibility = View.INVISIBLE

        val gen = ++generation
        val address = base
        val user = cfg.username
        val pass = cfg.password

        Thread {
            val r = RouterClient.login(address, user, pass)
            runOnUiThread {
                // 期间已切换网络或被新的请求取代，丢弃旧结果
                if (isFinishing || isDestroyed || gen != generation) return@runOnUiThread
                if (r.ok) {
                    val cm = CookieManager.getInstance()
                    cm.removeAllCookies(null)
                    r.cookies.forEach { cm.setCookie("$address/cgi-bin/luci", it) }
                    cm.flush()
                    web.loadUrl(target, mapOf("Accept-Language" to "zh-CN,zh;q=0.9"))
                } else if (r.error == "connect") {
                    showStatus(getString(R.string.status_connect, address))
                } else {
                    showStatus(getString(R.string.status_login))
                }
            }
        }.start()
    }

    private fun openSettings() {
        startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_EDIT, true))
        finish()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (::gateway.isInitialized) gateway.stop()
        if (::web.isInitialized) web.destroy()
        super.onDestroy()
    }

    companion object {
        /** 隐藏路由器的菜单、页眉、页脚，仅保留关机内容。不同主题如有残留可在此补充选择器 */
        private const val HIDE_JS = """(function(){
var s=document.getElementById('po-hide');
if(!s){s=document.createElement('style');s.id='po-hide';
s.textContent='header,#header,.main-left,#mainmenu,#menubar,footer,nav,.sidebar,.navbar,.tabmenu,.darkMask{display:none!important}'
+'.main-right,.main,#maincontent{margin:0!important;width:100%!important;max-width:100%!important;left:0!important}'
+'body{padding-top:0!important}';
document.head.appendChild(s);}
})();"""
    }
}
