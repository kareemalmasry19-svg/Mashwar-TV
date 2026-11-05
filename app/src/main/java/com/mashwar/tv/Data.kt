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

const val DEFAULT_UA = "MashwarTV/0.1 (Android) ExoPlayer"
const val GROUP_DEFAULT = "عام"

data class Channel(
    val name: String,
    val url: String,
    val logo: String?,
    val group: String,
    val userAgent: String? = null,
    val referrer: String? = null,
)

data class Playlist(
    val id: String,
    val name: String,
    val source: String,
    val isUri: Boolean,
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

        fun reset() {
            name = null; logo = null; group = GROUP_DEFAULT; ua = null; ref = null
        }

        text.lineSequence().forEach { raw ->
            val line = raw.trim().removePrefix("﻿")
            when {
                line.isEmpty() -> Unit
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    val attrs = attrRegex.findAll(line)
                        .associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                    logo = attrs["tvg-logo"]?.takeIf { it.isNotBlank() }
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
                    out += Channel(name ?: fallback, url, logo, group, ua, ref)
                    reset()
                }
            }
        }
        return out
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
            Playlist(o.getString("id"), o.getString("name"), o.getString("source"), o.optBoolean("isUri"))
        }
    }

    fun savePlaylists(list: List<Playlist>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("id", it.id).put("name", it.name).put("source", it.source).put("isUri", it.isUri))
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

    var lastChannelUrl: String?
        get() = prefs.getString("last_url", null)
        set(value) { prefs.edit().putString("last_url", value).apply() }
}

/** Downloads playlist text from a URL (following http<->https redirects) or reads a picked file. */
suspend fun fetchPlaylistText(context: Context, source: String, isUri: Boolean): String =
    withContext(Dispatchers.IO) {
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
