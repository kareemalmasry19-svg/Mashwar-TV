package com.mashwar.tv

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val store = Store(app)

    var playlists by mutableStateOf(store.playlists())
        private set

    /** Playlist management lives on its own screen; home shows folders. */
    var showManage by mutableStateOf(false)
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

    /** Signs in to an Xtream panel, then saves its live channels as a playlist that refreshes from the panel. */
    fun addXtream(name: String, server: String, user: String, pass: String, hls: Boolean, onDone: (String?) -> Unit) =
        viewModelScope.launch {
            loading = true
            try {
                val acc = Xtream.parseInput(server, user, pass, hls)
                val info = Xtream.login(acc)
                val text = Xtream.buildM3u(acc)
                savePlaylist(name.ifBlank { acc.host }, acc.encode(), false, text)
                message = "اتسجّل الدخول ✓ • " + Xtream.expiryText(info) +
                    (info.maxConnections?.let { " • أقصى عدد أجهزة: $it" } ?: "")
                onDone(null)
            } catch (e: Exception) {
                onDone(e.message ?: e.javaClass.simpleName)
            } finally {
                loading = false
            }
        }

    init {
        // First launch (including a friend who just installed the shared app): free channels are ready right away.
        if (playlists.none { it.source == FreeCatalog.SOURCE } && !store.flag("free_auto_added")) {
            store.setFlag("free_auto_added")
            viewModelScope.launch { addFreeCatalogQuietly() }
        }
    }

    private suspend fun addFreeCatalogQuietly() {
        runCatching { FreeCatalog.text(getApplication()) }.onSuccess { text ->
            val parsed = withContext(Dispatchers.Default) { M3uParser.parse(text) }
            if (parsed.isEmpty() || playlists.any { it.source == FreeCatalog.SOURCE }) return
            val p = Playlist(UUID.randomUUID().toString(), "قنوات مجانية — مصر والعرب", FreeCatalog.SOURCE, false, FreeCatalog.EPG_URL)
            withContext(Dispatchers.IO) { store.saveRaw(p.id, text) }
            playlists = listOf(p) + playlists
            store.savePlaylists(playlists)
        }
    }

    /** Playlists someone shared with the app (see ShareApp). Returns how many were added. */
    fun importPack(json: String) = viewModelScope.launch {
        val items = runCatching { ShareApp.readPack(json) }.getOrElse { message = "الملف ده مش ملف قوائم المصري TV"; return@launch }
        var added = 0
        for (item in items) {
            if (playlists.any { it.name == item.name && (it.source == item.url || item.url == null) }) continue
            val text = item.text ?: item.url?.let { url ->
                runCatching { fetchPlaylistText(getApplication(), url, false) }.getOrNull()
            } ?: continue
            val parsed = withContext(Dispatchers.Default) { M3uParser.parse(text) }
            if (parsed.isEmpty()) continue
            val p = Playlist(UUID.randomUUID().toString(), item.name, item.url ?: "shared", false,
                item.epgUrl ?: M3uParser.epgUrlOf(text))
            withContext(Dispatchers.IO) { store.saveRaw(p.id, text) }
            playlists = playlists + p
            store.savePlaylists(playlists)
            added++
        }
        message = if (added > 0) "اتضافت $added قائمة ✓" else "القوايم دي موجودة عندك أو مش شغالة"
    }

    /** A file opened with the app (WhatsApp, Files…): our shared pack, or a plain M3U. */
    fun importFromUri(uri: String) = viewModelScope.launch {
        val text = runCatching { fetchPlaylistText(getApplication(), uri, true) }.getOrElse {
            message = "مقدرتش أفتح الملف"; return@launch
        }
        if (ShareApp.isPack(text)) importPack(text) else savePlaylist("", uri, true, text)
    }

    /** Adds (or opens, if already added) the built-in free channels for Egypt and Arab countries. */
    fun addFreeCatalog() = viewModelScope.launch {
        playlists.firstOrNull { it.source == FreeCatalog.SOURCE }?.let { open(it); return@launch }
        loading = true
        try {
            val text = FreeCatalog.text(getApplication())
            savePlaylist("قنوات مجانية — مصر والعرب", FreeCatalog.SOURCE, false, text)
        } catch (e: Exception) {
            message = e.message ?: "مقدرتش أحمّل القنوات المجانية"
        } finally {
            loading = false
        }
    }

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
        val p = Playlist(
            UUID.randomUUID().toString(), name.ifBlank { "قائمة ${playlists.size + 1}" }, source, isUri,
            epgUrl = M3uParser.epgUrlOf(text),
        )
        withContext(Dispatchers.IO) { store.saveRaw(p.id, text) }
        playlists = playlists + p
        store.savePlaylists(playlists)
        message = "اتضافت ${parsed.size} قناة"
        channels = parsed // already parsed: open straight away
        current = p
        dead = emptySet()
        loadEpg(p)
    }

    fun open(p: Playlist) = viewModelScope.launch {
        loading = true
        val raw = withContext(Dispatchers.IO) { store.loadRaw(p.id) ?: "" }
        channels = withContext(Dispatchers.Default) { M3uParser.parse(raw) }
        // Older playlists: pick up the guide URL from the file header the first time.
        var pl = p
        if (pl.epgUrl == null) M3uParser.epgUrlOf(raw)?.let { url -> pl = p.copy(epgUrl = url); replacePlaylist(pl) }
        current = pl
        dead = store.dead(pl.id)
        // Free catalogue: refresh quietly in the background when the 6-hour copy is stale.
        if (pl.source == FreeCatalog.SOURCE && !FreeCatalog.cacheIsFresh(getApplication())) {
            viewModelScope.launch {
                runCatching { FreeCatalog.text(getApplication(), force = true) }.onSuccess { text ->
                    val fresh = withContext(Dispatchers.Default) { M3uParser.parse(text) }
                    if (fresh.isNotEmpty() && current?.id == pl.id) {
                        withContext(Dispatchers.IO) { store.saveRaw(pl.id, text) }
                        channels = fresh
                    }
                }
            }
        }
        loading = false
        loadEpg(pl)
    }

    // ---------- Programme guide (EPG)
    var epg by mutableStateOf<EpgData?>(null)
        private set
    var epgLoading by mutableStateOf(false)
        private set
    private var epgJob: Job? = null

    fun loadEpg(p: Playlist, force: Boolean = false) {
        epgJob?.cancel()
        epg = null
        shortEpg.clear()
        if (p.epgUrl == null) return
        // Xtream panels: their xmltv.php is often tens of MB and downloading it while watching slowed
        // the stream down. Use the light per-channel guide instead (see requestEpg).
        if (XtreamAccount.decode(p.source) != null && "xmltv.php" in p.epgUrl) return
        val list = channels
        epgJob = viewModelScope.launch {
            epgLoading = true
            val data = Epg.load(getApplication(), p, list, force)
            if (current?.id == p.id) {
                epg = data
                if (force) message = if (data == null || data.isEmpty) "دليل البرامج مفيهوش بيانات للقنوات دي" else "اتحدّث دليل البرامج"
            }
            epgLoading = false
        }
    }

    /** Xtream per-channel guide: url → programmes, filled on demand for channels on screen. */
    private val shortEpg = androidx.compose.runtime.mutableStateMapOf<String, List<Programme>>()
    private val shortEpgAsked = HashMap<String, Long>()
    private val shortEpgGate = Semaphore(3)

    fun requestEpg(c: Channel) {
        val acc = current?.source?.let { XtreamAccount.decode(it) } ?: return
        val id = xtreamStreamId(c.url) ?: return
        val t = System.currentTimeMillis()
        val asked = shortEpgAsked[c.url]
        val have = shortEpg[c.url]
        // re-ask when the cached "now" programme has ended, or every 30 min
        if (asked != null && t - asked < 30 * 60_000 && (have == null || have.any { it.stop > t })) return
        shortEpgAsked[c.url] = t
        viewModelScope.launch {
            val list = shortEpgGate.withPermit { runCatching { Xtream.shortEpg(acc, id) }.getOrDefault(emptyList()) }
            shortEpg[c.url] = list
        }
    }

    /** Now / next for a channel, from the XMLTV guide or the Xtream per-channel guide. */
    fun nowNext(c: Channel, time: Long): Pair<Programme?, Programme?> {
        epg?.let { e -> e.nowNext(c, time).let { if (it.first != null || it.second != null) return it } }
        val list = shortEpg[c.url] ?: return null to null
        val i = list.indexOfFirst { time in it.start until it.stop }
        if (i < 0) return null to list.firstOrNull { it.start > time }
        return list[i] to list.getOrNull(i + 1)
    }

    fun setEpgUrl(p: Playlist, url: String) {
        val u = url.trim().ifEmpty { null }
        val updated = p.copy(epgUrl = u)
        replacePlaylist(updated)
        if (current?.id == p.id) { current = updated; loadEpg(updated, force = true) }
    }

    private fun replacePlaylist(p: Playlist) {
        playlists = playlists.map { if (it.id == p.id) p else it }
        store.savePlaylists(playlists)
    }

    // ---------- Dead-channel scan
    var dead by mutableStateOf<Set<String>>(emptySet())
        private set
    var scanProgress by mutableStateOf<Pair<Int, Int>?>(null)
        private set
    private var scanJob: Job? = null

    fun scanChannels() {
        val p = current ?: return
        val list = channels.distinctBy { it.url }
        if (list.isEmpty() || scanJob?.isActive == true) return
        scanJob = viewModelScope.launch {
            val bad = ConcurrentHashMap.newKeySet<String>()
            val done = AtomicInteger()
            scanProgress = 0 to list.size
            // Xtream subscriptions usually allow only 1–2 streams at once: probing many in parallel gets
            // refused (working channels marked dead) and can flag the account. One at a time there.
            val gate = Semaphore(if (XtreamAccount.decode(p.source) != null) 1 else 12)
            coroutineScope {
                list.forEach { c ->
                    launch {
                        gate.withPermit {
                            if (!ChannelScanner.isAlive(c)) bad += c.url
                            val d = done.incrementAndGet()
                            if (d % 4 == 0 || d == list.size) scanProgress = d to list.size
                        }
                    }
                }
            }
            dead = bad.toSet()
            store.saveDead(p.id, dead)
            scanProgress = null
            message = if (dead.isEmpty()) "كل القنوات شغالة ✓" else "لقيت ${dead.size} قناة مش شغالة واتخفت"
        }
    }

    fun cancelScan() {
        scanJob?.cancel()
        scanProgress = null
    }

    fun clearDead() {
        val p = current ?: return
        dead = emptySet()
        store.saveDead(p.id, emptySet())
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
        section = Section.LIVE
        movies = null; seriesList = null; seriesDetail = null; vodError = null
    }

    // ---------- Movies & series (Xtream VOD)
    enum class Section { LIVE, MOVIES, SERIES }
    var section by mutableStateOf(Section.LIVE)
    var movies by mutableStateOf<List<VodItem>?>(null)
        private set
    var seriesList by mutableStateOf<List<SeriesItem>?>(null)
        private set
    var seriesDetail by mutableStateOf<SeriesDetail?>(null)
        private set
    var vodLoading by mutableStateOf(false)
        private set
    var vodError by mutableStateOf<String?>(null)
        private set

    val xtream: XtreamAccount? get() = current?.source?.let { XtreamAccount.decode(it) }

    fun loadMovies(force: Boolean = false) {
        val acc = xtream ?: return
        if ((movies != null && !force) || vodLoading) return
        viewModelScope.launch {
            vodLoading = true; vodError = null
            runCatching { Xtream.movies(acc) }.onSuccess { movies = it }.onFailure { vodError = it.message }
            vodLoading = false
        }
    }

    fun loadSeries(force: Boolean = false) {
        val acc = xtream ?: return
        if ((seriesList != null && !force) || vodLoading) return
        viewModelScope.launch {
            vodLoading = true; vodError = null
            runCatching { Xtream.series(acc) }.onSuccess { seriesList = it }.onFailure { vodError = it.message }
            vodLoading = false
        }
    }

    fun openSeries(item: SeriesItem) {
        val acc = xtream ?: return
        viewModelScope.launch {
            vodLoading = true
            runCatching { Xtream.seriesInfo(acc, item) }
                .onSuccess { seriesDetail = it }
                .onFailure { message = "مقدرتش أفتح المسلسل: ${it.message}" }
            vodLoading = false
        }
    }

    fun closeSeries() { seriesDetail = null }

    fun playMovie(v: VodItem) {
        val acc = xtream ?: return
        play(listOf(Channel(v.name, acc.movieUrl(v), v.poster, v.category)), 0, vod = true)
    }

    /** Plays a season from the chosen episode; the next episode starts by itself. */
    fun playEpisodes(d: SeriesDetail, episodes: List<Episode>, index: Int) {
        val acc = xtream ?: return
        val list = episodes.map { e ->
            Channel("${d.item.name} • م${e.season} ح${e.num}" + if (e.title.isNotBlank() && !e.title.startsWith(d.item.name)) " • ${e.title}" else "",
                acc.episodeUrl(e), e.image ?: d.item.cover, d.item.name)
        }
        play(list, index, vod = true)
    }

    fun resumePosition(url: String) = store.position(url)
    fun savePosition(url: String, ms: Long) = store.savePosition(url, ms)

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

    /** The channel watched before the current one (receiver-style "last channel"). */
    var previousChannel: Channel? = null
        private set

    /** True while a movie / episode is playing (seek bar, resume, no looping). */
    var playingVod by mutableStateOf(false)
        private set

    fun play(list: List<Channel>, index: Int, vod: Boolean = false) {
        if (!vod && !playingVod) nowPlaying?.let { if (it.url != list.getOrNull(index)?.url) previousChannel = it }
        playingVod = vod
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
        nowPlaying?.let { previousChannel = it }
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
