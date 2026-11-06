package com.mashwar.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val ALL_CATS = "الكل"

/** Live / Movies / Series switch shown at the top of an Xtream playlist. */
@Composable
fun SectionChips(vm: MainViewModel) {
    if (vm.xtream == null) return
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val tabs = listOf(
            MainViewModel.Section.LIVE to "📡 بث مباشر",
            MainViewModel.Section.MOVIES to "🎬 أفلام",
            MainViewModel.Section.SERIES to "📺 مسلسلات",
        )
        items(tabs) { (sec, label) ->
            FilterChip(
                selected = vm.section == sec,
                onClick = { vm.section = sec },
                label = { Text(label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) },
            )
        }
    }
}

/** Poster grid for movies or series, with search and categories. */
@Composable
fun VodGrid(vm: MainViewModel, movies: Boolean) {
    LaunchedEffect(movies) { if (movies) vm.loadMovies() else vm.loadSeries() }
    var query by remember(movies) { mutableStateOf("") }
    var cat by remember(movies) { mutableStateOf(ALL_CATS) }
    val all: List<Triple<String, String?, String>> = remember(vm.movies, vm.seriesList, movies) {
        // (name, image, category) for both kinds
        if (movies) vm.movies.orEmpty().map { Triple(it.name, it.poster, it.category) }
        else vm.seriesList.orEmpty().map { Triple(it.name, it.cover, it.category) }
    }
    val cats = remember(all) { listOf(ALL_CATS) + all.map { it.third }.distinct() }
    val shown by produceState(emptyList<Int>(), all, query, cat) {
        if (query.isNotBlank()) delay(150)
        val q = query.trim()
        value = withContext(Dispatchers.Default) {
            all.indices.filter { i ->
                val (n, _, c) = all[i]
                (cat == ALL_CATS || c == cat) && (q.isEmpty() || n.contains(q, ignoreCase = true))
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query, onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            placeholder = { Text(if (movies) "دوّر على فيلم…" else "دوّر على مسلسل…") },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            singleLine = true,
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(cats) { c -> FilterChip(selected = c == cat, onClick = { cat = c }, label = { Text(c, fontSize = 14.sp) }) }
        }
        when {
            vm.vodLoading && all.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Gold)
                    Spacer(Modifier.height(12.dp))
                    Text(if (movies) "بحمّل الأفلام من السيرفر…" else "بحمّل المسلسلات من السيرفر…", color = Muted)
                }
            }
            vm.vodError != null && all.isEmpty() -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("مقدرتش أحمّل: ${vm.vodError}", color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { if (movies) vm.loadMovies(true) else vm.loadSeries(true) }) { Text("جرّب تاني") }
                }
            }
            else -> {
                Text("${shown.size} ${if (movies) "فيلم" else "مسلسل"}", color = Muted, modifier = Modifier.padding(horizontal = 16.dp))
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(112.dp),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(shown, key = { it }) { i ->
                        val (name, img, _) = all[i]
                        val rating = if (movies) vm.movies?.getOrNull(i)?.rating else vm.seriesList?.getOrNull(i)?.rating
                        PosterCard(name, img, rating) {
                            if (movies) vm.movies?.getOrNull(i)?.let(vm::playMovie)
                            else vm.seriesList?.getOrNull(i)?.let(vm::openSeries)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PosterCard(name: String, image: String?, rating: String?, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(10.dp)).background(InkHigh)
                .border(1.dp, GoldBrush, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Movie, null, tint = Gold.copy(alpha = 0.35f), modifier = Modifier.size(36.dp))
            if (image != null) AsyncImage(image, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            rating?.let {
                Row(
                    Modifier.align(Alignment.TopEnd).padding(4.dp).background(Color(0xCC000000), RoundedCornerShape(6.dp))
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Star, null, tint = Gold, modifier = Modifier.size(12.dp))
                    Text(it.take(3), color = Cream, fontSize = 11.sp)
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(name, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, color = Cream)
    }
}

/** One series: cover, story, seasons and episodes. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SeriesScreen(vm: MainViewModel, d: SeriesDetail) {
    BackHandler { vm.closeSeries() }
    var season by remember(d.item.id) { mutableStateOf(d.seasons.keys.firstOrNull() ?: 1) }
    val episodes = d.seasons[season].orEmpty()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(d.item.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = { vm.closeSeries() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "رجوع") } },
            )
        },
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Row(Modifier.padding(16.dp)) {
                    Box(
                        Modifier.width(110.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(10.dp)).background(InkHigh)
                            .border(1.dp, GoldBrush, RoundedCornerShape(10.dp)),
                    ) { d.item.cover?.let { AsyncImage(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) } }
                    Spacer(Modifier.size(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(d.item.name, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = GoldLight)
                        d.item.rating?.let { Text("★ $it", color = Gold, fontSize = 14.sp) }
                        Spacer(Modifier.height(6.dp))
                        Text(d.item.plot ?: "", fontSize = 13.sp, color = Muted, maxLines = 7, overflow = TextOverflow.Ellipsis)
                        if (episodes.isNotEmpty()) {
                            Spacer(Modifier.height(10.dp))
                            Button(onClick = { vm.playEpisodes(d, episodes, 0) }) {
                                Icon(Icons.Filled.PlayArrow, null); Spacer(Modifier.size(6.dp)); Text("شغّل الموسم $season")
                            }
                        }
                    }
                }
            }
            if (d.seasons.size > 1) item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(d.seasons.keys.toList()) { s ->
                        FilterChip(selected = s == season, onClick = { season = s }, label = { Text("الموسم $s") })
                    }
                }
            }
            if (d.seasons.isEmpty()) item {
                Text("المسلسل ده مفيهوش حلقات على السيرفر.", color = Muted, modifier = Modifier.padding(16.dp))
            }
            itemsIndexed(episodes, key = { _, e -> e.id }) { i, e ->
                Row(
                    Modifier.fillMaxWidth().clickable { vm.playEpisodes(d, episodes, i) }.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.width(120.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp))
                            .background(Brush.linearGradient(listOf(InkHigh, Ink))).border(1.dp, GoldBrush, RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        (e.image ?: d.item.cover)?.let { AsyncImage(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                        Icon(Icons.Filled.PlayArrow, null, tint = GoldLight, modifier = Modifier.size(28.dp))
                    }
                    Spacer(Modifier.size(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("الحلقة ${e.num}", color = Gold, fontSize = 13.sp)
                        Text(e.title, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
