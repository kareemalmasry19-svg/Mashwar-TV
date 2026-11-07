package com.mashwar.tv

import android.content.Context
import android.net.wifi.WifiManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.TimeUnit

/** A TV (Samsung, LG, Sony…) that accepts videos over DLNA / UPnP. */
data class DlnaDevice(val name: String, val controlUrl: String)

/**
 * Minimal DLNA "play to" sender: finds MediaRenderers on the Wi-Fi with SSDP and tells
 * the TV to open the stream URL itself (SetAVTransportURI + Play). Works with TVs that
 * have no Chromecast, e.g. most Samsung TVs.
 */
object DlnaCast {
    /** TV currently showing our channel, or null. */
    var active by mutableStateOf<DlnaDevice?>(null)
        private set

    private val http = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    private const val AVT = "urn:schemas-upnp-org:service:AVTransport:1"

    /** What the last search saw, so the picker can say *why* nothing showed up. */
    data class Scan(val devices: List<DlnaDevice>, val wifi: Boolean, val responders: Int, val others: List<String>)

    suspend fun discover(context: Context, waitMs: Int = 5_000): Scan = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val wifiAddr = wifiAddress(app) ?: return@withContext Scan(emptyList(), wifi = false, responders = 0, others = emptyList())
        val wifi = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifi.createMulticastLock("kenue-dlna").apply { setReferenceCounted(false); acquire() }
        val locations = java.util.Collections.synchronizedSet(LinkedHashSet<String>())
        val responders = java.util.Collections.synchronizedSet(HashSet<String>())
        val group = InetAddress.getByName("239.255.255.250")
        val nif = NetworkInterface.getByInetAddress(wifiAddr)
        fun collect(text: String, from: InetAddress) {
            responders += from.hostAddress ?: return
            Regex("(?im)^location:\\s*(\\S+)").find(text)?.groupValues?.get(1)?.let { locations += it }
        }
        // Also listen for the TVs' own periodic announcements (NOTIFY) on port 1900.
        val listener = runCatching {
            MulticastSocket(1900).apply {
                reuseAddress = true
                soTimeout = 500
                joinGroup(InetSocketAddress(group, 1900), nif)
            }
        }.getOrNull()
        val listenThread = listener?.let { ls ->
            Thread {
                val buf = ByteArray(4096)
                val end = System.currentTimeMillis() + waitMs
                while (System.currentTimeMillis() < end) {
                    val p = DatagramPacket(buf, buf.size)
                    try { ls.receive(p) } catch (_: Exception) { continue }
                    collect(String(p.data, 0, p.length), p.address)
                }
            }.apply { start() }
        }
        try {
            // Search from the Wi-Fi interface explicitly (phones with mobile data on may pick the wrong one).
            MulticastSocket(InetSocketAddress(wifiAddr, 0)).use { sock ->
                sock.soTimeout = 500
                sock.timeToLive = 4
                nif?.let { sock.networkInterface = it }
                val targets = listOf(
                    "urn:schemas-upnp-org:device:MediaRenderer:1", AVT, "upnp:rootdevice", "ssdp:all",
                )
                repeat(2) {
                    for (st in targets) {
                        val msg = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: $st\r\nUSER-AGENT: Android UPnP/1.1 KenueTV/0.5\r\n\r\n"
                        val bytes = msg.toByteArray()
                        sock.send(DatagramPacket(bytes, bytes.size, group, 1900))
                    }
                }
                val buf = ByteArray(4096)
                val end = System.currentTimeMillis() + waitMs
                while (System.currentTimeMillis() < end) {
                    val p = DatagramPacket(buf, buf.size)
                    try { sock.receive(p) } catch (_: SocketTimeoutException) { continue }
                    collect(String(p.data, 0, p.length), p.address)
                }
            }
        } finally {
            listenThread?.join(waitMs.toLong() + 1_000)
            runCatching { listener?.close() }
            lock.release()
        }
        val described = locations.toList().take(40).mapNotNull { describeAny(it) }
        val devices = described.mapNotNull { it.first }.distinctBy { it.controlUrl }
        val others = described.filter { it.first == null }.map { it.second }.distinct()
        Scan(devices, wifi = true, responders = responders.size, others = others)
    }

    /**
     * Connect to a TV by its IP address, for networks where discovery is blocked.
     * Tries the description pages TVs usually serve (Samsung :9197/dmr first).
     */
    suspend fun probeIp(ipInput: String): DlnaDevice? = withContext(Dispatchers.IO) {
        val ip = ipInput.trim().removePrefix("http://").substringBefore('/').substringBefore(':')
        if (ip.isEmpty()) return@withContext null
        val candidates = listOf(
            "http://$ip:9197/dmr", "http://$ip:7676/smp_3_", "http://$ip:7676/smp_15_", "http://$ip:7676/smp_25_",
            "http://$ip:1900/description.xml", "http://$ip:49152/description.xml", "http://$ip:49153/description.xml",
            "http://$ip:8080/description.xml", "http://$ip:1400/xml/device_description.xml",
        )
        val quick = http.newBuilder().connectTimeout(2, TimeUnit.SECONDS).readTimeout(3, TimeUnit.SECONDS).build()
        for (url in candidates) {
            val xml = runCatching {
                quick.newCall(Request.Builder().url(url).build()).execute().use { if (it.isSuccessful) it.body?.string() else null }
            }.getOrNull() ?: continue
            describe(url, xml)?.let { return@withContext it }
        }
        null
    }

    /** IPv4 address of the phone on the current Wi-Fi network, or null when not on Wi-Fi. */
    private fun wifiAddress(ctx: Context): InetAddress? {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.allNetworks.firstOrNull { n ->
            cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        } ?: return null
        return cm.getLinkProperties(net)?.linkAddresses?.map { it.address }?.firstOrNull { it is Inet4Address }
    }

    /** (renderer, name) — renderer is null for devices that can't play videos (routers, speakers…). */
    private fun describeAny(location: String): Pair<DlnaDevice?, String>? = runCatching {
        val xml = http.newCall(Request.Builder().url(location).build()).execute().use { it.body?.string() } ?: return null
        val name = Regex("<friendlyName>(.*?)</friendlyName>", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)?.trim()
            ?.let(::unescape) ?: "جهاز"
        describe(location, xml) to name
    }.getOrNull()

    /** Reads the device description and finds its AVTransport control URL. */
    private fun describe(location: String, xml: String): DlnaDevice? = runCatching {
        val name = Regex("<friendlyName>(.*?)</friendlyName>", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)?.trim()
            ?: "TV"
        val service = Regex("<service>(.*?)</service>", RegexOption.DOT_MATCHES_ALL).findAll(xml)
            .map { it.groupValues[1] }
            .firstOrNull { "AVTransport" in it } ?: return null
        val control = Regex("<controlURL>(.*?)</controlURL>", RegexOption.DOT_MATCHES_ALL).find(service)?.groupValues?.get(1)?.trim()
            ?: return null
        val base = Regex("<URLBase>(.*?)</URLBase>").find(xml)?.groupValues?.get(1)?.trim() ?: location
        DlnaDevice(unescape(name), URL(URL(base), control).toString())
    }.getOrNull()

    /** Sends the channel to the TV. Returns null on success, or a short error message. */
    suspend fun play(device: DlnaDevice, channel: Channel): String? = withContext(Dispatchers.IO) {
        runCatching {
            soap(device, "Stop", "<InstanceID>0</InstanceID>", ignoreErrors = true)
            val mime = dlnaMime(channel.url)
            val didl = """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/"><item id="0" parentID="-1" restricted="1"><dc:title>${esc(channel.name)}</dc:title><upnp:class>object.item.videoItem</upnp:class><res protocolInfo="http-get:*:$mime:*">${esc(channel.url)}</res></item></DIDL-Lite>"""
            soap(device, "SetAVTransportURI",
                "<InstanceID>0</InstanceID><CurrentURI>${esc(channel.url)}</CurrentURI><CurrentURIMetaData>${esc(didl)}</CurrentURIMetaData>")
            soap(device, "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>")
            active = device
            null
        }.getOrElse { it.message ?: "الشاشة رفضت الطلب" }
    }

    suspend fun stop() = withContext(Dispatchers.IO) {
        active?.let { runCatching { soap(it, "Stop", "<InstanceID>0</InstanceID>", ignoreErrors = true) } }
        active = null
    }

    private fun soap(device: DlnaDevice, action: String, args: String, ignoreErrors: Boolean = false) {
        val body = """<?xml version="1.0" encoding="utf-8"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><u:$action xmlns:u="$AVT">$args</u:$action></s:Body></s:Envelope>"""
        val req = Request.Builder()
            .url(device.controlUrl)
            .header("SOAPACTION", "\"$AVT#$action\"")
            .post(body.toRequestBody("text/xml; charset=\"utf-8\"".toMediaType()))
            .build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful && !ignoreErrors) error("$action: HTTP ${r.code}")
        }
    }

    private fun dlnaMime(url: String): String {
        val u = url.lowercase()
        return when {
            "m3u8" in u -> "application/vnd.apple.mpegurl"
            ".mp4" in u -> "video/mp4"
            ".mkv" in u -> "video/x-matroska"
            else -> "video/mp2t" // live IPTV without an extension is usually MPEG-TS
        }
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    private fun unescape(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&amp;", "&")
}
