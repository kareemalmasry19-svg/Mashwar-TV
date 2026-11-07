package com.mashwar.tv

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

const val DEFAULT_UA = "KenueTV/0.3 (Android) ExoPlayer"
const val GROUP_DEFAULT = "عام"

data class Channel(
    val name: String,
    val url: String,
    val logo: String?,
    val group: String,
    val userAgent: String? = null,
    val referrer: String? = null,
    val tvgId: String? = null,
    /** Extra metadata (free catalogue / rich M3U): ISO country, language codes, categories, best quality, last scan. */
    val country: String? = null,
    val languages: String? = null,
    val categories: String? = null,
    val quality: String? = null,
    val status: String? = null,
)

data class Playlist(
    val id: String,
    val name: String,
    val source: String,
    val isUri: Boolean,
    /** XMLTV programme guide URL (from the M3U header or set by the user). */
    val epgUrl: String? = null,
)

/** Parses M3U / M3U8 (extended) playlists, including VLC options and Kodi-style "url|User-Agent=..". */
object M3uParser {
    private val attrRegex = Regex("""([\w-]+)="([^"]*)"""")

    fun parse(text: String): List<Channel> {
        val out = ArrayList<Channel>()
        var name: String? = null
        var logo: String? = null
        var group = GROUP_DEFAULT
        var ua: String? = null
        var ref: String? = null
        var tvgId: String? = null
        var country: String? = null
        var langs: String? = null
        var cats: String? = null
        var quality: String? = null
        var status: String? = null

        fun reset() {
            name = null; logo = null; group = GROUP_DEFAULT; ua = null; ref = null; tvgId = null
            country = null; langs = null; cats = null; quality = null; status = null
        }

        text.lineSequence().forEach { raw ->
            val line = raw.trim().removePrefix("﻿")
            when {
                line.isEmpty() -> Unit
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    val attrs = attrRegex.findAll(line)
                        .associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                    logo = attrs["tvg-logo"]?.takeIf { it.isNotBlank() }
                    tvgId = attrs["tvg-id"]?.trim()?.takeIf { it.isNotBlank() }
                    country = attrs["tvg-country"]?.trim()?.uppercase()?.takeIf { it.isNotBlank() }
                    langs = attrs["tvg-language"]?.trim()?.takeIf { it.isNotBlank() }
                    cats = attrs["x-categories"]?.trim()?.takeIf { it.isNotBlank() }
                    quality = attrs["x-quality"]?.trim()?.takeIf { it.isNotBlank() }
                    status = attrs["x-status"]?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
                    group = attrs["group-title"]?.takeIf { it.isNotBlank() } ?: GROUP_DEFAULT
                    val stripped = attrRegex.replace(line, "")
                    name = stripped.substringAfter(",", "").trim()
                        .ifBlank { attrs["tvg-name"] ?: "" }
                        .ifBlank { null }
                }
                line.startsWith("#EXTGRP:", ignoreCase = true) ->
                    line.substringAfter(":").trim().takeIf { it.isNotBlank() }?.let { group = it }
                line.startsWith("#EXTVLCOPT:http-user-agent=", ignoreCase = true) ->
                    ua = line.substringAfter("=").trim()
                line.startsWith("#EXTVLCOPT:http-referrer=", ignoreCase = true) ||
                    line.startsWith("#EXTVLCOPT:http-referer=", ignoreCase = true) ->
                    ref = line.substringAfter("=").trim()
                line.startsWith("#") -> Unit
                else -> {
                    var url = line
                    if (url.contains('|')) {
                        val opts = url.substringAfter('|')
                        url = url.substringBefore('|')
                        opts.split('&').forEach { kv ->
                            val k = kv.substringBefore('=').trim().lowercase()
                            val v = kv.substringAfter('=', "").trim()
                            when (k) {
                                "user-agent" -> ua = v
                                "referer", "referrer" -> ref = v
                            }
                        }
                    }
                    val fallback = url.substringAfterLast('/').substringBefore('?').ifBlank { "قناة" }
                    if (url.startsWith("http://", true) || url.startsWith("https://", true) || url.startsWith("rtsp://", true) || url.startsWith("rtmp://", true)) {
                        out += Channel(name ?: fallback, url, logo, group, ua, ref, tvgId, country, langs, cats, quality, status)
                    }
                    reset()
                }
            }
        }
        return out
    }

    /** EPG URL from the "#EXTM3U x-tvg-url=..." / "url-tvg=..." header, if any. */
    fun epgUrlOf(text: String): String? {
        val header = text.lineSequence().take(5).firstOrNull { it.trim().removePrefix("\uFEFF").startsWith("#EXTM3U", true) } ?: return null
        val attrs = attrRegex.findAll(header).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
        return (attrs["x-tvg-url"] ?: attrs["url-tvg"] ?: attrs["tvg-url"])
            ?.split(',')?.map { it.trim() }?.firstOrNull { it.startsWith("http") }
    }
}

/** Local storage: playlist index in SharedPreferences, raw playlist text in files. */
class Store(context: Context) {
    private val prefs = context.getSharedPreferences("mashwar", Context.MODE_PRIVATE)
    private val dir = File(context.filesDir, "playlists").apply { mkdirs() }

