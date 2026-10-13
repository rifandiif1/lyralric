package com.example.lyraplayer

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.example.lyraplayer.lyrics.LrcParser
import com.example.lyraplayer.lyrics.LrclibClient
import com.example.lyraplayer.lyrics.LrcResult
import com.example.lyraplayer.playback.PlaybackService
import com.example.lyraplayer.widget.LyricsWidgetProvider
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { LyraApp() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LyraApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var controller by remember { mutableStateOf<MediaController?>(null) }
    val savedPrefs = remember { context.getSharedPreferences("lyra", Context.MODE_PRIVATE) }
    var selectedUri by remember { mutableStateOf(savedPrefs.getString("current_audio_uri", null)?.let(Uri::parse)) }
    var lastLoadedUri by remember { mutableStateOf<Uri?>(null) }
    var title by remember { mutableStateOf(savedPrefs.getString("current_title", "Belum ada lagu").orEmpty()) }
    var artist by remember { mutableStateOf(savedPrefs.getString("current_artist", "").orEmpty()) }
    var searchTrack by remember { mutableStateOf("") }
    var searchArtist by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<LrcResult>>(emptyList()) }
    var lrcText by remember { mutableStateOf(savedPrefs.getString("current_lrc", "").orEmpty()) }
    var status by remember { mutableStateOf("Pilih lagu lokal untuk memulai.") }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(false) }
    var currentLyric by remember { mutableStateOf("") }

    DisposableEffect(context) {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future: ListenableFuture<MediaController> = MediaController.Builder(context, token).buildAsync()
        future.addListener({ try { controller = future.get() } catch (_: Exception) { status = "Gagal menghubungkan pemutar." } }, context.mainExecutor)
        onDispose { MediaController.releaseFuture(future) }
    }

    LaunchedEffect(controller, selectedUri) {
        val c = controller
        val uri = selectedUri
        if (c != null && uri != null && uri != lastLoadedUri) {
            val metadata = MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist.ifBlank { "Artis tidak diketahui" })
                .build()
            c.setMediaItem(MediaItem.Builder().setUri(uri).setMediaMetadata(metadata).build())
            c.prepare()
            c.play()
            lastLoadedUri = uri
            status = "Memutar: $title"
        }
        if (c != null) {
            while (true) {
                position = c.currentPosition.coerceAtLeast(0)
                duration = c.duration.takeIf { it > 0 && it != androidx.media3.common.C.TIME_UNSET } ?: 0L
                playing = c.isPlaying
                currentLyric = LrcParser.displayLine(lrcText, position)
                delay(300)
            }
        }
    }

    val lrcPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                lrcText = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty()
                context.getSharedPreferences("lyra", Context.MODE_PRIVATE).edit().putString("current_lrc", lrcText).apply()
                LyricsWidgetProvider.updateAll(context, title, LrcParser.displayLine(lrcText, position))
                status = "Berkas LRC berhasil dibuka."
            } catch (e: Exception) { status = "Gagal membuka LRC: ${e.message ?: "kesalahan baca berkas"}" }
        }
    }
    val saveLrcLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(lrcText) }
                status = "Berkas LRC berhasil diekspor."
            } catch (e: Exception) { status = "Gagal menyimpan LRC: ${e.message ?: "kesalahan tulis berkas"}" }
        }
    }

    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            selectedUri = uri
            val name = try {
                context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
            } catch (_: Exception) { null } ?: uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':') ?: "Audio lokal"
            title = name.substringBeforeLast('.', name)
            artist = ""
            searchArtist = ""
            searchTrack = title
            context.getSharedPreferences("lyra", Context.MODE_PRIVATE).edit()
                .putString("current_audio_uri", uri.toString()).putString("current_title", title).apply()
            status = if (controller == null) "Audio dipilih. Pemutar sedang disiapkan…" else "Menyiapkan audio…"
        }
    }

    MaterialTheme(colorScheme = darkColorScheme()) {
        Scaffold(topBar = { TopAppBar(title = { Text("Lyra Player") }) }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(title, style = MaterialTheme.typography.headlineSmall)
                if (artist.isNotBlank()) Text(artist)
                Text(status, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { audioPicker.launch(arrayOf("audio/*")) }) { Text("Pilih audio") }
                    Button(onClick = { if (playing) controller?.pause() else controller?.play() }, enabled = controller != null) { Text(if (playing) "Jeda" else "Putar") }
                    OutlinedButton(onClick = { controller?.seekTo((position - 10000).coerceAtLeast(0)) }, enabled = controller != null) { Text("-10 dtk") }
                    OutlinedButton(onClick = { controller?.seekTo(position + 10000) }, enabled = controller != null) { Text("+10 dtk") }
                }
                Text("${formatTime(position)} / ${formatTime(duration)}")
                Slider(
                    value = if (duration > 0) position.toFloat().coerceIn(0f, duration.toFloat()) else 0f,
                    onValueChange = { controller?.seekTo(it.toLong()) },
                    valueRange = 0f..duration.toFloat().coerceAtLeast(1f),
                    enabled = controller != null && duration > 0,
                    modifier = Modifier.fillMaxWidth()
                )
                HorizontalDivider()
                Text("Cari lirik di LRCLIB", style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(searchTrack, { searchTrack = it }, label = { Text("Judul lagu") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(searchArtist, { searchArtist = it }, label = { Text("Nama artis (opsional)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Button(onClick = {
                    scope.launch {
                        status = "Mencari lirik..."
                        try {
                            results = withContext(Dispatchers.IO) { LrclibClient.search(searchTrack, searchArtist) }
                            status = if (results.isEmpty()) "Lirik tidak ditemukan. Coba judul atau artis lain." else "Ditemukan ${results.size} hasil. Pilih salah satu."
                        } catch (e: Exception) { status = "Pencarian gagal: ${e.message ?: "periksa koneksi internet"}" }
                    }
                }, enabled = searchTrack.isNotBlank()) { Text("Cari lirik") }
                results.take(8).forEach { result ->
                    ElevatedCard(onClick = {
                        val text = result.syncedLyrics ?: result.plainLyrics.orEmpty()
                        lrcText = text
                        title = result.name
                        artist = result.artist
                        context.getSharedPreferences("lyra", Context.MODE_PRIVATE).edit()
                            .putString("current_lrc", text).putString("current_title", title)
                            .putString("current_artist", artist).apply()
                        LyricsWidgetProvider.updateAll(context, title, LrcParser.currentLine(LrcParser.parse(text), position)?.text ?: "Lirik siap dibaca")
                        controller?.let { c ->
                            val old = c.currentMediaItem
                            if (old != null) c.replaceMediaItem(c.currentMediaItemIndex, old.buildUpon().setMediaMetadata(MediaMetadata.Builder().setTitle(title).setArtist(artist).build()).build())
                        }
                        status = if (result.syncedLyrics != null) "Lirik tersinkron dipilih." else "Lirik teks biasa dipilih; tidak memiliki timestamp."
                    }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) { Text(result.name, style = MaterialTheme.typography.titleMedium); Text(result.artist); Text(if (result.syncedLyrics != null) "Lirik tersinkron (LRC)" else "Lirik teks biasa", style = MaterialTheme.typography.bodySmall) }
                    }
                }
                HorizontalDivider()
                Text("Editor LRC", style = MaterialTheme.typography.titleLarge)
                Text("Lirik yang dipilih akan muncul di sini. Format contoh: [00:12.340]Baris lirik")
                OutlinedTextField(value = lrcText, onValueChange = { lrcText = it }, modifier = Modifier.fillMaxWidth().height(220.dp), minLines = 8, label = { Text("Isi berkas .lrc") })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { lrcPicker.launch(arrayOf("text/*", "application/octet-stream")) }) { Text("Buka .lrc") }
                    OutlinedButton(onClick = { saveLrcLauncher.launch(title.substringBeforeLast('.', title) + ".lrc") }) { Text("Ekspor .lrc") }
                    OutlinedButton(onClick = { lrcText = (lrcText.trimEnd() + "\n" + LrcParser.timestamp(position) + " ").trimStart() }) { Text("Cap waktu") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { lrcText = LrcParser.shift(lrcText, -500) }) { Text("Maju 0,5 dtk") }
                    OutlinedButton(onClick = { lrcText = LrcParser.shift(lrcText, 500) }) { Text("Mundur 0,5 dtk") }
                    Button(onClick = {
                        context.getSharedPreferences("lyra", Context.MODE_PRIVATE).edit()
                            .putString("current_lrc", lrcText).putString("current_title", title)
                            .putString("current_artist", artist).apply()
                        LyricsWidgetProvider.updateAll(context, title, LrcParser.displayLine(lrcText, position))
                        status = "Lirik disimpan di aplikasi dan widget diperbarui."
                    }) { Text("Simpan") }
                }
                val parsed = remember(lrcText) { LrcParser.parse(lrcText) }
                Text("Pratinjau lirik tersinkron", style = MaterialTheme.typography.titleMedium)
                Text(currentLyric.ifBlank { "Belum ada baris lirik pada posisi ini." }, style = MaterialTheme.typography.headlineSmall)
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 180.dp)) {
                    itemsIndexed(parsed.take(12)) { _, line -> Text("${formatTime(line.timeMs)}  ${line.text}", modifier = Modifier.padding(vertical = 3.dp)) }
                }
                Text("Catatan: widget dan tampilan lockscreen mengikuti batasan Android/perangkat. Lock screen dapat menampilkan metadata lirik melalui sesi media, tetapi sistem tidak menjamin UI lirik penuh di semua perangkat.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    val safe = ms.coerceAtLeast(0)
    return "%02d:%02d".format(safe / 60000, (safe / 1000) % 60)
}
