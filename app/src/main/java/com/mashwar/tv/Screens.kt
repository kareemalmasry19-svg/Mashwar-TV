package com.mashwar.tv

import androidx.compose.ui.text.font.Font

import androidx.compose.ui.text.withStyle

import androidx.compose.ui.text.buildAnnotatedString

import androidx.compose.ui.text.SpanStyle

import androidx.compose.runtime.CompositionLocalProvider

import androidx.compose.ui.unit.LayoutDirection

import androidx.compose.ui.platform.LocalLayoutDirection

import androidx.compose.material3.CircularProgressIndicator

import androidx.compose.foundation.verticalScroll

import androidx.compose.foundation.rememberScrollState

import androidx.compose.runtime.mutableIntStateOf

import androidx.compose.material.icons.filled.VisibilityOff

import androidx.compose.material.icons.filled.Visibility

import androidx.compose.ui.text.input.VisualTransformation

import androidx.compose.ui.text.input.PasswordVisualTransformation

import androidx.compose.ui.text.input.KeyboardType

import androidx.compose.foundation.text.KeyboardOptions

import androidx.compose.ui.draw.alpha

import androidx.compose.material.icons.filled.NetworkCheck

import androidx.compose.material.icons.filled.Event

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

@Composable
private fun MessageToasts(vm: MainViewModel) {
    val ctx = LocalContext.current
    LaunchedEffect(vm.message) {
        vm.message?.let {
            Toast.makeText(ctx, it, Toast.LENGTH_LONG).show()
            vm.message = null
        }
    }
}

// ---------------------------------------------------------------- Home

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: MainViewModel) {
    MessageToasts(vm)
    var showAdd by remember { mutableStateOf(false) }
    var toDelete by remember { mutableStateOf<Playlist?>(null) }
    var toRename by remember { mutableStateOf<Playlist?>(null) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(title = { BrandTitle() })
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAdd = true },
                icon = { Icon(Icons.Filled.Add, null) },
                text = { Text("ضيف قائمة", fontSize = 18.sp) },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (vm.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (vm.playlists.isEmpty()) {
                Column(
                    Modifier.fillMaxSize().padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(Icons.Filled.Tv, null, Modifier.size(96.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(16.dp))
                    Text("مفيش قوائم لسه", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "ضيف رابط قائمة M3U من اشتراكك، أو افتح ملف M3U من الموبايل.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(24.dp))
                    OutlinedButton(onClick = { vm.addSample() }) { Text("جرّب بقائمة تجريبية") }
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp)) {
                    items(vm.playlists, key = { it.id }) { p ->
                        Card(
                            Modifier.fillMaxWidth().padding(vertical = 6.dp).clickable { vm.open(p) },
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                            border = BorderStroke(1.dp, GoldBrush),
                        ) {
                            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Tv, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.size(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(p.name, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        XtreamAccount.decode(p.source)?.let { "Xtream • ${it.user} @ ${it.host}" }
                                            ?: if (p.isUri) "ملف من الموبايل" else if (p.source == "sample") "روابط تجريبية" else p.source,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (p.source != "sample") {
                                    IconButton(onClick = { vm.refresh(p) }) { Icon(Icons.Filled.Refresh, "تحديث") }
                                }
                                IconButton(onClick = { toRename = p }) { Icon(Icons.Filled.Edit, "تغيير الاسم") }
                                IconButton(onClick = { toDelete = p }) { Icon(Icons.Filled.Delete, "مسح") }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAdd) AddPlaylistDialog(vm) { showAdd = false }
    toRename?.let { p ->
        var newName by remember(p.id) { mutableStateOf(p.name) }
        var newEpg by remember(p.id) { mutableStateOf(p.epgUrl ?: "") }
        AlertDialog(
            onDismissRequest = { toRename = null },
            title = { Text("تعديل القائمة") },
            text = {
                Column {
                    OutlinedTextField(newName, { newName = it }, singleLine = true, label = { Text("الاسم") })
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        newEpg, { newEpg = it }, singleLine = true,
                        label = { Text("رابط دليل البرامج EPG (اختياري)") },
                        placeholder = { Text("https://…/epg.xml.gz") },
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.rename(p, newName)
                    if (newEpg.trim() != (p.epgUrl ?: "")) vm.setEpgUrl(vm.playlists.first { it.id == p.id }, newEpg)
                    toRename = null
                }, enabled = newName.isNotBlank()) { Text("حفظ") }
            },
            dismissButton = { TextButton(onClick = { toRename = null }) { Text("إلغاء") } },
        )
    }
    toDelete?.let { p ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("تمسح \"${p.name}\"؟") },
            confirmButton = { TextButton(onClick = { vm.delete(p); toDelete = null }) { Text("امسح") } },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("لأ") } },
        )
    }
}

@Composable
private fun AddPlaylistDialog(vm: MainViewModel, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var tab by remember { mutableIntStateOf(0) } // 0 = M3U, 1 = Xtream
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var server by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var showPass by remember { mutableStateOf(false) }
    var hls by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            vm.addPlaylist(name, uri.toString(), true)
            onDismiss()
        }
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("قائمة جديدة") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = tab == 0, onClick = { tab = 0; err = null }, label = { Text("رابط / ملف M3U") })
                    FilterChip(selected = tab == 1, onClick = { tab = 1; err = null }, label = { Text("دخول Xtream") })
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(name, { name = it }, label = { Text("الاسم (اختياري)") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                if (tab == 0) {
                    OutlinedTextField(url, { url = it }, label = { Text("رابط M3U") }, singleLine = true)
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth()) {
                        Text("أو افتح ملف من الموبايل")
                    }
                } else {
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        Column {
                            OutlinedTextField(
                                server, { server = it }, singleLine = true,
                                label = { Text("Server  (http://host:port)") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(user, { user = it }, singleLine = true, label = { Text("Username") })
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                pass, { pass = it }, singleLine = true, label = { Text("Password") },
                                visualTransformation = if (showPass) VisualTransformation.None else PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                trailingIcon = {
                                    IconButton(onClick = { showPass = !showPass }) {
                                        Icon(if (showPass) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, null)
                                    }
                                },
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("صيغة البث:", fontSize = 14.sp)
                        Spacer(Modifier.size(8.dp))
                        FilterChip(selected = !hls, onClick = { hls = false }, label = { Text("TS (أضمن)") })
                        Spacer(Modifier.size(6.dp))
                        FilterChip(selected = hls, onClick = { hls = true }, label = { Text("HLS") })
                    }
                    Text("لو عندك رابط get.php كامل، حطه في خانة السيرفر وسيب الباقي فاضي.", fontSize = 12.sp, color = Muted)
                }
                err?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, fontSize = 14.sp)
                }
            }
        },
        confirmButton = {
            if (tab == 0) {
                Button(enabled = url.isNotBlank(), onClick = { vm.addPlaylist(name, url, false); onDismiss() }) { Text("ضيف") }
            } else {
                Button(
                    enabled = server.isNotBlank() && !busy,
                    onClick = {
                        busy = true; err = null
                        vm.addXtream(name, server, user, pass, hls) { e ->
                            busy = false
                            if (e == null) onDismiss() else err = e
                        }
                    },
                ) {
                    if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.Black)
                    else Text("دخول")
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("إلغاء") } },
    )
}

