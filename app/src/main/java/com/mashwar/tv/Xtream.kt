package com.mashwar.tv

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** An IPTV subscription on an Xtream Codes panel (server + username + password). */
data class XtreamAccount(val server: String, val user: String, val pass: String, val hls: Boolean) {
    /** Stored as the playlist's "source", so the existing refresh logic just works. */
    fun encode(): String = PREFIX + listOf(server, user, pass, if (hls) "m3u8" else "ts")
        .joinToString("|") { URLEncoder.encode(it, "UTF-8") }

    val host: String get() = Uri.parse(server).host ?: server

    companion object {
        const val PREFIX = "xtream|"

        fun decode(source: String): XtreamAccount? {
            if (!source.startsWith(PREFIX)) return null
            val p = source.removePrefix(PREFIX).split("|").map { URLDecoder.decode(it, "UTF-8") }
            if (p.size < 3) return null
            return XtreamAccount(p[0], p[1], p[2], p.getOrNull(3) != "ts")
        }
    }
}

data class XtreamInfo(val status: String, val expires: Long?, val maxConnections: Int?)

object Xtream {
    /**
     * Accepts "host:port", "http://host:port/", or a full get.php / player_api.php link
     * (username and password are read from the link when present).
     */
    fun parseInput(serverInput: String, user: String, pass: String, hls: Boolean): XtreamAccount {
        var s = serverInput.trim()
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "http://$s"
        val uri = Uri.parse(s)
        val u = user.trim().ifEmpty { uri.getQueryParameter("username").orEmpty() }
        val p = pass.trim().ifEmpty { uri.getQueryParameter("password").orEmpty() }
        val port = if (uri.port > 0) ":${uri.port}" else ""
        val base = "${uri.scheme}://${uri.host}$port"
        return XtreamAccount(base, u, p, hls)
    }

    private fun api(acc: XtreamAccount, action: String? = null): String {
        val q = "username=${URLEncoder.encode(acc.user, "UTF-8")}&password=${URLEncoder.encode(acc.pass, "UTF-8")}"
        return "${acc.server}/player_api.php?$q" + (action?.let { "&action=$it" } ?: "")
    }

    private fun get(url: String): String {
        val req = Request.Builder().url(url).header("User-Agent", DEFAULT_UA).build()
        sharedHttp.newCall(req).execute().use { r ->
            if (!r.isSuccessful) error("السيرفر رد بـ ${r.code}")
            return r.body?.string() ?: error("رد فاضي من السيرفر")
        }
    }

    /** Checks the account. Throws with an Arabic message the user can act on. */
    suspend fun login(acc: XtreamAccount): XtreamInfo = withContext(Dispatchers.IO) {
        if (acc.user.isEmpty() || acc.pass.isEmpty()) error("اكتب اسم المستخدم والباسورد")
        val body = runCatching { get(api(acc)) }.getOrElse { throw IllegalStateException("مقدرتش أوصل للسيرفر (${it.message})") }
        val json = runCatching { JSONObject(body) }.getOrElse { error("ده مش سيرفر Xtream، اتأكد من الرابط والبورت") }
        val ui = json.optJSONObject("user_info") ?: error("ده مش سيرفر Xtream، اتأكد من الرابط والبورت")
        if (ui.optString("auth") != "1" && ui.optInt("auth", 0) != 1) error("اسم المستخدم أو الباسورد غلط")
        val status = ui.optString("status")
        val exp = ui.optString("exp_date").toLongOrNull()?.times(1000)
        if (status.equals("Expired", true) || (exp != null && exp < System.currentTimeMillis())) error("الاشتراك منتهي")
        if (status.isNotEmpty() && !status.equals("Active", true)) error("الحساب مش مفعّل ($status)")
        XtreamInfo(status, exp, ui.optString("max_connections").toIntOrNull())
    }