    fun playlists(): List<Playlist> {
        val arr = JSONArray(prefs.getString("playlists", "[]"))
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Playlist(
                o.getString("id"), o.getString("name"), o.getString("source"), o.optBoolean("isUri"),
                o.optString("epgUrl").ifBlank { null },
            )
        }
    }

    fun savePlaylists(list: List<Playlist>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject().put("id", it.id).put("name", it.name).put("source", it.source).put("isUri", it.isUri)
                    .put("epgUrl", it.epgUrl ?: "")
            )
        }
        prefs.edit().putString("playlists", arr.toString()).apply()
    }

    fun saveRaw(id: String, text: String) = File(dir, "$id.m3u").writeText(text)
    fun loadRaw(id: String): String? = File(dir, "$id.m3u").takeIf { it.exists() }?.readText()
    fun deleteRaw(id: String) { File(dir, "$id.m3u").delete() }

    var favorites: Set<String>
        get() = prefs.getStringSet("favorites", emptySet())?.toSet() ?: emptySet()
        set(value) { prefs.edit().putStringSet("favorites", value).apply() }

    /** 0 = auto, otherwise max video height (720 / 480 / 360). */
    var maxQuality: Int
        get() = prefs.getInt("max_quality", 0)
        set(value) { prefs.edit().putInt("max_quality", value).apply() }

    /** Channels the last scan found not working, per playlist. */
    fun dead(playlistId: String): Set<String> = prefs.getStringSet("dead_$playlistId", emptySet())!!.toSet()
    fun saveDead(playlistId: String, urls: Set<String>) { prefs.edit().putStringSet("dead_$playlistId", urls).apply() }

    /** Where the user stopped in a movie / episode, to resume. */
    fun position(url: String): Long = prefs.getLong("pos_" + url.hashCode(), 0L)
    fun savePosition(url: String, ms: Long) { prefs.edit().putLong("pos_" + url.hashCode(), ms).apply() }

    var lastChannelUrl: String?
        get() = prefs.getString("last_url", null)
        set(value) { prefs.edit().putString("last_url", value).apply() }
}

/** Downloads playlist text from a URL (following http<->https redirects) or reads a picked file. */
suspend fun fetchPlaylistText(context: Context, source: String, isUri: Boolean): String =
    withContext(Dispatchers.IO) {
        XtreamAccount.decode(source)?.let { return@withContext Xtream.buildM3u(it) }
        if (source == FreeCatalog.SOURCE) return@withContext FreeCatalog.text(context, force = true)
        if (isUri) {
            context.contentResolver.openInputStream(Uri.parse(source))
                ?.bufferedReader()?.use { it.readText() }
                ?: error("مقدرتش أفتح الملف")
        } else {
            var current = source.trim()
            repeat(6) {
                val conn = URL(current).openConnection() as HttpURLConnection
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                conn.instanceFollowRedirects = false
                conn.setRequestProperty("User-Agent", DEFAULT_UA)
                try {
                    val code = conn.responseCode
                    if (code in 300..399) {
                        val loc = conn.getHeaderField("Location") ?: error("تحويل من غير عنوان")
                        current = URL(URL(current), loc).toString()
                    } else if (code in 200..299) {
                        return@withContext conn.inputStream.bufferedReader().use { it.readText() }
                    } else {
                        error("السيرفر رد بكود $code")
                    }
                } finally {
                    conn.disconnect()
                }
            }
            error("تحويلات كتير")
        }
    }

/** Public test streams so the app can be tried without any IPTV subscription. */
val SAMPLE_PLAYLIST = """
#EXTM3U
#EXTINF:-1 group-title="تجريبي",Apple – HLS (جودات متعددة)
https://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_fmp4/master.m3u8
#EXTINF:-1 group-title="تجريبي",Mux – Big Buck Bunny
https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8
#EXTINF:-1 group-title="تجريبي",Tears of Steel (HLS)
https://demo.unified-streaming.com/k8s/features/stable/video/tears-of-steel/tears-of-steel.ism/.m3u8
#EXTINF:-1 group-title="MP4",Big Buck Bunny (MP4)
https://storage.googleapis.com/exoplayer-test-media-0/BigBuckBunny_320x180.mp4
""".trimIndent()

/** Height in lines from a quality label ("1080p", "720i", "4K") or 0 when unknown. */
fun qualityHeight(q: String?): Int {
    val t = q?.lowercase() ?: return 0
    if ("4k" in t || "2160" in t) return 2160
    return Regex("(\\d{3,4})").find(t)?.groupValues?.get(1)?.toIntOrNull() ?: 0
}

/** All channel-list filters in one place (unit-tested). */
data class ChannelFilter(
    val query: String = "",
    val group: String? = null,          // null = all groups
    val favoritesOnly: Boolean = false,
    val category: String? = null,
    val language: String? = null,
    val onlyWorking: Boolean = false,   // last scan said "online" (or no status known)
    val minHeight: Int = 0,
)

fun filterChannels(all: List<Channel>, f: ChannelFilter, favorites: Set<String>, dead: Set<String>): List<Channel> {
    val q = f.query.trim()
    return all.filter { c ->
        c.url !in dead &&
            (f.group == null || c.group == f.group) &&
            (!f.favoritesOnly || c.url in favorites) &&
            (f.category == null || c.categories?.split(',')?.any { it.trim() == f.category } == true) &&
            (f.language == null || c.languages?.split(',')?.any { it.trim() == f.language } == true) &&
            (!f.onlyWorking || c.status == null || c.status == "online") &&
            (f.minHeight == 0 || qualityHeight(c.quality) >= f.minHeight) &&
            (q.isEmpty() || c.name.contains(q, ignoreCase = true))
    }
}
