package com.poweroff.client

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import java.net.Inet4Address

/**
 * 监听 WiFi 连接，取得当前 WiFi 的 IPv4 默认网关（即路由器地址）。
 * 切换 WiFi 时自动回调 [onChange]（主线程）。仅关注 WiFi，不受移动数据影响。
 *
 * @param bindProcess 为 true 时把应用网络绑定到 WiFi，
 *                    避免“WiFi 无外网时系统改走移动数据”导致访问不了局域网路由器。
 */
class WifiGateway(
    context: Context,
    private val bindProcess: Boolean,
    private val onChange: (String?) -> Unit
) {
    private val cm = context.applicationContext
        .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val main = Handler(Looper.getMainLooper())
    private var network: Network? = null
    private var started = false

    /** 当前 WiFi 网关地址；未连接 WiFi 时为 null */
    var gateway: String? = null
        private set

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(n: Network) {
            main.post { select(n, cm.getLinkProperties(n)) }
        }

        override fun onLinkPropertiesChanged(n: Network, lp: LinkProperties) {
            main.post { if (network == null || n == network) select(n, lp) }
        }

        override fun onLost(n: Network) {
            main.post {
                if (started && n == network) {
                    network = null
                    bind(null)
                    publish(null)
                }
            }
        }
    }

    fun start() {
        if (started) return
        started = true
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) // 无外网的 WiFi 也要识别
            .build()
        try {
            cm.registerNetworkCallback(request, callback)
        } catch (e: Exception) {
            started = false
        }
    }

    fun stop() {
        if (!started) return
        started = false
        try {
            cm.unregisterNetworkCallback(callback)
        } catch (e: Exception) {
        }
        bind(null)
    }

    private fun select(n: Network, lp: LinkProperties?) {
        if (!started) return
        network = n
        bind(n)
        publish(gatewayOf(lp))
    }

    private fun publish(g: String?) {
        if (g != gateway) {
            gateway = g
            onChange(g)
        }
    }

    private fun bind(n: Network?) {
        if (!bindProcess) return
        try {
            cm.bindProcessToNetwork(n)
        } catch (e: Exception) {
        }
    }

    private fun gatewayOf(lp: LinkProperties?): String? =
        lp?.routes
            ?.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }
            ?.gateway?.hostAddress
}
