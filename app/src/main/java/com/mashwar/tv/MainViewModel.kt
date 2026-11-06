package com.mashwar.tv

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val store = Store(app)

    var playlists by mutableStateOf(store.playlists())
        private set
    var current by mutableStateOf<Playlist?>(null)
        private set
    var channels by mutableStateOf<List<Channel>>(emptyList())
        private set
    var favorites by mutableStateOf(store.favorites)
        private set
    var loading by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)

    var queue by mutableStateOf<List<Channel>>(emptyList())
        private set
    var playingIndex by mutableIntStateOf(-1)
        private set

    val nowPlaying: Channel? get() = queue.getOrNull(playingIndex)

    fun addPlaylist(name: String, source: String, isUri: Boolean) = viewModelScope.launch {
        loading = true
        try {
            val text = fetchPlaylistText(getApplication(), source, isUri)
            savePlaylist(name, source, isUri, text)
        } catch (e: Exception) {
            message = "مقدرتش أحمّل القائمة: ${e.message ?: e.javaClass.simpleName}"
        } finally {
            loading = false
        }
    }

    fun addSample() = viewModelScope.launch { savePlaylist("قائمة تجريبية", "sample", false, SAMPLE_PLAYLIST) }

    private suspend fun savePlaylist(name: String, source: String, isUri: Boolean, text: String) {
        val parsed = withContext(Dispatchers.Default) { M3uParser.parse(text) }
        if (parsed.isEmpty()) {
            message = "الملف ده مفيهوش قنوات (اتأكد إنه M3U)"
            return
        }
        val p = Playlist(UUID.randomUUID().toString(), name.ifBlank { "قائمة ${playlists.size + 1}" }, source, isUri)
        withContext(Dispatchers.IO) { store.saveRaw(p.id, text) }
        playlists = playlists + p
        store.savePlaylists(playlists)
        message = "اتضافت ${parsed.size} قناة"
        channels = parsed // already parsed: open straight away
        current = p
    }

    fun open(p: Playlist) = viewModelScope.launch {
        loading = true
        channels = withContext(Dispatchers.IO) { M3uParser.parse(store.loadRaw(p.id) ?: "") }
        current = p
        loading = false
    }

    fun refresh(p: Playlist) = viewModelScope.launch {
        if (p.source == "sample") return@launch
        loading = true
        try {
            val text = fetchPlaylistText(getApplication(), p.source, p.isUri)
            val parsed = withContext(Dispatchers.Default) { M3uParser.parse(text) }
            if (parsed.isEmpty()) error("القائمة الجديدة فاضية")
            withContext(Dispatchers.IO) { store.saveRaw(p.id, text) }
            message = "اتحدّثت: ${parsed.size} قناة"
            if (current?.id == p.id) channels = parsed
        } catch (e: Exception) {
            message = "التحديث فشل: ${e.message}"
        } finally {
            loading = false
        }
    }

    fun delete(p: Playlist) {
        store.deleteRaw(p.id)
        playlists = playlists.filterNot { it.id == p.id }
        store.savePlaylists(playlists)
    }

    fun rename(p: Playlist, name: String) {
        val n = name.trim().ifEmpty { return }
        playlists = playlists.map { if (it.id == p.id) it.copy(name = n) else it }
        store.savePlaylists(playlists)
        if (current?.id == p.id) current = current?.copy(name = n)
    }

    fun closePlaylist() {
        current = null
        channels = emptyList()
    }

    fun toggleFavorite(c: Channel) {
        favorites = if (c.url in favorites) favorites - c.url else favorites + c.url
        store.favorites = favorites
    }

    val lastChannel: Channel?
        get() = store.lastChannelUrl?.let { url -> channels.firstOrNull { it.url == url } }

    /** Bumped on every new pick, so the player screen reloads the queue only then. */
    var queueVersion by mutableIntStateOf(0)
        private set

    /** Index in [queue] of the player's first item: the player only holds a window around the pick. */
    var windowStart = 0

    fun play(list: List<Channel>, index: Int) {
        queue = list
        playingIndex = index
        queueVersion++
        list.getOrNull(index)?.let { store.lastChannelUrl = it.url }
    }

    /** Called when the player itself moves (next/previous, steering wheel, car screen). */
    /** Player items for the current pick: at most ±[WINDOW] channels around it (big lists froze the app). */
    fun playerWindow(): Pair<List<Channel>, Int> {
        val i = playingIndex.coerceIn(0, (queue.size - 1).coerceAtLeast(0))
        val start = (i - WINDOW).coerceAtLeast(0)
        val end = (i + WINDOW + 1).coerceAtMost(queue.size)
        windowStart = start
        return queue.subList(start, end) to (i - start)
    }

    fun syncIndex(i: Int) {
        if (i !in queue.indices || i == playingIndex) return
        playingIndex = i
        store.lastChannelUrl = queue[i].url
    }

    var maxQuality by mutableIntStateOf(store.maxQuality)
        private set

    fun cycleQuality() {
        val steps = listOf(0, 720, 480, 360)
        maxQuality = steps[(steps.indexOf(maxQuality).coerceAtLeast(0) + 1) % steps.size]
        store.maxQuality = maxQuality
    }

    fun closePlayer() {
        playingIndex = -1
    }
}

private const val WINDOW = 100
