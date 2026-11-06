package com.mashwar.tv

import android.content.Context
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

data class Programme(val start: Long, val stop: Long, val title: String)

/** Programme guide for one playlist: programmes by XMLTV channel id, plus name → id for channels without tvg-id. */
class EpgData(private val byId: Map<String, List<Programme>>, private val idByName: Map<String, String>) {
    val isEmpty get() = byId.isEmpty()

    private fun programmes(c: Channel): List<Programme>? =
        c.tvgId?.let { byId[it] ?: byId[it.lowercase()] } ?: idByName[Epg.norm(c.name)]?.let { byId[it] }

    /** (now, next) for the channel at [time]. */
    fun nowNext(c: Channel, time: Long): Pair<Programme?, Programme?> {
        val list = programmes(c) ?: return null to null
        val i = list.indexOfFirst { time in it.start until it.stop }
        if (i < 0) return null to list.firstOrNull { it.start > time }
        return list[i] to list.getOrNull(i + 1)
    }
}

/**
 * Downloads an XMLTV guide (plain or .gz), keeps only this playlist's channels for the next day,
 * and caches the trimmed result for 12 hours.
 */
object Epg {
    private const val MAX_AGE_MS = 12 * 60 * 60 * 1000L

    private val http by lazy {
        OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    }

    fun norm(name: String) = name.lowercase(Locale.ROOT)
        .replace(Regex("""\b(hd|fhd|uhd|sd|4k|hevc)\b"""), "")
        .replace(Regex("""[^\p{L}\p{N}]+"""), "")

    suspend fun load(ctx: Context, playlist: Playlist, channels: List<Channel>, force: Boolean = false): EpgData? =
        withContext(Dispatchers.IO) {
            val url = playlist.epgUrl ?: return@withContext null
            val cache = File(ctx.filesDir, "epg_${playlist.id}.tsv")
            if (!force && cache.exists() && System.currentTimeMillis() - cache.lastModified() < MAX_AGE_MS) {
                readCache(cache)?.let { return@withContext it }
            }
            val data = runCatching { download(url, channels) }.getOrNull() ?: return@withContext readCache(cache)
            writeCache(cache, data.first, data.second)
            EpgData(data.first, data.second)
        }

    private fun download(url: String, channels: List<Channel>): Pair<Map<String, List<Programme>>, Map<String, String>> {
        val wantIds = channels.mapNotNull { it.tvgId }.flatMap { listOf(it, it.lowercase()) }.toHashSet()
        val wantNames = channels.map { norm(it.name) }.toHashSet()
        val keep = HashSet<String>()
        val idByName = HashMap<String, String>()
        val progs = HashMap<String, MutableList<Programme>>()
        val now = System.currentTimeMillis()
        val from = now - 2 * 3600_000L
        val to = now + 26 * 3600_000L
        val fmtZ = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US)
        val fmt = SimpleDateFormat("yyyyMMddHHmmss", Locale.US)
        fun time(s: String?): Long? {
            val t = s?.trim() ?: return null
            return runCatching { fmtZ.parse(t)?.time }.getOrNull() ?: runCatching { fmt.parse(t.take(14))?.time }.getOrNull()
        }

        http.newCall(Request.Builder().url(url).header("User-Agent", DEFAULT_UA).build()).execute().use { resp ->
            if (!resp.isSuccessful) error("EPG HTTP ${resp.code}")
            val raw = BufferedInputStream(resp.body!!.byteStream(), 64 * 1024)
            raw.mark(2)
            val gz = raw.read() == 0x1f && raw.read() == 0x8b
            raw.reset()
            val input: InputStream = if (gz) GZIPInputStream(raw, 64 * 1024) else raw
            val p = Xml.newPullParser()
            p.setInput(input, null)
            var ev = p.eventType
            var chId: String? = null
            var prChannel: String? = null
            var prStart = 0L
            var prStop = 0L
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) when (p.name) {
                    "channel" -> chId = p.getAttributeValue(null, "id")
                    "display-name" -> chId?.let { id ->
                        val n = norm(p.nextText())
                        if (id in wantIds || n in wantNames) {
                            keep += id
                            if (n in wantNames) idByName.putIfAbsent(n, id)
                        }
                    }
                    "programme" -> {
                        val c = p.getAttributeValue(null, "channel")
                        val s = time(p.getAttributeValue(null, "start"))
                        val e = time(p.getAttributeValue(null, "stop"))
                        prChannel = if (c != null && (c in keep || c in wantIds) && s != null && e != null && e > from && s < to) c else null
                        prStart = s ?: 0
                        prStop = e ?: 0
                    }
                    "title" -> prChannel?.let { c ->
                        val title = p.nextText().trim()
                        if (title.isNotEmpty()) progs.getOrPut(c) { ArrayList() } += Programme(prStart, prStop, title)
                        prChannel = null // first title only
                    }
                } else if (ev == XmlPullParser.END_TAG && p.name == "channel") {
                    chId = null
                }
                ev = p.next()
            }
        }
        return progs.mapValues { (_, v) -> v.sortedBy { it.start } } to idByName
    }

    private fun writeCache(f: File, byId: Map<String, List<Programme>>, idByName: Map<String, String>) {
        runCatching {
            f.bufferedWriter().use { w ->
                idByName.forEach { (n, id) -> w.write("N\t$n\t$id\n") }
                byId.forEach { (id, list) ->
                    list.forEach { pr -> w.write("P\t$id\t${pr.start}\t${pr.stop}\t${pr.title.replace('\t', ' ').replace('\n', ' ')}\n") }
                }
            }
        }
    }

    private fun readCache(f: File): EpgData? = runCatching {
        if (!f.exists()) return null
        val byId = HashMap<String, MutableList<Programme>>()
        val idByName = HashMap<String, String>()
        f.forEachLine { line ->
            val parts = line.split('\t')
            when (parts.firstOrNull()) {
                "N" -> if (parts.size >= 3) idByName[parts[1]] = parts[2]
                "P" -> if (parts.size >= 5) byId.getOrPut(parts[1]) { ArrayList() } +=
                    Programme(parts[2].toLong(), parts[3].toLong(), parts[4])
            }
        }
        EpgData(byId, idByName)
    }.getOrNull()
}
