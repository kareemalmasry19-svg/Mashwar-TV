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
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.appcompat.view.ContextThemeWrapper
import androidx.mediarouter.app.MediaRouteButton
import coil.compose.AsyncImage
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastState
import com.google.android.gms.cast.framework.CastStateListener
import java.util.Locale
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
            val casting = runCatching { CastContext.getSharedInstance(ctx).castState == CastState.CONNECTED }.getOrDefault(false)
            if (!casting) {
                controller?.stop()
                controller?.clearMediaItems()
            }
            MediaController.releaseFuture(future)
            controller = null
        }
    }
    return controller
}

/** Short Arabic explanation for the codes people actually hit with IPTV links. */
private fun explain(e: PlaybackException): String = when (e.errorCode) {
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
    PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
    PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED ->
        "الرابط مش بيرجّع فيديو بصيغة معروفة.\nغالبًا الرابط اتقفل أو بيفتح صفحة بدل البث."
    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
        "السيرفر رفض يشغّل القناة.\nممكن الرابط اتقفل أو محتاج اشتراك."
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
        "مش قادر يوصل للسيرفر.\nاتأكد من النت أو جرّب قناة تانية."
    else -> "القناة مش شغالة دلوقتي."
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
    // Small, almost-square screens (e.g. Galaxy Z Flip cover screen): no forced landscape, smaller controls.
    val cfg = LocalConfiguration.current
    val shortSide = minOf(cfg.screenWidthDp, cfg.screenHeightDp)
    val longSide = maxOf(cfg.screenWidthDp, cfg.screenHeightDp)
    val compact = shortSide < 480 && longSide < shortSide * 1.4f
    var isPlaying by remember { mutableStateOf(false) }
    var buffering by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var showControls by remember { mutableStateOf(true) }
    var interaction by remember { mutableIntStateOf(0) }
    var resizeIdx by remember { mutableIntStateOf(0) }
    var retryKey by remember { mutableIntStateOf(0) }
    var errorTick by remember { mutableIntStateOf(0) }
    var showList by remember { mutableStateOf(false) }

    // Name of the TV we're casting to, or null when playing on the phone.
    var castDevice by remember { mutableStateOf<String?>(null) }
    val castCtx = remember { runCatching { CastContext.getSharedInstance(ctx) }.getOrNull() }
    DisposableEffect(castCtx) {
        val cc = castCtx ?: return@DisposableEffect onDispose { }
        val l = CastStateListener { st ->
            castDevice = if (st == CastState.CONNECTED)
                cc.sessionManager.currentCastSession?.castDevice?.friendlyName ?: "الشاشة" else null
        }
        cc.addCastStateListener(l)
        l.onCastStateChanged(cc.castState)
        onDispose { cc.removeCastStateListener(l) }
    }
    BackHandler(enabled = showList) { showList = false }

    // Full screen + landscape while the player is open; restore on exit.
    DisposableEffect(compact) {
        val activity = ctx.findActivity()
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        val oldOrientation = activity?.requestedOrientation
        if (!compact) activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
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
                // The service retries some errors itself (live edge, other stream formats);
                // only show the message if the player is still failing a moment later.
                errorTick++
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

    // Quality cap: lower resolution = fewer stalls on weak networks. 0 means automatic.
    LaunchedEffect(player, vm.maxQuality) {
        val p = player ?: return@LaunchedEffect
        val q = vm.maxQuality
        val b = p.trackSelectionParameters.buildUpon()
        if (q == 0) b.clearVideoSizeConstraints() else b.setMaxVideoSize(Int.MAX_VALUE, q)
        p.trackSelectionParameters = b.build()
    }

    LaunchedEffect(errorTick) {
        if (errorTick == 0) return@LaunchedEffect
        delay(2_500)
        val e = player?.playerError ?: return@LaunchedEffect
        error = explain(e) + "\n" + e.errorCodeName
        showControls = true
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
        if (showControls && error == null && !showList) {
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
            if (castDevice == null) CircularProgressIndicator(Modifier.align(Alignment.Center).size(64.dp), color = Gold)
        }
        castDevice?.let { name ->
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.Cast, null, tint = Gold, modifier = Modifier.size(if (compact) 48.dp else 72.dp))
                Spacer(Modifier.height(8.dp))
                Text("بيتعرض على $name", color = Cream, fontSize = if (compact) 15.sp else 20.sp)
            }
        }
        error?.let { msg ->
            val text = msg.substringBeforeLast('\n')
            val code = msg.substringAfterLast('\n')
            Column(
                Modifier.align(Alignment.Center).windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(text, color = Color.White, fontSize = if (compact) 16.sp else 20.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(6.dp))
                // Error code is English: lay it out left-to-right so brackets don't get scrambled.
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Text(code, color = Muted, fontSize = if (compact) 10.sp else 12.sp, textAlign = TextAlign.Center)
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { retryKey++ }) { Text("جرّب تاني", fontSize = if (compact) 15.sp else 18.sp) }
                    OutlinedButton(onClick = { player?.seekToNextMediaItem() }) {
                        Text("القناة التالية", fontSize = if (compact) 15.sp else 18.sp, color = Color.White)
                    }
                }
            }
        }

        AnimatedVisibility(showControls, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            val iconBtn = if (compact) 44.dp else 56.dp
            val icon = if (compact) 26.dp else 32.dp
            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                // Top bar
                Row(
                    Modifier.fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent)))
                        .padding(horizontal = if (compact) 4.dp else 16.dp, vertical = if (compact) 4.dp else 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { vm.closePlayer() }, Modifier.size(iconBtn)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "رجوع", tint = Color.White, modifier = Modifier.size(icon))
                    }
                    Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                        Text(channel.name, color = GoldLight, fontSize = if (compact) 16.sp else 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${vm.playingIndex + 1} / ${vm.queue.size} • ${channel.group}", color = Muted, fontSize = if (compact) 11.sp else 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    val fav = channel.url in vm.favorites
                    IconButton(onClick = { vm.toggleFavorite(channel); interaction++ }, Modifier.size(iconBtn)) {
                        Icon(if (fav) Icons.Filled.Star else Icons.Filled.StarBorder, "مفضلة",
                            tint = if (fav) MaterialTheme.colorScheme.secondary else Color.White, modifier = Modifier.size(icon))
                    }
                    IconButton(onClick = { showList = true; interaction++ }, Modifier.size(iconBtn)) {
                        Icon(Icons.AutoMirrored.Filled.List, "القنوات", tint = Color.White, modifier = Modifier.size(icon))
                    }
                    CastButton(Modifier.size(iconBtn))
                    Row(
                        Modifier.clickable { vm.cycleQuality(); interaction++ }.padding(horizontal = 6.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val label = if (vm.maxQuality == 0) "تلقائي" else "${vm.maxQuality}p"
                        Icon(Icons.Filled.HighQuality, "الجودة: $label", tint = Color.White, modifier = Modifier.size(icon))
                        Spacer(Modifier.size(4.dp))
                        Text(label, color = Color.White, fontSize = if (compact) 11.sp else 16.sp)
                    }
                    Row(
                        Modifier.clickable { resizeIdx = (resizeIdx + 1) % RESIZE_MODES.size; interaction++ }.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.AspectRatio, RESIZE_MODES[resizeIdx].second, tint = Color.White, modifier = Modifier.size(icon))
                        if (!compact) {
                            Spacer(Modifier.size(6.dp))
                            Text(RESIZE_MODES[resizeIdx].second, color = Color.White, fontSize = 16.sp)
                        }
                    }
                }

                // Big centre controls — kept left-to-right like every media player.
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Row(
                        Modifier.align(Alignment.Center),
                        horizontalArrangement = Arrangement.spacedBy(if (compact) 20.dp else 48.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BigButton(Icons.Filled.SkipPrevious, "السابقة", if (compact) 56.dp else 72.dp) { player?.seekToPreviousMediaItem(); interaction++ }
                        BigButton(if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, "تشغيل", if (compact) 72.dp else 96.dp) {
                            player?.let { if (it.isPlaying) it.pause() else it.play() }
                            interaction++
                        }
                        BigButton(Icons.Filled.SkipNext, "التالية", if (compact) 56.dp else 72.dp) { player?.seekToNextMediaItem(); interaction++ }
                    }
                }
            }
        }

        // Receiver-style channel list: tap the list icon, pick a channel, keep watching.
        if (showList) {
            Box(
                Modifier.fillMaxSize().background(Color(0x66000000)).clickable(
                    interactionSource = remember { MutableInteractionSource() }, indication = null,
                ) { showList = false },
            )
        }
        AnimatedVisibility(
            showList,
            enter = slideInHorizontally { it } + fadeIn(),
            exit = slideOutHorizontally { it } + fadeOut(),
            modifier = Modifier.align(Alignment.CenterStart),
        ) {
            ChannelPanel(
                vm = vm,
                compact = compact,
                onPick = { i ->
                    player?.seekTo(i, C.TIME_UNSET)
                    player?.play()
                    showList = false
                    interaction++
                },
            )
        }
    }
}

