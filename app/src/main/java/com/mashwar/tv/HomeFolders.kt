package com.mashwar.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Home: every playlist is a gold folder; tap it and you're straight in the channels. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: MainViewModel) {
    MessageToasts(vm)
    var showAdd by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { BrandTitle() },
                expandedHeight = 84.dp,
                actions = {
                    IconButton(onClick = { vm.showManage = true }) { Icon(Icons.Filled.Tune, "إدارة القوائم", tint = Gold) }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Ink),
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (vm.loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Gold)
            if (vm.playlists.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Gold)
                        Spacer(Modifier.height(12.dp))
                        Text("بجهّز القنوات…", color = Muted)
                        Spacer(Modifier.height(20.dp))
                        Text("أو ضيف قايمتك", color = Gold, modifier = Modifier.clickable { showAdd = true }.padding(8.dp))
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(150.dp),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(vm.playlists, key = { it.id }) { p -> Folder(p) { vm.open(p) } }
                    item(key = "add") { AddFolder { showAdd = true } }
                    item(span = { GridItemSpan(maxLineSpan) }, key = "hint") {
                        Text(
                            "تعديل أو مسح أو مشاركة القوايم من ⚙ فوق",
                            color = Muted, fontSize = 12.sp, textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        )
                    }
                }
            }
        }
    }
    if (showAdd) AddPlaylistDialog(vm) { showAdd = false }
}

private fun folderBadge(p: Playlist): Pair<String, String> = when {
    p.source == FreeCatalog.SOURCE -> "📡" to "قنوات مجانية"
    XtreamAccount.decode(p.source) != null -> "🎬" to "قنوات • أفلام • مسلسلات"
    p.source == "sample" -> "🧪" to "تجريبي"
    else -> "📺" to "قنوات"
}

@Composable
private fun Folder(p: Playlist, onClick: () -> Unit) {
    val (emoji, kind) = folderBadge(p)
    Column(
        Modifier.clip(RoundedCornerShape(18.dp))
            .background(Brush.verticalGradient(listOf(InkHigh, Ink)))
            .border(1.2.dp, GoldBrush, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Folder, null, tint = Gold, modifier = Modifier.size(78.dp))
            // folder "shine"
            Icon(Icons.Filled.Folder, null, tint = Color.White.copy(alpha = 0.10f), modifier = Modifier.size(78.dp).padding(top = 6.dp, end = 10.dp))
            Text(emoji, fontSize = 26.sp, modifier = Modifier.padding(top = 10.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(p.name, color = GoldLight, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 2,
            overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        Text(kind, color = Muted, fontSize = 12.sp, maxLines = 1, textAlign = TextAlign.Center)
    }
}

@Composable
private fun AddFolder(onClick: () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(18.dp))
            .border(1.dp, Gold.copy(alpha = 0.35f), RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(78.dp), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Add, null, tint = Gold.copy(alpha = 0.7f), modifier = Modifier.size(44.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text("قائمة جديدة", color = Gold, fontSize = 16.sp)
        Text("M3U • Xtream", color = Muted, fontSize = 12.sp)
    }
}
