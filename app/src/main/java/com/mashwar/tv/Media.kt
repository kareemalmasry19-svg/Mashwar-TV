@file:OptIn(UnstableApi::class)

package com.mashwar.tv

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy

private const val EXTRA_UA = "mashwar.ua"
private const val EXTRA_REF = "mashwar.ref"
private const val EXTRA_TRY = "mashwar.try"
private const val EXTRA_MIME0 = "mashwar.mime0"

val FORMAT_ERRORS = setOf(
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
    PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
    PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
)

/**
 * Same channel, next stream format to try (HLS → plain stream/TS → DASH), skipping the
 * one that just failed. Returns null when every format has been tried.
 */
fun nextFormatAttempt(item: MediaItem): MediaItem? {
    val rm = item.requestMetadata
    val extras = Bundle(rm.extras ?: Bundle())
    val attempt = extras.getInt(EXTRA_TRY, 0)
    // Remember the first guess so every later attempt skips it.
    if (attempt == 0) extras.putString(EXTRA_MIME0, item.localConfiguration?.mimeType ?: "")
    val first = extras.getString(EXTRA_MIME0)?.ifEmpty { null }
    val candidates = listOf(MimeTypes.APPLICATION_M3U8, null, MimeTypes.APPLICATION_MPD).filter { it != first }
    if (attempt >= candidates.size) return null
    extras.putInt(EXTRA_TRY, attempt + 1)
    return item.buildUpon()
        .setMimeType(candidates[attempt])
        .setRequestMetadata(rm.buildUpon().setExtras(extras).build())
        .build()
}

fun guessMime(url: String): String? {
    val u = url.lowercase()
    return when {
        "m3u8" in u -> MimeTypes.APPLICATION_M3U8
        ".mpd" in u -> MimeTypes.APPLICATION_MPD
        else -> null
    }
}

/**
 * A playable MediaItem for a channel. The URL and HTTP headers also travel in
 * requestMetadata, because that part survives being sent from a controller
 * (phone UI / Android Auto) to the playback service.
 */
fun Channel.toMediaItem(mediaId: String = url): MediaItem {
    val extras = Bundle().apply {
        userAgent?.let { putString(EXTRA_UA, it) }
        referrer?.let { putString(EXTRA_REF, it) }
    }
    return MediaItem.Builder()
        .setMediaId(mediaId)
        .setUri(url)
        .setMimeType(guessMime(url))
        .setRequestMetadata(
            MediaItem.RequestMetadata.Builder().setMediaUri(Uri.parse(url)).setExtras(extras).build()
        )
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(name)
                .setDisplayTitle(name)
                .setArtist(group)
                .setArtworkUri(logo?.let(Uri::parse))
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_TV_CHANNEL)
                .build()
        )
        .build()
}

fun folderItem(id: String, title: String): MediaItem =
    MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setDisplayTitle(title)
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                .build()
        )
        .build()

/** Builds each source with that channel's own User-Agent / Referer (applies to HLS segments too). */
class ChannelSourceFactory(private val context: Context) : MediaSource.Factory {
    private var drm: DrmSessionManagerProvider? = null
    // IPTV servers drop segment requests a lot: retry each load more before failing.
    private var errorPolicy: LoadErrorHandlingPolicy = DefaultLoadErrorHandlingPolicy(8)

    override fun setDrmSessionManagerProvider(drmSessionManagerProvider: DrmSessionManagerProvider): MediaSource.Factory {
        drm = drmSessionManagerProvider
        return this
    }

    override fun setLoadErrorHandlingPolicy(loadErrorHandlingPolicy: LoadErrorHandlingPolicy): MediaSource.Factory {
        errorPolicy = loadErrorHandlingPolicy
        return this
    }

    override fun getSupportedTypes(): IntArray =
        intArrayOf(C.CONTENT_TYPE_HLS, C.CONTENT_TYPE_DASH, C.CONTENT_TYPE_OTHER)

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val extras = mediaItem.requestMetadata.extras
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(extras?.getString(EXTRA_UA) ?: DEFAULT_UA)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
        extras?.getString(EXTRA_REF)?.let { http.setDefaultRequestProperties(mapOf("Referer" to it)) }
        val factory = DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http))
        drm?.let { factory.setDrmSessionManagerProvider(it) }
        factory.setLoadErrorHandlingPolicy(errorPolicy)
        return factory.createMediaSource(mediaItem)
    }
}

/**
 * Browse tree for Android Auto:
 *   root → [★ favourites] + playlists → groups (if more than one) → channels
 * Channel ids carry their parent ("ch~<playlist>~<index>@<parent>") so a pick
 * from the car can queue its siblings for next/previous on the steering wheel.
 */