@Composable
private fun ChannelPanel(vm: MainViewModel, compact: Boolean, onPick: (Int) -> Unit) {
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (vm.playingIndex - 3).coerceAtLeast(0))
    Column(
        Modifier.fillMaxHeight().width(if (compact) 250.dp else 340.dp)
            .background(Color(0xF20A0A0B))
            .border(1.dp, Gold.copy(alpha = 0.5f))
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Text(
            vm.nowPlaying?.group ?: "القنوات",
            color = Gold, fontWeight = FontWeight.Bold, fontSize = if (compact) 15.sp else 18.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
        )
        HorizontalDivider(color = Gold.copy(alpha = 0.35f))
        LazyColumn(state = listState) {
            itemsIndexed(vm.queue, key = { i, c -> "$i-${c.url}" }) { i, c ->
                val selected = i == vm.playingIndex
                Row(
                    Modifier.fillMaxWidth()
                        .background(if (selected) Gold.copy(alpha = 0.18f) else Color.Transparent)
                        .clickable { onPick(i) }
                        .padding(horizontal = 12.dp, vertical = if (compact) 6.dp else 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        Text(
                            String.format(Locale.US, "%03d", i + 1),
                            color = if (selected) GoldLight else Gold.copy(alpha = 0.8f),
                            fontFamily = FontFamily.Monospace, fontSize = if (compact) 12.sp else 14.sp,
                            modifier = Modifier.width(if (compact) 34.dp else 40.dp),
                        )
                    }
                    Box(
                        Modifier.size(if (compact) 30.dp else 36.dp).clip(RoundedCornerShape(6.dp)).background(InkHigh),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (c.logo != null) {
                            AsyncImage(c.logo, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(2.dp))
                        } else {
                            Text(c.name.take(1), color = Gold, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Spacer(Modifier.size(10.dp))
                    Text(
                        c.name, color = if (selected) GoldLight else Cream,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        fontSize = if (compact) 13.sp else 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** Standard Cast button (finds Chromecast / Google TV devices on the Wi-Fi). */
@Composable
private fun CastButton(modifier: Modifier = Modifier) {
    AndroidView(
        factory = { c ->
            MediaRouteButton(ContextThemeWrapper(c, androidx.appcompat.R.style.Theme_AppCompat)).also {
                runCatching { CastButtonFactory.setUpMediaRouteButton(c.applicationContext, it) }
            }
        },
        modifier = modifier,
    )
}

@Composable
private fun BigButton(icon: ImageVector, label: String, size: Dp, onClick: () -> Unit) {
    Box(
        Modifier.size(size).background(Color(0xB3000000), CircleShape)
            .border(1.5.dp, GoldBrush, CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = GoldLight, modifier = Modifier.size(size * 0.6f))
    }
}
