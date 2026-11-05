@file:OptIn(UnstableApi::class)

package com.mashwar.tv

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.SessionAvailabilityListener
import androidx.media3.exoplayer.DefaultLoadControl
import com.google.android.gms.cast.framework.CastContext
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.ListeningExecutorService
import com.google.common.util.concurrent.MoreExecutors
import java.util.concurrent.Executors

/**
 * One player for everything: the phone screen, the notification, and Android Auto
 * all control this session, so steering-wheel buttons and the car screen stay in sync.
 */
class PlaybackService : MediaLibraryService() {
    private var session: MediaLibrarySession? = null
    private val handler = Handler(Looper.getMainLooper())
    private var reconnects = 0
    private lateinit var exo: ExoPlayer
    private var cast: CastPlayer? = null
    /** Items as they were on the phone before casting (Cast drops our HTTP headers). */
    private var beforeCast: List<MediaItem> = emptyList()
    @Volatile private var casting = false
    /** All ChannelLibrary work runs here (big playlists would freeze the car screen on the main thread). */
    private val bg: ListeningExecutorService = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor())
    private lateinit var library: ChannelLibrary

    override fun onCreate() {
        super.onCreate()
        library = ChannelLibrary(this)
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(ChannelSourceFactory(this))
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            // Bigger buffer than the default (50s max) so short network drops don't stall playback.
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(
                        /* minBufferMs = */ 30_000,
                        /* maxBufferMs = */ 120_000,
                        /* bufferForPlaybackMs = */ 2_500,
                        /* bufferForPlaybackAfterRebufferMs = */ 6_000,
                    )
                    .setPrioritizeTimeOverSizeThresholds(true)
                    .build()
            )
            .build()
        player.repeatMode = Player.REPEAT_MODE_ALL
        exo = player
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) reconnects = 0
            }

            override fun onPlayerError(error: PlaybackException) {
                // Network dropped (tunnels, weak signal): reconnect quietly a few times.
                if (error.errorCode in NETWORK_ERRORS && reconnects < MAX_RECONNECTS) {
                    reconnects++
                    handler.postDelayed({
                        if (player.playerError != null) {
                            player.prepare()
                            player.play()
                        }
                    }, 1_500)
                    return
                }
                // Live stream fell behind: jump back to the live edge.
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    player.seekToDefaultPosition()
                    player.prepare()
                    return
                }
                // Many IPTV links hide the real format (e.g. HLS behind ".php" or no extension):
                // try the item again as another format before reporting the error.
                if (error.errorCode in FORMAT_ERRORS) {
                    val i = player.currentMediaItemIndex
                    val retry = player.currentMediaItem?.let { nextFormatAttempt(it) } ?: return
                    player.replaceMediaItem(i, retry)
                    player.prepare()
                    player.play()
                }
            }
        })
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaLibrarySession.Builder(this, player, Callback())
            .setSessionActivity(open)
            .build()
        setUpCast()
    }

    private fun setUpCast() {
        // Needs Google Play services; without them the app simply has no Cast.
        val ctx = runCatching { CastContext.getSharedInstance(this) }.getOrNull() ?: return
        cast = CastPlayer(ctx).also { cp ->
            cp.repeatMode = Player.REPEAT_MODE_ALL
            cp.setSessionAvailabilityListener(object : SessionAvailabilityListener {
                override fun onCastSessionAvailable() = switchTo(cp)
                override fun onCastSessionUnavailable() = switchTo(exo)
            })
        }
    }

    /** Moves the current queue between the phone player and the Cast device. */
    private fun switchTo(target: Player) {
        val s = session ?: return
        val from = s.player
        if (from === target) return
        val toCast = target !== exo
        val index = from.currentMediaItemIndex
        val playWhenReady = from.playWhenReady
        val items = if (toCast) {
            (0 until from.mediaItemCount).map { from.getMediaItemAt(it) }.also { beforeCast = it }
        } else {
            // Back from Cast: same queue, with each item's original headers restored.
            (0 until from.mediaItemCount).map { from.getMediaItemAt(it) }.map { ci ->
                beforeCast.find { it.localConfiguration?.uri == ci.localConfiguration?.uri } ?: ci
            }
        }
        from.stop()
        from.clearMediaItems()
        casting = toCast
        if (items.isNotEmpty()) {
            val list = if (toCast) items.map(::castable) else items
            target.setMediaItems(list, index.coerceIn(0, list.lastIndex), C.TIME_UNSET)
            target.prepare()
            target.playWhenReady = playWhenReady
        }
        s.player = target
    }

    /** The Cast receiver needs an explicit format; IPTV is HLS unless it says TS. */
    private fun castable(item: MediaItem): MediaItem {
        if (item.localConfiguration?.mimeType != null) return item
        val url = item.localConfiguration?.uri?.toString().orEmpty().lowercase()
        val mime = if (".ts" in url && "m3u8" !in url) MimeTypes.VIDEO_MP2T else MimeTypes.APPLICATION_M3U8
        return item.buildUpon().setMimeType(mime).build()
    }

    private fun forPlayer(items: List<MediaItem>) = if (casting) items.map(::castable) else items

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        bg.shutdown()
        cast?.setSessionAvailabilityListener(null)
        cast?.release()
        exo.release()
        session?.release()
        session = null
        super.onDestroy()
    }

    private fun <T> done(v: T): ListenableFuture<T> = Futures.immediateFuture(v)

    private fun <T> page(list: List<T>, page: Int, pageSize: Int): List<T> =
        if (page < 0 || pageSize <= 0 || pageSize == Int.MAX_VALUE) list
        else list.drop(page * pageSize).take(pageSize)

    private inner class Callback : MediaLibrarySession.Callback {

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val rootExtras = Bundle().apply {
                putInt("android.media.browse.CONTENT_STYLE_BROWSABLE_HINT", 1) // list
                putInt("android.media.browse.CONTENT_STYLE_PLAYABLE_HINT", 1)  // list
                putBoolean("android.media.browse.SEARCH_SUPPORTED", true)
            }
            val outParams = LibraryParams.Builder().setExtras(rootExtras).build()
            return bg.submit<LibraryResult<MediaItem>> {
                library.reload() // pick up playlists/favourites changed in the phone UI
                LibraryResult.ofItem(library.root(), outParams)
            }
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            return bg.submit<LibraryResult<MediaItem>> {
                library.item(mediaId)?.let { LibraryResult.ofItem(it, null) }
                    ?: LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
            }
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            return bg.submit<LibraryResult<ImmutableList<MediaItem>>> {
                library.children(parentId)?.let { LibraryResult.ofItemList(page(it, page, pageSize), params) }
                    ?: LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
            }
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> {
            bg.execute {
                val n = library.search(query).size
                handler.post { session.notifySearchResultChanged(browser, query, n, params) }
            }
            return done(LibraryResult.ofVoid())
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
            bg.submit<LibraryResult<ImmutableList<MediaItem>>> {
                LibraryResult.ofItemList(page(library.search(query), page, pageSize), params)
            }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> =
            bg.submit<MutableList<MediaItem>> { forPlayer(expand(mediaItems).first).toMutableList() }

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            return bg.submit<MediaSession.MediaItemsWithStartPosition> {
                val (items, expandedStart) = expand(mediaItems)
                val start = expandedStart ?: startIndex.takeIf { it in items.indices } ?: 0
                MediaSession.MediaItemsWithStartPosition(forPlayer(items), start, C.TIME_UNSET)
            }
        }

        /**
         * - One channel picked in the car → its whole folder, starting at that channel.
         * - Voice "play X" (search query only) → matching channels.
         * - Items from the phone UI → resolved one by one.
         */
        private fun expand(items: List<MediaItem>): Pair<List<MediaItem>, Int?> {
            if (items.size == 1) {
                val one = items[0]
                if (one.mediaId.startsWith("ch~")) {
                    library.queueFor(one.mediaId)?.let { (q, i) -> return q to i }
                }
                val query = one.requestMetadata.searchQuery
                if (query != null && one.requestMetadata.mediaUri == null && !one.mediaId.startsWith("ch~")) {
                    val found = library.search(query)
                    if (found.isNotEmpty()) return found to 0
                }
            }
            return items.mapNotNull { library.resolve(it) } to null
        }
    }
}

private const val MAX_RECONNECTS = 5

private val NETWORK_ERRORS = setOf(
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
    PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
    PlaybackException.ERROR_CODE_TIMEOUT,
)
