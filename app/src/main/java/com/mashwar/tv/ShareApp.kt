package com.mashwar.tv

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * "Send the app to a friend": shares the installed APK plus a small playlists file.
 * Only M3U playlists without account credentials go in the file — never Xtream logins, passwords,
 * or M3U links that carry a username/password. The free channels need nothing: they are built in.
 */
object ShareApp {
    private const val MARK = "almasry_tv_pack"

    data class Item(val name: String, val url: String?, val text: String?, val epgUrl: String?)

    private val credentialUrl = Regex("""(?i)(username=|password=|/get\.php|/player_api\.php|/(live|movie|series)/[^/\s]+/[^/\s]+/\d+)""")

    /** True if a playlist (its link or its content) would leak someone's subscription. */
    fun hasCredentials(source: String, text: String?): Boolean =
        credentialUrl.containsMatchIn(source) || (text != null && credentialUrl.containsMatchIn(text))

    fun buildPack(playlists: List<Playlist>, loadRaw: (String) -> String?): Pair<String, List<String>> {
        val arr = JSONArray()
        val skipped = ArrayList<String>()
        for (p in playlists) {
            if (p.source == FreeCatalog.SOURCE || p.source == "sample") continue // built in on every install
            if (XtreamAccount.decode(p.source) != null) { skipped += p.name; continue }
            val isLink = p.source.startsWith("http://") || p.source.startsWith("https://")
            val text = if (isLink) null else loadRaw(p.id)
            if (hasCredentials(p.source, text)) { skipped += p.name; continue }
            if (!isLink && text.isNullOrBlank()) continue
            arr.put(JSONObject().put("name", p.name).apply {
                if (isLink) put("url", p.source) else put("text", text)
                p.epgUrl?.let { put("epg", it) }
            })
        }
        return JSONObject().put(MARK, 1).put("playlists", arr).toString() to skipped
    }

    fun isPack(text: String) = text.trimStart().startsWith("{") && text.contains("\"$MARK\"")

    fun readPack(json: String): List<Item> {
        val root = JSONObject(json)
        require(root.has(MARK))
        val arr = root.optJSONArray("playlists") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val name = o.optString("name").ifBlank { "قائمة" }
            val url = o.optString("url").takeIf { it.startsWith("http://") || it.startsWith("https://") }
            val text = o.optString("text").takeIf { it.isNotBlank() }
            if (url == null && text == null) null
            else if (hasCredentials(url.orEmpty(), text)) null // never import someone's account by accident
            else Item(name, url, text, o.optString("epg").takeIf { it.startsWith("http") })
        }
    }

    /** Opens the share sheet with the APK and the playlists file. */
    fun share(ctx: Context, playlists: List<Playlist>, onNote: (String) -> Unit) {
        runCatching {
            val dir = File(ctx.cacheDir, "share").apply { deleteRecursively(); mkdirs() }
            val apk = File(dir, "AlmasryTV.apk")
            File(ctx.applicationInfo.sourceDir).copyTo(apk, overwrite = true)
            val store = Store(ctx)
            val (pack, skipped) = buildPack(playlists, store::loadRaw)
            val packFile = File(dir, "AlmasryTV-playlists.json").apply { writeText(pack) }
            val auth = "${ctx.packageName}.share"
            val uris = arrayListOf(FileProvider.getUriForFile(ctx, auth, apk), FileProvider.getUriForFile(ctx, auth, packFile))
            val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "*/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                putExtra(Intent.EXTRA_TEXT,
                    "المصري TV 📺\n1) سطّب AlmasryTV.apk\n2) افتح ملف AlmasryTV-playlists.json بالتطبيق عشان القوايم تتضاف\nالقنوات المجانية موجودة جوه التطبيق لوحدها.")
                clipData = ClipData.newRawUri("", uris[0]).apply { addItem(ClipData.Item(uris[1])) }
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            ctx.startActivity(Intent.createChooser(send, "ابعت المصري TV").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            if (skipped.isNotEmpty()) onNote("مابعتش: ${skipped.joinToString("، ")} — فيها بيانات اشتراك (يوزر/باسورد)")
        }.onFailure { onNote("مقدرتش أجهّز المشاركة: ${it.message}") }
    }
}
