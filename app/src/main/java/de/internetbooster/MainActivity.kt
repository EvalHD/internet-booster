package de.internetbooster

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.TrafficStats
import android.net.VpnService
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import kotlin.math.abs

class MainActivity : Activity() {
    private val dnsNames = arrayOf("Cloudflare", "Google", "Quad9")
    private lateinit var p: SharedPreferences
    private lateinit var info: TextView
    private lateinit var dnsBtn: Button
    private val h = Handler(Looper.getMainLooper())
    private val pings = ArrayList<Long>()
    private val exec = Executors.newSingleThreadExecutor()
    @Volatile private var pinging = false

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun sw(label: String, key: String) = Switch(this).apply {
        text = label; textSize = 16f
        setPadding(0, dp(10), 0, dp(10))
        isChecked = p.getBoolean(key, false)
        setOnCheckedChangeListener { _, c ->
            p.edit().putBoolean(key, c).apply()
            onToggle(key)
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        p = getSharedPreferences("s", MODE_PRIVATE)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(32), dp(16), dp(16))
        }
        root.addView(TextView(this).apply { text = "Internet-Booster"; textSize = 26f })
        root.addView(sw("Schnelles DNS", "dns"))
        dnsBtn = Button(this).apply {
            setOnClickListener {
                p.edit().putInt("dns_idx", (p.getInt("dns_idx", 0) + 1) % 3).apply()
                updateBtn(); applyVpn()
            }
        }
        updateBtn()
        root.addView(dnsBtn)
        root.addView(sw("Werbe- und Tracker-Blocker", "block"))
        root.addView(sw("Ping-Monitor (Ping, Jitter, Paketverlust)", "ping"))
        root.addView(sw("WLAN-Check (Band, Signal, Tipps)", "wifi"))
        root.addView(sw("Daten-Fresser anzeigen", "hogs"))
        info = TextView(this).apply { textSize = 15f; setPadding(0, dp(16), 0, 0) }
        root.addView(info)
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun updateBtn() {
        dnsBtn.text = "DNS-Anbieter: ${dnsNames[p.getInt("dns_idx", 0)]} (tippen zum Wechseln)"
    }

    private fun onToggle(key: String) {
        if (key == "dns" || key == "block") applyVpn()
        if (key == "ping") { if (p.getBoolean("ping", false)) startPing() else pinging = false }
    }

    private fun applyVpn() {
        if (p.getBoolean("dns", false) || p.getBoolean("block", false)) {
            val i = VpnService.prepare(this)
            if (i != null) startActivityForResult(i, 1)
            else startService(Intent(this, BoosterVpnService::class.java))
        } else stopService(Intent(this, BoosterVpnService::class.java))
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        if (req == 1 && res == RESULT_OK) startService(Intent(this, BoosterVpnService::class.java))
    }

    private fun startPing() {
        if (pinging) return
        pinging = true
        exec.execute {
            while (pinging) {
                val t = System.nanoTime()
                var r = -1L
                try {
                    Socket().use { it.connect(InetSocketAddress("1.1.1.1", 443), 1500) }
                    r = (System.nanoTime() - t) / 1_000_000
                } catch (_: Exception) {}
                synchronized(pings) { pings.add(r); if (pings.size > 20) pings.removeAt(0) }
                try { Thread.sleep(1000) } catch (_: Exception) {}
            }
        }
    }

    private val tick = object : Runnable {
        override fun run() { info.text = report(); h.postDelayed(this, 1500) }
    }

    override fun onResume() {
        super.onResume()
        if (p.getBoolean("ping", false)) startPing()
        h.post(tick)
    }

    override fun onPause() {
        super.onPause()
        h.removeCallbacks(tick); pinging = false
    }

    private fun report(): String {
        val s = StringBuilder()
        if (BoosterVpnService.active) {
            s.append("DNS aktiv: ${dnsNames[p.getInt("dns_idx", 0)]}\n")
            s.append("Anfragen: ${BoosterVpnService.forwarded.get()} weitergeleitet, ")
            s.append("${BoosterVpnService.blocked.get()} blockiert\n\n")
        } else s.append("DNS/Blocker: aus\n\n")

        if (p.getBoolean("ping", false)) {
            val l = synchronized(pings) { ArrayList(pings) }
            val ok = l.filter { it >= 0 }
            if (l.isEmpty()) s.append("Ping: messe ...\n\n")
            else if (ok.isEmpty()) s.append("Ping: keine Verbindung (100 % Verlust)\n\n")
            else {
                val avg = ok.average()
                val jit = if (ok.size > 1) ok.zipWithNext { a, c -> abs(a - c).toDouble() }.average() else 0.0
                val loss = 100 * (l.size - ok.size) / l.size
                val rating = if (avg < 50) "gut" else if (avg < 100) "okay" else "langsam"
                s.append("Ping: %.0f ms (%s)\nJitter: %.0f ms\nPaketverlust: %d %%\n\n".format(avg, rating, jit, loss))
            }
        }

        if (p.getBoolean("wifi", false)) {
            try {
                val wi = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).connectionInfo
                if (wi != null && wi.frequency > 0) {
                    val band = if (wi.frequency < 3000) "2,4 GHz" else "5/6 GHz"
                    s.append("WLAN: $band, Signal ${wi.rssi} dBm, Tempo ${wi.linkSpeed} Mbit/s\n")
                    if (wi.frequency < 3000) s.append("Tipp: 5-GHz-WLAN ist meist schneller.\n")
                    if (wi.rssi < -70) s.append("Tipp: Signal schwach, näher an den Router gehen.\n")
                    s.append("\n")
                } else s.append("WLAN: nicht verbunden\n\n")
            } catch (_: Exception) { s.append("WLAN: keine Daten\n\n") }
        }

        if (p.getBoolean("hogs", false)) {
            try {
                val top = packageManager.getInstalledApplications(0)
                    .map { it to (maxOf(0L, TrafficStats.getUidRxBytes(it.uid)) + maxOf(0L, TrafficStats.getUidTxBytes(it.uid))) }
                    .filter { it.second > 0 }
                    .sortedByDescending { it.second }.take(5)
                s.append("Meiste Daten seit Neustart:\n")
                top.forEach { s.append("• ${packageManager.getApplicationLabel(it.first)}: ${it.second / 1048576} MB\n") }
            } catch (_: Exception) { s.append("Daten-Fresser: keine Daten\n") }
        }
        return s.toString()
    }
}
