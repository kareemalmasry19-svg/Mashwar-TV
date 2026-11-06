package com.mashwar.tv

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.app.PictureInPictureParams
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.lifecycle.Lifecycle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.Coil
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import java.io.File

// FragmentActivity (not plain ComponentActivity): the Cast button opens its device picker as a fragment dialog.
class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        CrashLog.install(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Coil.setImageLoader(
            ImageLoader.Builder(this)
                .okHttpClient(sharedHttp) // same fast connections as the streams
                .respectCacheHeaders(false) // IPTV logo hosts often send no-cache
                .crossfade(false)
                .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.20).build() }
                .diskCache { DiskCache.Builder().directory(cacheDir.resolve("logos")).maxSizeBytes(100L * 1024 * 1024).build() }
                .build()
        )
        addOnPictureInPictureModeChangedListener { info ->
            PipState.inPip = info.isInPictureInPictureMode
            // Closing the PiP window leaves the activity stopped: tell the player to stop too.
            if (!info.isInPictureInPictureMode && !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                PipState.dismissed++
            }
        }
        setContent { App() }
    }

    /** Home / recents while watching → keep watching in a small floating window. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (PipState.playerActive) enterPip()
    }

    fun enterPip() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching {
            enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build())
        }
    }
}

/** Saves the last crash to a file so the next launch can show it (and the user can copy it to us). */
object CrashLog {
    private fun file(ctx: Context) = File(ctx.filesDir, "last_crash.txt")

    fun install(ctx: Context) {
        val app = ctx.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous is Handler) return
        Thread.setDefaultUncaughtExceptionHandler(Handler(app, previous))
    }

    fun take(ctx: Context): String? {
        val f = file(ctx)
        if (!f.exists()) return null
        return runCatching { f.readText() }.getOrNull().also { f.delete() }
    }

    private class Handler(val app: Context, val next: Thread.UncaughtExceptionHandler?) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(t: Thread, e: Throwable) {
            runCatching {
                val version = app.packageManager.getPackageInfo(app.packageName, 0).versionName
                file(app).writeText("Almasry TV $version\nThread: ${t.name}\n\n${e.stackTraceToString()}")
            }
            next?.uncaughtException(t, e)
        }
    }
}

@Composable
fun App(vm: MainViewModel = viewModel()) {
    MaterialTheme(colorScheme = KenueColors) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                when {
                    vm.nowPlaying != null -> PlayerScreen(vm)
                    vm.seriesDetail != null -> SeriesScreen(vm, vm.seriesDetail!!)
                    vm.current != null -> ChannelsScreen(vm)
                    else -> HomeScreen(vm)
                }
            }
            CrashReportDialog()
        }
    }
}

@Composable
private fun CrashReportDialog() {
    val ctx = LocalContext.current
    var report by remember { mutableStateOf(CrashLog.take(ctx)) }
    val text = report ?: return
    AlertDialog(
        onDismissRequest = { report = null },
        title = { Text("التطبيق قفل المرة اللي فاتت") },
        text = {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Text(
                    text.take(4000), fontSize = 11.sp,
                    modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Almasry TV crash", text))
                Toast.makeText(ctx, "اتنسخ، ابعته في الشات", Toast.LENGTH_SHORT).show()
                report = null
            }) { Text("انسخ التفاصيل") }
        },
        dismissButton = { TextButton(onClick = { report = null }) { Text("قفل") } },
    )
}
