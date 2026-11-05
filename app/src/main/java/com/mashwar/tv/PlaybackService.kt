@file:OptIn(UnstableApi::class)

package com.mashwar.tv

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * One player for everything: the phone screen, the notification, and Android Auto
 * all control this session, so steering-wheel buttons and the car screen stay in sync.
 */
class PlaybackService : MediaLibraryService() {
    private var session: MediaLibrarySession? = null
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
            .build()
        player.repeatMode = Player.REPEAT_MODE_ALL
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                // Live stream fell behind: jump back to the live edge.
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    player.seekToDefaultPosition()
                    player.prepare()
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
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        session?.run {
            player.release()
            release()
        }
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
            library.reload() // pick up playlists/favourites changed in the phone UI
            val rootExtras = Bundle().apply {
                putInt("android.media.browse.CONTENT_STYLE_BROWSABLE_HINT", 1) // list
                putInt("android.media.browse.CONTENT_STYLE_PLAYABLE_HINT", 1)  // list
                putBoolean("android.media.browse.SEARCH_SUPPORTED", true)
            }
            val outParams = LibraryParams.Builder().setExtras(rootExtras).build()
            return done(LibraryResult.ofItem(library.root(), outParams))
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val item = library.item(mediaId)
                ?: return done(LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE))
            return done(LibraryResult.ofItem(item, null))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val kids = library.children(parentId)
                ?: return done(LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE))
            return done(LibraryResult.ofItemList(page(kids, page, pageSize), params))
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> {
            session.notifySearchResultChanged(browser, query, library.search(query).size, params)
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
            done(LibraryResult.ofItemList(page(library.search(query), page, pageSize), params))

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> =
            done(expand(mediaItems).first.toMutableList())

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val (items, expandedStart) = expand(mediaItems)
            val start = expandedStart ?: startIndex.takeIf { it in items.indices } ?: 0
            return done(MediaSession.MediaItemsWithStartPosition(items, start, C.TIME_UNSET))
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
