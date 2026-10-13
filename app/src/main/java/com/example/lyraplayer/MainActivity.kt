package com.example.lyraplayer

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

private data class AudioTrack(
    val id: String,
    val uri: Uri,
    val title: String,
    val artist: String,
    val album: String,
    val duration: Long,
    val folder: String = "",
    val isSaf: Boolean = false
)

private data class LyraPlaylist(val name: String, val trackIds: List<String>)

private enum class LyraTab(val label: String, val icon: String) {
    HOME("Home", "⌂"), LIBRARY("Musik", "♫"), SEARCH("Cari", "⌕"),
    PLAYLISTS("Playlist", "☷"), NOW_PLAYING("Diputar", "▶"), QUEUE("Antrean", "≡"), LYRICS("Lirik", "♪"), SETTINGS("Setelan", "⚙")
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { LyraApp() }
    }
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
    var status by remember { mutableStateOf("Siap memindai koleksi musik.") }
    var tab by remember { mutableStateOf(LyraTab.HOME) }
    var playing by remember { mutableStateOf(false) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var currentTitle by remember { mutableStateOf(prefs.getString("current_title", "Belum ada lagu").orEmpty()) }
    var currentArtist by remember { mutableStateOf(prefs.getString("current_artist", "").orEmpty()) }
    var currentAlbum by remember { mutableStateOf("") }
    var currentUri by remember { mutableStateOf<Uri?>(null) }
    var shuffle by remember { mutableStateOf(false) }
    var repeatMode by remember { mutableIntStateOf(Player.REPEAT_MODE_OFF) }
    var favoriteIds by remember { mutableStateOf(prefs.getStringSet("favorites", emptySet())?.toSet() ?: emptySet()) }
    var playlists by remember { mutableStateOf(loadPlaylists(prefs.getString("playlists_json", "[]").orEmpty())) }
    var showCreatePlaylist by remember { mutableStateOf(false) }
    var trackToAdd by remember { mutableStateOf<AudioTrack?>(null) }
    var newPlaylistName by remember { mutableStateOf("") }
    var selectedPlaylist by remember { mutableStateOf<String?>(null) }
    var selectedFolder by remember { mutableStateOf(prefs.getString("music_folder_uri", "").orEmpty()) }
    var darkMode by remember { mutableStateOf(prefs.getBoolean("dark_mode", true)) }
    var searchTrack by remember { mutableStateOf("") }
    var searchArtist by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<LrcResult>>(emptyList()) }
    var lrcText by remember { mutableStateOf(prefs.getString("current_lrc", "").orEmpty()) }
    var currentLyric by remember { mutableStateOf("") }
    var albumArt by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var sleepMinutes by remember { mutableIntStateOf(0) }
    var showSleepDialog by remember { mutableStateOf(false) }
    var lastPlayed by remember { mutableStateOf(prefs.getString("recent_ids", "").orEmpty().split("|").filter { it.isNotBlank() }) }
    var playCounts by remember { mutableStateOf(loadCounts(prefs.getString("play_counts_json", "{}").orEmpty())) }

    fun permissionName() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

    fun scanSongs() {
        try {
            val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            val projection = arrayOf(
                MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.DATA
            )
            val found = linkedMapOf<String, AudioTrack>()
            context.contentResolver.query(
                collection, projection, "${MediaStore.Audio.Media.IS_MUSIC} != 0", null,
                "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
            )?.use { cursor: Cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val dataCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol).toString()
                    val title = cursor.getString(titleCol)?.takeIf { it.isNotBlank() } ?: "Judul tidak diketahui"
                    val artist = cursor.getString(artistCol)?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: "Artis tidak diketahui"
                    val album = cursor.getString(albumCol)?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: "Album tidak diketahui"
                    val path = if (dataCol >= 0) cursor.getString(dataCol).orEmpty() else ""
                    found["media:$id"] = AudioTrack("media:$id", Uri.withAppendedPath(collection, id), title, artist, album, cursor.getLong(durationCol), path.substringBeforeLast("/", ""))
                }
            }

            // Optional user-selected folder: scan only files the user explicitly granted access to.
            if (selectedFolder.isNotBlank()) {
                runCatching {
                    val tree = DocumentFile.fromTreeUri(context, Uri.parse(selectedFolder))
                    fun visit(folder: DocumentFile, depth: Int) {
                        if (depth > 12) return
                        folder.listFiles().forEach { file ->
                            if (file.isDirectory) visit(file, depth + 1)
                            else if (file.isFile && file.name.orEmpty().substringAfterLast('.', "").lowercase(Locale.ROOT) in
                                setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "opus", "3gp", "mid", "amr")) {
                                val uri = file.uri
                                val id = "saf:$uri"
                                val title = file.name.orEmpty().substringBeforeLast('.').ifBlank { "Audio tanpa judul" }
                                if (!found.containsKey(id)) found[id] = AudioTrack(id, uri, title, "Artis tidak diketahui", "Folder pilihan", 0L, file.parentFile?.name.orEmpty(), true)
                            }
                        }
                    }
                    if (tree != null) visit(tree, 0)
                }
            }
            songs = found.values.sortedBy { it.title.lowercase(Locale.ROOT) }
            status = if (songs.isEmpty()) {
                "Belum ada lagu ditemukan. Beri izin audio atau pilih folder musik."
            } else "${songs.size} lagu tersedia${if (selectedFolder.isNotBlank()) " (termasuk folder pilihan)" else ""}."
        } catch (e: Exception) {
            status = "Gagal membaca musik: ${e.message ?: "kesalahan akses media"}"
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) scanSongs() else status = "Izin audio diperlukan untuk membaca koleksi musik yang dikenali Android."
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            selectedFolder = uri.toString()
            prefs.edit().putString("music_folder_uri", selectedFolder).apply()
            scanSongs()
        }
    }
    val lrcPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) try {
            lrcText = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty()
            prefs.edit().putString("current_lrc", lrcText).apply()
            status = "Berkas LRC berhasil dibuka."
        } catch (e: Exception) { status = "Gagal membuka LRC: ${e.message}" }
    }
    val saveLrcLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) try {
            context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(lrcText) }
            status = "Berkas LRC berhasil diekspor."
        } catch (e: Exception) { status = "Gagal mengekspor LRC: ${e.message}" }
    }

    fun savePlaylists() {
        val array = JSONArray()
        playlists.forEach { p ->
            array.put(JSONObject().put("name", p.name).put("ids", JSONArray(p.trackIds)))
        }
        prefs.edit().putString("playlists_json", array.toString()).apply()
    }

    fun toggleFavorite(track: AudioTrack) {
        favoriteIds = if (track.id in favoriteIds) favoriteIds - track.id else favoriteIds + track.id
        prefs.edit().putStringSet("favorites", favoriteIds).apply()
    }

    fun playTracks(list: List<AudioTrack>, index: Int) {
        val c = controller ?: run { status = "Pemutar sedang disiapkan. Coba lagi sebentar."; return }
        if (list.isEmpty() || index !in list.indices) return
        val mediaItems = list.map { song ->
            MediaItem.Builder().setUri(song.uri).setMediaId(song.id)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(song.title).setArtist(song.artist).setAlbumTitle(song.album).build())
                .build()
        }
        c.setMediaItems(mediaItems, index, 0L)
        c.prepare()
        c.play()
        val track = list[index]
        currentTitle = track.title
        currentArtist = track.artist
        currentAlbum = track.album
        currentUri = track.uri
        searchTrack = track.title
        searchArtist = track.artist.takeUnless { it == "Artis tidak diketahui" }.orEmpty()
        val newRecent = (listOf(track.id) + lastPlayed.filterNot { it == track.id }).take(60)
        lastPlayed = newRecent
        playCounts = playCounts.toMutableMap().also { it[track.id] = (it[track.id] ?: 0) + 1 }
        prefs.edit()
            .putString("current_title", track.title).putString("current_artist", track.artist)
            .putString("recent_ids", newRecent.joinToString("|"))
            .putString("play_counts_json", JSONObject(playCounts as Map<*, *>).toString())
            .apply()
        status = "Memutar: ${track.title}"
    }

    DisposableEffect(context) {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future: ListenableFuture<MediaController> = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            try { controller = future.get() }
            catch (_: Exception) { status = "Pemutar gagal disiapkan. Coba buka ulang aplikasi." }
        }, context.mainExecutor)
        onDispose { MediaController.releaseFuture(future) }
    }

    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(context, permissionName()) == PackageManager.PERMISSION_GRANTED) scanSongs()
        else permissionLauncher.launch(permissionName())
    }

    LaunchedEffect(currentUri) {
        albumArt = withContext(Dispatchers.IO) {
            val uri = currentUri
            if (uri == null) null else runCatching {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, uri)
                    val bytes = retriever.embeddedPicture
                    if (bytes != null) BitmapFactory.decodeByteArray(bytes, 0, bytes.size) else null
                } finally {
                    retriever.release()
                }
            }.getOrNull()
        }
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
            c.mediaMetadata.albumTitle?.toString()?.let { currentAlbum = it }
            currentUri = c.currentMediaItem?.localConfiguration?.uri
            currentLyric = LrcParser.displayLine(lrcText, position)
            LyricsWidgetProvider.updateAll(context, currentTitle, currentLyric)
            delay(500)
        }
    }

    LaunchedEffect(sleepMinutes) {
        if (sleepMinutes > 0) {
            val remaining = sleepMinutes * 60_000L
            delay(remaining)
            controller?.pause()
            sleepMinutes = 0
            status = "Timer tidur selesai. Musik dijeda."
        }
    }

    val filtered = remember(songs, search) {
        if (search.isBlank()) songs else songs.filter {
            it.title.contains(search, true) || it.artist.contains(search, true) ||
                it.album.contains(search, true) || it.folder.contains(search, true)
        }
    }
    val currentTrack = songs.firstOrNull { it.uri == currentUri }
    val recentTracks = lastPlayed.mapNotNull { id -> songs.firstOrNull { it.id == id } }
    val favoriteTracks = songs.filter { it.id in favoriteIds }
    val playlistTracks = if (selectedPlaylist == "Favorit") favoriteTracks else
        selectedPlaylist?.let { name -> playlists.firstOrNull { it.name == name }?.trackIds?.mapNotNull { id -> songs.firstOrNull { it.id == id } }.orEmpty() }.orEmpty()
    val accent = androidx.compose.ui.graphics.Color(0xFFB8F36B)
    val colors = if (darkMode) darkColorScheme(
        primary = accent, secondary = accent, background = androidx.compose.ui.graphics.Color(0xFF101010),
        surface = androidx.compose.ui.graphics.Color(0xFF1C1C1C), surfaceVariant = androidx.compose.ui.graphics.Color(0xFF292929)
    ) else lightColorScheme(primary = androidx.compose.ui.graphics.Color(0xFF4D6E16), secondary = androidx.compose.ui.graphics.Color(0xFF4D6E16))

    MaterialTheme(colorScheme = colors) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Column { Text("LYRA", fontWeight = FontWeight.Black); Text("LOCAL MUSIC · LYRICS", style = MaterialTheme.typography.labelSmall) } },
                    actions = {
                        TextButton(onClick = {
                            if (ContextCompat.checkSelfPermission(context, permissionName()) == PackageManager.PERMISSION_GRANTED) scanSongs()
                            else permissionLauncher.launch(permissionName())
                        }) { Text("Pindai") }
                    }
                )
            },
            bottomBar = {
                Column {
                    if (currentUri != null || playing || currentTitle != "Belum ada lagu") {
                        Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 5.dp) {
                            Column(Modifier.fillMaxWidth().clickable { tab = LyraTab.NOW_PLAYING }.padding(horizontal = 12.dp, vertical = 7.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(currentTitle, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                                        Text(currentArtist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                                    }
                                    TextButton(onClick = { controller?.seekToPreviousMediaItem() }) { Text("|◀") }
                                    Button(onClick = { if (playing) controller?.pause() else controller?.play() }, contentPadding = PaddingValues(horizontal = 14.dp)) { Text(if (playing) "Ⅱ" else "▶") }
                                    TextButton(onClick = { controller?.seekToNextMediaItem() }) { Text("▶|") }
                                }
                                if (duration > 0) {
                                    Slider(value = position.coerceIn(0L, duration).toFloat(), onValueChange = { position = it.toLong() }, onValueChangeFinished = { controller?.seekTo(position) }, valueRange = 0f..duration.toFloat(), modifier = Modifier.height(22.dp))
                                }
                            }
                        }
                    }
                    NavigationBar {
                        LyraTab.values().forEach { item ->
                            NavigationBarItem(
                                selected = tab == item, onClick = { tab = item },
                                icon = { Text(item.icon) }, label = { Text(item.label, maxLines = 1) }
                            )
                        }
                    }
                }
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
                when (tab) {
                    LyraTab.HOME -> {
                        Spacer(Modifier.height(12.dp))
                        Text("Dengarkan koleksimu", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(vertical = 8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            SummaryCard("Semua lagu", songs.size.toString(), Modifier.weight(1f)) { tab = LyraTab.LIBRARY }
                            SummaryCard("Favorit", favoriteTracks.size.toString(), Modifier.weight(1f)) { search = ""; tab = LyraTab.LIBRARY }
                            SummaryCard("Playlist", playlists.size.toString(), Modifier.weight(1f)) { tab = LyraTab.PLAYLISTS }
                        }
                        Spacer(Modifier.height(14.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Baru diputar", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            TextButton(onClick = { tab = LyraTab.LIBRARY }) { Text("Lihat semua") }
                        }
                        if (recentTracks.isEmpty()) EmptyState("Belum ada riwayat", "Putar lagu untuk mulai membuat riwayat lokal.")
                        else TrackList(recentTracks.take(12), currentUri, playing, favoriteIds, ::playTracks, ::toggleFavorite, onAddToPlaylist = { trackToAdd = it }, showFolder = false)
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { folderPicker.launch(null) }) { Text("Pilih folder musik") }
                            OutlinedButton(onClick = { showSleepDialog = true }) { Text("Timer tidur") }
                        }
                    }
                    LyraTab.LIBRARY -> {
                        Spacer(Modifier.height(8.dp))
                        Text("Perpustakaan", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        Text("${songs.size} lagu · ${songs.map { it.artist }.distinct().size} artis · ${songs.map { it.album }.distinct().size} album", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                        OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth().padding(vertical = 8.dp), label = { Text("Cari judul, artis, album, folder") }, singleLine = true, shape = RoundedCornerShape(24.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Text(if (favoriteTracks.isNotEmpty() && search.equals("favorit", true)) "Favorit" else "${filtered.size} hasil", Modifier.weight(1f))
                            TextButton(onClick = { controller?.shuffleModeEnabled = !(controller?.shuffleModeEnabled ?: false) }) { Text(if (shuffle) "Acak ✓" else "Acak") }
                            TextButton(onClick = { val next = when (controller?.repeatMode ?: Player.REPEAT_MODE_OFF) { Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL; Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE; else -> Player.REPEAT_MODE_OFF }; controller?.repeatMode = next; repeatMode = next }) {
                                Text(when (repeatMode) { Player.REPEAT_MODE_ALL -> "Ulang semua"; Player.REPEAT_MODE_ONE -> "Ulang satu"; else -> "Ulang mati" })
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { search = "" }) { Text("Semua") }
                            OutlinedButton(onClick = { search = "favorit" }) { Text("Favorit") }
                            OutlinedButton(onClick = { folderPicker.launch(null) }) { Text("Folder") }
                        }
                        val libraryList = if (search.equals("favorit", true)) favoriteTracks else filtered
                        TrackList(libraryList, currentUri, playing, favoriteIds, ::playTracks, ::toggleFavorite, onAddToPlaylist = { trackToAdd = it }, showFolder = true)
                    }
                    LyraTab.SEARCH -> {
                        Spacer(Modifier.height(10.dp))
                        Text("Cari musik", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth().padding(vertical = 8.dp), label = { Text("Judul, artis, album, atau folder") }, singleLine = true)
                        Text("${filtered.size} hasil ditemukan", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                        TrackList(filtered, currentUri, playing, favoriteIds, ::playTracks, ::toggleFavorite, onAddToPlaylist = { trackToAdd = it }, showFolder = true)
                    }
                    LyraTab.PLAYLISTS -> {
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Playlist", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            Button(onClick = { showCreatePlaylist = true }) { Text("+ Buat") }
                        }
                        Text("Playlist tersimpan secara lokal di perangkat.", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(8.dp))
                        if (selectedPlaylist != null) {
                            TextButton(onClick = { selectedPlaylist = null }) { Text("← Semua playlist") }
                            Text(selectedPlaylist.orEmpty(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { playTracks(playlistTracks, 0) }, enabled = playlistTracks.isNotEmpty()) { Text("Putar") }
                                OutlinedButton(onClick = {
                                    val p = playlists.firstOrNull { it.name == selectedPlaylist }
                                    if (p != null) {
                                        playlists = playlists.map { if (it.name == p.name) p.copy(trackIds = (p.trackIds + songs.map { s -> s.id }).distinct()) else it }
                                        savePlaylists()
                                    }
                                }) { Text("Tambah semua lagu") }
                                OutlinedButton(onClick = {
                                    playlists = playlists.filterNot { it.name == selectedPlaylist }
                                    savePlaylists()
                                    selectedPlaylist = null
                                }) { Text("Hapus") }
                            }
                            TrackList(playlistTracks, currentUri, playing, favoriteIds, ::playTracks, ::toggleFavorite, onAddToPlaylist = { trackToAdd = it }, showFolder = false)
                        } else {
                            if (playlists.isEmpty()) EmptyState("Belum ada playlist", "Buat playlist lalu tambahkan lagu dari daftar musik.")
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
                                item {
                                    ElevatedCard(onClick = { selectedPlaylist = "Favorit" }) {
                                        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Text("♥", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(end = 12.dp))
                                            Column(Modifier.weight(1f)) { Text("Favorit", fontWeight = FontWeight.Bold); Text("${favoriteTracks.size} lagu", style = MaterialTheme.typography.bodySmall) }
                                            TextButton(onClick = { playTracks(favoriteTracks, 0) }, enabled = favoriteTracks.isNotEmpty()) { Text("▶") }
                                        }
                                    }
                                }
                                items(playlists, key = { it.name }) { p ->
                                    ElevatedCard(onClick = { selectedPlaylist = p.name }) {
                                        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Text("♫", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(end = 12.dp))
                                            Column(Modifier.weight(1f)) { Text(p.name, fontWeight = FontWeight.Bold); Text("${p.trackIds.size} item", style = MaterialTheme.typography.bodySmall) }
                                            TextButton(onClick = { playTracks(p.trackIds.mapNotNull { id -> songs.firstOrNull { it.id == id } }, 0) }) { Text("▶") }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    LyraTab.NOW_PLAYING -> {
                        NowPlayingScreen(
                            title = currentTitle,
                            artist = currentArtist,
                            album = currentAlbum,
                            albumArt = albumArt,
                            isPlaying = playing,
                            position = position,
                            duration = duration,
                            shuffle = shuffle,
                            repeatMode = repeatMode,
                            lrcText = lrcText,
                            onPlayPause = { if (playing) controller?.pause() else controller?.play() },
                            onPrevious = { controller?.seekToPreviousMediaItem() },
                            onNext = { controller?.seekToNextMediaItem() },
                            onSeek = { controller?.seekTo(it) },
                            onShuffle = { controller?.shuffleModeEnabled = !(controller?.shuffleModeEnabled ?: false) },
                            onRepeat = {
                                val next = when (controller?.repeatMode ?: Player.REPEAT_MODE_OFF) {
                                    Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                                    Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                                    else -> Player.REPEAT_MODE_OFF
                                }
                                controller?.repeatMode = next
                                repeatMode = next
                            },
                            onOpenLyrics = { tab = LyraTab.LYRICS },
                            onOpenQueue = { tab = LyraTab.QUEUE }
                        )
                    }
                    LyraTab.QUEUE -> {
                        Spacer(Modifier.height(10.dp))
                        Text("Antrean pemutaran", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        Text("Shuffle: ${if (shuffle) "aktif" else "mati"} · Repeat: ${when (repeatMode) { Player.REPEAT_MODE_ALL -> "semua"; Player.REPEAT_MODE_ONE -> "satu"; else -> "mati" }}", style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { controller?.shuffleModeEnabled = !(controller?.shuffleModeEnabled ?: false) }) { Text("Acak") }
                            OutlinedButton(onClick = { val next = when (controller?.repeatMode ?: Player.REPEAT_MODE_OFF) { Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL; Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE; else -> Player.REPEAT_MODE_OFF }; controller?.repeatMode = next; repeatMode = next }) { Text("Ubah repeat") }
                        }
                        val queue = controller?.let { c -> (0 until c.mediaItemCount).mapNotNull { i ->
                            val item = c.getMediaItemAt(i)
                            val uri = item.localConfiguration?.uri ?: return@mapNotNull null
                            AudioTrack(item.mediaId.ifBlank { uri.toString() }, uri, item.mediaMetadata.title?.toString() ?: "Audio", item.mediaMetadata.artist?.toString() ?: "Artis tidak diketahui", item.mediaMetadata.albumTitle?.toString() ?: "", 0L)
                        } }.orEmpty()
                        if (queue.isEmpty()) EmptyState("Antrean kosong", "Pilih lagu atau playlist untuk mulai memutar.")
                        else LazyColumn {
                            items(queue.indices.toList(), key = { queue[it].id + "_$it" }) { index ->
                                val track = queue[index]
                                Row(Modifier.fillMaxWidth().clickable { controller?.seekTo(index, 0L); controller?.play() }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(if (track.uri == currentUri) "▶" else "${index + 1}", modifier = Modifier.width(32.dp))
                                    Column(Modifier.weight(1f)) { Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(track.artist, style = MaterialTheme.typography.bodySmall) }
                                    TextButton(onClick = { controller?.removeMediaItem(index) }) { Text("Hapus") }
                                }
                                HorizontalDivider()
                            }
                        }
                    }
                    LyraTab.LYRICS -> {
                        Spacer(Modifier.height(8.dp))
                        Text("Lirik & editor LRC", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        Text("$currentTitle · $currentArtist", style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { searchTrack = currentTitle; searchArtist = currentArtist }) { Text("Gunakan lagu saat ini") }
                            OutlinedButton(onClick = { lrcPicker.launch(arrayOf("text/*", "application/octet-stream")) }) { Text("Buka LRC") }
                        }
                        OutlinedTextField(searchTrack, { searchTrack = it }, Modifier.fillMaxWidth(), label = { Text("Judul lagu") }, singleLine = true)
                        OutlinedTextField(searchArtist, { searchArtist = it }, Modifier.fillMaxWidth(), label = { Text("Artis (opsional)") }, singleLine = true)
                        Button(onClick = {
                            scope.launch {
                                status = "Mencari lirik di LRCLIB..."
                                try {
                                    results = withContext(Dispatchers.IO) { LrclibClient.search(searchTrack, searchArtist) }
                                    status = if (results.isEmpty()) "Lirik tidak ditemukan." else "Pilih hasil lirik di bawah."
                                } catch (e: Exception) { status = "Pencarian gagal: ${e.message ?: "periksa koneksi"}" }
                            }
                        }, enabled = searchTrack.isNotBlank()) { Text("Cari lirik online") }
                        Text(currentLyric.ifBlank { "Lirik belum tersedia" }, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(vertical = 8.dp))
                        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 150.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            items(results.take(8)) { result ->
                                ElevatedCard(onClick = {
                                    lrcText = result.syncedLyrics ?: result.plainLyrics.orEmpty()
                                    prefs.edit().putString("current_lrc", lrcText).apply()
                                    status = if (result.syncedLyrics != null) "Lirik tersinkron dipilih." else "Lirik teks biasa dipilih."
                                }) {
                                    Column(Modifier.padding(10.dp)) {
                                        Text(result.name, fontWeight = FontWeight.SemiBold)
                                        Text(result.artist, style = MaterialTheme.typography.bodySmall)
                                        Text(if (result.syncedLyrics != null) "Lirik tersinkron" else "Lirik biasa", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                        OutlinedTextField(lrcText, { lrcText = it }, Modifier.weight(1f).fillMaxWidth(), label = { Text("Isi berkas .lrc") })
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(onClick = { saveLrcLauncher.launch(currentTitle.substringBeforeLast('.', currentTitle) + ".lrc") }) { Text("Ekspor") }
                            OutlinedButton(onClick = { lrcText = (lrcText.trimEnd() + "\n" + LrcParser.timestamp(position) + " ").trimStart() }) { Text("Cap waktu") }
                            OutlinedButton(onClick = { lrcText = LrcParser.shift(lrcText, -500) }) { Text("−0,5s") }
                            OutlinedButton(onClick = { lrcText = LrcParser.shift(lrcText, 500) }) { Text("+0,5s") }
                        }
                        Button(onClick = {
                            prefs.edit().putString("current_lrc", lrcText).apply()
                            LyricsWidgetProvider.updateAll(context, currentTitle, LrcParser.displayLine(lrcText, position))
                            status = "Lirik disimpan."
                        }, modifier = Modifier.fillMaxWidth()) { Text("Simpan lirik") }
                    }
                    LyraTab.SETTINGS -> {
                        Spacer(Modifier.height(10.dp))
                        Text("Pengaturan", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        SettingRow("Tema gelap", "Gunakan antarmuka gelap", darkMode, { darkMode = it; prefs.edit().putBoolean("dark_mode", it).apply() })
                        ElevatedCard(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Penyimpanan musik", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text(if (selectedFolder.isBlank()) "Folder khusus belum dipilih." else "Folder khusus tersimpan dan akan dipindai bersama koleksi media.")
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = { folderPicker.launch(null) }) { Text("Pilih folder") }
                                    OutlinedButton(onClick = { selectedFolder = ""; prefs.edit().remove("music_folder_uri").apply(); scanSongs() }) { Text("Hapus folder") }
                                }
                            }
                        }
                        ElevatedCard(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Timer tidur", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text(if (sleepMinutes > 0) "Timer aktif: $sleepMinutes menit" else "Musik dapat dijeda otomatis setelah waktu yang dipilih.")
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = { showSleepDialog = true }) { Text("Atur timer") }
                                    OutlinedButton(onClick = { sleepMinutes = 0 }) { Text("Matikan") }
                                }
                            }
                        }
                        ElevatedCard(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Koleksi & privasi", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text("Riwayat dan favorit disimpan secara lokal. Pencarian lirik online memerlukan koneksi internet.")
                                Text("Lagu ditemukan: ${songs.size} · Favorit: ${favoriteIds.size} · Riwayat: ${lastPlayed.size}")
                                OutlinedButton(onClick = { scanSongs() }) { Text("Pindai ulang koleksi") }
                                OutlinedButton(onClick = {
                                    favoriteIds = emptySet()
                                    lastPlayed = emptyList()
                                    playCounts = emptyMap()
                                    prefs.edit().remove("favorites").remove("recent_ids").remove("play_counts_json").apply()
                                    status = "Riwayat dan favorit lokal dihapus."
                                }) { Text("Hapus favorit & riwayat") }
                            }
                        }
                        ElevatedCard(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Statistik lokal", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text("Total pemutaran tercatat: ${playCounts.values.sum()}")
                                val top = playCounts.entries.sortedByDescending { it.value }.take(5).mapNotNull { entry -> songs.firstOrNull { it.id == entry.key }?.let { "${it.title} (${entry.value}×)" } }
                                if (top.isNotEmpty()) top.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                        Text("Catatan: fitur audio lanjutan seperti equalizer native, crossfade, ReplayGain, rekomendasi cerdas, Android Auto, dan backup cloud memerlukan implementasi serta pengujian tambahan.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 12.dp))
                    }
                }
            }
        }
    }

    if (showCreatePlaylist) {
        AlertDialog(
            onDismissRequest = { showCreatePlaylist = false },
            title = { Text("Buat playlist") },
            text = { OutlinedTextField(newPlaylistName, { newPlaylistName = it }, label = { Text("Nama playlist") }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    val name = newPlaylistName.trim()
                    if (name.isNotBlank() && playlists.none { it.name.equals(name, true) }) {
                        playlists = playlists + LyraPlaylist(name, emptyList())
                        savePlaylists()
                        newPlaylistName = ""
                        showCreatePlaylist = false
                        selectedPlaylist = name
                    }
                }) { Text("Buat") }
            },
            dismissButton = { TextButton(onClick = { showCreatePlaylist = false }) { Text("Batal") } }
        )
    }
    if (trackToAdd != null) {
        AlertDialog(
            onDismissRequest = { trackToAdd = null },
            title = { Text("Tambahkan ke playlist") },
            text = {
                if (playlists.isEmpty()) {
                    Text("Belum ada playlist. Buat playlist terlebih dahulu dari tab Playlist.")
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        playlists.forEach { playlist ->
                            TextButton(onClick = {
                                val track = trackToAdd
                                if (track != null) {
                                    playlists = playlists.map { p ->
                                        if (p.name == playlist.name) p.copy(trackIds = (p.trackIds + track.id).distinct()) else p
                                    }
                                    val arr = JSONArray()
                                    playlists.forEach { p -> arr.put(JSONObject().put("name", p.name).put("ids", JSONArray(p.trackIds))) }
                                    prefs.edit().putString("playlists_json", arr.toString()).apply()
                                }
                                trackToAdd = null
                            }, modifier = Modifier.fillMaxWidth()) {
                                Text("${playlist.name}  ·  ${playlist.trackIds.size} lagu")
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { trackToAdd = null }) { Text("Tutup") } }
        )
    }
    if (showSleepDialog) {
        AlertDialog(
            onDismissRequest = { showSleepDialog = false },
            title = { Text("Timer tidur") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Pilih waktu sampai musik dijeda.")
                    listOf(5, 10, 15, 30, 45, 60).forEach { minutes ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { sleepMinutes = minutes; showSleepDialog = false }) {
                            RadioButton(selected = sleepMinutes == minutes, onClick = { sleepMinutes = minutes; showSleepDialog = false })
                            Text("$minutes menit")
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { sleepMinutes = 0; showSleepDialog = false }) { Text("Batal timer") } },
            dismissButton = { TextButton(onClick = { showSleepDialog = false }) { Text("Tutup") } }
        )
    }
}

@Composable
private fun NowPlayingScreen(
    title: String,
    artist: String,
    album: String,
    albumArt: android.graphics.Bitmap?,
    isPlaying: Boolean,
    position: Long,
    duration: Long,
    shuffle: Boolean,
    repeatMode: Int,
    lrcText: String,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onShuffle: () -> Unit,
    onRepeat: () -> Unit,
    onOpenLyrics: () -> Unit,
    onOpenQueue: () -> Unit
) {
    val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "lyraVisualizer")
    val barValues = (0 until 36).map { index ->
        transition.animateFloat(
            initialValue = 0.18f + ((index * 17) % 50) / 100f,
            targetValue = 0.22f + ((index * 29 + 23) % 70) / 100f,
            animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                animation = androidx.compose.animation.core.tween(
                    durationMillis = 480 + (index % 6) * 120,
                    easing = androidx.compose.animation.core.FastOutSlowInEasing
                ),
                repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
            ),
            label = "bar$index"
        )
    }
    val parsed = remember(lrcText) { LrcParser.parse(lrcText) }
    val activeIndex = parsed.indexOfLast { it.timeMs <= position }.coerceAtLeast(0)
    val lyricsState = rememberLazyListState()
    LaunchedEffect(activeIndex, parsed.size) {
        if (parsed.isNotEmpty()) runCatching { lyricsState.animateScrollToItem(activeIndex.coerceIn(parsed.indices)) }
    }

    Column(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(Color(0xFF28332C), Color(0xFF171A18), Color(0xFF101010)))
        ).padding(horizontal = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("SEDANG DIPUTAR", style = MaterialTheme.typography.labelSmall, color = Color(0xFFB8F36B), letterSpacing = 2.sp)
                Text("Now Playing", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color.White)
            }
            TextButton(onClick = onOpenQueue) { Text("Antrean  ≡", color = Color.White) }
        }
        Spacer(Modifier.height(18.dp))
        Surface(
            modifier = Modifier.fillMaxWidth().aspectRatio(1f).padding(horizontal = 14.dp),
            shape = RoundedCornerShape(22.dp),
            color = Color(0xFF2B302C),
            shadowElevation = 18.dp
        ) {
            if (albumArt != null) {
                androidx.compose.foundation.Image(
                    bitmap = albumArt.asImageBitmap(),
                    contentDescription = "Sampul album $title",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.linearGradient(listOf(Color(0xFF506442), Color(0xFF222923), Color(0xFF111512)))
                    ),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("♫", style = MaterialTheme.typography.displayLarge, color = Color(0xFFB8F36B), fontSize = 100.sp)
                        Text("LYRA PLAYER", color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.labelLarge, letterSpacing = 3.sp)
                    }
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        Column(Modifier.fillMaxWidth()) {
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(artist, style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.72f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (album.isNotBlank()) Text(album, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.55f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(10.dp))
        Slider(
            value = if (duration > 0) position.coerceIn(0L, duration).toFloat() else 0f,
            onValueChange = { if (duration > 0) onSeek(it.toLong()) },
            valueRange = 0f..(duration.coerceAtLeast(1L)).toFloat(),
            enabled = duration > 0
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatTime(position), color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.labelSmall)
            Text(formatTime(duration), color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.labelSmall)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onShuffle) { Text(if (shuffle) "🔀" else "⇄", color = if (shuffle) Color(0xFFB8F36B) else Color.White, fontSize = 20.sp) }
            TextButton(onClick = onPrevious) { Text("⏮", color = Color.White, fontSize = 24.sp) }
            Button(
                onClick = onPlayPause,
                modifier = Modifier.size(68.dp),
                shape = RoundedCornerShape(50),
                contentPadding = PaddingValues(0.dp)
            ) { Text(if (isPlaying) "Ⅱ" else "▶", fontSize = 28.sp) }
            TextButton(onClick = onNext) { Text("⏭", color = Color.White, fontSize = 24.sp) }
            TextButton(onClick = onRepeat) {
                Text(when (repeatMode) {
                    Player.REPEAT_MODE_ALL -> "🔁"
                    Player.REPEAT_MODE_ONE -> "🔂"
                    else -> "↻"
                }, color = if (repeatMode != Player.REPEAT_MODE_OFF) Color(0xFFB8F36B) else Color.White, fontSize = 20.sp)
            }
        }
        Spacer(Modifier.height(4.dp))
        // Decorative animated visualizer; it is an animation, not a microphone/audio-spectrum analyzer.
        Canvas(Modifier.fillMaxWidth().height(42.dp)) {
            val gap = size.width / (barValues.size * 1.8f)
            val barWidth = gap * 0.8f
            barValues.forEachIndexed { index, animated ->
                val h = size.height * (if (isPlaying) animated.value.coerceIn(0.12f, 0.95f) else 0.12f)
                val x = index * gap * 1.8f
                drawRoundRect(
                    color = Color(0xFFB8F36B).copy(alpha = if (isPlaying) 0.9f else 0.28f),
                    topLeft = androidx.compose.ui.geometry.Offset(x, (size.height - h) / 2f),
                    size = androidx.compose.ui.geometry.Size(barWidth, h),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth, barWidth)
                )
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("LIRIK", style = MaterialTheme.typography.labelLarge, color = Color(0xFFB8F36B), letterSpacing = 2.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = onOpenLyrics) { Text("Buka lirik lengkap  ↗", color = Color.White) }
        }
        if (parsed.isNotEmpty()) {
            androidx.compose.foundation.lazy.LazyColumn(
                state = lyricsState,
                modifier = Modifier.fillMaxWidth().weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                contentPadding = PaddingValues(vertical = 4.dp)
            ) {
                items(parsed.size) { index ->
                    Text(
                        parsed[index].text.ifBlank { "♪" },
                        modifier = Modifier.fillMaxWidth().clickable { onSeek(parsed[index].timeMs) }.padding(vertical = 7.dp, horizontal = 10.dp),
                        style = if (index == activeIndex) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
                        fontWeight = if (index == activeIndex) FontWeight.Bold else FontWeight.Normal,
                        color = if (index == activeIndex) Color(0xFFB8F36B) else Color.White.copy(alpha = 0.55f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        } else {
            Text(
                "Lirik tersinkron akan tampil di sini. Buka tab Lirik untuk mencari melalui LRCLIB.",
                color = Color.White.copy(alpha = 0.65f),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 8.dp)
            )
            Spacer(Modifier.weight(1f, fill = true))
        }
    }
}

private fun formatTime(ms: Long): String {
    val seconds = (ms.coerceAtLeast(0L) / 1000L)
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

@Composable
private fun SummaryCard(title: String, value: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    ElevatedCard(modifier.clickable { onClick() }) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(title, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun EmptyState(title: String, message: String) {
    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("♫", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.secondary)
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(message, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun TrackList(
    tracks: List<AudioTrack>,
    currentUri: Uri?,
    playing: Boolean,
    favoriteIds: Set<String>,
    playTracks: (List<AudioTrack>, Int) -> Unit,
    toggleFavorite: (AudioTrack) -> Unit,
    onAddToPlaylist: (AudioTrack) -> Unit,
    showFolder: Boolean
) {
    if (tracks.isEmpty()) {
        EmptyState("Belum ada lagu", "Coba pindai ulang atau pilih folder musik dari pengaturan.")
        return
    }
    LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        items(tracks, key = { it.id }) { song ->
            val isCurrent = song.uri == currentUri
            Row(Modifier.fillMaxWidth().clickable { playTracks(tracks, tracks.indexOfFirst { it.id == song.id }) }.padding(vertical = 10.dp, horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.size(48.dp)) {
                    Box(contentAlignment = Alignment.Center) { Text(if (isCurrent && playing) "♫" else "♪", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.secondary) }
                }
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium, color = if (isCurrent) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface)
                    Text("${song.artist} · ${song.album}", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (showFolder && song.folder.isNotBlank()) Text(song.folder, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                }
                TextButton(onClick = { toggleFavorite(song) }) { Text(if (song.id in favoriteIds) "♥" else "♡") }
                TextButton(onClick = { onAddToPlaylist(song) }) { Text("+") }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
        }
    }
}

@Composable
private fun SettingRow(title: String, description: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.SemiBold); Text(description, style = MaterialTheme.typography.bodySmall) }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private fun loadPlaylists(raw: String): List<LyraPlaylist> = runCatching {
    val arr = JSONArray(raw)
    (0 until arr.length()).map { i ->
        val obj = arr.getJSONObject(i)
        val ids = obj.optJSONArray("ids") ?: JSONArray()
        LyraPlaylist(obj.optString("name"), (0 until ids.length()).map { ids.optString(it) })
    }
}.getOrDefault(emptyList())

private fun loadCounts(raw: String): Map<String, Int> = runCatching {
    val obj = JSONObject(raw)
    obj.keys().asSequence().associateWith { obj.optInt(it, 0) }
}.getOrDefault(emptyMap())
