package com.example.lyraplayer

import android.Manifest
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.database.Cursor
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.Visualizer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import coil.compose.AsyncImage
import com.example.lyraplayer.lyrics.AudioTagEditor
import com.example.lyraplayer.lyrics.LrcParser
import com.example.lyraplayer.lyrics.LrcResult
import com.example.lyraplayer.lyrics.LrclibClient
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
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

// ============================================================================
// DATA CLASSES & HELPER FUNCTIONS (DIDEKLARASIKAN DI ATAS AGAR BEBAS ERROR TIPE)
// ============================================================================

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

private data class MetadataOverride(
    val title: String,
    val artist: String,
    val album: String,
    val artworkUri: String = ""
)

private data class LyraPlaylist(
    val name: String,
    val trackIds: List<String>
)

private data class KaraokeWord(
    val text: String,
    val startMs: Long,
    val endMs: Long
)

private data class KaraokeLine(
    val startMs: Long,
    val endMs: Long,
    val plainText: String,
    val words: List<KaraokeWord>,
    val isEnhanced: Boolean
)

private enum class LyraTab(val label: String, val icon: String) {
    HOME("Home", "⌂"),
    NOW_PLAYING("Putar", "▶"),
    LIBRARY("Musik", "♫"),
    SEARCH("Cari", "⌕"),
    PLAYLISTS("Playlist", "☷"),
    QUEUE("Antrean", "≡"),
    LYRICS("Lirik", "♪"),
    SETTINGS("Setelan", "⚙")
}

private fun lyricsCacheKey(title: String, artist: String): String =
    (title.trim().substringBeforeLast('.', title.trim()) + "|" + artist.trim())
        .lowercase(Locale.ROOT).replace(Regex("\\s+"), " ").trim()

private fun formatTime(ms: Long): String {
    val total = (ms.coerceAtLeast(0L) / 1000L).toInt()
    return String.format(Locale.ROOT, "%d:%02d", total / 60, total % 60)
}

private fun JSONArray?.toStringList(): List<String> {
    val array = this ?: return emptyList()
    val result = mutableListOf<String>()
    for (i in 0 until array.length()) {
        val item = array.optString(i)
        if (!item.isNullOrBlank() && item != "null") {
            result.add(item)
        }
    }
    return result
}

private fun loadPlaylists(raw: String): List<LyraPlaylist> = runCatching {
    val arr = JSONArray(raw)
    val list = mutableListOf<LyraPlaylist>()
    for (i in 0 until arr.length()) {
        val obj = arr.getJSONObject(i)
        val idsArr = obj.optJSONArray("ids") ?: JSONArray()
        val ids = mutableListOf<String>()
        for (j in 0 until idsArr.length()) {
            val idStr = idsArr.optString(j)
            if (idStr.isNotBlank()) ids.add(idStr)
        }
        list.add(LyraPlaylist(obj.optString("name", "Playlist"), ids))
    }
    list
}.getOrDefault(emptyList())

private fun serializePlaylists(list: List<LyraPlaylist>): JSONArray {
    val arr = JSONArray()
    for (p in list) {
        val obj = JSONObject()
        obj.put("name", p.name)
        obj.put("ids", JSONArray(p.trackIds))
        arr.put(obj)
    }
    return arr
}

private fun loadCounts(raw: String): Map<String, Int> = runCatching {
    val obj = JSONObject(raw)
    val map = mutableMapOf<String, Int>()
    val keys = obj.keys()
    while (keys.hasNext()) {
        val k = keys.next()
        map[k] = obj.optInt(k, 0)
    }
    map
}.getOrDefault(emptyMap())

private fun serializeCounts(map: Map<String, Int>): JSONObject {
    val obj = JSONObject()
    for ((k, v) in map.entries) {
        obj.put(k, v)
    }
    return obj
}

private fun loadMetadataOverrides(raw: String): Map<String, MetadataOverride> = runCatching {
    val obj = JSONObject(raw)
    val map = mutableMapOf<String, MetadataOverride>()
    val keys = obj.keys()
    while (keys.hasNext()) {
        val k = keys.next()
        val item = obj.getJSONObject(k)
        map[k] = MetadataOverride(
            title = item.optString("title", ""),
            artist = item.optString("artist", ""),
            album = item.optString("album", ""),
            artworkUri = item.optString("artworkUri", "")
        )
    }
    map
}.getOrDefault(emptyMap())

private fun saveMetadataOverrides(prefs: SharedPreferences, map: Map<String, MetadataOverride>) {
    val root = JSONObject()
    for ((key, value) in map.entries) {
        val item = JSONObject()
        item.put("title", value.title)
        item.put("artist", value.artist)
        item.put("album", value.album)
        item.put("artworkUri", value.artworkUri)
        root.put(key, item)
    }
    prefs.edit().putString("metadata_overrides_json", root.toString()).apply()
}

// ============================================================================
// KOMPONEN LOGO VEKTOR CUSTOM LYRA (DAPAT DIPAKAI DI SEMUA LAYAR)
// ============================================================================

@Composable
private fun LyraBrandLogo(
    size: Dp = 42.dp,
    cornerRadius: Dp = 12.dp,
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(cornerRadius))
    ) {
        val w = this.size.width
        val h = this.size.height

        // Background Gradient Gelap
        drawRect(
            brush = Brush.linearGradient(
                colors = listOf(Color(0xFF1E2B14), Color(0xFF0C1009)),
                start = Offset(0f, 0f),
                end = Offset(w, h)
            )
        )

        // Lingkaran Glow Dalam
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color(0xFF8FE03C).copy(alpha = 0.25f), Color.Transparent),
                center = Offset(w * 0.5f, h * 0.5f),
                radius = w * 0.45f
            ),
            radius = w * 0.45f,
            center = Offset(w * 0.5f, h * 0.5f)
        )

        // Lengkungan Harpa (Lyra)
        val lyraPath = Path().apply {
            moveTo(w * 0.28f, h * 0.26f)
            cubicTo(w * 0.18f, h * 0.45f, w * 0.24f, h * 0.70f, w * 0.40f, h * 0.78f)
            lineTo(w * 0.60f, h * 0.78f)
            cubicTo(w * 0.76f, h * 0.70f, w * 0.82f, h * 0.45f, w * 0.72f, h * 0.26f)
        }
        drawPath(
            path = lyraPath,
            brush = Brush.verticalGradient(listOf(Color(0xFFD9FF9A), Color(0xFF76D229))),
            style = Stroke(width = w * 0.065f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )

        // Garis Pijakan Bawah Lyra
        drawLine(
            color = Color(0xFFB8F36B),
            start = Offset(w * 0.34f, h * 0.86f),
            end = Offset(w * 0.66f, h * 0.86f),
            strokeWidth = w * 0.055f,
            cap = StrokeCap.Round
        )

        // 3 Bar Spektrum Senar Tengah
        drawLine(
            color = Color(0xFF8FE03C),
            start = Offset(w * 0.39f, h * 0.40f),
            end = Offset(w * 0.39f, h * 0.66f),
            strokeWidth = w * 0.05f,
            cap = StrokeCap.Round
        )
        drawLine(
            color = Color(0xFFD9FF9A),
            start = Offset(w * 0.50f, h * 0.22f),
            end = Offset(w * 0.50f, h * 0.68f),
            strokeWidth = w * 0.055f,
            cap = StrokeCap.Round
        )
        drawLine(
            color = Color(0xFF8FE03C),
            start = Offset(w * 0.61f, h * 0.34f),
            end = Offset(w * 0.61f, h * 0.66f),
            strokeWidth = w * 0.05f,
            cap = StrokeCap.Round
        )
    }
}