// ---------------------------------------------------------------- Channels

private const val ALL = "الكل"
private const val FAVS = "★ المفضلة"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelsScreen(vm: MainViewModel) {
    MessageToasts(vm)
    BackHandler { vm.closePlaylist() }
    var query by remember { mutableStateOf("") }
    var group by remember { mutableStateOf(ALL) }
    val groups = remember(vm.channels) { listOf(ALL, FAVS) + vm.channels.map { it.group }.distinct() }
    // Filtering thousands of channels on every keystroke janks the UI: do it off the main thread.
    var showDead by remember { mutableStateOf(false) }
    val now by produceState(System.currentTimeMillis()) {
        while (true) { delay(30_000); value = System.currentTimeMillis() }
    }
    vm.scanProgress?.let { (done, total) ->
        AlertDialog(
            onDismissRequest = { },
            title = { Text("فحص القنوات") },
            text = {
                Column {
                    Text("بجرّب كل قناة… $done من $total")
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = { if (total == 0) 0f else done.toFloat() / total },
                        modifier = Modifier.fillMaxWidth(), color = Gold, trackColor = Gold.copy(alpha = 0.2f),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("القنوات اللي مش شغالة هتتخفي من القائمة.", fontSize = 13.sp, color = Muted)
                }
            },
            confirmButton = { TextButton(onClick = { vm.cancelScan() }) { Text("إيقاف") } },
        )
    }
    val filtered by produceState(vm.channels, vm.channels, query, group, vm.favorites, vm.dead, showDead) {
        if (query.isNotBlank()) delay(150)
        val all = vm.channels
        val favs = vm.favorites
        val dead = if (showDead) emptySet() else vm.dead
        val q = query.trim()
        value = withContext(Dispatchers.Default) {
            if (group == ALL && q.isEmpty() && dead.isEmpty()) all
            else all.filter { c ->
                c.url !in dead &&
                    (group == ALL || (group == FAVS && c.url in favs) || c.group == group) &&
                    (q.isEmpty() || c.name.contains(q, ignoreCase = true))
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(vm.current?.name ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = { vm.closePlaylist() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "رجوع") }
                },
                actions = {
                    if (vm.current?.epgUrl != null) {
                        IconButton(onClick = { vm.current?.let { vm.loadEpg(it, force = true) } }, enabled = !vm.epgLoading) {
                            Icon(Icons.Filled.Event, "تحديث دليل البرامج", tint = if (vm.epgLoading) Muted else Gold)
                        }
                    }
                    IconButton(onClick = { vm.scanChannels() }) { Icon(Icons.Filled.NetworkCheck, "فحص القنوات", tint = Gold) }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (vm.loading || vm.vodLoading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Gold)
            SectionChips(vm)
            if (vm.section != MainViewModel.Section.LIVE) {
                VodGrid(vm, movies = vm.section == MainViewModel.Section.MOVIES)
                return@Column
            }
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                placeholder = { Text("دوّر على قناة…") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                singleLine = true,
            )
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(groups) { g ->
                    FilterChip(selected = g == group, onClick = { group = g }, label = { Text(g, fontSize = 16.sp) })
                }
            }
            vm.lastChannel?.let { last ->
                if (query.isBlank() && group == ALL) {
                    Button(
                        onClick = { vm.play(vm.channels, vm.channels.indexOf(last)) },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(56.dp),
                    ) {
                        Icon(Icons.Filled.PlayArrow, null)
                        Spacer(Modifier.size(8.dp))
                        Text("كمّل: ${last.name}", maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 18.sp)
                    }
                }
            }
            Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${filtered.size} قناة", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                if (vm.epgLoading) Text("بيحمّل دليل البرامج…", color = Muted, fontSize = 12.sp)
                if (vm.dead.isNotEmpty()) {
                    FilterChip(
                        selected = showDead, onClick = { showDead = !showDead },
                        label = { Text(if (showDead) "إخفاء الواقعة (${vm.dead.size})" else "إظهار الواقعة (${vm.dead.size})", fontSize = 13.sp) },
                    )
                }
            }
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                itemsIndexed(filtered, key = { i, c -> "$i-${c.url}" }, contentType = { _, _ -> "channel" }) { i, c ->
                    LaunchedEffect(c.url) { vm.requestEpg(c) }
                    ChannelRow(
                        c, c.url in vm.favorites, dead = c.url in vm.dead,
                        now = vm.nowNext(c, now).first, time = now,
                        onFav = { vm.toggleFavorite(c) },
                    ) { vm.play(filtered, i) }
                }
            }
        }
    }
}

