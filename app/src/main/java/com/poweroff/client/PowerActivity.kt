package com.poweroff.client

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar

/** 登录路由器并只显示 LuCI 的“关机”页面 */
class PowerActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var progress: ProgressBar
    private lateinit var cfg: Config
    private lateinit var base: String
    private lateinit var target: String
    private var relogged = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_power)

        cfg = Config(this)
        base = RouterClient.normalize(cfg.address) ?: run { openSettings(); return }
        target = "$base/cgi-bin/luci/admin/system/poweroff"

        web = findViewById(R.id.webView)
        progress = findViewById(R.id.progress)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.inflateMenu(R.menu.power_menu)
        toolbar.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.action_refresh -> { relogged = false; start(); true }
                R.id.action_settings -> { openSettings(); true }
                else -> false
            }
        }

        setupWebView()
        start()
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
                progress.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView, url: String) {
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
                                toast(R.string.err_login)
                                openSettings()
                            }
                        } else {
                            view.visibility = View.VISIBLE
                            progress.visibility = View.GONE
                        }
                    }
                }
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    toast(R.string.err_connect)
                    progress.visibility = View.GONE
                }
            }

            // 仅对用户配置的路由器地址接受自签名 HTTPS 证书
            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                val host = Uri.parse(base).host
                if (host != null && host == Uri.parse(error.url).host) handler.proceed() else handler.cancel()
            }
        }
    }

    private fun start() {
        progress.visibility = View.VISIBLE
        web.visibility = View.INVISIBLE
        val address = base
        val user = cfg.username
        val pass = cfg.password

        Thread {
            val r = RouterClient.login(address, user, pass)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (r.ok) {
                    val cm = CookieManager.getInstance()
                    cm.removeAllCookies(null)
                    r.cookies.forEach { cm.setCookie("$address/cgi-bin/luci", it) }
                    cm.flush()
                    web.loadUrl(target)
                } else {
                    toast(if (r.error == "connect") R.string.err_connect else R.string.err_login)
                    openSettings()
                }
            }
        }.start()
    }

    private fun openSettings() {
        startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_EDIT, true))
        finish()
    }

    private fun toast(res: Int) = Toast.makeText(this, res, Toast.LENGTH_LONG).show()

    override fun onDestroy() {
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