// ============================================================================
// ENHANCED LRC KARAOKE PARSER ENGINE
// ============================================================================

private object EnhancedLrcEngine {
    private val lineTimestampRegex = Regex("\\[(\\d{1,2}):(\\d{2})(?:[.:](\\d{1,3}))?\\]")
    private val wordTimestampRegex = Regex("<(\\d{1,2}):(\\d{2})(?:[.:](\\d{1,3}))?>([^<]*)")
    private val inlineBracketRegex = Regex("\\[(\\d{1,2}):(\\d{2})(?:[.:](\\d{1,3}))?\\]([^\\[]*)")

    private fun toMs(minStr: String, secStr: String, fracStr: String?): Long {
        val min = minStr.toLongOrNull() ?: 0L
        val sec = secStr.toLongOrNull() ?: 0L
        val frac = when (fracStr?.length ?: 0) {
            1 -> (fracStr?.toLongOrNull() ?: 0L) * 100L
            2 -> (fracStr?.toLongOrNull() ?: 0L) * 10L
            3 -> fracStr?.toLongOrNull() ?: 0L
            else -> 0L
        }
        return min * 60_000L + sec * 1_000L + frac
    }

    fun stripEnhancedTags(raw: String): String =
        raw.replace(Regex("<\\d{1,2}:\\d{2}(?:[.:]\\d{1,3})?>"), "").trim()

    fun formatWordTag(ms: Long): String {
        val safe = ms.coerceAtLeast(0L)
        val m = safe / 60_000L
        val s = (safe % 60_000L) / 1_000L
        val cs = (safe % 1_000L) / 10L
        return String.format(Locale.ROOT, "<%02d:%02d.%02d>", m, s, cs)
    }

    fun parse(lrcText: String): List<KaraokeLine> {
        if (lrcText.isBlank()) return emptyList()
        data class RawEntry(val startMs: Long, val body: String)
        val rawEntries = mutableListOf<RawEntry>()

        for (rawLine in lrcText.lines()) {
            val trimmed = rawLine.trim()
            if (trimmed.isBlank()) continue
            val prefixRegex = Regex("^(?:\\[\\d{1,2}:\\d{2}(?:[.:]\\d{1,3})?\\])+")
            val prefixMatch = prefixRegex.find(trimmed)
            if (prefixMatch != null) {
                val prefix = prefixMatch.value
                val body = trimmed.substring(prefix.length).trim()
                for (m in lineTimestampRegex.findAll(prefix)) {
                    val ms = toMs(m.groupValues[1], m.groupValues[2], m.groupValues.getOrNull(3))
                    rawEntries.add(RawEntry(ms, body))
                }
            }
        }

        if (rawEntries.isEmpty()) {
            val plainLines = lrcText.lines().map { it.trim() }.filter { it.isNotBlank() }
            return plainLines.mapIndexed { idx, text ->
                val s = idx * 4000L
                val e = s + 3800L
                KaraokeLine(s, e, text, buildInterpolatedWords(text, s, e), false)
            }
        }

        val sorted = rawEntries.sortedBy { it.startMs }
        return sorted.mapIndexed { idx, entry ->
            val nextStart = sorted.getOrNull(idx + 1)?.startMs ?: (entry.startMs + 5000L)
            val lineEnd = maxOf(entry.startMs + 600L, nextStart)
            val body = entry.body

            val angleMatches = wordTimestampRegex.findAll(body).toList()
            if (angleMatches.isNotEmpty()) {
                val parsedWords = mutableListOf<KaraokeWord>()
                val leadingText = body.substringBefore("<").trim()
                val firstTagMs = toMs(
                    angleMatches.first().groupValues[1],
                    angleMatches.first().groupValues[2],
                    angleMatches.first().groupValues.getOrNull(3)
                )
                if (leadingText.isNotEmpty()) {
                    parsedWords.add(
                        KaraokeWord(
                            text = "$leadingText ",
                            startMs = entry.startMs,
                            endMs = maxOf(entry.startMs + 80L, firstTagMs)
                        )
                    )
                }
                for (i in angleMatches.indices) {
                    val m = angleMatches[i]
                    val wStart = toMs(m.groupValues[1], m.groupValues[2], m.groupValues.getOrNull(3))
                    val rawWord = m.groupValues[4]
                    val nextWStart = if (i + 1 < angleMatches.size) {
                        toMs(
                            angleMatches[i + 1].groupValues[1],
                            angleMatches[i + 1].groupValues[2],
                            angleMatches[i + 1].groupValues.getOrNull(3)
                        )
                    } else lineEnd
                    if (rawWord.isNotEmpty()) {
                        parsedWords.add(KaraokeWord(rawWord, wStart, maxOf(wStart + 60L, nextWStart)))
                    }
                }
                val plain = parsedWords.joinToString("") { it.text }.trim()
                KaraokeLine(entry.startMs, lineEnd, plain, parsedWords, true)
            } else if (lineTimestampRegex.containsMatchIn(body)) {
                val normalized = "[${
                    String.format(
                        Locale.ROOT,
                        "%02d:%02d.%02d",
                        entry.startMs / 60000,
                        (entry.startMs % 60000) / 1000,
                        (entry.startMs % 1000) / 10
                    )
                }]$body"
                val matches = inlineBracketRegex.findAll(normalized).toList()
                val parsedWords = matches.mapIndexedNotNull { i, m ->
                    val wStart = toMs(m.groupValues[1], m.groupValues[2], m.groupValues.getOrNull(3))
                    val rawWord = m.groupValues[4]
                    val nextWStart = if (i + 1 < matches.size) {
                        toMs(matches[i + 1].groupValues[1], matches[i + 1].groupValues[2], matches[i + 1].groupValues.getOrNull(3))
                    } else lineEnd
                    if (rawWord.isNotEmpty()) KaraokeWord(rawWord, wStart, maxOf(wStart + 60L, nextWStart)) else null
                }
                val plain = parsedWords.joinToString("") { it.text }.trim()
                KaraokeLine(entry.startMs, lineEnd, plain, parsedWords, true)
            } else {
                val clean = stripEnhancedTags(body)
                KaraokeLine(
                    startMs = entry.startMs,
                    endMs = lineEnd,
                    plainText = clean,
                    words = buildInterpolatedWords(clean, entry.startMs, lineEnd),
                    isEnhanced = false
                )
            }
        }
    }

    private fun buildInterpolatedWords(text: String, startMs: Long, endMs: Long): List<KaraokeWord> {
        val tokens = text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return emptyList()
        val effectiveDuration = (endMs - startMs).coerceAtLeast(400L) * 88 / 100
        val totalChars = tokens.sumOf { it.length.coerceAtLeast(1) }.coerceAtLeast(1)
        var cursorMs = startMs
        return tokens.mapIndexed { idx, token ->
            val weight = token.length.coerceAtLeast(1).toFloat() / totalChars.toFloat()
            val wordDur = (effectiveDuration * weight).toLong().coerceAtLeast(60L)
            val wStart = cursorMs
            val wEnd = if (idx == tokens.lastIndex) startMs + effectiveDuration else (wStart + wordDur)
            cursorMs = wEnd
            val suffix = if (idx < tokens.lastIndex) " " else ""
            KaraokeWord(token + suffix, wStart, maxOf(wStart + 40L, wEnd))
        }
    }
}

