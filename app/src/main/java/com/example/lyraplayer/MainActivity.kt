package com.example.lyraplayer

import android.Manifest
import android.content.ComponentName
import android.content.ContentUris
import android.media.audiofx.Equalizer
import android.media.audiofx.BassBoost
import android.media.audiofx.Visualizer
import android.os.Handler
import android.os.Looper
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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
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
import androidx.documentfile.provider.DocumentFile
import androidx.media3.common.MediaItem
import coil.compose.AsyncImage
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
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

private fun lyricsCacheKey(title: String, artist: String): String =
    (title.trim().substringBeforeLast('.', title.trim()) + "|" + artist.trim())
        .lowercase(Locale.ROOT).replace(Regex("\\s+"), " ").trim()

private data class AudioTrack(
    val id: String,
    val uri: Uri,
    val title: String,
    val artist: String,
    val album: String,
    val duration: Long,
    val folder: String = "",
    val isSaf: Boolean = false,
    val artworkUri: Uri? = null
)

private data class LyraPlaylist(val name: String, val trackIds: List<String>)

private enum class LyraTab(val label: String, val icon: String) {
    HOME("Home", "⌂"), NOW_PLAYING("Putar", "▶"), LIBRARY("Musik", "♫"), SEARCH("Cari", "⌕"),
    PLAYLISTS("Playlist", "☷"), QUEUE("Antrean", "≡"), LYRICS("Lirik", "♪"), SETTINGS("Setelan", "⚙")
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
    var audioSessionId by remember { mutableIntStateOf(prefs.getInt("audio_session_id", 0)) }
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
    var lyricsStatus by remember { mutableStateOf("Lirik otomatis siap") }
    var autoLyrics by remember { mutableStateOf(prefs.getBoolean("auto_lyrics", true)) }
    var sleepMinutes by remember { mutableIntStateOf(0) }
    var showSleepDialog by remember { mutableStateOf(false) }
    var smartShuffle by remember { mutableStateOf(prefs.getBoolean("smart_shuffle", true)) }
    var lastPlayed by remember { mutableStateOf(prefs.getString("recent_ids", "").orEmpty().split("|").filter { it.isNotBlank() }) }
    var playCounts by remember { mutableStateOf(loadCounts(prefs.getString("play_counts_json", "{}").orEmpty())) }

    fun permissionName() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