@Composable
private fun ChannelRow(
    c: Channel, fav: Boolean, dead: Boolean, now: Programme?, time: Long,
    onFav: () -> Unit, onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().alpha(if (dead) 0.45f else 1f).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)).background(InkHigh)
                .border(1.dp, GoldBrush, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (c.logo != null) {
                AsyncImage(model = c.logo, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(4.dp))
            } else {
                Text(c.name.take(1), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Gold)
            }
        }
        Spacer(Modifier.size(14.dp))
        Column(Modifier.weight(1f)) {
            Text(c.name, fontSize = 19.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            when {
                dead -> Text("مش شغالة في آخر فحص", fontSize = 13.sp, color = MaterialTheme.colorScheme.error, maxLines = 1)
                now != null -> {
                    Text(now.title, fontSize = 13.sp, color = GoldLight, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    LinearProgressIndicator(
                        progress = { ((time - now.start).toFloat() / (now.stop - now.start).coerceAtLeast(1)).coerceIn(0f, 1f) },
                        modifier = Modifier.padding(top = 4.dp).fillMaxWidth(0.7f).height(2.dp),
                        color = Gold, trackColor = Gold.copy(alpha = 0.2f),
                    )
                }
                else -> Text(c.group, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
        IconButton(onClick = onFav, modifier = Modifier.size(52.dp)) {
            Icon(
                if (fav) Icons.Filled.Star else Icons.Filled.StarBorder, "مفضلة",
                tint = if (fav) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private val RuqaaFont = FontFamily(Font(R.font.aref_ruqaa_bold, FontWeight.Bold))
private val CinzelFont = FontFamily(Font(R.font.cinzel_bold, FontWeight.Bold))

/** "المصري TV" in shimmering gold: Aref Ruqaa calligraphy + Cinzel caps (both OFL), like the app icon. */
@Composable
fun BrandTitle(compact: Boolean = false) {
    val size = if (compact) 22.sp else 30.sp
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(fontFamily = RuqaaFont, fontSize = size)) { append("المصري") }
            append(" ")
            withStyle(SpanStyle(fontFamily = CinzelFont, fontSize = size * 0.72f, letterSpacing = 2.sp)) { append("TV") }
        },
        style = TextStyle(brush = shimmeringGold(), fontWeight = FontWeight.Bold),
    )
}