class ChannelLibrary(context: Context) {
    private val store = Store(context)
    private var playlistsCache: List<Playlist>? = null
    private val channelsCache = HashMap<String, List<Channel>>()

    fun reload() {
        playlistsCache = null
        channelsCache.clear()
    }

    private fun playlists(): List<Playlist> = playlistsCache ?: store.playlists().also { playlistsCache = it }
    private fun channels(pl: String): List<Channel> =
        channelsCache.getOrPut(pl) { M3uParser.parse(store.loadRaw(pl) ?: "") }
    private fun groups(pl: String): List<String> = channels(pl).map { it.group }.distinct()

    private fun chId(pl: String, idx: Int, parent: String) = "ch~$pl~$idx@$parent"

    private fun channelOf(id: String): Channel? {
        val parts = id.substringBefore('@').split('~')
        if (parts.size != 3 || parts[0] != "ch") return null
        return channels(parts[1]).getOrNull(parts[2].toIntOrNull() ?: return null)
    }

    private fun favourites(): List<MediaItem> {
        val favs = store.favorites
        if (favs.isEmpty()) return emptyList()
        val seen = HashSet<String>()
        return playlists().flatMap { p ->
            channels(p.id).withIndex()
                .filter { it.value.url in favs && seen.add(it.value.url) }
                .map { it.value.toMediaItem(chId(p.id, it.index, FAVS)) }
        }
    }

    fun root(): MediaItem = folderItem(ROOT, "مشوار TV")

    fun item(id: String): MediaItem? = when {
        id == ROOT -> root()
        id == FAVS -> folderItem(FAVS, "★ المفضلة")
        id.startsWith("pl~") -> playlists().find { it.id == id.removePrefix("pl~") }?.let { folderItem(id, it.name) }
        id.startsWith("grp~") -> {
            val parts = id.split('~')
            parts.getOrNull(2)?.toIntOrNull()?.let { groups(parts[1]).getOrNull(it) }?.let { folderItem(id, it) }
        }
        id.startsWith("ch~") -> channelOf(id)?.toMediaItem(id)
        else -> null
    }

    fun children(parent: String): List<MediaItem>? = when {
        parent == ROOT -> buildList {
            if (store.favorites.isNotEmpty()) add(folderItem(FAVS, "★ المفضلة"))
            playlists().forEach { add(folderItem("pl~${it.id}", it.name)) }
        }
        parent == FAVS -> favourites()
        parent.startsWith("pl~") -> {
            val pl = parent.removePrefix("pl~")
            val gs = groups(pl)
            if (gs.size > 1) gs.mapIndexed { i, g -> folderItem("grp~$pl~$i", g) }
            else channels(pl).mapIndexed { i, c -> c.toMediaItem(chId(pl, i, parent)) }
        }
        parent.startsWith("grp~") -> {
            val parts = parent.split('~')
            val pl = parts.getOrNull(1)
            val name = pl?.let { p -> parts.getOrNull(2)?.toIntOrNull()?.let { groups(p).getOrNull(it) } }
            if (pl == null || name == null) null
            else channels(pl).withIndex().filter { it.value.group == name }
                .map { it.value.toMediaItem(chId(pl, it.index, parent)) }
        }
        parent.startsWith("search~") -> search(parent.removePrefix("search~"))
        else -> null
    }

    fun search(query: String): List<MediaItem> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        return playlists().flatMap { p ->
            channels(p.id).withIndex()
                .filter { it.value.name.contains(q, ignoreCase = true) }
                .map { it.value.toMediaItem(chId(p.id, it.index, "search~$q")) }
        }.take(MAX_SEARCH)
    }

    /** The channel's siblings (same folder) and its position, for next/previous. */
    fun queueFor(id: String): Pair<List<MediaItem>, Int>? {
        val list = children(id.substringAfter('@', ""))?.filter { it.mediaMetadata.isPlayable == true } ?: return null
        val idx = list.indexOfFirst { it.mediaId == id }
        return if (idx >= 0) list to idx else null
    }

    /** Turns whatever a controller sent into a playable item. */
    fun resolve(item: MediaItem): MediaItem? {
        if (item.mediaId.startsWith("ch~")) return item(item.mediaId)
        val uri = item.requestMetadata.mediaUri ?: item.localConfiguration?.uri ?: return null
        return item.buildUpon().setUri(uri).setMimeType(guessMime(uri.toString())).build()
    }

    companion object {
        const val ROOT = "root"
        const val FAVS = "favs"
        private const val MAX_SEARCH = 50
    }
}
