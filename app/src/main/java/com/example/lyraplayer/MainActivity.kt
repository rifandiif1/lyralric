package com.example.lyraplayer

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
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

private data class AudioTrack(val id: Long, val uri: Uri, val title: String, val artist: String, val album: String, val duration: Long)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContent { LyraApp() } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LyraApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("lyra", Context.MODE_PRIVATE) }
    var controller by remember { mutableStateOf<MediaController?>(null) }
    var songs by remember { mutableStateOf<List<AudioTrack>>(emptyList()) }
    var search by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Meminta izin membaca musik...") }
    var tab by remember { mutableIntStateOf(0) }
    var playing by remember { mutableStateOf(false) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var currentTitle by remember { mutableStateOf(prefs.getString("current_title", "Belum ada lagu").orEmpty()) }
    var currentArtist by remember { mutableStateOf(prefs.getString("current_artist", "").orEmpty()) }
    var currentUri by remember { mutableStateOf<Uri?>(null) }
    var shuffle by remember { mutableStateOf(false) }
    var repeatMode by remember { mutableIntStateOf(Player.REPEAT_MODE_OFF) }
    var searchTrack by remember { mutableStateOf("") }
    var searchArtist by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<LrcResult>>(emptyList()) }
    var lrcText by remember { mutableStateOf(prefs.getString("current_lrc", "").orEmpty()) }
    var currentLyric by remember { mutableStateOf("") }

    fun permissionName() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    fun scanSongs() {
        try {
            val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            val projection = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.DURATION)
            val found = mutableListOf<AudioTrack>()
            context.contentResolver.query(collection, projection, "${MediaStore.Audio.Media.IS_MUSIC} != 0", null, "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC")?.use { cursor: Cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    found += AudioTrack(id, Uri.withAppendedPath(collection, id.toString()), cursor.getString(titleCol)?.takeIf { it.isNotBlank() } ?: "Judul tidak diketahui", cursor.getString(artistCol)?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: "Artis tidak diketahui", cursor.getString(albumCol)?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: "Album tidak diketahui", cursor.getLong(durationCol))
                }
            }
            songs = found
            status = if (found.isEmpty()) "Belum ada lagu terindeks. Pastikan file audio tersimpan di HP, lalu pindai ulang." else "${found.size} lagu ditemukan di perangkat."
        } catch (e: Exception) { status = "Gagal membaca musik: ${e.message ?: "kesalahan akses media"}" }
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) scanSongs() else status = "Izin audio diperlukan agar Lyra bisa membaca lagu lokal." }
    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(context, permissionName()) == PackageManager.PERMISSION_GRANTED) scanSongs()
        else permissionLauncher.launch(permissionName())
    }
    DisposableEffect(context) {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future: ListenableFuture<MediaController> = MediaController.Builder(context, token).buildAsync()
        future.addListener({ try { controller = future.get() } catch (_: Exception) { status = "Pemutar gagal disiapkan. Coba buka ulang aplikasi." } }, context.mainExecutor)
        onDispose { MediaController.releaseFuture(future) }
    }
    LaunchedEffect(controller) {
        val c = controller ?: return@LaunchedEffect
        while (true) {
            playing = c.isPlaying
            position = c.currentPosition.coerceAtLeast(0L)
            duration = c.duration.takeIf { it > 0 && it != androidx.media3.common.C.TIME_UNSET } ?: 0L
            shuffle = c.shuffleModeEnabled
            repeatMode = c.repeatMode
            c.mediaMetadata.title?.toString()?.takeIf { it.isNotBlank() }?.let { currentTitle = it }
            c.mediaMetadata.artist?.toString()?.takeIf { it.isNotBlank() }?.let { currentArtist = it }
            currentUri = c.currentMediaItem?.localConfiguration?.uri
            currentLyric = LrcParser.displayLine(lrcText, position)
            LyricsWidgetProvider.updateAll(context, currentTitle, currentLyric)
            delay(500)
        }
    }
    fun playSong(index: Int) {
        val c = controller ?: run { status = "Pemutar sedang disiapkan, coba lagi sebentar."; return }
        if (index !in songs.indices) return
        val items = songs.map { song -> MediaItem.Builder().setUri(song.uri).setMediaMetadata(MediaMetadata.Builder().setTitle(song.title).setArtist(song.artist).setAlbumTitle(song.album).build()).build() }
        c.setMediaItems(items, index, 0L); c.prepare(); c.play()
        currentTitle = songs[index].title; currentArtist = songs[index].artist
        searchTrack = currentTitle; searchArtist = currentArtist.takeUnless { it == "Artis tidak diketahui" }.orEmpty()
        prefs.edit().putString("current_title", currentTitle).putString("current_artist", currentArtist).apply()
        status = "Memutar: $currentTitle"
    }
    val lrcPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) try { lrcText = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty(); prefs.edit().putString("current_lrc", lrcText).apply(); status = "Berkas LRC berhasil dibuka." } catch (e: Exception) { status = "Gagal membuka LRC: ${e.message}" } }
    val saveLrcLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri -> if (uri != null) try { context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(lrcText) }; status = "Berkas LRC berhasil diekspor." } catch (e: Exception) { status = "Gagal mengekspor LRC: ${e.message}" } }

    MaterialTheme(colorScheme = darkColorScheme(primary = androidx.compose.ui.graphics.Color(0xFFB8F36B), secondary = androidx.compose.ui.graphics.Color(0xFFB8F36B), background = androidx.compose.ui.graphics.Color(0xFF101010), surface = androidx.compose.ui.graphics.Color(0xFF1C1C1C))) {
        Scaffold(topBar = { TopAppBar(title = { Column { Text("LYRA", fontWeight = FontWeight.Black); Text("MUSIC PLAYER", style = MaterialTheme.typography.labelSmall) } }, actions = { TextButton(onClick = { if (ContextCompat.checkSelfPermission(context, permissionName()) == PackageManager.PERMISSION_GRANTED) scanSongs() else permissionLauncher.launch(permissionName()) }) { Text("Pindai ulang") } }) }, bottomBar = {
            Column {
                if (currentUri != null || playing || currentTitle != "Belum ada lagu") Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 4.dp) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text(currentTitle, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold); Text(currentArtist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall) }
                        TextButton(onClick = { controller?.seekToPreviousMediaItem() }) { Text("|◀") }
                        Button(onClick = { if (playing) controller?.pause() else controller?.play() }) { Text(if (playing) "Ⅱ" else "▶") }
                        TextButton(onClick = { controller?.seekToNextMediaItem() }) { Text("▶|") }
                    }
                }
                NavigationBar { NavigationBarItem(selected = tab == 0, onClick = { tab = 0 }, icon = { Text("♫") }, label = { Text("Musik") }); NavigationBarItem(selected = tab == 1, onClick = { tab = 1 }, icon = { Text("♪") }, label = { Text("Lirik") }) }
            }
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
                if (tab == 0) {
                    Spacer(Modifier.height(8.dp)); Text("Perpustakaan musik", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
                    OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth(), label = { Text("Cari lagu, artis, atau album") }, singleLine = true, shape = RoundedCornerShape(28.dp))
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${songs.size} lagu", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                        TextButton(onClick = { controller?.shuffleModeEnabled = !(controller?.shuffleModeEnabled ?: false) }) { Text(if (shuffle) "Acak: aktif" else "Acak lagu") }
                        TextButton(onClick = { val next = when (controller?.repeatMode ?: Player.REPEAT_MODE_OFF) { Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL; Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE; else -> Player.REPEAT_MODE_OFF }; controller?.repeatMode = next; repeatMode = next }) { Text(when (repeatMode) { Player.REPEAT_MODE_ALL -> "Ulang: semua"; Player.REPEAT_MODE_ONE -> "Ulang: satu"; else -> "Ulang: mati" }) }
                    }
                    val filtered = remember(songs, search) { if (search.isBlank()) songs else songs.filter { it.title.contains(search, true) || it.artist.contains(search, true) || it.album.contains(search, true) } }
                    if (songs.isEmpty()) Surface(Modifier.fillMaxWidth().padding(top = 16.dp), shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text("Belum ada lagu", style = MaterialTheme.typography.titleLarge); Text("Lyra memindai audio lokal yang terindeks di penyimpanan internal dan kartu SD."); Button(onClick = { if (ContextCompat.checkSelfPermission(context, permissionName()) == PackageManager.PERMISSION_GRANTED) scanSongs() else permissionLauncher.launch(permissionName()) }) { Text("Izinkan akses / pindai lagu") } }
                    } else LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        items(filtered, key = { it.id }) { song ->
                            val isCurrent = song.uri == currentUri
                            Row(Modifier.fillMaxWidth().clickable { playSong(songs.indexOfFirst { it.id == song.id }) }.padding(vertical = 10.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.size(48.dp)) { Box(contentAlignment = Alignment.Center) { Text(if (isCurrent && playing) "♫" else "♪", style = MaterialTheme.typography.headlineSmall) } }
                                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) { Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium, color = if (isCurrent) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface); Text("${song.artist} · ${song.album}", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                Text(formatTime(song.duration), style = MaterialTheme.typography.labelSmall)
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                        }
                    }
                } else {
                    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Spacer(Modifier.height(8.dp)); Text("Lirik & editor LRC", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold); Text("Lagu sekarang: $currentTitle", style = MaterialTheme.typography.bodyMedium)
                        OutlinedTextField(searchTrack, { searchTrack = it }, Modifier.fillMaxWidth(), label = { Text("Judul lagu") }, singleLine = true)
                        OutlinedTextField(searchArtist, { searchArtist = it }, Modifier.fillMaxWidth(), label = { Text("Artis (opsional)") }, singleLine = true)
                        Button(onClick = { scope.launch { status = "Mencari lirik di LRCLIB..."; try { results = withContext(Dispatchers.IO) { LrclibClient.search(searchTrack, searchArtist) }; status = if (results.isEmpty()) "Lirik tidak ditemukan." else "Pilih hasil lirik di bawah." } catch (e: Exception) { status = "Pencarian gagal: ${e.message ?: "periksa koneksi"}" } } }, enabled = searchTrack.isNotBlank()) { Text("Cari lirik") }
                        Text(status, style = MaterialTheme.typography.bodySmall)
                        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 150.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { items(results.take(8)) { result -> ElevatedCard(onClick = { lrcText = result.syncedLyrics ?: result.plainLyrics.orEmpty(); currentTitle = result.name; currentArtist = result.artist; prefs.edit().putString("current_lrc", lrcText).putString("current_title", currentTitle).putString("current_artist", currentArtist).apply(); LyricsWidgetProvider.updateAll(context, currentTitle, LrcParser.displayLine(lrcText, position)); status = if (result.syncedLyrics != null) "Lirik tersinkron dipilih." else "Lirik teks biasa dipilih." }) { Column(Modifier.padding(10.dp)) { Text(result.name, fontWeight = FontWeight.SemiBold); Text(result.artist, style = MaterialTheme.typography.bodySmall); Text(if (result.syncedLyrics != null) "Lirik tersinkron" else "Lirik biasa", style = MaterialTheme.typography.labelSmall) } } } }
                        Text("Lirik saat ini", style = MaterialTheme.typography.titleMedium); Text(currentLyric.ifBlank { "Lirik belum tersedia" }, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.secondary)
                        OutlinedTextField(lrcText, { lrcText = it }, Modifier.weight(1f).fillMaxWidth(), label = { Text("Isi berkas .lrc") })
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { OutlinedButton(onClick = { lrcPicker.launch(arrayOf("text/*", "application/octet-stream")) }) { Text("Buka LRC") }; OutlinedButton(onClick = { saveLrcLauncher.launch(currentTitle.substringBeforeLast('.', currentTitle) + ".lrc") }) { Text("Ekspor") }; OutlinedButton(onClick = { lrcText = (lrcText.trimEnd() + "\n" + LrcParser.timestamp(position) + " ").trimStart() }) { Text("Cap waktu") } }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { OutlinedButton(onClick = { lrcText = LrcParser.shift(lrcText, -500) }) { Text("Maju 0,5 dtk") }; OutlinedButton(onClick = { lrcText = LrcParser.shift(lrcText, 500) }) { Text("Mundur 0,5 dtk") }; Button(onClick = { prefs.edit().putString("current_lrc", lrcText).putString("current_title", currentTitle).putString("current_artist", currentArtist).apply(); LyricsWidgetProvider.updateAll(context, currentTitle, LrcParser.displayLine(lrcText, position)); status = "Lirik disimpan." }) { Text("Simpan") } }
                    }
                }
            }
        }
    }
}

private fun formatTime(ms: Long): String { val safe = ms.coerceAtLeast(0L); return "%d:%02d".format(safe / 60000, (safe / 1000) % 60) }