    fun scanSongs() {
        try {
            val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            val projection = arrayOf(
                MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.DATA, MediaStore.Audio.Media.ALBUM_ID
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
                val albumIdCol = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM_ID)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol).toString()
                    val title = cursor.getString(titleCol)?.takeIf { it.isNotBlank() } ?: "Judul tidak diketahui"
                    val artist = cursor.getString(artistCol)?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: "Artis tidak diketahui"
                    val album = cursor.getString(albumCol)?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: "Album tidak diketahui"
                    val path = if (dataCol >= 0) cursor.getString(dataCol).orEmpty() else ""
                    val albumId = if (albumIdCol >= 0) cursor.getLong(albumIdCol) else 0L
                    val art = if (albumId > 0L) ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), albumId) else null
                    found["media:$id"] = AudioTrack("media:$id", Uri.withAppendedPath(collection, id), title, artist, album, cursor.getLong(durationCol), path.substringBeforeLast("/", ""), artworkUri = art)
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
            prefs.edit().putString("current_lrc", lrcText).putString("current_lrc_key", lyricsCacheKey(currentTitle, currentArtist)).apply()
            status = "Berkas LRC berhasil dibuka."
        } catch (e: Exception) { status = "Gagal membuka LRC: ${e.message}" }
    }
    val saveLrcLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) try {
            context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(lrcText) }
            status = "Berkas LRC berhasil diekspor."
        } catch (e: Exception) { status = "Gagal mengekspor LRC: ${e.message}" }
    }
    val backupExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) try {
            val backup = JSONObject()
                .put("format", "lyra-backup-v1")
                .put("favorites", JSONArray(favoriteIds.toList()))
                .put("recent_ids", JSONArray(lastPlayed))
                .put("play_counts", JSONObject(playCounts as Map<*, *>))
                .put("playlists", JSONArray().also { arr -> playlists.forEach { arr.put(JSONObject().put("name", it.name).put("ids", JSONArray(it.trackIds))) } })
                .put("dark_mode", darkMode).put("smart_shuffle", smartShuffle)
            context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(backup.toString(2)) }
            status = "Backup Lyra berhasil diekspor. Simpan berkas ini di tempat aman."
        } catch (e: Exception) { status = "Gagal mengekspor backup: ${e.message}" }
    }
    val backupImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) try {
            val root = JSONObject(context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty())
            require(root.optString("format") == "lyra-backup-v1") { "Format backup tidak dikenal" }
            favoriteIds = root.optJSONArray("favorites").toStringList().toSet()
            lastPlayed = root.optJSONArray("recent_ids").toStringList()
            playCounts = loadCounts(root.optJSONObject("play_counts")?.toString() ?: "{}")
            val arr = root.optJSONArray("playlists") ?: JSONArray()
            playlists = (0 until arr.length()).map { i -> arr.getJSONObject(i).let { LyraPlaylist(it.optString("name", "Playlist"), it.optJSONArray("ids").toStringList()) } }
            darkMode = root.optBoolean("dark_mode", true)
            smartShuffle = root.optBoolean("smart_shuffle", true)
            prefs.edit().putStringSet("favorites", favoriteIds).putString("recent_ids", lastPlayed.joinToString("|"))
                .putString("play_counts_json", JSONObject(playCounts as Map<*, *>).toString())
                .putString("playlists_json", JSONArray().also { a -> playlists.forEach { a.put(JSONObject().put("name", it.name).put("ids", JSONArray(it.trackIds))) } }.toString())
                .putBoolean("dark_mode", darkMode).putBoolean("smart_shuffle", smartShuffle).apply()
            status = "Backup berhasil dipulihkan. Pindai ulang koleksi jika lagu belum terlihat."
        } catch (e: Exception) { status = "Backup gagal dibuka: ${e.message ?: "berkas tidak valid"}" }
    }
    val m3uExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/x-mpegurl")) { uri ->
        if (uri != null) try {
            val playlistTracksNow = when (selectedPlaylist) {
                null -> songs
                "Favorit" -> favoriteIds.mapNotNull { id -> songs.firstOrNull { it.id == id } }
                else -> selectedPlaylist?.let { name -> playlists.firstOrNull { it.name == name }?.trackIds?.mapNotNull { id -> songs.firstOrNull { it.id == id } }.orEmpty() } ?: songs
            }
            val content = buildString { appendLine("#EXTM3U"); playlistTracksNow.forEach { appendLine("#EXTINF:${(it.duration / 1000).toInt()},${it.artist} - ${it.title}"); appendLine(it.uri.toString()) } }
            context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(content) }
            status = "M3U diekspor. URI content Android mungkin hanya bisa dipakai di perangkat ini."
        } catch (e: Exception) { status = "Gagal mengekspor M3U: ${e.message}" }
    }
    val m3uImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) try {
            val lines = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readLines() }.orEmpty()
            val resolved = lines.filter { it.isNotBlank() && !it.startsWith("#") }.mapNotNull { entry ->
                songs.firstOrNull { it.uri.toString() == entry.trim() }
                    ?: songs.firstOrNull { it.title.equals(entry.substringAfterLast('/').substringBeforeLast('.'), true) }
            }.distinctBy { it.id }
            if (resolved.isEmpty()) status = "Tidak ada lagu yang bisa dicocokkan. Impor M3U paling cocok untuk URI atau judul dari koleksi yang sama."
            else {
                val name = "Impor M3U ${playlists.size + 1}"
                playlists = playlists + LyraPlaylist(name, resolved.map { it.id })
                prefs.edit().putString("playlists_json", JSONArray().also { arr -> playlists.forEach { arr.put(JSONObject().put("name", it.name).put("ids", JSONArray(it.trackIds))) } }.toString()).apply()
                status = "Playlist $name dibuat (${resolved.size} lagu)."
            }
        } catch (e: Exception) { status = "Gagal mengimpor M3U: ${e.message}" }
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
                .setMediaMetadata(MediaMetadata.Builder().setTitle(song.title).setArtist(song.artist).setAlbumTitle(song.album).setArtworkUri(song.artworkUri).build())
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

    LaunchedEffect(controller) {
        val c = controller ?: return@LaunchedEffect
        while (true) {
            playing = c.isPlaying
            audioSessionId = prefs.getInt("audio_session_id", 0)
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

    LaunchedEffect(currentTitle, currentArtist, autoLyrics) {
        val title = currentTitle.trim().substringBeforeLast('.', currentTitle.trim())
        val artist = currentArtist.trim()
        if (!autoLyrics || title.isBlank() || title == "Belum ada lagu" || title == "Judul tidak diketahui") {
            if (!autoLyrics) lyricsStatus = "Pencarian lirik otomatis dimatikan"
            return@LaunchedEffect
        }
        val key = lyricsCacheKey(title, artist)
        // Never leave lyrics from the previous song visible while the new track is being resolved.
        if (prefs.getString("current_lrc_key", "") != key) {
            lrcText = ""
            currentLyric = ""
            prefs.edit().putString("current_lrc", "").apply()
        }
        lyricsStatus = "Memeriksa cache lirik lokal…"
        val cache = runCatching { JSONObject(prefs.getString("lyrics_cache_json", "{}").orEmpty()) }.getOrElse { JSONObject() }
        val cached = cache.optString(key, "").takeIf { it.isNotBlank() && it != "null" }
        if (cached != null) {
            lrcText = cached
            prefs.edit().putString("current_lrc", cached).putString("current_lrc_key", key).apply()
            lyricsStatus = "Lirik ditemukan di cache lokal"
            return@LaunchedEffect
        }
        // Imported or edited lyrics for this exact track also count as local-first results.
        val savedForCurrentTrack = prefs.getString("current_lrc", "").orEmpty()
        if (prefs.getString("current_lrc_key", "") == key && savedForCurrentTrack.isNotBlank()) {
            lrcText = savedForCurrentTrack
            cache.put(key, savedForCurrentTrack)
            prefs.edit().putString("lyrics_cache_json", cache.toString()).apply()
            lyricsStatus = "Lirik lokal ditemukan"
            return@LaunchedEffect
        }
        lyricsStatus = "Mencari lirik otomatis: $title${if (artist.isNotBlank()) " · $artist" else ""}"
        try {
            val found = withContext(Dispatchers.IO) {
                val cleanTitle = title.replace(Regex("(?i)\\s*[-|]\\s*(official\\s*)?(audio|video|lyrics?|visualizer).*?$"), "").trim().ifBlank { title }
                val cleanArtist = artist.takeUnless { it.isBlank() || it.equals("Artis tidak diketahui", true) || it.equals("unknown", true) || it.equals("<unknown>", true) }.orEmpty()
                val primary = runCatching { LrclibClient.search(cleanTitle, cleanArtist) }.getOrDefault(emptyList())
                val candidates = if (primary.isNotEmpty() || cleanArtist.isBlank()) primary else
                    runCatching { LrclibClient.search(cleanTitle) }.getOrDefault(emptyList())
                candidates.sortedWith(compareByDescending<LrcResult> { !it.syncedLyrics.isNullOrBlank() }
                    .thenByDescending { it.name.equals(cleanTitle, true) }
                    .thenByDescending { cleanArtist.isNotBlank() && it.artist.equals(cleanArtist, true) })
                    .firstOrNull { !it.syncedLyrics.isNullOrBlank() || !it.plainLyrics.isNullOrBlank() }
            }
            if (found != null) {
                val lyrics = found.syncedLyrics ?: found.plainLyrics.orEmpty()
                // Ignore a slow result if the user has already moved to another track.
                if (lyricsCacheKey(currentTitle, currentArtist) == key) {
                    lrcText = lyrics
                    val latest = runCatching { JSONObject(prefs.getString("lyrics_cache_json", "{}").orEmpty()) }.getOrElse { JSONObject() }
                    latest.put(key, lyrics)
                    while (latest.length() > 150) latest.remove(latest.keys().asSequence().firstOrNull() ?: break)
                    prefs.edit().putString("lyrics_cache_json", latest.toString()).putString("current_lrc", lyrics).putString("current_lrc_key", key).apply()
                    results = listOf(found)
                    lyricsStatus = if (!found.syncedLyrics.isNullOrBlank()) "Lirik tersinkron ditemukan dan disimpan lokal" else "Lirik teks ditemukan dan disimpan lokal"
                }
            } else {
                if (lyricsCacheKey(currentTitle, currentArtist) == key) lyricsStatus = "Lirik tidak ditemukan. Kamu bisa mencari manual atau mengimpor LRC."
            }
        } catch (e: Exception) {
            if (lyricsCacheKey(currentTitle, currentArtist) == key) lyricsStatus = "Pencarian gagal: ${e.message ?: "periksa koneksi internet"}"
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
                        ElevatedCard(
                            onClick = { tab = LyraTab.NOW_PLAYING },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(24.dp),
                            colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(76.dp).background(Brush.linearGradient(listOf(Color(0xFFB8F36B), Color(0xFF34451E))), RoundedCornerShape(18.dp)), contentAlignment = Alignment.Center) {
                                    Text("♫", style = MaterialTheme.typography.displayMedium, color = Color(0xFF101010), fontWeight = FontWeight.Black)
                                }
                                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                                    Text("PLAY NOW", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Black)
                                    Text(currentTitle, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(currentArtist.ifBlank { "Pilih lagu untuk mulai" }, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("Buka pemutar · lirik · visualizer  →", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 5.dp))
                                }
                                FilledTonalButton(onClick = { if (playing) controller?.pause() else if (currentUri != null) controller?.play() else if (songs.isNotEmpty()) playTracks(songs, 0) }) { Text(if (playing) "Ⅱ" else "▶") }
                            }
                        }
                        Spacer(Modifier.height(16.dp))
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
                    LyraTab.NOW_PLAYING -> {
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("PLAY NOW", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
                            TextButton(onClick = { tab = LyraTab.QUEUE }) { Text("Antrean ☷") }
                        }
                        Box(Modifier.fillMaxWidth().weight(0.46f).padding(horizontal = 22.dp, vertical = 8.dp).background(
                            Brush.linearGradient(listOf(Color(0xFFB8F36B), Color(0xFF526D36), Color(0xFF171D13))), RoundedCornerShape(28.dp)
                        ), contentAlignment = Alignment.Center) {
                            if (currentTrack?.artworkUri != null) {
                                AsyncImage(model = currentTrack.artworkUri, contentDescription = "Sampul ${currentTrack.album}", modifier = Modifier.fillMaxSize())
                            } else {
                                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                    Text("♫", style = MaterialTheme.typography.displayLarge, color = Color(0xFF101010), fontWeight = FontWeight.Black)
                                    Text(currentAlbum.ifBlank { "LYRA SESSION" }.uppercase(), color = Color(0xFF101010), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(currentTitle, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(currentArtist.ifBlank { "Artis tidak diketahui" }, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(currentAlbum.ifBlank { "Album tidak diketahui" }, style = MaterialTheme.typography.bodySmall)
                            }
                            IconButton(onClick = { currentTrack?.let { toggleFavorite(it) } }) { Text(if (currentTrack?.id?.let { it in favoriteIds } == true) "♥" else "♡", style = MaterialTheme.typography.headlineSmall) }
                        }
                        if (duration > 0) {
                            Slider(value = position.coerceIn(0L, duration).toFloat(), onValueChange = { position = it.toLong() }, onValueChangeFinished = { controller?.seekTo(position) }, valueRange = 0f..duration.toFloat())
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(formatTime(position), style = MaterialTheme.typography.labelSmall)
                                Text("−${formatTime((duration - position).coerceAtLeast(0L))}", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        AudioVisualizer(audioSessionId = audioSessionId, isPlaying = playing, modifier = Modifier.fillMaxWidth().height(92.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { controller?.shuffleModeEnabled = !(controller?.shuffleModeEnabled ?: false) }) { Text(if (shuffle) "🔀✓" else "🔀") }
                            FilledTonalButton(onClick = { controller?.seekToPreviousMediaItem() }, modifier = Modifier.size(54.dp), shape = RoundedCornerShape(18.dp)) { Text("|◀") }
                            Button(onClick = { if (playing) controller?.pause() else if (currentUri != null) controller?.play() else if (songs.isNotEmpty()) playTracks(songs, 0) }, modifier = Modifier.size(70.dp), shape = RoundedCornerShape(24.dp)) { Text(if (playing) "Ⅱ" else "▶", style = MaterialTheme.typography.headlineMedium) }
                            FilledTonalButton(onClick = { controller?.seekToNextMediaItem() }, modifier = Modifier.size(54.dp), shape = RoundedCornerShape(18.dp)) { Text("▶|") }
                            IconButton(onClick = { val next = when (repeatMode) { Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL; Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE; else -> Player.REPEAT_MODE_OFF }; controller?.repeatMode = next; repeatMode = next }) { Text(when (repeatMode) { Player.REPEAT_MODE_ALL -> "🔁"; Player.REPEAT_MODE_ONE -> "🔂"; else -> "↻" }) }
                        }
                        ElevatedCard(Modifier.fillMaxWidth().padding(top = 8.dp), shape = RoundedCornerShape(20.dp)) {
                            Column(Modifier.fillMaxWidth().clickable { tab = LyraTab.LYRICS }.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("LYRICS · LIRIK SAAT INI", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Black)
                                Text(currentLyric.ifBlank { "Lirik belum tersedia — ketuk untuk mencari atau mengimpor LRC" }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                                Text("Buka editor lirik →", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 6.dp))
                            }
                        }
                    }
                    LyraTab.LIBRARY -> {
                        Spacer(Modifier.height(8.dp))
                        Text("Perpustakaan", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        Text("${songs.size} lagu · ${songs.map { it.artist }.distinct().size} artis · ${songs.map { it.album }.distinct().size} album", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                        OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth().padding(vertical = 8.dp), label = { Text("Cari judul, artis, album, folder") }, singleLine = true, shape = RoundedCornerShape(24.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Text(if (favoriteTracks.isNotEmpty() && search.equals("favorit", true)) "Favorit" else "${filtered.size} hasil", Modifier.weight(1f))
                            TextButton(onClick = {
                                smartShuffle = !smartShuffle; prefs.edit().putBoolean("smart_shuffle", smartShuffle).apply()
                                controller?.shuffleModeEnabled = smartShuffle
                                if (smartShuffle && songs.isNotEmpty()) { val ranked = songs.sortedBy { (playCounts[it.id] ?: 0) * 10 + if (it.id in lastPlayed.take(12)) 100 else 0 }.shuffled(); playTracks(ranked, 0) }
                            }) { Text(if (smartShuffle) "Acak pintar ✓" else "Acak") }
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
                        Text(lyricsStatus, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
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
                                    val key = lyricsCacheKey(currentTitle, currentArtist)
                                    val cache = runCatching { JSONObject(prefs.getString("lyrics_cache_json", "{}").orEmpty()) }.getOrElse { JSONObject() }
                                    cache.put(key, lrcText)
                                    prefs.edit().putString("current_lrc", lrcText).putString("current_lrc_key", key).putString("lyrics_cache_json", cache.toString()).apply()
                                    lyricsStatus = if (result.syncedLyrics != null) "Lirik tersinkron dipilih dan disimpan lokal" else "Lirik teks dipilih dan disimpan lokal"
                                    status = lyricsStatus
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
                            val key = lyricsCacheKey(currentTitle, currentArtist)
                            val cache = runCatching { JSONObject(prefs.getString("lyrics_cache_json", "{}").orEmpty()) }.getOrElse { JSONObject() }
                            cache.put(key, lrcText)
                            prefs.edit().putString("current_lrc", lrcText).putString("current_lrc_key", key).putString("lyrics_cache_json", cache.toString()).apply()
                            LyricsWidgetProvider.updateAll(context, currentTitle, LrcParser.displayLine(lrcText, position))
                            lyricsStatus = "Lirik disimpan ke cache lokal"
                            status = "Lirik disimpan."
                        }, modifier = Modifier.fillMaxWidth()) { Text("Simpan lirik") }
                    }
                    LyraTab.SETTINGS -> {
                        Spacer(Modifier.height(10.dp))
                        Text("Pengaturan", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        SettingRow("Tema gelap", "Gunakan antarmuka gelap", darkMode, { darkMode = it; prefs.edit().putBoolean("dark_mode", it).apply() })
                        SettingRow("Pencarian lirik otomatis", "Cari lirik saat lagu berubah; gunakan cache lokal sebelum internet", autoLyrics, { autoLyrics = it; prefs.edit().putBoolean("auto_lyrics", it).apply() })
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
                                Text("Backup & playlist portabel", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text("Backup mencakup favorit, riwayat, statistik, playlist, dan tema. File audio tidak disalin.", style = MaterialTheme.typography.bodySmall)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = { backupExportLauncher.launch("lyra-backup.json") }) { Text("Ekspor backup") }
                                    OutlinedButton(onClick = { backupImportLauncher.launch(arrayOf("application/json", "text/*")) }) { Text("Pulihkan") }
                                }
                                OutlinedButton(onClick = { m3uExportLauncher.launch("lyra-playlist.m3u") }, modifier = Modifier.fillMaxWidth()) { Text(if (selectedPlaylist == null) "Ekspor semua lagu sebagai M3U" else "Ekspor playlist terpilih sebagai M3U") }
                                OutlinedButton(onClick = { m3uImportLauncher.launch(arrayOf("audio/x-mpegurl", "audio/mpegurl", "application/vnd.apple.mpegurl", "text/plain", "*/*")) }, modifier = Modifier.fillMaxWidth()) { Text("Impor M3U sebagai playlist") }
                            }
                        }
                        ElevatedCard(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Audio & equalizer", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text("Equalizer native memakai sesi audio lagu yang sedang berjalan. Efek yang tersedia bergantung perangkat.", style = MaterialTheme.typography.bodySmall)
                                EqualizerPanel(audioSessionId = audioSessionId)
                            }
                        }
                        ElevatedCard(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Acak pintar", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text("Memprioritaskan lagu yang jarang diputar dan mengurangi lagu yang baru saja didengarkan.", style = MaterialTheme.typography.bodySmall)
                                SettingRow("Aktifkan acak pintar", "Gunakan histori lokal sebagai bobot pemilihan", smartShuffle, { smartShuffle = it; prefs.edit().putBoolean("smart_shuffle", it).apply(); controller?.shuffleModeEnabled = it })
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
                        Text("Catatan: dukungan equalizer dan bass boost bergantung perangkat. Visualizer visual saat ini adalah animasi playback; analisis spektrum real-time memerlukan izin dan pengujian tambahan pada perangkat.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 12.dp))
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
private fun EqualizerPanel(audioSessionId: Int) {
    var enabled by remember(audioSessionId) { mutableStateOf(false) }
    var bandValues by remember(audioSessionId) { mutableStateOf(listOf(0f, 0f, 0f, 0f, 0f)) }
    var range by remember(audioSessionId) { mutableStateOf(1000f) }
    var labels by remember(audioSessionId) { mutableStateOf(listOf("60 Hz", "230 Hz", "910 Hz", "3.6 kHz", "14 kHz")) }
    var bass by remember(audioSessionId) { mutableFloatStateOf(0f) }
    val eq = remember(audioSessionId) { if (audioSessionId > 0) runCatching { Equalizer(0, audioSessionId) }.getOrNull() else null }
    val bassBoost = remember(audioSessionId) { if (audioSessionId > 0) runCatching { BassBoost(0, audioSessionId) }.getOrNull() else null }
    DisposableEffect(eq, bassBoost) {
        onDispose { runCatching { eq?.release() }; runCatching { bassBoost?.release() } }
    }
    LaunchedEffect(eq) {
        if (eq != null) runCatching {
            val r = eq.bandLevelRange
            range = maxOf(kotlin.math.abs(r[0].toFloat()), kotlin.math.abs(r[1].toFloat()))
            val count = eq.numberOfBands.toInt().coerceAtMost(10)
            bandValues = (0 until count).map { eq.getBandLevel(it.toShort()).toFloat() }
            labels = (0 until count).map { band ->
                val hz = eq.getCenterFreq(band.toShort()) / 1000
                if (hz >= 1000) "${hz / 1000f} kHz" else "$hz Hz"
            }
            enabled = eq.enabled
        }
    }
    if (audioSessionId <= 0 || eq == null) {
        Text("Mulai putar lagu untuk mengaktifkan equalizer.", style = MaterialTheme.typography.bodySmall)
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Equalizer", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
        Switch(checked = enabled, onCheckedChange = { value -> enabled = value; runCatching { eq.enabled = value } })
    }
    bandValues.forEachIndexed { index, value ->
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(labels.getOrElse(index) { "Band ${index + 1}" }, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                Text("${value.toInt()} mB", style = MaterialTheme.typography.labelSmall)
            }
            Slider(value = value, onValueChange = { next ->
                bandValues = bandValues.toMutableList().also { it[index] = next }
                runCatching { eq.setBandLevel(index.toShort(), next.toInt().toShort()) }
            }, valueRange = -range..range, enabled = enabled)
        }
    }
    if (bassBoost != null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Bass boost", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text("${bass.toInt()}%", style = MaterialTheme.typography.labelSmall)
        }
        Slider(value = bass, onValueChange = { next -> bass = next; runCatching { bassBoost.setStrength((next * 10).toInt().toShort()); bassBoost.enabled = next > 0f } }, valueRange = 0f..100f)
    }
    TextButton(onClick = { bandValues = List(bandValues.size) { 0f }; bandValues.forEachIndexed { index, _ -> runCatching { eq.setBandLevel(index.toShort(), 0.toShort()) } }; bass = 0f; runCatching { bassBoost?.setStrength(0.toShort()); bassBoost?.let { it.enabled = false } } }) { Text("Reset audio") }
}

private fun formatTime(ms: Long): String {
    val total = (ms.coerceAtLeast(0L) / 1000L).toInt()
    return String.format(Locale.ROOT, "%d:%02d", total / 60, total % 60)
}

@Composable
private fun AudioVisualizer(audioSessionId: Int, isPlaying: Boolean, modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var hasPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasPermission = granted
    }
    var spectrum by remember(audioSessionId) { mutableStateOf(List(48) { 0.06f }) }
    var captureError by remember(audioSessionId) { mutableStateOf(false) }

    DisposableEffect(audioSessionId, isPlaying, hasPermission) {
        var visualizer: Visualizer? = null
        val mainHandler = Handler(Looper.getMainLooper())
        if (audioSessionId > 0 && isPlaying && hasPermission) {
            try {
                val capture = Visualizer(audioSessionId)
                val range = Visualizer.getCaptureSizeRange()
                capture.captureSize = range[1].coerceAtMost(2048)
                capture.setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(v: Visualizer?, waveform: ByteArray?, samplingRate: Int) = Unit
                    override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, samplingRate: Int) {
                        if (fft == null || fft.size < 8) return
                        val bins = 48
                        val values = MutableList(bins) { 0.035f }
                        val maxBin = fft.size / 2
                        for (i in 0 until bins) {
                            val low = (1.0 * Math.pow(maxBin.toDouble(), i.toDouble() / bins)).toInt().coerceIn(1, maxBin - 1)
                            val high = (1.0 * Math.pow(maxBin.toDouble(), (i + 1).toDouble() / bins)).toInt().coerceIn(low + 1, maxBin)
                            var peak = 0.0
                            for (bin in low until high) {
                                val real = fft[2 * bin].toInt()
                                val imaginary = fft[2 * bin + 1].toInt()
                                val magnitude = kotlin.math.sqrt((real * real + imaginary * imaginary).toDouble())
                                if (magnitude > peak) peak = magnitude
                            }
                            values[i] = (kotlin.math.ln(1.0 + peak) / kotlin.math.ln(1.0 + 180.0)).toFloat().coerceIn(0.04f, 1f)
                        }
                        mainHandler.post { spectrum = values }
                    }
                }, Visualizer.getMaxCaptureRate() / 2, false, true)
                capture.enabled = true
                visualizer = capture
                captureError = false
            } catch (_: Exception) {
                captureError = true
                visualizer?.release()
                visualizer = null
            }
        } else {
            spectrum = List(48) { 0.035f }
            captureError = false
        }
        onDispose {
            try { visualizer?.enabled = false } catch (_: Exception) { }
            try { visualizer?.release() } catch (_: Exception) { }
            mainHandler.removeCallbacksAndMessages(null)
        }
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("LIVE AUDIO SPECTRUM", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            Text(when {
                !hasPermission -> "Perlu izin analisis audio"
                captureError -> "Spektrum tidak didukung sesi ini"
                isPlaying -> "REAL-TIME FFT"
                else -> "MENUNGGU AUDIO"
            }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!hasPermission) {
            OutlinedButton(onClick = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) }, modifier = Modifier.fillMaxWidth()) {
                Text("Aktifkan analisis spektrum audio")
            }
        }
        Canvas(Modifier.fillMaxWidth().height(66.dp)) {
            val count = spectrum.size
            val gap = 2.2f
            val barWidth = ((size.width - gap * (count - 1)) / count).coerceAtLeast(1f)
            for (i in 0 until count) {
                val h = size.height * spectrum[i].coerceIn(0.035f, 1f)
                val x = i * (barWidth + gap)
                drawRoundRect(
                    brush = Brush.verticalGradient(listOf(Color(0xFFD9FF9A), Color(0xFF8FE03C), Color(0xFF315B18))),
                    topLeft = Offset(x, size.height - h),
                    size = androidx.compose.ui.geometry.Size(barWidth, h),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth / 2f, barWidth / 2f)
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("BASS", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("MID", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("TREBLE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
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

private fun JSONArray?.toStringList(): List<String> {
    val array = this ?: return emptyList()
    return (0 until array.length()).mapNotNull { i -> array.optString(i).takeIf { it.isNotBlank() && it != "null" } }
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
