@file:OptIn(UnstableApi::class)

package com.mashwar.tv

import java.util.Date

import java.text.SimpleDateFormat

import androidx.compose.material3.LinearProgressIndicator

import androidx.compose.material.icons.filled.PictureInPictureAlt

import androidx.compose.material.icons.filled.Dialpad

import androidx.compose.material.icons.filled.History

import androidx.compose.ui.input.key.type

import androidx.compose.ui.input.key.onPreviewKeyEvent

import androidx.compose.ui.input.key.KeyEventType

import androidx.compose.ui.focus.focusRequester

import androidx.compose.ui.focus.FocusRequester

import androidx.compose.foundation.focusable

import android.view.KeyEvent as AndroidKey

import android.os.Build

import androidx.compose.ui.graphics.RectangleShape

import kotlinx.coroutines.withContext

import kotlinx.coroutines.Dispatchers

import androidx.compose.ui.text.TextStyle

import androidx.compose.runtime.produceState

import androidx.compose.material3.OutlinedTextFieldDefaults

import androidx.compose.material3.OutlinedTextField

import androidx.compose.material.icons.filled.Close

import androidx.compose.material.icons.filled.Search

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.ConnectedTv
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
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
import androidx.compose.ui.AbsoluteAlignment
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
    val compactNow = shortSide < 480 && longSide < shortSide * 1.4f
    var compact by remember { mutableStateOf(compactNow) }
    LaunchedEffect(compactNow, PipState.inPip) { if (!PipState.inPip) compact = compactNow }
    val inPip = PipState.inPip
    var isPlaying by remember { mutableStateOf(false) }
    var buffering by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var showControls by remember { mutableStateOf(true) }
    var interaction by remember { mutableIntStateOf(0) }
    var resizeIdx by remember { mutableIntStateOf(0) }
    var retryKey by remember { mutableIntStateOf(0) }
    var errorTick by remember { mutableIntStateOf(0) }
    var showList by remember { mutableStateOf(false) }
    var showKeypad by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf("") }
    var zapNote by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }

    // Leaving the app while watching shrinks the player into a PiP window (see MainActivity).
    DisposableEffect(Unit) {
        PipState.playerActive = true
        onDispose { PipState.playerActive = false }
    }
    LaunchedEffect(PipState.dismissed) { if (PipState.dismissed > 0) player?.pause() }
    LaunchedEffect(inPip) { if (inPip) { showList = false; showKeypad = false } }

    val now by produceState(System.currentTimeMillis()) {
        while (true) { delay(30_000); value = System.currentTimeMillis() }
    }
    var showTvPicker by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val dlna = DlnaCast.active
    // While a TV is showing the channel, keep the phone quiet and follow channel changes on the TV.
    LaunchedEffect(vm.playingIndex) {
        val tv = DlnaCast.active ?: return@LaunchedEffect
        player?.pause()
        vm.nowPlaying?.let { ch -> DlnaCast.play(tv, ch) }
    }

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
                vm.syncIndex(vm.windowStart + p.currentMediaItemIndex)
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
        val (window, startIdx) = vm.playerWindow()
        p.setMediaItems(window.map { it.toMediaItem() }, startIdx, C.TIME_UNSET)
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

    // Jump to a channel by its position in the list (keeps the player window logic in one place).
    val jump: (Int) -> Unit = { i ->
        val local = i - vm.windowStart
        val p = player
        if (p != null && local in 0 until p.mediaItemCount) {
            p.seekTo(local, C.TIME_UNSET)
            p.play()
        } else if (i in vm.queue.indices) {
            vm.play(vm.queue, i) // outside the loaded window: reload around it
        }
        interaction++
    }
    val backToPrevious: () -> Unit = {
        val prev = vm.previousChannel
        val i = prev?.let { pc -> vm.queue.indexOfFirst { it.url == pc.url } } ?: -1
        if (i >= 0) jump(i) else zapNote = "مفيش قناة سابقة"
    }
    fun commitNumber() {
        val n = typed.toIntOrNull()
        typed = ""
        if (n == null) return
        if (n in 1..vm.queue.size) { jump(n - 1); zapNote = null } else zapNote = "مفيش قناة رقم $n"
    }
    // Receiver behaviour: digits wait a moment for the next digit, then switch.
    LaunchedEffect(typed) {
        if (typed.isEmpty()) return@LaunchedEffect
        delay(if (typed.length >= 4) 300 else 1_800)
        commitNumber()
    }
    LaunchedEffect(zapNote) { if (zapNote != null) { delay(2_000); zapNote = null } }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .focusRequester(focus).focusable()
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val code = e.nativeKeyEvent.keyCode
                when {
                    code in AndroidKey.KEYCODE_0..AndroidKey.KEYCODE_9 -> { typed = (typed + (code - AndroidKey.KEYCODE_0)).take(4); true }
                    code in AndroidKey.KEYCODE_NUMPAD_0..AndroidKey.KEYCODE_NUMPAD_9 -> { typed = (typed + (code - AndroidKey.KEYCODE_NUMPAD_0)).take(4); true }
                    code == AndroidKey.KEYCODE_ENTER || code == AndroidKey.KEYCODE_DPAD_CENTER || code == AndroidKey.KEYCODE_NUMPAD_ENTER ->
                        if (typed.isNotEmpty()) { commitNumber(); true } else false
                    code == AndroidKey.KEYCODE_LAST_CHANNEL -> { backToPrevious(); true }
                    code == AndroidKey.KEYCODE_CHANNEL_UP -> { player?.seekToNextMediaItem(); true }
                    code == AndroidKey.KEYCODE_CHANNEL_DOWN -> { player?.seekToPreviousMediaItem(); true }
                    else -> false
                }
            },
    ) {
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
            if (castDevice == null && dlna == null) CircularProgressIndicator(Modifier.align(Alignment.Center).size(64.dp), color = Gold)
        }
        dlna?.let { tv ->
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.ConnectedTv, null, tint = Gold, modifier = Modifier.size(if (compact) 48.dp else 72.dp))
                Spacer(Modifier.height(8.dp))
                Text("بيتعرض على ${tv.name}", color = Cream, fontSize = if (compact) 15.sp else 20.sp)
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = { scope.launch { DlnaCast.stop(); player?.play() } }) {
                    Text("وقّف العرض على الشاشة", color = GoldLight)
                }
            }
        }
        if (showTvPicker) {
            TvPickerDialog(
                onDismiss = { showTvPicker = false },
                onPick = { tv ->
                    showTvPicker = false
                    val ch = vm.nowPlaying ?: return@TvPickerDialog
                    scope.launch {
                        val err = DlnaCast.play(tv, ch)
                        if (err == null) player?.pause()
                        else { error = "الشاشة مقبلتش القناة\n$err"; showControls = true }
                    }
                },
            )
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

        AnimatedVisibility(showControls && !inPip, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
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
                        val (cur, next) = vm.epg?.nowNext(channel, now) ?: (null to null)
                        if (cur != null || next != null) {
                            Text(
                                buildString {
                                    cur?.let { append("الآن: ${it.title}") }
                                    next?.let { if (isNotEmpty()) append("  •  "); append("بعده ${hhmm(it.start)}: ${it.title}") }
                                },
                                color = GoldLight, fontSize = if (compact) 11.sp else 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            cur?.let { pr ->
                                LinearProgressIndicator(
                                    progress = { ((now - pr.start).toFloat() / (pr.stop - pr.start).coerceAtLeast(1)).coerceIn(0f, 1f) },
                                    modifier = Modifier.padding(top = 3.dp).fillMaxWidth(0.6f).height(2.dp),
                                    color = Gold, trackColor = Gold.copy(alpha = 0.2f),
                                )
                            }
                        }
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
                    IconButton(onClick = { showTvPicker = true; interaction++ }, Modifier.size(iconBtn)) {
                        Icon(Icons.Filled.ConnectedTv, "اعرض على شاشة سامسونج / LG",
                            tint = if (dlna != null) Gold else Color.White, modifier = Modifier.size(icon))
                    }
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
                    Row(
                        Modifier.align(Alignment.BottomCenter).padding(bottom = if (compact) 6.dp else 18.dp)
                            .windowInsetsPadding(WindowInsets.safeDrawing),
                        horizontalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 16.dp),
                    ) {
                        PillButton(Icons.Filled.History, "القناة السابقة", compact) { backToPrevious() }
                        PillButton(Icons.Filled.Dialpad, "رقم القناة", compact) { showKeypad = true; interaction++ }
                        if (Build.VERSION.SDK_INT >= 26) {
                            PillButton(Icons.Filled.PictureInPictureAlt, "صورة جوه صورة", compact) {
                                (ctx.findActivity() as? MainActivity)?.enterPip()
                            }
                        }
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
        // Channel number being typed (top-left, like a receiver), or a short note.
        if (!inPip && (typed.isNotEmpty() || zapNote != null)) {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Box(
                    Modifier.align(AbsoluteAlignment.TopLeft).windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp)
                        .background(Color(0xCC000000), RoundedCornerShape(12.dp))
                        .border(1.5.dp, GoldBrush, RoundedCornerShape(12.dp))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    if (typed.isNotEmpty()) {
                        Text(typed, style = TextStyle(brush = shimmeringGold(200f), fontSize = if (compact) 30.sp else 44.sp,
                            fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace))
                    } else {
                        Text(zapNote ?: "", color = Cream, fontSize = 15.sp)
                    }
                }
            }
        }
        if (showKeypad && !inPip) {
            Keypad(
                typed = typed,
                compact = compact,
                onDigit = { d -> typed = (typed + d).take(4) },
                onOk = { commitNumber(); showKeypad = false },
                onPrevious = { backToPrevious(); showKeypad = false },
                onDismiss = { showKeypad = false; typed = "" },
            )
        }
        AnimatedVisibility(
            showList && !inPip,
            enter = slideInHorizontally { -it } + fadeIn(),
            exit = slideOutHorizontally { -it } + fadeOut(),
            modifier = Modifier.align(AbsoluteAlignment.CenterLeft),
        ) {
            ChannelPanel(
                vm = vm,
                compact = compact,
                now = now,
                onPick = { i ->
                    jump(i)
                    showList = false
                },
            )
        }
    }
}