    /** Live channels as an M3U playlist (with the panel's own EPG link in the header). */
    suspend fun buildM3u(acc: XtreamAccount): String = withContext(Dispatchers.IO) {
        val cats = HashMap<String, String>()
        runCatching {
            val arr = JSONArray(get(api(acc, "get_live_categories")))
            for (i in 0 until arr.length()) arr.getJSONObject(i).let { cats[it.optString("category_id")] = it.optString("category_name") }
        }
        val streams = JSONArray(get(api(acc, "get_live_streams")))
        val ext = if (acc.hls) "m3u8" else "ts"
        val user = URLEncoder.encode(acc.user, "UTF-8")
        val pass = URLEncoder.encode(acc.pass, "UTF-8")
        val epg = "${acc.server}/xmltv.php?username=$user&password=$pass"
        fun attr(s: String) = s.replace('"', '\'').replace('\n', ' ')
        buildString(streams.length() * 160) {
            append("#EXTM3U x-tvg-url=\"").append(epg).append("\"\n")
            for (i in 0 until streams.length()) {
                val o = streams.getJSONObject(i)
                val id = o.optString("stream_id")
                if (id.isEmpty()) continue
                val name = o.optString("name").trim().ifEmpty { "قناة $id" }
                val group = cats[o.optString("category_id")]?.takeIf { it.isNotBlank() } ?: GROUP_DEFAULT
                append("#EXTINF:-1")
                o.optString("epg_channel_id").takeIf { it.isNotBlank() && it != "null" }?.let { append(" tvg-id=\"").append(attr(it)).append('"') }
                o.optString("stream_icon").takeIf { it.startsWith("http") }?.let { append(" tvg-logo=\"").append(attr(it)).append('"') }
                append(" group-title=\"").append(attr(group)).append("\",").append(name.replace('\n', ' ')).append('\n')
                append(acc.server).append("/live/").append(user).append('/').append(pass).append('/').append(id).append('.').append(ext).append('\n')
            }
        }
    }

    fun expiryText(info: XtreamInfo): String =
        info.expires?.let { "الاشتراك ساري لحد " + SimpleDateFormat("d/M/yyyy", Locale.US).format(Date(it)) } ?: "اشتراك مفتوح"
}

/** stream_id of an Xtream live URL: ".../live/user/pass/1234.ts" → "1234". */
fun xtreamStreamId(url: String): String? =
    url.substringAfterLast('/').substringBefore('.').takeIf { id -> id.isNotEmpty() && id.all { it.isDigit() } }

/** Current + next programmes for one channel (a few hundred bytes, instead of the panel's whole XMLTV file). */
suspend fun Xtream.shortEpg(acc: XtreamAccount, streamId: String): List<Programme> = withContext(Dispatchers.IO) {
    val q = "username=${URLEncoder.encode(acc.user, "UTF-8")}&password=${URLEncoder.encode(acc.pass, "UTF-8")}"
    val req = Request.Builder()
        .url("${acc.server}/player_api.php?$q&action=get_short_epg&stream_id=$streamId&limit=4")
        .header("User-Agent", DEFAULT_UA).build()
    val body = sharedHttp.newCall(req).execute().use { r -> if (r.isSuccessful) r.body?.string() else null } ?: return@withContext emptyList()
    val arr = runCatching { JSONObject(body).optJSONArray("epg_listings") }.getOrNull() ?: return@withContext emptyList()
    fun b64(s: String) = runCatching { String(android.util.Base64.decode(s, android.util.Base64.DEFAULT)) }.getOrDefault(s)
    (0 until arr.length()).mapNotNull { i ->
        val o = arr.optJSONObject(i) ?: return@mapNotNull null
        val start = o.optString("start_timestamp").toLongOrNull()?.times(1000) ?: return@mapNotNull null
        val stop = o.optString("stop_timestamp").toLongOrNull()?.times(1000) ?: return@mapNotNull null
        val title = b64(o.optString("title")).trim()
        if (title.isEmpty()) null else Programme(start, stop, title)
    }.sortedBy { it.start }
}

// ---------------------------------------------------------------- Movies & series (VOD)

data class VodItem(val id: String, val name: String, val poster: String?, val category: String, val ext: String, val rating: String?)
data class SeriesItem(val id: String, val name: String, val cover: String?, val category: String, val plot: String?, val rating: String?)
data class Episode(val id: String, val season: Int, val num: Int, val title: String, val ext: String, val image: String?)
data class SeriesDetail(val item: SeriesItem, val seasons: Map<Int, List<Episode>>)

private fun XtreamAccount.q() = "username=${URLEncoder.encode(user, "UTF-8")}&password=${URLEncoder.encode(pass, "UTF-8")}"
private fun XtreamAccount.enc(s: String) = URLEncoder.encode(s, "UTF-8")

