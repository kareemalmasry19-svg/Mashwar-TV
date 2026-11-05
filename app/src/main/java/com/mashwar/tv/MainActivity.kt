package com.mashwar.tv

import android.os.Bundle
import coil.Coil
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Coil.setImageLoader(
            ImageLoader.Builder(this)
                .respectCacheHeaders(false) // IPTV logo hosts often send no-cache
                .crossfade(false)
                .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.20).build() }
                .diskCache { DiskCache.Builder().directory(cacheDir.resolve("logos")).maxSizeBytes(100L * 1024 * 1024).build() }
                .build()
        )
        setContent { App() }
    }
}

@Composable
fun App(vm: MainViewModel = viewModel()) {
    MaterialTheme(colorScheme = KenueColors) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                when {
                    vm.nowPlaying != null -> PlayerScreen(vm)
                    vm.current != null -> ChannelsScreen(vm)
                    else -> HomeScreen(vm)
                }
            }
        }
    }
}