@Composable
private fun ChannelPanel(vm: MainViewModel, compact: Boolean, now: Long, onPick: (Int) -> Unit) {
    var query by remember { mutableStateOf("") }
    // (index in queue, channel) so picks from search results still map to the right channel.
    val shown by produceState(vm.queue.withIndex().toList(), vm.queue, query) {
        val q = query.trim()
        if (q.isNotEmpty()) delay(120)
        value = withContext(Dispatchers.Default) {
            val all = vm.queue.withIndex()
            if (q.isEmpty()) all.toList() else all.filter { it.value.name.contains(q, ignoreCase = true) }
        }
    }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (vm.playingIndex - 3).coerceAtLeast(0))
    LaunchedEffect(query) { if (query.isNotEmpty()) listState.scrollToItem(0) }
    Column(
        Modifier.fillMaxHeight().width(if (compact) 260.dp else 360.dp)
            .background(Brush.horizontalGradient(listOf(Color(0xFA050505), Color(0xF2121014))))
            .border(1.dp, GoldBrush, RectangleShape)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Text(
            vm.nowPlaying?.group ?: "القنوات",
            style = TextStyle(brush = shimmeringGold(), fontWeight = FontWeight.Bold, fontSize = if (compact) 15.sp else 19.sp),
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 6.dp),
        )
        OutlinedTextField(
            value = query, onValueChange = { query = it },
            placeholder = { Text("دوّر على قناة…", fontSize = 14.sp) },
            leadingIcon = { Icon(Icons.Filled.Search, null, tint = Gold) },
            trailingIcon = {
                if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, "مسح", tint = Muted) }
            },
            singleLine = true,
            textStyle = TextStyle(fontSize = 15.sp, color = Cream),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Gold, unfocusedBorderColor = Gold.copy(alpha = 0.4f), cursorColor = Gold,
            ),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
        )
        HorizontalDivider(Modifier.padding(top = 6.dp), color = Gold.copy(alpha = 0.35f))
        if (shown.isEmpty()) {
            Text("مفيش قناة بالاسم ده", color = Muted, modifier = Modifier.padding(16.dp))
        }
        LazyColumn(state = listState) {
            items(shown, key = { "${it.index}-${it.value.url}" }) { (i, c) ->
                val selected = i == vm.playingIndex
                Row(
                    Modifier.fillMaxWidth()
                        .background(
                            if (selected) Brush.horizontalGradient(listOf(Gold.copy(alpha = 0.35f), Gold.copy(alpha = 0.08f)))
                            else Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent))
                        )
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
                    Column {
                        Text(
                            c.name, color = if (selected) GoldLight else Cream,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            fontSize = if (compact) 13.sp else 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        vm.epg?.nowNext(c, now)?.first?.let { pr ->
                            Text(pr.title, color = Muted, fontSize = if (compact) 10.sp else 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

/** Looks for DLNA TVs on the Wi-Fi (Samsung, LG, Sony…) and lets the user pick one. */
@Composable
private fun TvPickerDialog(onDismiss: () -> Unit, onPick: (DlnaDevice) -> Unit) {
    val ctx = LocalContext.current
    var scan by remember { mutableStateOf<DlnaCast.Scan?>(null) }
    var round by remember { mutableIntStateOf(0) }
    LaunchedEffect(round) {
        scan = null
        scan = runCatching { DlnaCast.discover(ctx) }.getOrElse { DlnaCast.Scan(emptyList(), true, 0, emptyList()) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("اعرض على الشاشة") },
        text = {
            Column {
                val r = scan
                when {
                    r == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(24.dp), color = Gold, strokeWidth = 2.dp)
                        Spacer(Modifier.size(12.dp))
                        Text("بدوّر على الشاشات اللي على نفس الواي فاي…")
                    }
                    r.devices.isNotEmpty() -> r.devices.forEach { d ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onPick(d) }.padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Filled.ConnectedTv, null, tint = Gold)
                            Spacer(Modifier.size(12.dp))
                            Text(d.name, fontSize = 17.sp)
                        }
                    }
                    !r.wifi -> Text("الموبايل مش متوصل بواي فاي. وصّله بنفس شبكة الشاشة وجرّب تاني.")
                    r.responders == 0 -> Text(
                        "مفيش أي جهاز رد على الشبكة.\n" +
                            "• اتأكد إن الشاشة شغالة (مش Standby) وعلى نفس الواي فاي.\n" +
                            "• لو الراوتر فيه Guest Wi-Fi أو AP Isolation، اقفله.\n" +
                            "• في سامسونج: الإعدادات ▸ عام ▸ إدارة الأجهزة الخارجية ▸ مدير اتصال الأجهزة ▸ إشعار الوصول: فعّله."
                    )
                    else -> Text(
                        "لقيت ${r.responders} جهاز على الشبكة، بس ولا واحد فيهم قبل عرض فيديو" +
                            (if (r.others.isNotEmpty()) ":\n" + r.others.take(6).joinToString("\n") { "• $it" } else ".") +
                            "\n\nلو شاشتك في القائمة دي، فعّل في الشاشة: إدارة الأجهزة الخارجية ▸ مدير اتصال الأجهزة."
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { round++ }, enabled = scan != null) { Text("دوّر تاني") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } },
    )
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

private fun hhmm(t: Long): String = SimpleDateFormat("HH:mm", Locale.US).format(Date(t))

@Composable
private fun PillButton(icon: ImageVector, label: String, compact: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(Color(0xB3000000))
            .border(1.dp, GoldBrush, RoundedCornerShape(50)).clickable(onClick = onClick)
            .padding(horizontal = if (compact) 10.dp else 14.dp, vertical = if (compact) 6.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, label, tint = GoldLight, modifier = Modifier.size(if (compact) 18.dp else 22.dp))
        if (!compact) {
            Spacer(Modifier.size(6.dp))
            Text(label, color = Cream, fontSize = 13.sp)
        }
    }
}

/** On-screen number pad for jumping to a channel number, like a receiver remote. */
@Composable
private fun Keypad(
    typed: String, compact: Boolean,
    onDigit: (Int) -> Unit, onOk: () -> Unit, onPrevious: () -> Unit, onDismiss: () -> Unit,
) {
    Box(
        Modifier.fillMaxSize().background(Color(0x99000000)).clickable(
            interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss,
        ),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Column(
                Modifier.background(Color(0xF2000000), RoundedCornerShape(20.dp))
                    .border(1.5.dp, GoldBrush, RoundedCornerShape(20.dp))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
                    .padding(if (compact) 10.dp else 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    typed.ifEmpty { "---" },
                    style = TextStyle(brush = shimmeringGold(200f), fontSize = if (compact) 26.sp else 34.sp,
                        fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace),
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                val key = if (compact) 48.dp else 60.dp
                listOf(listOf(1, 2, 3), listOf(4, 5, 6), listOf(7, 8, 9)).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                        row.forEach { d -> KeyButton(d.toString(), key) { onDigit(d) } }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                    KeyButton("↺", key, onPrevious)
                    KeyButton("0", key) { onDigit(0) }
                    KeyButton("OK", key, onOk)
                }
            }
        }
    }
}

@Composable
private fun KeyButton(label: String, size: Dp, onClick: () -> Unit) {
    Box(
        Modifier.size(size).clip(CircleShape).background(InkHigh).border(1.dp, GoldBrush, CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = GoldLight, fontSize = if (label.length > 1) 16.sp else 22.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun BigButton(icon: ImageVector, label: String, size: Dp, onClick: () -> Unit) {
    Box(
        Modifier.size(size).background(Color(0xB3000000), CircleShape)
            .border(2.dp, GoldBrush, CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = GoldLight, modifier = Modifier.size(size * 0.6f))
    }
}