private fun fetch(url: String): String {
    val req = Request.Builder().url(url).header("User-Agent", DEFAULT_UA).build()
    sharedHttp.newCall(req).execute().use { r ->
        if (!r.isSuccessful) error("السيرفر رد بـ ${r.code}")
        return r.body?.string() ?: error("رد فاضي من السيرفر")
    }
}

private fun categories(acc: XtreamAccount, action: String): Map<String, String> = runCatching {
    val arr = JSONArray(fetch("${acc.server}/player_api.php?${acc.q()}&action=$action"))
    (0 until arr.length()).associate { i -> arr.getJSONObject(i).let { it.optString("category_id") to it.optString("category_name") } }
}.getOrDefault(emptyMap())

private fun JSONObject.str(key: String) = optString(key).takeIf { it.isNotBlank() && it != "null" }

suspend fun Xtream.movies(acc: XtreamAccount): List<VodItem> = withContext(Dispatchers.IO) {
    val cats = categories(acc, "get_vod_categories")
    val arr = JSONArray(fetch("${acc.server}/player_api.php?${acc.q()}&action=get_vod_streams"))
    (0 until arr.length()).mapNotNull { i ->
        val o = arr.optJSONObject(i) ?: return@mapNotNull null
        val id = o.str("stream_id") ?: return@mapNotNull null
        VodItem(
            id, o.str("name") ?: "فيلم $id", o.str("stream_icon")?.takeIf { it.startsWith("http") },
            cats[o.optString("category_id")] ?: GROUP_DEFAULT, o.str("container_extension") ?: "mp4",
            o.str("rating")?.takeIf { it != "0" },
        )
    }
}

suspend fun Xtream.series(acc: XtreamAccount): List<SeriesItem> = withContext(Dispatchers.IO) {
    val cats = categories(acc, "get_series_categories")
    val arr = JSONArray(fetch("${acc.server}/player_api.php?${acc.q()}&action=get_series"))
    (0 until arr.length()).mapNotNull { i ->
        val o = arr.optJSONObject(i) ?: return@mapNotNull null
        val id = o.str("series_id") ?: return@mapNotNull null
        SeriesItem(
            id, o.str("name") ?: "مسلسل $id", o.str("cover")?.takeIf { it.startsWith("http") },
            cats[o.optString("category_id")] ?: GROUP_DEFAULT, o.str("plot"), o.str("rating")?.takeIf { it != "0" },
        )
    }
}

suspend fun Xtream.seriesInfo(acc: XtreamAccount, item: SeriesItem): SeriesDetail = withContext(Dispatchers.IO) {
    val json = JSONObject(fetch("${acc.server}/player_api.php?${acc.q()}&action=get_series_info&series_id=${item.id}"))
    val seasons = sortedMapOf<Int, MutableList<Episode>>()
    fun add(o: JSONObject, seasonHint: Int?) {
        val id = o.str("id") ?: return
        val season = o.optString("season").toIntOrNull() ?: seasonHint ?: 1
        val info = o.optJSONObject("info")
        seasons.getOrPut(season) { ArrayList() } += Episode(
            id, season, o.optString("episode_num").toIntOrNull() ?: 0,
            o.str("title") ?: "الحلقة ${o.optString("episode_num")}", o.str("container_extension") ?: "mp4",
            info?.str("movie_image")?.takeIf { it.startsWith("http") },
        )
    }
    // Panels return "episodes" either as {"1":[...],"2":[...]} or as [[...],[...]].
    json.optJSONObject("episodes")?.let { eps ->
        eps.keys().forEach { k -> eps.optJSONArray(k)?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { add(it, k.toIntOrNull()) } } }
    } ?: json.optJSONArray("episodes")?.let { outer ->
        for (s in 0 until outer.length()) outer.optJSONArray(s)?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { add(it, s + 1) } }
    }
    val plot = json.optJSONObject("info")?.str("plot") ?: item.plot
    SeriesDetail(item.copy(plot = plot), seasons.mapValues { (_, v) -> v.sortedBy { it.num } })
}

fun XtreamAccount.movieUrl(v: VodItem) = "$server/movie/${enc(user)}/${enc(pass)}/${v.id}.${v.ext}"
fun XtreamAccount.episodeUrl(e: Episode) = "$server/series/${enc(user)}/${enc(pass)}/${e.id}.${e.ext}"