// ============================================================================
// MAIN ACTIVITY & APPLICATION UI
// ============================================================================

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
    val prefs = remember { context.getSharedPreferences("lyra", Context.MODE_PRIVATE) }

    var controller by remember { mutableStateOf<MediaController?>(null) }
    var songs by remember { mutableStateOf<List<AudioTrack>>(emptyList()) }
    var metadataOverrides by remember {
        mutableStateOf<Map<String, MetadataOverride>>(
            loadMetadataOverrides(prefs.getString("metadata_overrides_json", "{}").orEmpty())
        )
    }
    var search by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Siap memindai koleksi musik.") }
    var tab by remember { mutableStateOf(LyraTab.HOME) }
    var playing by remember { mutableStateOf(false) }
    var audioSessionId by remember { mutableIntStateOf(prefs.getInt("audio_session_id", 0)) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var currentTitle by remember { mutableStateOf(prefs.getString("current_title", "Belum ada lagu").orEmpty()) }
    var currentArtist by remember { mutableStateOf(prefs.getString("current_artist", "").orEmpty()) }
    var currentAlbum by remember { mutableStateOf(prefs.getString("current_album", "").orEmpty()) }
    var currentUri by remember { mutableStateOf<Uri?>(null) }
    var shuffle by remember { mutableStateOf(false) }
    var repeatMode by remember { mutableIntStateOf(Player.REPEAT_MODE_OFF) }
    var favoriteIds by remember {
        mutableStateOf<Set<String>>(prefs.getStringSet("favorites", emptySet())?.toSet() ?: emptySet())
    }
    var playlists by remember {
        mutableStateOf<List<LyraPlaylist>>(loadPlaylists(prefs.getString("playlists_json", "[]").orEmpty()))
    }
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
    var lastPlayed by remember {
        mutableStateOf<List<String>>(
            prefs.getString("recent_ids", "").orEmpty().split("|").filter { it.isNotBlank() }
        )
    }
    var playCounts by remember {
        mutableStateOf<Map<String, Int>>(loadCounts(prefs.getString("play_counts_json", "{}").orEmpty()))
    }

    var showMetadataDialog by remember { mutableStateOf(false) }
    var editingTrackTarget by remember { mutableStateOf<AudioTrack?>(null) }
    var editTitle by remember { mutableStateOf("") }
    var editArtist by remember { mutableStateOf("") }
    var editAlbum by remember { mutableStateOf("") }
    var editArtworkUri by remember { mutableStateOf("") }

    var showFullKaraokeInPlayer by remember { mutableStateOf(false) }
    var selectedLyricLine by remember { mutableIntStateOf(-1) }
    var editedLyricLine by remember { mutableStateOf("") }

    val parsedKaraokeLines = remember(lrcText) { EnhancedLrcEngine.parse(lrcText) }
    val hasEnhancedKaraoke = remember(parsedKaraokeLines) { parsedKaraokeLines.any { it.isEnhanced } }

    fun permissionName() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

    fun applyTrackOverride(raw: AudioTrack, overrides: Map<String, MetadataOverride> = metadataOverrides): AudioTrack {
        val ov = overrides[raw.id] ?: overrides[raw.uri.toString()] ?: return raw
        val customArt = ov.artworkUri.takeIf { it.isNotBlank() }?.let { runCatching { Uri.parse(it) }.getOrNull() } ?: raw.artworkUri
        return raw.copy(
            title = ov.title.ifBlank { raw.title },
            artist = ov.artist.ifBlank { raw.artist },
            album = ov.album.ifBlank { raw.album },
            artworkUri = customArt
        )
    }

    fun openMetadataEditor(track: AudioTrack?) {
        editingTrackTarget = track
        editTitle = track?.title ?: currentTitle
        editArtist = track?.artist ?: currentArtist
        editAlbum = track?.album ?: currentAlbum
        editArtworkUri = track?.artworkUri?.toString().orEmpty()
        showMetadataDialog = true
    }

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
                    val rawTrack = AudioTrack("media:$id", Uri.withAppendedPath(collection, id), title, artist, album, cursor.getLong(durationCol), path.substringBeforeLast("/", ""), artworkUri = art)
                    found["media:$id"] = applyTrackOverride(rawTrack)
                }
            }

            if (selectedFolder.isNotBlank()) {
                runCatching {
                    val tree = DocumentFile.fromTreeUri(context, Uri.parse(selectedFolder))
                    fun visit(folder: DocumentFile, depth: Int) {
                        if (depth > 12) return
                        for (file in folder.listFiles()) {
                            if (file.isDirectory) visit(file, depth + 1)
                            else if (file.isFile && file.name.orEmpty().substringAfterLast('.', "").lowercase(Locale.ROOT) in
                                setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "opus", "3gp", "mid", "amr")
                            ) {
                                val uri = file.uri
                                val id = "saf:$uri"
                                val title = file.name.orEmpty().substringBeforeLast('.').ifBlank { "Audio tanpa judul" }
                                if (!found.containsKey(id)) {
                                    val rawTrack = AudioTrack(id, uri, title, "Artis tidak diketahui", "Folder pilihan", 0L, file.parentFile?.name.orEmpty(), true)
                                    found[id] = applyTrackOverride(rawTrack)
                                }
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
    val artworkPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            editArtworkUri = uri.toString()
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
                .put("play_counts", serializeCounts(playCounts))
                .put("playlists", serializePlaylists(playlists))
                .put("dark_mode", darkMode)
                .put("smart_shuffle", smartShuffle)
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
            val importedPlaylists = mutableListOf<LyraPlaylist>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                importedPlaylists.add(LyraPlaylist(obj.optString("name", "Playlist"), obj.optJSONArray("ids").toStringList()))
            }
            playlists = importedPlaylists
            darkMode = root.optBoolean("dark_mode", true)
            smartShuffle = root.optBoolean("smart_shuffle", true)
            prefs.edit()
                .putStringSet("favorites", favoriteIds)
                .putString("recent_ids", lastPlayed.joinToString("|"))
                .putString("play_counts_json", serializeCounts(playCounts).toString())
                .putString("playlists_json", serializePlaylists(playlists).toString())
                .putBoolean("dark_mode", darkMode)
                .putBoolean("smart_shuffle", smartShuffle)
                .apply()
            status = "Backup berhasil dipulihkan. Pindai ulang koleksi jika lagu belum terlihat."
        } catch (e: Exception) { status = "Backup gagal dibuka: ${e.message ?: "berkas tidak valid"}" }
    }
    val m3uExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/x-mpegurl")) { uri ->
        if (uri != null) try {
            val activeName = selectedPlaylist
            val playlistTracksNow: List<AudioTrack> = when (activeName) {
                null -> songs
                "Favorit" -> favoriteIds.mapNotNull { id -> songs.firstOrNull { it.id == id } }
                else -> playlists.firstOrNull { p -> p.name == activeName }?.trackIds?.mapNotNull { id -> songs.firstOrNull { s -> s.id == id } } ?: songs
            }
            val content = buildString {
                appendLine("#EXTM3U")
                for (track in playlistTracksNow) {
                    appendLine("#EXTINF:${(track.duration / 1000).toInt()},${track.artist} - ${track.title}")
                    appendLine(track.uri.toString())
                }
            }
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
                val updated = playlists.toMutableList()
                updated.add(LyraPlaylist(name, resolved.map { it.id }))
                playlists = updated
                prefs.edit().putString("playlists_json", serializePlaylists(updated).toString()).apply()
                status = "Playlist $name dibuat (${resolved.size} lagu)."
            }
        } catch (e: Exception) { status = "Gagal mengimpor M3U: ${e.message}" }
    }

    fun savePlaylists() {
        prefs.edit().putString("playlists_json", serializePlaylists(playlists).toString()).apply()
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
        val updatedCounts = playCounts.toMutableMap()
        updatedCounts[track.id] = (updatedCounts[track.id] ?: 0) + 1
        playCounts = updatedCounts
        prefs.edit()
            .putString("current_title", track.title)
            .putString("current_artist", track.artist)
            .putString("current_album", track.album)
            .putString("recent_ids", newRecent.joinToString("|"))
            .putString("play_counts_json", serializeCounts(updatedCounts).toString())
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

    LaunchedEffect(controller, metadataOverrides) {
        val c = controller ?: return@LaunchedEffect
        var lastWidgetSync = 0L
        while (true) {
            playing = c.isPlaying
            audioSessionId = prefs.getInt("audio_session_id", 0)
            position = c.currentPosition.coerceAtLeast(0L)
            duration = c.duration.takeIf { it > 0 && it != androidx.media3.common.C.TIME_UNSET } ?: 0L
            shuffle = c.shuffleModeEnabled
            repeatMode = c.repeatMode

            val activeUri = c.currentMediaItem?.localConfiguration?.uri
            currentUri = activeUri
            val activeMediaId = c.currentMediaItem?.mediaId.orEmpty()
            val ov = metadataOverrides[activeMediaId] ?: activeUri?.toString()?.let { metadataOverrides[it] }

            val resolvedTitle = ov?.title?.takeIf { it.isNotBlank() }
                ?: c.mediaMetadata.title?.toString()?.takeIf { it.isNotBlank() }
                ?: currentTitle
            val resolvedArtist = ov?.artist?.takeIf { it.isNotBlank() }
                ?: c.mediaMetadata.artist?.toString()?.takeIf { it.isNotBlank() }
                ?: currentArtist
            val resolvedAlbum = ov?.album?.takeIf { it.isNotBlank() }
                ?: c.mediaMetadata.albumTitle?.toString()
                ?: currentAlbum

            currentTitle = resolvedTitle
            currentArtist = resolvedArtist
            currentAlbum = resolvedAlbum

            val rawLine = LrcParser.displayLine(lrcText, position)
            currentLyric = EnhancedLrcEngine.stripEnhancedTags(rawLine)

            val now = System.currentTimeMillis()
            if (now - lastWidgetSync > 450L) {
                lastWidgetSync = now
                LyricsWidgetProvider.updateAll(context, currentTitle, currentLyric)
            }
            delay(45)
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
    val playlistTracks: List<AudioTrack> = if (selectedPlaylist == "Favorit") {
        favoriteTracks
    } else {
        val activeName = selectedPlaylist
        if (activeName != null) {
            playlists.firstOrNull { p -> p.name == activeName }?.trackIds?.mapNotNull { id -> songs.firstOrNull { s -> s.id == id } }.orEmpty()
        } else emptyList()
    }

    val accent = Color(0xFFB8F36B)
    val colors = if (darkMode) darkColorScheme(
        primary = accent, secondary = accent, background = Color(0xFF0B0D0A),
        surface = Color(0xFF161914), surfaceVariant = Color(0xFF232820)
    ) else lightColorScheme(primary = Color(0xFF4D6E16), secondary = Color(0xFF4D6E16))

    MaterialTheme(colorScheme = colors) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            LyraBrandLogo(size = 38.dp, cornerRadius = 11.dp)
                            Column {
                                Text("LYRA", fontWeight = FontWeight.Black)
                                Text("KARAOKE · ENHANCED LRC · FFT", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    },
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
                    if ((currentUri != null || playing || currentTitle != "Belum ada lagu") && tab != LyraTab.NOW_PLAYING) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            tonalElevation = 8.dp,
                            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
                        ) {
                            Column(
                                Modifier.fillMaxWidth()
                                    .clickable { tab = LyraTab.NOW_PLAYING }
                                    .padding(horizontal = 14.dp, vertical = 8.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (currentTrack?.artworkUri != null) {
                                        AsyncImage(
                                            model = currentTrack.artworkUri,
                                            contentDescription = null,
                                            modifier = Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)),
                                            contentScale = ContentScale.Crop
                                        )
                                    } else {
                                        LyraBrandLogo(size = 42.dp, cornerRadius = 12.dp)
                                    }
                                    Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                                        Text(currentTitle, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold)
                                        Text(
                                            currentLyric.ifBlank { currentArtist.ifBlank { "Ketuk untuk membuka layar putar" } },
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    IconButton(onClick = { controller?.seekToPreviousMediaItem() }) { Text("|◀") }
                                    FilledTonalButton(
                                        onClick = { if (playing) controller?.pause() else controller?.play() },
                                        contentPadding = PaddingValues(horizontal = 14.dp),
                                        shape = RoundedCornerShape(14.dp)
                                    ) { Text(if (playing) "Ⅱ" else "▶") }
                                    IconButton(onClick = { controller?.seekToNextMediaItem() }) { Text("▶|") }
                                }
                                if (duration > 0) {
                                    LinearProgressIndicator(
                                        progress = { (position.toFloat() / duration.toFloat()).coerceIn(0f, 1f) },
                                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(3.dp).clip(RoundedCornerShape(2.dp)),
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                    NavigationBar {
                        LyraTab.values().forEach { item ->
                            NavigationBarItem(
                                selected = tab == item, onClick = { tab = item },
                                icon = { Text(item.icon) }, label = { Text(item.label, maxLines = 1, fontSize = 10.sp) }
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
                            shape = RoundedCornerShape(26.dp),
                            colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (currentTrack?.artworkUri != null) {
                                    AsyncImage(
                                        model = currentTrack.artworkUri,
                                        contentDescription = null,
                                        modifier = Modifier.size(80.dp).clip(RoundedCornerShape(20.dp)),
                                        contentScale = ContentScale.Crop
                                    )
                                } else {
                                    LyraBrandLogo(size = 80.dp, cornerRadius = 20.dp)
                                }
                                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                                    Text("PLAYING NOW", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Black)
                                    Text(currentTitle, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(currentArtist.ifBlank { "Pilih lagu untuk mulai" }, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("Karaoke LRC · Visualizer FFT · Edit Tag →", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 5.dp))
                                }
                                FilledTonalButton(onClick = { if (playing) controller?.pause() else if (currentUri != null) controller?.play() else if (songs.isNotEmpty()) playTracks(songs, 0) }) { Text(if (playing) "Ⅱ" else "▶") }
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        Text("Koleksi Musikmu", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
                        Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(vertical = 6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            SummaryCard("Semua lagu", songs.size.toString(), Modifier.weight(1f)) { tab = LyraTab.LIBRARY }
                            SummaryCard("Favorit", favoriteTracks.size.toString(), Modifier.weight(1f)) { search = "favorit"; tab = LyraTab.LIBRARY }
                            SummaryCard("Playlist", playlists.size.toString(), Modifier.weight(1f)) { tab = LyraTab.PLAYLISTS }
                        }
                        Spacer(Modifier.height(14.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Baru diputar", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            TextButton(onClick = { tab = LyraTab.LIBRARY }) { Text("Lihat semua") }
                        }
                        if (recentTracks.isEmpty()) EmptyState("Belum ada riwayat", "Putar lagu untuk mulai membuat riwayat lokal.")
                        else TrackList(
                            tracks = recentTracks.take(12),
                            currentUri = currentUri,
                            playing = playing,
                            favoriteIds = favoriteIds,
                            playTracks = ::playTracks,
                            toggleFavorite = ::toggleFavorite,
                            onAddToPlaylist = { trackToAdd = it },
                            onEditTrack = { openMetadataEditor(it) },
                            showFolder = false,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                            OutlinedButton(onClick = { folderPicker.launch(null) }, modifier = Modifier.weight(1f)) { Text("Pilih folder") }
                            OutlinedButton(onClick = { showSleepDialog = true }, modifier = Modifier.weight(1f)) { Text("Timer tidur") }
                        }
                    }

                    LyraTab.NOW_PLAYING -> {
                        Spacer(Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(20.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .padding(3.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(16.dp),
                                    color = if (!showFullKaraokeInPlayer) MaterialTheme.colorScheme.primary else Color.Transparent,
                                    modifier = Modifier.clickable { showFullKaraokeInPlayer = false }
                                ) {
                                    Text(
                                        "Sampul & Spektrum",
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = if (!showFullKaraokeInPlayer) Color(0xFF101010) else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Surface(
                                    shape = RoundedCornerShape(16.dp),
                                    color = if (showFullKaraokeInPlayer) MaterialTheme.colorScheme.primary else Color.Transparent,
                                    modifier = Modifier.clickable { showFullKaraokeInPlayer = true }
                                ) {
                                    Text(
                                        if (hasEnhancedKaraoke) "Karaoke LRC ★" else "Lirik Karaoke",
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = if (showFullKaraokeInPlayer) Color(0xFF101010) else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Row {
                                TextButton(onClick = { openMetadataEditor(currentTrack) }) { Text("✎ Edit Info") }
                                TextButton(onClick = { tab = LyraTab.QUEUE }) { Text("Antrean ☷") }
                            }
                        }

                        Spacer(Modifier.height(6.dp))

                        if (!showFullKaraokeInPlayer) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .weight(0.42f)
                                    .padding(horizontal = 12.dp, vertical = 4.dp)
                                    .clip(RoundedCornerShape(28.dp))
                                    .background(Brush.linearGradient(listOf(Color(0xFF283A18), Color(0xFF151E0D), Color(0xFF0C1009))))
                                    .clickable { showFullKaraokeInPlayer = true },
                                contentAlignment = Alignment.Center
                            ) {
                                if (currentTrack?.artworkUri != null) {
                                    AsyncImage(
                                        model = currentTrack.artworkUri,
                                        contentDescription = "Sampul ${currentTrack.album}",
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop
                                    )
                                } else {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                        LyraBrandLogo(size = 110.dp, cornerRadius = 28.dp)
                                        Spacer(Modifier.height(10.dp))
                                        Text(
                                            currentAlbum.ifBlank { "LYRA KARAOKE" }.uppercase(),
                                            color = Color(0xFFB8F36B),
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Black,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                                Surface(
                                    color = Color.Black.copy(alpha = 0.55f),
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp)
                                ) {
                                    Text(
                                        "Ketuk untuk layar penuh Lirik Karaoke ♪",
                                        color = Color.White,
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                    )
                                }
                            }

                            KaraokeMiniStage(
                                lines = parsedKaraokeLines,
                                positionMs = position,
                                hasEnhancedTag = hasEnhancedKaraoke,
                                onExpandClick = { showFullKaraokeInPlayer = true },
                                onOpenEditorClick = { tab = LyraTab.LYRICS }
                            )
                        } else {
                            KaraokeFullStage(
                                lines = parsedKaraokeLines,
                                positionMs = position,
                                hasEnhancedTag = hasEnhancedKaraoke,
                                onSeekTo = { targetMs -> controller?.seekTo(targetMs); position = targetMs },
                                onEditLyricsClick = { tab = LyraTab.LYRICS },
                                modifier = Modifier.fillMaxWidth().weight(0.55f)
                            )
                        }

                        Spacer(Modifier.height(8.dp))

                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.weight(1f).clickable { openMetadataEditor(currentTrack) }) {
                                Text(
                                    currentTitle,
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.ExtraBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    "${currentArtist.ifBlank { "Artis tidak diketahui" }} · ${currentAlbum.ifBlank { "Album tidak diketahui" }}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.secondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            IconButton(onClick = { currentTrack?.let { toggleFavorite(it) } }) {
                                Text(
                                    if (currentTrack?.id?.let { it in favoriteIds } == true) "♥" else "♡",
                                    style = MaterialTheme.typography.headlineSmall,
                                    color = if (currentTrack?.id?.let { it in favoriteIds } == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                )
                            }
                            FilledTonalIconButton(onClick = { openMetadataEditor(currentTrack) }) {
                                Text("✎", style = MaterialTheme.typography.titleMedium)
                            }
                        }

                        if (duration > 0) {
                            Slider(
                                value = position.coerceIn(0L, duration).toFloat(),
                                onValueChange = { position = it.toLong() },
                                onValueChangeFinished = { controller?.seekTo(position) },
                                valueRange = 0f..duration.toFloat(),
                                modifier = Modifier.height(26.dp)
                            )
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(formatTime(position), style = MaterialTheme.typography.labelSmall)
                                Text("−${formatTime((duration - position).coerceAtLeast(0L))}", style = MaterialTheme.typography.labelSmall)
                            }
                        }

                        AudioVisualizer(
                            audioSessionId = audioSessionId,
                            isPlaying = playing,
                            modifier = Modifier.fillMaxWidth().height(76.dp)
                        )

                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = { controller?.shuffleModeEnabled = !(controller?.shuffleModeEnabled ?: false) }) {
                                Text(if (shuffle) "🔀✓" else "🔀")
                            }
                            FilledTonalButton(
                                onClick = { controller?.seekToPreviousMediaItem() },
                                modifier = Modifier.size(52.dp),
                                shape = RoundedCornerShape(16.dp),
                                contentPadding = PaddingValues(0.dp)
                            ) { Text("|◀") }
                            Button(
                                onClick = {
                                    if (playing) controller?.pause()
                                    else if (currentUri != null) controller?.play()
                                    else if (songs.isNotEmpty()) playTracks(songs, 0)
                                },
                                modifier = Modifier.size(66.dp),
                                shape = RoundedCornerShape(22.dp),
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Text(if (playing) "Ⅱ" else "▶", style = MaterialTheme.typography.headlineMedium)
                            }
                            FilledTonalButton(
                                onClick = { controller?.seekToNextMediaItem() },
                                modifier = Modifier.size(52.dp),
                                shape = RoundedCornerShape(16.dp),
                                contentPadding = PaddingValues(0.dp)
                            ) { Text("▶|") }
                            IconButton(onClick = {
                                val next = when (repeatMode) {
                                    Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                                    Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                                    else -> Player.REPEAT_MODE_OFF
                                }
                                controller?.repeatMode = next
                                repeatMode = next
                            }) {
                                Text(
                                    when (repeatMode) {
                                        Player.REPEAT_MODE_ALL -> "🔁"
                                        Player.REPEAT_MODE_ONE -> "🔂"
                                        else -> "↻"
                                    }
                                )
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
                        TrackList(
                            tracks = libraryList,
                            currentUri = currentUri,
                            playing = playing,
                            favoriteIds = favoriteIds,
                            playTracks = ::playTracks,
                            toggleFavorite = ::toggleFavorite,
                            onAddToPlaylist = { trackToAdd = it },
                            onEditTrack = { openMetadataEditor(it) },
                            showFolder = true,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    LyraTab.SEARCH -> {
                        Spacer(Modifier.height(10.dp))
                        Text("Cari musik", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth().padding(vertical = 8.dp), label = { Text("Judul, artis, album, atau folder") }, singleLine = true)
                        Text("${filtered.size} hasil ditemukan", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                        TrackList(
                            tracks = filtered,
                            currentUri = currentUri,
                            playing = playing,
                            favoriteIds = favoriteIds,
                            playTracks = ::playTracks,
                            toggleFavorite = ::toggleFavorite,
                            onAddToPlaylist = { trackToAdd = it },
                            onEditTrack = { openMetadataEditor(it) },
                            showFolder = true,
                            modifier = Modifier.weight(1f)
                        )
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
                                        val allSongIds = songs.map { s -> s.id }
                                        playlists = playlists.map { item ->
                                            if (item.name == p.name) item.copy(trackIds = (item.trackIds + allSongIds).distinct()) else item
                                        }
                                        savePlaylists()
                                    }
                                }) { Text("Tambah semua lagu") }
                                OutlinedButton(onClick = {
                                    playlists = playlists.filterNot { it.name == selectedPlaylist }
                                    savePlaylists()
                                    selectedPlaylist = null
                                }) { Text("Hapus") }
                            }
                            TrackList(
                                tracks = playlistTracks,
                                currentUri = currentUri,
                                playing = playing,
                                favoriteIds = favoriteIds,
                                playTracks = ::playTracks,
                                toggleFavorite = ::toggleFavorite,
                                onAddToPlaylist = { trackToAdd = it },
                                onEditTrack = { openMetadataEditor(it) },
                                showFolder = false,
                                modifier = Modifier.weight(1f)
                            )
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
                        val queue = controller?.let { c ->
                            (0 until c.mediaItemCount).mapNotNull { i ->
                                val item = c.getMediaItemAt(i)
                                val uri = item.localConfiguration?.uri ?: return@mapNotNull null
                                val raw = AudioTrack(item.mediaId.ifBlank { uri.toString() }, uri, item.mediaMetadata.title?.toString() ?: "Audio", item.mediaMetadata.artist?.toString() ?: "Artis tidak diketahui", item.mediaMetadata.albumTitle?.toString() ?: "", 0L)
                                applyTrackOverride(raw)
                            }
                        }.orEmpty()
                        if (queue.isEmpty()) EmptyState("Antrean kosong", "Pilih lagu atau playlist untuk mulai memutar.")
                        else LazyColumn {
                            items(queue.indices.toList(), key = { queue[it].id + "_$it" }) { index ->
                                val track = queue[index]
                                Row(Modifier.fillMaxWidth().clickable { controller?.seekTo(index, 0L); controller?.play() }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(if (track.uri == currentUri) "▶" else "${index + 1}", modifier = Modifier.width(32.dp))
                                    Column(Modifier.weight(1f)) { Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(track.artist, style = MaterialTheme.typography.bodySmall) }
                                    TextButton(onClick = { openMetadataEditor(track) }) { Text("✎") }
                                    TextButton(onClick = { controller?.removeMediaItem(index) }) { Text("Hapus") }
                                }
                                HorizontalDivider()
                            }
                        }
                    }

                    LyraTab.LYRICS -> {
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Lirik & Editor Enhanced LRC", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                                Text("$currentTitle · $currentArtist", style = MaterialTheme.typography.bodySmall)
                            }
                            Surface(
                                color = if (hasEnhancedKaraoke) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text(
                                    if (hasEnhancedKaraoke) "ENHANCED KARAOKE" else "SYNCED / AUTO KARAOKE",
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = if (hasEnhancedKaraoke) Color(0xFF101010) else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Text(lyricsStatus, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(vertical = 4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { searchTrack = currentTitle; searchArtist = currentArtist }) { Text("Gunakan lagu saat ini") }
                            OutlinedButton(onClick = { lrcPicker.launch(arrayOf("text/*", "application/octet-stream")) }) { Text("Impor .LRC") }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            OutlinedTextField(searchTrack, { searchTrack = it }, Modifier.weight(1f), label = { Text("Judul lagu") }, singleLine = true)
                            OutlinedTextField(searchArtist, { searchArtist = it }, Modifier.weight(1f), label = { Text("Artis") }, singleLine = true)
                        }
                        Button(onClick = {
                            scope.launch {
                                status = "Mencari lirik di LRCLIB..."
                                try {
                                    results = withContext(Dispatchers.IO) { LrclibClient.search(searchTrack, searchArtist) }
                                    status = if (results.isEmpty()) "Lirik tidak ditemukan." else "Pilih hasil lirik di bawah."
                                } catch (e: Exception) { status = "Pencarian gagal: ${e.message ?: "periksa koneksi"}" }
                            }
                        }, enabled = searchTrack.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Cari lirik online") }

                        if (results.isNotEmpty()) {
                            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 120.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
                                            Text("${result.artist} · ${if (result.syncedLyrics != null) "Tersinkron" else "Teks biasa"}", style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                }
                            }
                        }

                        OutlinedTextField(
                            value = lrcText,
                            onValueChange = { lrcText = it },
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            label = { Text("Isi berkas .lrc (Mendukung Enhanced LRC: [00:12.00]<00:12.00>Kata <00:12.50>Demi <00:13.00>Kata)") },
                            minLines = 4
                        )

                        if (selectedLyricLine >= 0) {
                            OutlinedTextField(
                                value = editedLyricLine,
                                onValueChange = { editedLyricLine = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Edit baris terpilih (sisipkan <mm:ss.xx> sebelum kata untuk karaoke)") }
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = {
                                    editedLyricLine += " ${EnhancedLrcEngine.formatWordTag(position)}"
                                }) { Text("+ Tag Kata ${EnhancedLrcEngine.formatWordTag(position)}") }
                                Button(onClick = {
                                    val all = lrcText.lines().toMutableList()
                                    if (selectedLyricLine in all.indices) {
                                        val original = all[selectedLyricLine]
                                        val timestampPrefix = Regex("^(?:\\[[0-9]{2}:[0-9]{2}(?:[.:][0-9]{1,3})?\\])+").find(original)?.value.orEmpty()
                                        all[selectedLyricLine] = timestampPrefix + editedLyricLine
                                        lrcText = all.joinToString("\n")
                                    }
                                    selectedLyricLine = -1
                                }) { Text("Terapkan") }
                                TextButton(onClick = { selectedLyricLine = -1 }) { Text("Batal") }
                            }
                        } else {
                            val indexedLines = remember(lrcText) {
                                lrcText.lines().mapIndexedNotNull { index, line ->
                                    if (index < 120 && line.isNotBlank()) Pair(index, line) else null
                                }
                            }
                            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 110.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                items(indexedLines) { pair ->
                                    val lineIndex = pair.first
                                    val lyricLine = pair.second
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Text(lyricLine.takeWhile { it != ']' }.removePrefix("[").ifBlank { "teks" }, Modifier.width(62.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                                        Text(lyricLine.substringAfterLast("]").ifBlank { lyricLine }, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                                        TextButton(onClick = {
                                            selectedLyricLine = lineIndex
                                            editedLyricLine = if (lyricLine.contains("]")) lyricLine.substringAfterLast("]") else lyricLine
                                        }) { Text("Edit") }
                                    }
                                }
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(onClick = { lrcText = (lrcText.trimEnd() + "\n" + LrcParser.timestamp(position) + " ").trimStart() }) { Text("+ [Baris]") }
                            OutlinedButton(onClick = { lrcText = lrcText.trimEnd() + " " + EnhancedLrcEngine.formatWordTag(position) + " " }) { Text("+ <Kata>") }
                            OutlinedButton(onClick = { lrcText = LrcParser.shift(lrcText, -500) }) { Text("−0,5s") }
                            OutlinedButton(onClick = { lrcText = LrcParser.shift(lrcText, 500) }) { Text("+0,5s") }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                            OutlinedButton(onClick = { saveLrcLauncher.launch(currentTitle.substringBeforeLast('.', currentTitle) + ".lrc") }, modifier = Modifier.weight(1f)) { Text("Ekspor .LRC") }
                            OutlinedButton(onClick = {
                                val uri = currentUri
                                if (uri == null) {
                                    lyricsStatus = "Putar atau pilih lagu terlebih dahulu."
                                } else {
                                    lyricsStatus = "Menanamkan lirik ke tag audio…"
                                    scope.launch {
                                        val result = runCatching { withContext(Dispatchers.IO) { AudioTagEditor.embedLyrics(context, uri, lrcText) } }
                                        lyricsStatus = if (result.isSuccess) "Lirik berhasil ditanamkan ke tag file audio." else "Gagal menanamkan lirik: ${result.exceptionOrNull()?.message ?: "file hanya-baca"}"
                                        status = lyricsStatus
                                    }
                                }
                            }, modifier = Modifier.weight(1f)) { Text("Tanam ke File") }
                            Button(onClick = {
                                val key = lyricsCacheKey(currentTitle, currentArtist)
                                val cache = runCatching { JSONObject(prefs.getString("lyrics_cache_json", "{}").orEmpty()) }.getOrElse { JSONObject() }
                                cache.put(key, lrcText)
                                prefs.edit().putString("current_lrc", lrcText).putString("current_lrc_key", key).putString("lyrics_cache_json", cache.toString()).apply()
                                LyricsWidgetProvider.updateAll(context, currentTitle, EnhancedLrcEngine.stripEnhancedTags(LrcParser.displayLine(lrcText, position)))
                                lyricsStatus = "Lirik disimpan ke cache lokal"
                                status = "Lirik disimpan."
                            }, modifier = Modifier.weight(1f)) { Text("Simpan") }
                        }
                    }

                    LyraTab.SETTINGS -> {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 10.dp)) {
                            item {
                                Text("Pengaturan", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                                SettingRow("Tema gelap", "Gunakan antarmuka gelap", darkMode) { darkMode = it; prefs.edit().putBoolean("dark_mode", it).apply() }
                                SettingRow("Pencarian lirik otomatis", "Cari lirik saat lagu berubah; gunakan cache lokal sebelum internet", autoLyrics) { autoLyrics = it; prefs.edit().putBoolean("auto_lyrics", it).apply() }
                            }
                            item {
                                ElevatedCard(Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("Penyimpanan musik", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                        Text(if (selectedFolder.isBlank()) "Folder khusus belum dipilih." else "Folder khusus tersimpan dan akan dipindai bersama koleksi media.")
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Button(onClick = { folderPicker.launch(null) }) { Text("Pilih folder") }
                                            OutlinedButton(onClick = { selectedFolder = ""; prefs.edit().remove("music_folder_uri").apply(); scanSongs() }) { Text("Hapus folder") }
                                        }
                                    }
                                }
                            }
                            item {
                                ElevatedCard(Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("Audio & Equalizer", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                        EqualizerPanel(audioSessionId = audioSessionId)
                                    }
                                }
                            }
                            item {
                                ElevatedCard(Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("Timer tidur", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                        Text(if (sleepMinutes > 0) "Timer aktif: $sleepMinutes menit" else "Musik dapat dijeda otomatis setelah waktu yang dipilih.")
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Button(onClick = { showSleepDialog = true }) { Text("Atur timer") }
                                            OutlinedButton(onClick = { sleepMinutes = 0 }) { Text("Matikan") }
                                        }
                                    }
                                }
                            }
                            item {
                                ElevatedCard(Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("Backup & Playlist Portabel", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Button(onClick = { backupExportLauncher.launch("lyra-backup.json") }) { Text("Ekspor backup") }
                                            OutlinedButton(onClick = { backupImportLauncher.launch(arrayOf("application/json", "text/*")) }) { Text("Pulihkan") }
                                        }
                                        OutlinedButton(onClick = { m3uExportLauncher.launch("lyra-playlist.m3u") }, modifier = Modifier.fillMaxWidth()) { Text("Ekspor sebagai M3U") }
                                        OutlinedButton(onClick = { m3uImportLauncher.launch(arrayOf("audio/x-mpegurl", "audio/mpegurl", "application/vnd.apple.mpegurl", "text/plain", "*/*")) }, modifier = Modifier.fillMaxWidth()) { Text("Impor M3U sebagai playlist") }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showMetadataDialog) {
        val target = editingTrackTarget ?: currentTrack
        AlertDialog(
            onDismissRequest = { showMetadataDialog = false },
            title = { Text("Edit Keterangan Lagu") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Perubahan disimpan secara permanen di database lokal Lyra (tidak akan hilang saat memindai ulang musik).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    OutlinedTextField(editTitle, { editTitle = it }, label = { Text("Judul lagu") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(editArtist, { editArtist = it }, label = { Text("Artis") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(editAlbum, { editAlbum = it }, label = { Text("Album") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = editArtworkUri,
                            onValueChange = { editArtworkUri = it },
                            label = { Text("URI Sampul (opsional)") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedButton(onClick = { artworkPicker.launch(arrayOf("image/*")) }) {
                            Text("Pilih Foto")
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    val title = editTitle.trim().ifBlank { target?.title ?: currentTitle }
                    val artist = editArtist.trim().ifBlank { "Artis tidak diketahui" }
                    val album = editAlbum.trim().ifBlank { "Album tidak diketahui" }
                    val artStr = editArtworkUri.trim()
                    val ov = MetadataOverride(title, artist, album, artStr)

                    val updatedMap = metadataOverrides.toMutableMap()
                    if (target != null) {
                        updatedMap[target.id] = ov
                        updatedMap[target.uri.toString()] = ov
                    } else if (currentUri != null) {
                        updatedMap[currentUri.toString()] = ov
                    }
                    metadataOverrides = updatedMap
                    saveMetadataOverrides(prefs, updatedMap)

                    songs = songs.map { s ->
                        if ((target != null && (s.id == target.id || s.uri == target.uri)) || (target == null && s.uri == currentUri)) {
                            applyTrackOverride(s, updatedMap)
                        } else s
                    }

                    if (target == null || target.uri == currentUri) {
                        currentTitle = title
                        currentArtist = artist
                        currentAlbum = album
                        prefs.edit()
                            .putString("current_title", title)
                            .putString("current_artist", artist)
                            .putString("current_album", album)
                            .apply()

                        val c = controller
                        val idx = c?.currentMediaItemIndex ?: -1
                        if (c != null && idx >= 0 && idx < c.mediaItemCount) {
                            val old = c.getMediaItemAt(idx)
                            val artUri = artStr.takeIf { it.isNotBlank() }?.let { runCatching { Uri.parse(it) }.getOrNull() } ?: old.mediaMetadata.artworkUri
                            val updatedItem = old.buildUpon().setMediaMetadata(
                                old.mediaMetadata.buildUpon()
                                    .setTitle(title)
                                    .setArtist(artist)
                                    .setAlbumTitle(album)
                                    .setArtworkUri(artUri)
                                    .build()
                            ).build()
                            c.replaceMediaItem(idx, updatedItem)
                        }
                        searchTrack = title
                        searchArtist = artist
                    }

                    showMetadataDialog = false
                    status = "Keterangan lagu \"$title\" berhasil disimpan."
                }) { Text("Simpan Permanen") }
            },
            dismissButton = {
                Row {
                    if (target != null && (metadataOverrides.containsKey(target.id) || metadataOverrides.containsKey(target.uri.toString()))) {
                        TextButton(onClick = {
                            val updatedMap = metadataOverrides.toMutableMap()
                            updatedMap.remove(target.id)
                            updatedMap.remove(target.uri.toString())
                            metadataOverrides = updatedMap
                            saveMetadataOverrides(prefs, updatedMap)
                            scanSongs()
                            showMetadataDialog = false
                            status = "Keterangan lagu dikembalikan ke asli."
                        }) { Text("Reset") }
                    }
                    TextButton(onClick = { showMetadataDialog = false }) { Text("Batal") }
                }
            }
        )
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
                        val updated = playlists.toMutableList()
                        updated.add(LyraPlaylist(name, emptyList()))
                        playlists = updated
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
                        for (playlist in playlists) {
                            TextButton(onClick = {
                                val track = trackToAdd
                                if (track != null) {
                                    playlists = playlists.map { p ->
                                        if (p.name == playlist.name) p.copy(trackIds = (p.trackIds + listOf(track.id)).distinct()) else p
                                    }
                                    savePlaylists()
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

// ============================================================================
// KOMPONEN KARAOKE LRC, VISUALIZER, DAN PANEL PENDUKUNG
// ============================================================================

@Composable
private fun KaraokeWordText(
    line: KaraokeLine,
    positionMs: Long,
    isCurrentLine: Boolean,
    fontSizeSp: Int,
    textAlign: TextAlign = TextAlign.Center,
    modifier: Modifier = Modifier
) {
    val activeColor = Color(0xFFB8F36B)
    val sungColor = Color.White
    val unsungColor = if (isCurrentLine) Color.White.copy(alpha = 0.42f) else Color.White.copy(alpha = 0.25f)

    if (!isCurrentLine || line.words.isEmpty()) {
        Text(
            text = line.plainText.ifBlank { "•••" },
            fontSize = fontSizeSp.sp,
            fontWeight = if (isCurrentLine) FontWeight.ExtraBold else FontWeight.SemiBold,
            color = if (isCurrentLine) sungColor else unsungColor,
            textAlign = textAlign,
            lineHeight = (fontSizeSp + 7).sp,
            modifier = modifier
        )
        return
    }

    val annotated = buildAnnotatedString {
        for (word in line.words) {
            when {
                positionMs >= word.endMs -> {
                    withStyle(SpanStyle(color = sungColor, fontWeight = FontWeight.ExtraBold)) {
                        append(word.text)
                    }
                }
                positionMs in word.startMs until word.endMs -> {
                    val dur = (word.endMs - word.startMs).coerceAtLeast(1L)
                    val fraction = ((positionMs - word.startMs).toFloat() / dur.toFloat()).coerceIn(0f, 1f)
                    val splitIndex = (word.text.length * fraction).toInt().coerceIn(0, word.text.length)
                    val sungPart = word.text.substring(0, splitIndex)
                    val restPart = word.text.substring(splitIndex)
                    withStyle(SpanStyle(color = activeColor, fontWeight = FontWeight.Black)) {
                        append(sungPart)
                    }
                    withStyle(SpanStyle(color = activeColor.copy(alpha = 0.65f), fontWeight = FontWeight.ExtraBold)) {
                        append(restPart)
                    }
                }
                else -> {
                    withStyle(SpanStyle(color = unsungColor, fontWeight = FontWeight.Bold)) {
                        append(word.text)
                    }
                }
            }
        }
    }

    Text(
        text = annotated,
        fontSize = fontSizeSp.sp,
        textAlign = textAlign,
        lineHeight = (fontSizeSp + 7).sp,
        modifier = modifier
    )
}

@Composable
private fun KaraokeMiniStage(
    lines: List<KaraokeLine>,
    positionMs: Long,
    hasEnhancedTag: Boolean,
    onExpandClick: () -> Unit,
    onOpenEditorClick: () -> Unit
) {
    val activeIdx = remember(lines, positionMs) {
        lines.indexOfLast { it.startMs <= positionMs }.coerceAtLeast(0)
    }
    val currentLine = lines.getOrNull(activeIdx)
    val nextLine = lines.getOrNull(activeIdx + 1)

    ElevatedCard(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onExpandClick() }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (hasEnhancedTag)
