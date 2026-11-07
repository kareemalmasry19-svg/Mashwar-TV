package com.mashwar.tv

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.zip.GZIPInputStream

/**
 * Free-to-air channels for Egypt and the Arab countries.
 * Built every 6 hours on GitHub (catalog/build.mjs) from IPTV Nexus, with iptv-org as fallback,
 * and published as a ~30 KB file — the app never downloads the 25 MB source catalogues.
 */
object FreeCatalog {
    const val SOURCE = "free:arab"
    private const val BASE = "https://github.com/kareemalmasry19-svg/Mashwar-TV/releases/download/catalog"
    const val URL = "$BASE/arab.json.gz"
    const val EPG_URL = "$BASE/arab-epg.xml.gz"
    const val MAX_AGE_MS = 6 * 60 * 60 * 1000L

    private fun cacheFile(ctx: Context) = File(ctx.filesDir, "free_catalog.json")

    fun isFresh(modifiedMs: Long, nowMs: Long, maxAgeMs: Long = MAX_AGE_MS): Boolean =
        modifiedMs > 0 && nowMs >= modifiedMs && nowMs - modifiedMs < maxAgeMs

    fun cacheIsFresh(ctx: Context): Boolean = cacheFile(ctx).let { it.exists() && isFresh(it.lastModified(), System.currentTimeMillis()) }

    /** Catalogue as M3U text. Uses the cache when fresh; falls back to the cache when the network fails. */
    suspend fun text(ctx: Context, force: Boolean = false): String = withContext(Dispatchers.IO) {
        val cache = cacheFile(ctx)
        if (!force && cacheIsFresh(ctx)) return@withContext toM3u(cache.readText())
        val json = runCatching { download() }.getOrNull()
        if (json != null) {
            cache.writeText(json)
            return@withContext toM3u(json)
        }
        if (cache.exists()) return@withContext toM3u(cache.readText())
        error("مقدرتش أحمّل القنوات المجانية، اتأكد من النت")
    }

    private fun download(): String {
        val req = Request.Builder().url(URL).header("User-Agent", DEFAULT_UA).build()
        val bytes = sharedHttp.newCall(req).execute().use { r ->
            if (!r.isSuccessful) error("HTTP ${r.code}")
            r.body?.bytes() ?: error("empty")
        }
        val text = if (bytes.size > 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte())
            GZIPInputStream(bytes.inputStream()).bufferedReader().readText() else String(bytes)
        require(JSONObject(text).optJSONArray("channels") != null) { "bad catalogue" }
        return text
    }

    /** Converts the compact catalogue JSON into M3U the rest of the app already understands. */
    fun toM3u(json: String): String {
        val root = JSONObject(json)
        val list = root.optJSONArray("channels") ?: JSONArray()
        val sb = StringBuilder("#EXTM3U x-tvg-url=\"$EPG_URL\"\n")
        fun attr(s: String) = s.replace('"', '\'').replace('\n', ' ')
        for (i in 0 until list.length()) {
            val c = list.optJSONObject(i) ?: continue
            val id = c.optString("id")
            val name = c.optString("n")
            if (id.isBlank() || name.isBlank()) continue
            val streams = c.optJSONArray("s") ?: continue
            val s = streams.optJSONObject(0) ?: continue
            val url = s.optString("u")
            if (!url.startsWith("http://") && !url.startsWith("https://")) continue
            val cc = c.optString("c").uppercase(Locale.ROOT)
            val cats = c.optJSONArray("cat")?.let { a -> (0 until a.length()).joinToString(",") { a.optString(it) } }.orEmpty()
            val langs = c.optJSONArray("l")?.let { a -> (0 until a.length()).joinToString(",") { a.optString(it) } }.orEmpty()
            sb.append("#EXTINF:-1 tvg-id=\"").append(attr(id)).append('"')
            c.optString("logo").takeIf { it.startsWith("http") }?.let { sb.append(" tvg-logo=\"").append(attr(it)).append('"') }
            if (cc.isNotEmpty()) sb.append(" tvg-country=\"").append(cc).append('"')
            if (langs.isNotEmpty()) sb.append(" tvg-language=\"").append(attr(langs)).append('"')
            if (cats.isNotEmpty()) sb.append(" x-categories=\"").append(attr(cats)).append('"')
            (c.optString("q").takeIf { it.isNotBlank() && it != "null" } ?: s.optString("q").takeIf { it.isNotBlank() && it != "null" })
                ?.let { sb.append(" x-quality=\"").append(attr(it)).append('"') }
            sb.append(" x-status=\"").append(attr(s.optString("st").ifBlank { "unknown" })).append('"')
            sb.append(" group-title=\"").append(attr(countryName(cc))).append("\",").append(name.replace('\n', ' ')).append('\n')
            s.optString("ua").takeIf { it.isNotBlank() && it != "null" }?.let { sb.append("#EXTVLCOPT:http-user-agent=").append(it).append('\n') }
            s.optString("ref").takeIf { it.isNotBlank() && it != "null" }?.let { sb.append("#EXTVLCOPT:http-referrer=").append(it).append('\n') }
            sb.append(url).append('\n')
        }
        return sb.toString()
    }

    private val ARABIC_NAMES = mapOf(
        "EG" to "🇪🇬 مصر", "SA" to "🇸🇦 السعودية", "AE" to "🇦🇪 الإمارات", "KW" to "🇰🇼 الكويت", "QA" to "🇶🇦 قطر",
        "BH" to "🇧🇭 البحرين", "OM" to "🇴🇲 عُمان", "YE" to "🇾🇪 اليمن", "IQ" to "🇮🇶 العراق", "SY" to "🇸🇾 سوريا",
        "LB" to "🇱🇧 لبنان", "JO" to "🇯🇴 الأردن", "PS" to "🇵🇸 فلسطين", "SD" to "🇸🇩 السودان", "LY" to "🇱🇾 ليبيا",
        "TN" to "🇹🇳 تونس", "DZ" to "🇩🇿 الجزائر", "MA" to "🇲🇦 المغرب", "MR" to "🇲🇷 موريتانيا", "SO" to "🇸🇴 الصومال",
        "DJ" to "🇩🇯 جيبوتي", "KM" to "🇰🇲 جزر القمر",
    )

    /** Arab countries by name; Arabic channels broadcast from elsewhere go under "عربي من الخارج". */
    fun countryName(cc: String): String = ARABIC_NAMES[cc] ?: "🌍 عربي من الخارج"

    val CATEGORY_NAMES = linkedMapOf(
        "sports" to "رياضة", "news" to "أخبار", "general" to "عام", "movies" to "أفلام", "series" to "مسلسلات",
        "kids" to "أطفال", "animation" to "كرتون", "music" to "موسيقى", "religious" to "ديني", "entertainment" to "ترفيه",
        "documentary" to "وثائقي", "education" to "تعليمي", "culture" to "ثقافة", "business" to "اقتصاد",
        "family" to "عائلي", "comedy" to "كوميدي", "cooking" to "طبخ", "classic" to "كلاسيك", "lifestyle" to "منوعات",
        "travel" to "سفر", "science" to "علوم", "weather" to "طقس", "auto" to "سيارات", "shop" to "تسوق",
        "legislative" to "برلمان", "outdoor" to "طبيعة",
    )

    val LANGUAGE_NAMES = mapOf("ara" to "عربي", "eng" to "إنجليزي", "fra" to "فرنساوي", "kur" to "كردي", "fas" to "فارسي", "urd" to "أوردو", "som" to "صومالي", "tur" to "تركي")
}
