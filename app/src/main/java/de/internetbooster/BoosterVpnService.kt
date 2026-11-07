package de.internetbooster

import android.net.VpnService
import android.content.Intent
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class BoosterVpnService : VpnService() {

    companion object {
        @Volatile var active = false
        val blocked = AtomicInteger()
        val forwarded = AtomicInteger()
        val dnsIps = arrayOf("1.1.1.1", "8.8.8.8", "9.9.9.9")
        private val blockList = listOf(
            "doubleclick.net", "googlesyndication.com", "googleadservices.com",
            "adservice.google.com", "adnxs.com", "adsrvr.org", "taboola.com",
            "outbrain.com", "criteo.com", "criteo.net", "scorecardresearch.com",
            "moatads.com", "amazon-adsystem.com", "adcolony.com", "applovin.com",
            "mopub.com", "inmobi.com", "chartboost.com", "vungle.com", "flurry.com",
            "app-measurement.com", "appsflyer.com", "adjust.com", "unityads.unity3d.com",
            "ads.yahoo.com", "advertising.com", "rubiconproject.com", "pubmatic.com"
        )
    }

    private var tun: ParcelFileDescriptor? = null
    @Volatile private var running = false
    private val pool = Executors.newFixedThreadPool(8)
    private lateinit var upstream: String
    private var block = true

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        stopVpn()
        val p = getSharedPreferences("s", MODE_PRIVATE)
        upstream = dnsIps[p.getInt("dns_idx", 0).coerceIn(0, 2)]
        block = p.getBoolean("block", false)
        tun = Builder()
            .setSession("Internet-Booster")
            .addAddress("10.0.0.2", 32)
            .addDnsServer("10.0.0.1")
            .addRoute("10.0.0.1", 32)
            .setBlocking(true)
            .establish()
        val fd = tun ?: return START_NOT_STICKY
        running = true
        active = true
        Thread { loop(fd) }.start()
        return START_STICKY
    }

    override fun onDestroy() { stopVpn() }

    private fun stopVpn() {
        running = false
        active = false
        try { tun?.close() } catch (_: Exception) {}
        tun = null
    }

    private fun loop(fd: ParcelFileDescriptor) {
        val input = FileInputStream(fd.fileDescriptor)
        val output = FileOutputStream(fd.fileDescriptor)
        val buf = ByteArray(32767)
        try {
            while (running) {
                val n = input.read(buf)
                if (n < 28) continue
                if ((buf[0].toInt() shr 4) != 4 || buf[9].toInt() != 17) continue
                val ihl = (buf[0].toInt() and 15) * 4
                val dstPort = ((buf[ihl + 2].toInt() and 255) shl 8) or (buf[ihl + 3].toInt() and 255)
                if (dstPort != 53) continue
                val pkt = buf.copyOf(n)
                pool.execute { handle(pkt, ihl, output) }
            }
        } catch (_: Exception) {}
    }

    private fun handle(pkt: ByteArray, ihl: Int, out: FileOutputStream) {
        try {
            val q = pkt.copyOfRange(ihl + 8, pkt.size)
            if (q.size < 13) return
            var i = 12
            val sb = StringBuilder()
            while (i < q.size && q[i].toInt() != 0) {
                val l = q[i].toInt() and 255
                if (l > 63 || i + 1 + l > q.size) return
                if (sb.isNotEmpty()) sb.append('.')
                sb.append(String(q, i + 1, l))
                i += l + 1
            }
            val qEnd = i + 5
            val name = sb.toString().lowercase()
            val resp: ByteArray
            if (block && qEnd <= q.size && blockList.any { name == it || name.endsWith(".$it") }) {
                resp = q.copyOf(qEnd)
                resp[2] = 0x81.toByte(); resp[3] = 0x83.toByte()
                for (k in 6..11) resp[k] = 0
                blocked.incrementAndGet()
            } else {
                DatagramSocket().use { s ->
                    protect(s)
                    s.soTimeout = 3000
                    s.send(DatagramPacket(q, q.size, InetAddress.getByName(upstream), 53))
                    val r = ByteArray(4096)
                    val dp = DatagramPacket(r, r.size)
                    s.receive(dp)
                    resp = r.copyOf(dp.length)
                }
                forwarded.incrementAndGet()
            }
            val total = 28 + resp.size
            val o = ByteArray(total)
            o[0] = 0x45.toByte()
            o[2] = (total shr 8).toByte(); o[3] = total.toByte()
            o[6] = 0x40.toByte(); o[8] = 64; o[9] = 17
            System.arraycopy(pkt, 16, o, 12, 4)
            System.arraycopy(pkt, 12, o, 16, 4)
            val c = csum(o, 20)
            o[10] = (c shr 8).toByte(); o[11] = c.toByte()
            System.arraycopy(pkt, ihl + 2, o, 20, 2)
            System.arraycopy(pkt, ihl, o, 22, 2)
            val ul = 8 + resp.size
            o[24] = (ul shr 8).toByte(); o[25] = ul.toByte()
            System.arraycopy(resp, 0, o, 28, resp.size)
            synchronized(out) { out.write(o) }
        } catch (_: Exception) {}
    }

    private fun csum(b: ByteArray, len: Int): Int {
        var s = 0L
        var i = 0
        while (i < len) {
            s += ((b[i].toInt() and 255) shl 8) or (b[i + 1].toInt() and 255)
            i += 2
        }
        while ((s shr 16) != 0L) s = (s and 0xffff) + (s shr 16)
        return (s.inv() and 0xffff).toInt()
    }
}
