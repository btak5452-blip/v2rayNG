package com.v2ray.ang.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.core.LauncherManager
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.helper.MessageHelper
import org.json.JSONObject
import java.net.URLEncoder

class TunnelActivity : ComponentActivity() {

    private lateinit var web: WebView

    private val vpnLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            if (r.resultCode == RESULT_OK) LauncherManager.startService(this)
            else push("error", "Izin VPN ditolak.")
        }

    private val notifLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { askVpn() }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            when (i?.getIntExtra("key", 0)) {
                AppConfig.MSG_STATE_RUNNING,
                AppConfig.MSG_STATE_START_SUCCESS -> push("on", "")

                AppConfig.MSG_STATE_NOT_RUNNING,
                AppConfig.MSG_STATE_STOP_SUCCESS -> push("off", "")

                AppConfig.MSG_STATE_START_FAILURE -> {
                    val m = try { i.getStringExtra("content") } catch (e: Exception) { null }
                    push("error", m ?: "Gagal menyambung.")
                }
            }
        }
    }

    inner class Bridge {
        @JavascriptInterface
        fun connect(json: String) {
            runOnUiThread { prepare(json) }
        }

        @JavascriptInterface
        fun disconnect() {
            runOnUiThread { LauncherManager.stopService(this@TunnelActivity) }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.addJavascriptInterface(Bridge(), "Android")
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                // Minta daemon melaporkan status saat ini (jalan atau tidak)
                MessageHelper.sendMsg2Service(this@TunnelActivity, AppConfig.MSG_REGISTER_CLIENT, "")
            }
        }
        setContentView(web)

        ViewCompat.setOnApplyWindowInsetsListener(web) { v, insets ->
            val b = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
            )
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }

        ContextCompat.registerReceiver(
            this,
            stateReceiver,
            IntentFilter(AppConfig.BROADCAST_ACTION_ACTIVITY),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        web.loadUrl("file:///android_asset/tunnel.html")
    }

    override fun onDestroy() {
        try { unregisterReceiver(stateReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }

    /** Kirim status ke halaman HTML: window.onTunnelState(state, pesan) */
    private fun push(state: String, msg: String) {
        if (!::web.isInitialized) return
        val js = "window.onTunnelState&&window.onTunnelState(" +
            JSONObject.quote(state) + "," + JSONObject.quote(msg) + ")"
        web.post { web.evaluateJavascript(js, null) }
    }

    /** Ubah profil dari HTML jadi profil v2rayNG, pilih, lalu minta izin dan jalankan. */
    private fun prepare(json: String) {
        try {
            val link = buildLink(JSONObject(json))
            val (count, _) = AngConfigManager.importBatchConfig(link, "", false)
            if (count <= 0) throw IllegalStateException("Akun tidak bisa dibaca.")
            val guid = MmkvManager.decodeServerList("").firstOrNull()
                ?: throw IllegalStateException("Akun tidak tersimpan.")
            MmkvManager.setSelectServer(guid)
        } catch (e: Exception) {
            push("error", e.message ?: "Config tidak valid.")
            return
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            askVpn()
        }
    }

    private fun askVpn() {
        val i = VpnService.prepare(this)
        if (i == null) LauncherManager.startService(this) else vpnLauncher.launch(i)
    }

    private fun buildLink(o: JSONObject): String {
        val proto = o.optString("proto", "vmess")
        val host = o.getString("host")
        val port = o.getInt("port")
        val id = o.getString("id")
        val name = o.optString("name", host).ifBlank { host }
        val net = o.optString("net", "ws")
        val path = o.optString("path", "/").ifBlank { "/" }
        val wsHost = o.optString("wshost", "")
        val tls = o.optBoolean("tls", false)
        val sni = o.optString("sni", "")
        val fp = o.optString("fp", "")
        val alpn = o.optString("alpn", "")
        val insecure = o.optBoolean("insecure", false)
        val sec = o.optString("sec", "auto").ifBlank { "auto" }

        if (proto == "vmess") {
            val j = JSONObject()
                .put("v", "2")
                .put("ps", name)
                .put("add", host)
                .put("port", port.toString())
                .put("id", id)
                .put("aid", "0")
                .put("scy", sec)
                .put("net", net)
                .put("type", "none")
                .put("host", wsHost)
                .put("path", path)
                .put("tls", if (tls) "tls" else "")
                .put("sni", sni)
                .put("alpn", alpn)
                .put("fp", fp)
            return "vmess://" + Base64.encodeToString(j.toString().toByteArray(), Base64.NO_WRAP)
        }

        fun e(s: String) = URLEncoder.encode(s, "UTF-8")
        val q = StringBuilder("type=").append(e(net))
            .append("&security=").append(if (tls) "tls" else "none")
        if (proto == "vless") q.append("&encryption=none")
        if (wsHost.isNotEmpty()) q.append("&host=").append(e(wsHost))
        if (net == "ws" || net == "h2") q.append("&path=").append(e(path))
        if (tls) {
            if (sni.isNotEmpty()) q.append("&sni=").append(e(sni))
            if (fp.isNotEmpty()) q.append("&fp=").append(e(fp))
            if (alpn.isNotEmpty()) q.append("&alpn=").append(e(alpn))
            if (insecure) q.append("&allowInsecure=1")
        }
        val h = if (host.contains(":")) "[$host]" else host
        return "$proto://${e(id)}@$h:$port?$q#${e(name)}"
    }
}
