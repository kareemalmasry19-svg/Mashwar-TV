@file:OptIn(UnstableApi::class)

package com.mashwar.tv

import android.app.Activity
import android.content.Context
import android.content.ComponentName
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay

private fun Context.findActivity(): Activity? {
    var c = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/** Connects to [PlaybackService]; the same player Android Auto and the notification use. */
@Composable
private fun rememberMediaController(): MediaController? {
    val ctx = LocalContext.current
    var controller by remember { mutableStateOf<MediaController?>(null) }
    DisposableEffect(Unit) {
        val token = SessionToken(ctx, ComponentName(ctx, PlaybackService::class.java))
        val future = MediaController.Builder(ctx, token).buildAsync()
        future.addListener({ controller = runCatching { future.get() }.getOrNull() }, ContextCompat.getMainExecutor(ctx))
        onDispose {
            controller?.stop()
            controller?.clearMediaItems()
            MediaController.releaseFuture(future)
            controller = null
        }
    }
    return controller
}

private val RESIZE_MODES = listOf(
    AspectRatioFrameLayout.RESIZE_MODE_FIT to "ملائم",
    AspectRatioFrameLayout.RESIZE_MODE_ZOOM to "تكبير",
    AspectRatioFrameLayout.RESIZE_MODE_FILL to "مطّ",
)

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(vm: MainViewModel) {
    val channel = vm.nowPlaying ?: return
    val ctx = LocalContext.current
    BackHandler { vm.closePlayer() }

    val player = rememberMediaController()
    var isPlaying by remember { mutableStateOf(false) }
    var buffering by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var showControls by remember { mutableStateOf(true) }
    var interaction by remember { mutableIntStateOf(0) }
    var resizeIdx by remember { mutableIntStateOf(0) }
    var retryKey by remember { mutableIntStateOf(0) }

    // Full screen + landscape while the player is open; restore on exit.
    DisposableEffect(Unit) {
        val activity = ctx.findActivity()
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        val oldOrientation = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            if (activity != null && oldOrientation != null) activity.requestedOrientation = oldOrientation
        }
    }

    DisposableEffect(player) {
        val p = player ?: return@DisposableEffect onDispose { }
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_READY) error = null
            }
            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                error = null
                vm.syncIndex(p.currentMediaItemIndex)
            }
            override fun onPlayerError(e: PlaybackException) {
                // BEHIND_LIVE_WINDOW is recovered by the service itself.
                if (e.errorCode != PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    error = "القناة مش شغالة دلوقتي\n(${e.errorCodeName})"
                    showControls = true
                }
            }
        }
        p.addListener(listener)
        onDispose { p.removeListener(listener) }
    }

    // New pick from the phone list → hand the whole list to the service as a queue.
    LaunchedEffect(player, vm.queueVersion) {
        val p = player ?: return@LaunchedEffect
        error = null
        buffering = true
        p.setMediaItems(vm.queue.map { it.toMediaItem() }, vm.playingIndex.coerceAtLeast(0), C.TIME_UNSET)
        p.prepare()
        p.play()
        showControls = true
        interaction++
    }

    LaunchedEffect(retryKey) {
        if (retryKey == 0) return@LaunchedEffect
        val p = player ?: return@LaunchedEffect
        error = null
        buffering = true
        p.prepare()
        p.play()
    }

    // Auto-hide controls after 4 seconds of no interaction.
    LaunchedEffect(showControls, interaction) {
        if (showControls && error == null) {
            delay(4_000)
            showControls = false
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = {
                PlayerView(it).apply {
                    useController = false
                    keepScreenOn = true
                }
            },
            update = {
                it.player = player
                it.resizeMode = RESIZE_MODES[resizeIdx].first
            },
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier.fillMaxSize().clickable(
                interactionSource = remember { MutableInteractionSource() }, indication = null,
            ) { showControls = !showControls; interaction++ },
        )

        if (buffering && error == null) {
            CircularProgressIndicator(Modifier.align(Alignment.Center).size(64.dp))
        }
        error?.let {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(it, color = Color.White, fontSize = 20.sp)
                Spacer(Modifier.height(12.dp))
                Button(onClick = { retryKey++ }) { Text("جرّب تاني", fontSize = 18.sp) }
            }
        }

        AnimatedVisibility(showControls, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
                // Top bar
                Row(
                    Modifier.fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent)))
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { vm.closePlayer() }, Modifier.size(56.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "رجوع", tint = Color.White, modifier = Modifier.size(32.dp))
                    }
                    Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                        Text(channel.name, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${vm.playingIndex + 1} / ${vm.queue.size} • ${channel.group}", color = Color(0xFFB0BEC5), fontSize = 14.sp, maxLines = 1)
                    }
                    val fav = channel.url in vm.favorites
                    IconButton(onClick = { vm.toggleFavorite(channel); interaction++ }, Modifier.size(56.dp)) {
                        Icon(if (fav) Icons.Filled.Star else Icons.Filled.StarBorder, "مفضلة",
                            tint = if (fav) MaterialTheme.colorScheme.secondary else Color.White, modifier = Modifier.size(32.dp))
                    }
                    Row(
                        Modifier.clickable { resizeIdx = (resizeIdx + 1) % RESIZE_MODES.size; interaction++ }.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.AspectRatio, null, tint = Color.White, modifier = Modifier.size(30.dp))
                        Spacer(Modifier.size(6.dp))
                        Text(RESIZE_MODES[resizeIdx].second, color = Color.White, fontSize = 16.sp)
                    }
                }

                // Big centre controls — kept left-to-right like every media player.
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Row(
                        Modifier.align(Alignment.Center),
                        horizontalArrangement = Arrangement.spacedBy(48.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BigButton(Icons.Filled.SkipPrevious, "السابقة", 72.dp) { player?.seekToPreviousMediaItem(); interaction++ }
                        BigButton(if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, "تشغيل", 96.dp) {
                            player?.let { if (it.isPlaying) it.pause() else it.play() }
                            interaction++
                        }
                        BigButton(Icons.Filled.SkipNext, "التالية", 72.dp) { player?.seekToNextMediaItem(); interaction++ }
                    }
                }
            }
        }
    }
}

@Composable
private fun BigButton(icon: ImageVector, label: String, size: Dp, onClick: () -> Unit) {
    Box(
        Modifier.size(size).background(Color(0x99000000), CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = Color.White, modifier = Modifier.size(size * 0.6f))
    }
}
