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
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
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

// === 1. MODELS, ENUMS & HELPERS ===
private data class AudioTrack(val id: String, val uri: Uri, val title: String, val artist: String, val album: String, val duration: Long, val folder: String = "", val isSaf: Boolean = false, val artworkUri: Uri? = null)
private data class MetadataOverride(val title: String, val artist: String, val album: String, val artworkUri: String = "")
private data class LyraPlaylist(val name: String, val trackIds: List<String>)
private data class KaraokeWord(val text: String, val startMs: Long, val endMs: Long)
private data class KaraokeLine(val startMs: Long, val endMs: Long, val plainText: String, val words: List<KaraokeWord>, val isEnhanced: Boolean)

private enum class LyraTab(val label: String) { HOME("Home"), NOW_PLAYING("Putar"), LIBRARY("Musik"), LYRICS("Lirik"), SETTINGS("Setelan") }
private enum class LibrarySubTab(val title: String) { ALL("Semua Lagu"), FAVORITES("Favorit"), PLAYLISTS("Playlist"), QUEUE("Antrean") }
private enum class SortOption(val label: String) { TITLE_ASC("Judul A-Z"), TITLE_DESC("Judul Z-A"), ARTIST_ASC("Artis A-Z"), DURATION_DESC("Terpanjang"), DURATION_ASC("Terpendek"), MOST_PLAYED("Sering Diputar") }
private enum class LyricsSubTab(val title: String) { PREVIEW("♪ Karaoke"), SEARCH("⌕ Cari Online"), EDITOR("✎ Studio LRC") }
private enum class LyraIconType { HOME, PLAY, PAUSE, NEXT, PREV, SHUFFLE, REPEAT_OFF, REPEAT_ALL, REPEAT_ONE, HEART_FILLED, HEART_OUTLINE, EDIT, PLUS, QUEUE, MUSIC, LYRICS, SETTINGS, SEARCH }

private fun lyricsCacheKey(title: String, artist: String): String =
    (title.trim().substringBeforeLast('.', title.trim()) + "|" + artist.trim()).lowercase(Locale.ROOT).replace(Regex("\\s+"), " ").trim()

private fun formatTime(ms: Long): String {
    val total = (ms.coerceAtLeast(0L) / 1000L).toInt()
    return String.format(Locale.ROOT, "%d:%02d", total / 60, total % 60)
}

private fun cleanSearchQuery(rawTitle: String, rawArtist: String): Pair<String, String> {
    var t = rawTitle.trim().substringBeforeLast('.', rawTitle.trim())
    var a = rawArtist.trim().takeUnless { it.equals("Artis tidak diketahui", true) || it.equals("<unknown>", true) || it.equals("unknown", true) }.orEmpty()
    t = t.replace(Regex("(?i)\\[.*?(official|lyric|audio|video|mv|hd|hq|visualizer|karaoke|ost|remaster).*?\\]"), "")
        .replace(Regex("(?i)\\(.*?(official|lyric|audio|video|mv|hd|hq|visualizer|karaoke|ost|remaster).*?\\)"), "")
        .replace(Regex("(?i)\\s*[-|]\\s*(official\\s*)?(music\\s*)?(audio|video|lyrics?|visualizer|mv|karaoke).*?$"), "")
        .replace(Regex("(?i)\\b(ft\\.?|feat\\.?)\\s+[^-()\\[\\]]+"), "").replace(Regex("_+"), " ").trim()
    if (a.isBlank() && t.contains(" - ")) {
        val p = t.split(" - ", limit = 2)
        if (p.size == 2 && p[0].length in 2..40 && p[1].length >= 2) { a = p[0].trim(); t = p[1].trim() }
    } else if (a.isNotBlank() && t.lowercase(Locale.ROOT).startsWith(a.lowercase(Locale.ROOT) + " - ")) {
        t = t.substring(a.length + 3).trim()
    }
    return Pair(t.ifBlank { rawTitle.trim() }, a)
}

private suspend fun smartLyricsSearch(rawTitle: String, rawArtist: String): List<LrcResult> = withContext(Dispatchers.IO) {
    val (cleanT, cleanA) = cleanSearchQuery(rawTitle, rawArtist)
    val merged = linkedMapOf<String, LrcResult>()
    fun collect(list: List<LrcResult>) {
        for (item in list) if (!item.syncedLyrics.isNullOrBlank() || !item.plainLyrics.isNullOrBlank()) {
            val k = "${item.name.lowercase(Locale.ROOT)}|${item.artist.lowercase(Locale.ROOT)}|${!item.syncedLyrics.isNullOrBlank()}"
            if (!merged.containsKey(k)) merged[k] = item
        }
    }
    if (cleanT.isNotBlank() && cleanA.isNotBlank()) runCatching { LrclibClient.search(cleanT, cleanA) }.getOrNull()?.let { collect(it) }
    if (merged.values.none { !it.syncedLyrics.isNullOrBlank() } && cleanT.isNotBlank()) runCatching { LrclibClient.search(cleanT, "") }.getOrNull()?.let { collect(it) }
    if (merged.isEmpty() && rawTitle.isNotBlank() && !rawTitle.equals(cleanT, true)) runCatching { LrclibClient.search(rawTitle.trim(), "") }.getOrNull()?.let { collect(it) }
    merged.values.sortedWith(compareByDescending<LrcResult> { it.syncedLyrics?.contains("<") == true }.thenByDescending { !it.syncedLyrics.isNullOrBlank() }.thenByDescending { it.name.equals(cleanT, true) })
}

private fun JSONArray?.toStringList(): List<String> {
    val arr = this ?: return emptyList()
    val res = mutableListOf<String>()
    for (i in 0 until arr.length()) { val s = arr.optString(i); if (!s.isNullOrBlank() && s != "null") res.add(s) }
    return res
}

private fun loadPlaylists(raw: String): List<LyraPlaylist> = runCatching {
    val arr = JSONArray(raw); val list = mutableListOf<LyraPlaylist>()
    for (i in 0 until arr.length()) { val o = arr.getJSONObject(i); list.add(LyraPlaylist(o.optString("name", "Playlist"), o.optJSONArray("ids").toStringList())) }
    list
}.getOrDefault(emptyList())

private fun serializePlaylists(list: List<LyraPlaylist>): JSONArray {
    val arr = JSONArray()
    for (p in list) arr.put(JSONObject().put("name", p.name).put("ids", JSONArray(p.trackIds)))
    return arr
}

private fun loadCounts(raw: String): Map<String, Int> = runCatching {
    val o = JSONObject(raw); val m = mutableMapOf<String, Int>()
    val keys = o.keys(); while (keys.hasNext()) { val k = keys.next(); m[k] = o.optInt(k, 0) }
    m
}.getOrDefault(emptyMap())

private fun serializeCounts(map: Map<String, Int>): JSONObject {
    val o = JSONObject(); for ((k, v) in map.entries) o.put(k, v); return o
}

private fun loadMetadataOverrides(raw: String): Map<String, MetadataOverride> = runCatching {
    val o = JSONObject(raw); val m = mutableMapOf<String, MetadataOverride>()
    val keys = o.keys(); while (keys.hasNext()) { val k = keys.next(); val it = o.getJSONObject(k); m[k] = MetadataOverride(it.optString("title", ""), it.optString("artist", ""), it.optString("album", ""), it.optString("artworkUri", "")) }
    m
}.getOrDefault(emptyMap())

private fun saveMetadataOverrides(prefs: SharedPreferences, map: Map<String, MetadataOverride>) {
    val root = JSONObject()
    for ((k, v) in map.entries) root.put(k, JSONObject().put("title", v.title).put("artist", v.artist).put("album", v.album).put("artworkUri", v.artworkUri))
    prefs.edit().putString("metadata_overrides_json", root.toString()).apply()
}

// === 2. ENHANCED LRC KARAOKE ENGINE ===
private object EnhancedLrcEngine {
    private val lineTsRegex = Regex("\\[(\\d{1,2}):(\\d{2})(?:[.:](\\d{1,3}))?\\]")
    private val wordTsRegex = Regex("<(\\d{1,2}):(\\d{2})(?:[.:](\\d{1,3}))?>([^<]*)")
    private val inlineBrRegex = Regex("\\[(\\d{1,2}):(\\d{2})(?:[.:](\\d{1,3}))?\\]([^\\[]*)")
    private val anyTagRegex = Regex("(\\[|<)(\\d{1,2}):(\\d{2})(?:[.:](\\d{1,3}))?(\\]|>)")

    private fun toMs(mStr: String, sStr: String, fStr: String?): Long {
        val m = mStr.toLongOrNull() ?: 0L; val s = sStr.toLongOrNull() ?: 0L
        val f = when (fStr?.length ?: 0) { 1 -> (fStr?.toLongOrNull() ?: 0L) * 100L; 2 -> (fStr?.toLongOrNull() ?: 0L) * 10L; 3 -> fStr?.toLongOrNull() ?: 0L; else -> 0L }
        return m * 60_000L + s * 1_000L + f
    }

    fun stripEnhancedTags(raw: String): String = raw.replace(Regex("<\\d{1,2}:\\d{2}(?:[.:]\\d{1,3})?>"), "").trim()
    fun formatLineTag(ms: Long): String { val s = ms.coerceAtLeast(0L); return String.format(Locale.ROOT, "[%02d:%02d.%02d]", s / 60_000L, (s % 60_000L) / 1_000L, (s % 1_000L) / 10L) }
    fun formatWordTag(ms: Long): String { val s = ms.coerceAtLeast(0L); return String.format(Locale.ROOT, "<%02d:%02d.%02d>", s / 60_000L, (s % 60_000L) / 1_000L, (s % 1_000L) / 10L) }

    fun shiftAllTags(lrc: String, deltaMs: Long): String = anyTagRegex.replace(lrc) { m ->
        val n = (toMs(m.groupValues[2], m.groupValues[3], m.groupValues.getOrNull(4)) + deltaMs).coerceAtLeast(0L)
        String.format(Locale.ROOT, "%s%02d:%02d.%02d%s", m.groupValues[1], n / 60_000L, (n % 60_000L) / 1_000L, (n % 1_000L) / 10L, m.groupValues[5])
    }

    fun parse(lrcText: String, enableAutoWord: Boolean): List<KaraokeLine> {
        if (lrcText.isBlank()) return emptyList()
        val rawEntries = mutableListOf<Pair<Long, String>>()
        for (rawLine in lrcText.lines()) {
            val tr = rawLine.trim(); if (tr.isBlank()) continue
            val pm = Regex("^(?:\\[\\d{1,2}:\\d{2}(?:[.:]\\d{1,3})?\\])+").find(tr)
            if (pm != null) {
                val body = tr.substring(pm.value.length).trim()
                for (m in lineTsRegex.findAll(pm.value)) rawEntries.add(Pair(toMs(m.groupValues[1], m.groupValues[2], m.groupValues.getOrNull(3)), body))
            }
        }
        if (rawEntries.isEmpty()) {
            return lrcText.lines().map { it.trim() }.filter { it.isNotBlank() }.mapIndexed { i, txt ->
                val s = i * 4000L; val e = s + 3800L
                KaraokeLine(s, e, txt, if (enableAutoWord) interpolateWords(txt, s, e) else emptyList(), false)
            }
        }
        val sorted = rawEntries.sortedBy { it.first }
        return sorted.mapIndexed { idx, entry ->
            val startMs = entry.first; val body = entry.second
            val lineEnd = maxOf(startMs + 600L, sorted.getOrNull(idx + 1)?.first ?: (startMs + 5000L))
            val angleMatches = wordTsRegex.findAll(body).toList()
            if (angleMatches.isNotEmpty()) {
                val words = mutableListOf<KaraokeWord>()
                val lead = body.substringBefore("<").trim()
                val firstMs = toMs(angleMatches.first().groupValues[1], angleMatches.first().groupValues[2], angleMatches.first().groupValues.getOrNull(3))
                if (lead.isNotEmpty()) words.add(KaraokeWord("$lead ", startMs, maxOf(startMs + 80L, firstMs)))
                for (i in angleMatches.indices) {
                    val m = angleMatches[i]; val wS = toMs(m.groupValues[1], m.groupValues[2], m.groupValues.getOrNull(3))
                    val nextW = if (i + 1 < angleMatches.size) toMs(angleMatches[i + 1].groupValues[1], angleMatches[i + 1].groupValues[2], angleMatches[i + 1].groupValues.getOrNull(3)) else lineEnd
                    if (m.groupValues[4].isNotEmpty()) words.add(KaraokeWord(m.groupValues[4], wS, maxOf(wS + 60L, nextW)))
                }
                KaraokeLine(startMs, lineEnd, words.joinToString("") { it.text }.trim(), words, true)
            } else if (lineTsRegex.containsMatchIn(body)) {
                val matches = inlineBrRegex.findAll("${formatLineTag(startMs)}$body").toList()
                val words = matches.mapIndexedNotNull { i, m ->
                    val wS = toMs(m.groupValues[1], m.groupValues[2], m.groupValues.getOrNull(3))
                    val nextW = if (i + 1 < matches.size) toMs(matches[i + 1].groupValues[1], matches[i + 1].groupValues[2], matches[i + 1].groupValues.getOrNull(3)) else lineEnd
                    if (m.groupValues[4].isNotEmpty()) KaraokeWord(m.groupValues[4], wS, maxOf(wS + 60L, nextW)) else null
                }
                KaraokeLine(startMs, lineEnd, words.joinToString("") { it.text }.trim(), words, true)
            } else {
                val clean = stripEnhancedTags(body)
                KaraokeLine(startMs, lineEnd, clean, if (enableAutoWord) interpolateWords(clean, startMs, lineEnd) else emptyList(), false)
            }
        }
    }

    private fun interpolateWords(text: String, startMs: Long, endMs: Long): List<KaraokeWord> {
        val tokens = text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return emptyList()
        val effDur = (endMs - startMs).coerceAtLeast(400L) * 88 / 100
        val totalChars = tokens.sumOf { it.length.coerceAtLeast(1) }.coerceAtLeast(1)
        var cur = startMs
        return tokens.mapIndexed { idx, token ->
            val dur = (effDur * (token.length.coerceAtLeast(1).toFloat() / totalChars)).toLong().coerceAtLeast(60L)
            val wEnd = if (idx == tokens.lastIndex) startMs + effDur else (cur + dur)
            val w = KaraokeWord(if (idx < tokens.lastIndex) "$token " else token, cur, maxOf(cur + 40L, wEnd))
            cur = wEnd; w
        }
    }
}

// === 3. CUSTOM VECTOR ICONS & UI COMPONENTS ===
@Composable
private fun LyraVectorIcon(type: LyraIconType, tint: Color = Color.White, size: Dp = 22.dp, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width; val h = this.size.height
        val stroke = Stroke(width = w * 0.095f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (type) {
            LyraIconType.PLAY -> drawPath(Path().apply { moveTo(w * 0.28f, h * 0.18f); lineTo(w * 0.80f, h * 0.50f); lineTo(w * 0.28f, h * 0.82f); close() }, tint)
            LyraIconType.PAUSE -> {
                drawRoundRect(tint, Offset(w * 0.24f, h * 0.18f), Size(w * 0.18f, h * 0.64f), CornerRadius(w * 0.05f))
                drawRoundRect(tint, Offset(w * 0.58f, h * 0.18f), Size(w * 0.18f, h * 0.64f), CornerRadius(w * 0.05f))
            }
            LyraIconType.NEXT -> {
                drawPath(Path().apply { moveTo(w * 0.18f, h * 0.20f); lineTo(w * 0.64f, h * 0.50f); lineTo(w * 0.18f, h * 0.80f); close() }, tint)
                drawRoundRect(tint, Offset(w * 0.70f, h * 0.20f), Size(w * 0.12f, h * 0.60f), CornerRadius(w * 0.04f))
            }
            LyraIconType.PREV -> {
                drawRoundRect(tint, Offset(w * 0.18f, h * 0.20f), Size(w * 0.12f, h * 0.60f), CornerRadius(w * 0.04f))
                drawPath(Path().apply { moveTo(w * 0.82f, h * 0.20f); lineTo(w * 0.36f, h * 0.50f); lineTo(w * 0.82f, h * 0.80f); close() }, tint)
            }
            LyraIconType.SHUFFLE -> {
                drawPath(Path().apply { moveTo(w * 0.14f, h * 0.30f); cubicTo(w * 0.42f, h * 0.30f, w * 0.48f, h * 0.70f, w * 0.82f, h * 0.70f) }, tint, style = stroke)
                drawPath(Path().apply { moveTo(w * 0.14f, h * 0.70f); cubicTo(w * 0.42f, h * 0.70f, w * 0.48f, h * 0.30f, w * 0.82f, h * 0.30f) }, tint, style = stroke)
                drawPath(Path().apply { moveTo(w * 0.70f, h * 0.18f); lineTo(w * 0.86f, h * 0.30f); lineTo(w * 0.70f, h * 0.42f) }, tint, style = stroke)
                drawPath(Path().apply { moveTo(w * 0.70f, h * 0.58f); lineTo(w * 0.86f, h * 0.70f); lineTo(w * 0.70f, h * 0.82f) }, tint, style = stroke)
            }
            LyraIconType.REPEAT_OFF, LyraIconType.REPEAT_ALL, LyraIconType.REPEAT_ONE -> {
                drawPath(Path().apply { moveTo(w * 0.20f, h * 0.46f); lineTo(w * 0.20f, h * 0.36f); cubicTo(w * 0.20f, h * 0.25f, w * 0.28f, h * 0.22f, w * 0.38f, h * 0.22f); lineTo(w * 0.78f, h * 0.22f) }, tint, style = stroke)
                drawPath(Path().apply { moveTo(w * 0.66f, h * 0.12f); lineTo(w * 0.82f, h * 0.22f); lineTo(w * 0.66f, h * 0.32f) }, tint, style = stroke)
                drawPath(Path().apply { moveTo(w * 0.80f, h * 0.54f); lineTo(w * 0.80f, h * 0.64f); cubicTo(w * 0.80f, h * 0.75f, w * 0.72f, h * 0.78f, w * 0.62f, h * 0.78f); lineTo(w * 0.22f, h * 0.78f) }, tint, style = stroke)
                drawPath(Path().apply { moveTo(w * 0.34f, h * 0.68f); lineTo(w * 0.18f, h * 0.78f); lineTo(w * 0.34f, h * 0.88f) }, tint, style = stroke)
                if (type == LyraIconType.REPEAT_ONE) {
                    drawCircle(tint, radius = w * 0.17f, center = Offset(w * 0.50f, h * 0.50f))
                    drawLine(Color(0xFF0B0D0A), Offset(w * 0.50f, h * 0.40f), Offset(w * 0.50f, h * 0.60f), strokeWidth = w * 0.08f, cap = StrokeCap.Round)
                }
            }
            LyraIconType.HEART_FILLED, LyraIconType.HEART_OUTLINE -> {
                val p = Path().apply {
                    moveTo(w * 0.50f, h * 0.82f)
                    cubicTo(w * 0.15f, h * 0.60f, w * 0.12f, h * 0.26f, w * 0.32f, h * 0.20f)
                    cubicTo(w * 0.44f, h * 0.16f, w * 0.50f, h * 0.28f, w * 0.50f, h * 0.32f)
                    cubicTo(w * 0.50f, h * 0.28f, w * 0.56f, h * 0.16f, w * 0.68f, h * 0.20f)
                    cubicTo(w * 0.88f, h * 0.26f, w * 0.85f, h * 0.60f, w * 0.50f, h * 0.82f); close()
                }
                if (type == LyraIconType.HEART_FILLED) drawPath(p, tint) else drawPath(p, tint, style = stroke)
            }
            LyraIconType.EDIT -> drawPath(Path().apply { moveTo(w * 0.20f, h * 0.80f); lineTo(w * 0.34f, h * 0.76f); lineTo(w * 0.78f, h * 0.32f); lineTo(w * 0.68f, h * 0.22f); lineTo(w * 0.24f, h * 0.66f); close() }, tint, style = stroke)
            LyraIconType.PLUS -> {
                drawLine(tint, Offset(w * 0.50f, h * 0.22f), Offset(w * 0.50f, h * 0.78f), w * 0.10f, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.22f, h * 0.50f), Offset(w * 0.78f, h * 0.50f), w * 0.10f, StrokeCap.Round)
            }
            LyraIconType.QUEUE -> {
                drawLine(tint, Offset(w * 0.20f, h * 0.28f), Offset(w * 0.80f, h * 0.28f), w * 0.09f, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.20f, h * 0.50f), Offset(w * 0.80f, h * 0.50f), w * 0.09f, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.20f, h * 0.72f), Offset(w * 0.60f, h * 0.72f), w * 0.09f, StrokeCap.Round)
            }
            LyraIconType.HOME -> drawPath(Path().apply { moveTo(w * 0.16f, h * 0.48f); lineTo(w * 0.50f, h * 0.18f); lineTo(w * 0.84f, h * 0.48f); lineTo(w * 0.74f, h * 0.48f); lineTo(w * 0.74f, h * 0.82f); lineTo(w * 0.26f, h * 0.82f); lineTo(w * 0.26f, h * 0.48f); close() }, tint, style = stroke)
            LyraIconType.MUSIC -> {
                drawLine(tint, Offset(w * 0.38f, h * 0.70f), Offset(w * 0.38f, h * 0.24f), w * 0.09f, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.74f, h * 0.64f), Offset(w * 0.74f, h * 0.18f), w * 0.09f, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.38f, h * 0.24f), Offset(w * 0.74f, h * 0.18f), w * 0.11f, StrokeCap.Round)
                drawCircle(tint, radius = w * 0.12f, center = Offset(w * 0.29f, h * 0.72f))
                drawCircle(tint, radius = w * 0.12f, center = Offset(w * 0.65f, h * 0.66f))
            }
            LyraIconType.LYRICS -> {
                drawRoundRect(tint, Offset(w * 0.36f, h * 0.16f), Size(w * 0.28f, h * 0.42f), CornerRadius(w * 0.14f), style = stroke)
                drawPath(Path().apply { moveTo(w * 0.24f, h * 0.44f); cubicTo(w * 0.24f, h * 0.72f, w * 0.76f, h * 0.72f, w * 0.76f, h * 0.44f) }, tint, style = stroke)
                drawLine(tint, Offset(w * 0.50f, h * 0.68f), Offset(w * 0.50f, h * 0.84f), w * 0.09f, StrokeCap.Round)
            }
            LyraIconType.SETTINGS -> {
                drawCircle(tint, radius = w * 0.22f, center = Offset(w * 0.50f, h * 0.50f), style = stroke)
                drawLine(tint, Offset(w * 0.50f, h * 0.14f), Offset(w * 0.50f, h * 0.24f), w * 0.10f, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.50f, h * 0.76f), Offset(w * 0.50f, h * 0.86f), w * 0.10f, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.14f, h * 0.50f), Offset(w * 0.24f, h * 0.50f), w * 0.10f, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.76f, h * 0.50f), Offset(w * 0.86f, h * 0.50f), w * 0.10f, StrokeCap.Round)
            }
            LyraIconType.SEARCH -> {
                drawCircle(tint, radius = w * 0.24f, center = Offset(w * 0.44f, h * 0.44f), style = stroke)
                drawLine(tint, Offset(w * 0.62f, h * 0.62f), Offset(w * 0.82f, h * 0.82f), w * 0.10f, StrokeCap.Round)
            }
        }
    }
}

@Composable
private fun LyraBrandLogo(size: Dp = 42.dp, cornerRadius: Dp = 12.dp, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(size).clip(RoundedCornerShape(cornerRadius))) {
        val w = this.size.width; val h = this.size.height
        drawRect(brush = Brush.linearGradient(listOf(Color(0xFF1E2B14), Color(0xFF0C1009)), Offset.Zero, Offset(w, h)))
        drawCircle(brush = Brush.radialGradient(listOf(Color(0xFF8FE03C).copy(alpha = 0.25f), Color.Transparent), Offset(w * 0.5f, h * 0.5f), w * 0.45f), radius = w * 0.45f, center = Offset(w * 0.5f, h * 0.5f))
        val lyraPath = Path().apply { moveTo(w * 0.28f, h * 0.26f); cubicTo(w * 0.18f, h * 0.45f, w * 0.24f, h * 0.70f, w * 0.40f, h * 0.78f); lineTo(w * 0.60f, h * 0.78f); cubicTo(w * 0.76f, h * 0.70f, w * 0.82f, h * 0.45f, w * 0.72f, h * 0.26f) }
        drawPath(lyraPath, Brush.verticalGradient(listOf(Color(0xFFD9FF9A), Color(0xFF76D229))), style = Stroke(w * 0.065f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawLine(Color(0xFFB8F36B), Offset(w * 0.34f, h * 0.86f), Offset(w * 0.66f, h * 0.86f), w * 0.055f, StrokeCap.Round)
        drawLine(Color(0xFF8FE03C), Offset(w * 0.39f, h * 0.40f), Offset(w * 0.39f, h * 0.66f), w * 0.05f, StrokeCap.Round)
        drawLine(Color(0xFFD9FF9A), Offset(w * 0.50f, h * 0.22f), Offset(w * 0.50f, h * 0.68f), w * 0.055f, StrokeCap.Round)
        drawLine(Color(0xFF8FE03C), Offset(w * 0.61f, h * 0.34f), Offset(w * 0.61f, h * 0.66f), w * 0.05f, StrokeCap.Round)
    }
}

@Composable
private fun SummaryCard(title: String, value: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    ElevatedCard(modifier = modifier.clickable { onClick() }, shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.primary)
            Text(title, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun EmptyState(title: String, message: String) {
    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LyraBrandLogo(size = 54.dp, cornerRadius = 16.dp)
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(message, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SettingRow(title: String, description: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.SemiBold); Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun TrackList(tracks: List<AudioTrack>, currentUri: Uri?, playing: Boolean, favoriteIds: Set<String>, playTracks: (List<AudioTrack>, Int) -> Unit, toggleFavorite: (AudioTrack) -> Unit, onAddToPlaylist: (AudioTrack) -> Unit, onEditTrack: (AudioTrack) -> Unit, showFolder: Boolean, modifier: Modifier = Modifier) {
    if (tracks.isEmpty()) { EmptyState("Tidak ada lagu yang cocok", "Ubah kata kunci pencarian, kurangi filter durasi minimal, atau pindai ulang."); return }
    LazyColumn(modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        items(tracks, key = { it.id }) { song ->
            val isCurrent = song.uri == currentUri; val isFav = song.id in favoriteIds
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (isCurrent) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f) else Color.Transparent).clickable { playTracks(tracks, tracks.indexOfFirst { it.id == song.id }) }.padding(vertical = 8.dp, horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.size(48.dp)) {
                    if (song.artworkUri != null) AsyncImage(model = song.artworkUri, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    else Box(contentAlignment = Alignment.Center) { if (isCurrent && playing) LyraBrandLogo(size = 48.dp, cornerRadius = 12.dp) else LyraVectorIcon(LyraIconType.MUSIC, tint = MaterialTheme.colorScheme.secondary, size = 22.dp) }
                }
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = if (isCurrent) FontWeight.ExtraBold else FontWeight.SemiBold, color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (song.duration > 0L) Text(formatTime(song.duration), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        Text("${song.artist} · ${song.album}", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (showFolder && song.folder.isNotBlank()) Text(song.folder, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                }
                IconButton(onClick = { onEditTrack(song) }, modifier = Modifier.size(34.dp)) { LyraVectorIcon(LyraIconType.EDIT, tint = MaterialTheme.colorScheme.onSurfaceVariant, size = 18.dp) }
                IconButton(onClick = { toggleFavorite(song) }, modifier = Modifier.size(34.dp)) { LyraVectorIcon(if (isFav) LyraIconType.HEART_FILLED else LyraIconType.HEART_OUTLINE, tint = if (isFav) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, size = 18.dp) }
                IconButton(onClick = { onAddToPlaylist(song) }, modifier = Modifier.size(34.dp)) { LyraVectorIcon(LyraIconType.PLUS, tint = MaterialTheme.colorScheme.onSurfaceVariant, size = 18.dp) }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
        }
    }
}

@Composable
private fun KaraokeWordText(line: KaraokeLine, positionMs: Long, isCurrentLine: Boolean, fontSizeSp: Int, textAlign: TextAlign = TextAlign.Center, modifier: Modifier = Modifier) {
    val activeColor = Color(0xFFB8F36B); val sungColor = Color.White
    val unsungColor = if (isCurrentLine) Color.White.copy(alpha = 0.42f) else Color.White.copy(alpha = 0.25f)
    if (!isCurrentLine || line.words.isEmpty()) {
        Text(line.plainText.ifBlank { "•••" }, fontSize = fontSizeSp.sp, fontWeight = if (isCurrentLine) FontWeight.ExtraBold else FontWeight.SemiBold, color = if (isCurrentLine) activeColor else unsungColor, textAlign = textAlign, lineHeight = (fontSizeSp + 7).sp, modifier = modifier)
        return
    }
    val annotated = buildAnnotatedString {
        for (word in line.words) {
            when {
                positionMs >= word.endMs -> withStyle(SpanStyle(color = sungColor, fontWeight = FontWeight.ExtraBold)) { append(word.text) }
                positionMs in word.startMs until word.endMs -> {
                    val dur = (word.endMs - word.startMs).coerceAtLeast(1L)
                    val splitIdx = (word.text.length * ((positionMs - word.startMs).toFloat() / dur.toFloat()).coerceIn(0f, 1f)).toInt().coerceIn(0, word.text.length)
                    withStyle(SpanStyle(color = activeColor, fontWeight = FontWeight.Black)) { append(word.text.substring(0, splitIdx)) }
                    withStyle(SpanStyle(color = activeColor.copy(alpha = 0.65f), fontWeight = FontWeight.ExtraBold)) { append(word.text.substring(splitIdx)) }
                }
                else -> withStyle(SpanStyle(color = unsungColor, fontWeight = FontWeight.Bold)) { append(word.text) }
            }
        }
    }
    Text(annotated, fontSize = fontSizeSp.sp, textAlign = textAlign, lineHeight = (fontSizeSp + 7).sp, modifier = modifier)
}

@Composable
private fun KaraokeMiniStage(lines: List<KaraokeLine>, positionMs: Long, hasEnhancedTag: Boolean, autoWordKaraoke: Boolean, onExpandClick: () -> Unit, onOpenSearchClick: () -> Unit) {
    val activeIdx = remember(lines, positionMs) { lines.indexOfLast { it.startMs <= positionMs }.coerceAtLeast(0) }
    val currentLine = lines.getOrNull(activeIdx); val nextLine = lines.getOrNull(activeIdx + 1)
    ElevatedCard(shape = RoundedCornerShape(22.dp), colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.fillMaxWidth().clickable { if (lines.isEmpty()) onOpenSearchClick() else onExpandClick() }.padding(horizontal = 16.dp, vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(when { hasEnhancedTag -> "KARAOKE ENHANCED LRC ★"; autoWordKaraoke -> "AUTO-WORD KARAOKE"; else -> "SYNCED LRC (PER BARIS)" }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Black)
                Text(if (lines.isEmpty()) "Cari Lirik ⌕" else "Layar Penuh ↗", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(4.dp))
            if (currentLine == null) Text("Lirik belum tersedia — ketuk untuk mencari lirik online", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 6.dp))
            else {
                KaraokeWordText(currentLine, positionMs, true, 17, TextAlign.Center, Modifier.fillMaxWidth())
                if (nextLine != null) { Spacer(Modifier.height(3.dp)); Text(nextLine.plainText.ifBlank { "•••" }, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.45f), maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center) }
            }
        }
    }
}

@Composable
private fun KaraokeFullStage(lines: List<KaraokeLine>, positionMs: Long, hasEnhancedTag: Boolean, autoWordKaraoke: Boolean, onToggleAutoWord: () -> Unit, onSeekTo: (Long) -> Unit, onEditLyricsClick: () -> Unit, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    val activeIdx = remember(lines, positionMs) { lines.indexOfLast { it.startMs <= positionMs }.coerceAtLeast(0) }
    LaunchedEffect(activeIdx, lines.size) { if (lines.isNotEmpty() && activeIdx in lines.indices) listState.animateScrollToItem(index = activeIdx, scrollOffset = -170) }
    Box(modifier.clip(RoundedCornerShape(26.dp)).background(Brush.verticalGradient(listOf(Color(0xFF1F2A16), Color(0xFF12170E), Color(0xFF0D0F0B)))).border(1.dp, Color.White.copy(alpha = 0.07f), RoundedCornerShape(26.dp)).padding(horizontal = 14.dp, vertical = 10.dp)) {
        if (lines.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                LyraBrandLogo(size = 64.dp, cornerRadius = 18.dp); Spacer(Modifier.height(12.dp))
                Text("Lirik Belum Tersedia", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.White)
                Spacer(Modifier.height(10.dp))
                Button(onClick = onEditLyricsClick, shape = RoundedCornerShape(16.dp)) { Text("⌕ Cari Lirik Online Sekarang") }
            }
        } else {
            LazyColumn(state = listState, contentPadding = PaddingValues(vertical = 85.dp), verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxSize()) {
                itemsIndexed(lines) { index, line ->
                    val isCurrent = index == activeIdx; val distance = abs(index - activeIdx)
                    val scale by animateFloatAsState(if (isCurrent) 1.03f else 0.95f, tween(220), label = "kS")
                    val alphaVal by animateFloatAsState(when { isCurrent -> 1f; distance == 1 -> 0.60f; distance == 2 -> 0.36f; else -> 0.20f }, tween(220), label = "kA")
                    Column(Modifier.fillMaxWidth().graphicsLayer { scaleX = scale; scaleY = scale }.alpha(alphaVal).clip(RoundedCornerShape(18.dp)).background(if (isCurrent) Color.White.copy(alpha = 0.08f) else Color.Transparent).clickable { onSeekTo(line.startMs) }.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                            Text(formatTime(line.startMs), style = MaterialTheme.typography.labelSmall, color = if (isCurrent) Color(0xFFB8F36B) else Color.White.copy(alpha = 0.35f))
                            if (isCurrent) Text("● AKTIF", style = MaterialTheme.typography.labelSmall, fontSize = 9.sp, color = Color(0xFFB8F36B), fontWeight = FontWeight.Black)
                        }
                        Spacer(Modifier.height(4.dp))
                        KaraokeWordText(line, positionMs, isCurrent, if (isCurrent) 21 else 16, TextAlign.Start, Modifier.fillMaxWidth())
                        if (isCurrent && line.words.isNotEmpty()) {
                            val lineProg = ((positionMs - line.startMs).toFloat() / (line.endMs - line.startMs).coerceAtLeast(1L).toFloat()).coerceIn(0f, 1f)
                            LinearProgressIndicator(progress = { lineProg }, modifier = Modifier.fillMaxWidth(0.6f).padding(top = 8.dp).height(3.dp).clip(RoundedCornerShape(2.dp)), color = Color(0xFFB8F36B), trackColor = Color.White.copy(alpha = 0.15f))
                        }
                    }
                }
            }
            Surface(color = Color.Black.copy(alpha = 0.72f), shape = RoundedCornerShape(12.dp), modifier = Modifier.align(Alignment.TopEnd).clickable { onToggleAutoWord() }) {
                Text(when { hasEnhancedTag -> "ENHANCED LRC ★"; autoWordKaraoke -> "AUTO-WORD: ON"; else -> "AUTO-WORD: OFF" }, style = MaterialTheme.typography.labelSmall, color = if (hasEnhancedTag || autoWordKaraoke) Color(0xFFB8F36B) else Color.LightGray, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
            }
        }
    }
}

@Composable
private fun EqualizerPanel(audioSessionId: Int) {
    var enabled by remember(audioSessionId) { mutableStateOf(false) }
    var bandValues by remember(audioSessionId) { mutableStateOf<List<Float>>(listOf(0f, 0f, 0f, 0f, 0f)) }
    var range by remember(audioSessionId) { mutableFloatStateOf(1000f) }
    var labels by remember(audioSessionId) { mutableStateOf<List<String>>(listOf("60 Hz", "230 Hz", "910 Hz", "3.6 kHz", "14 kHz")) }
    var bass by remember(audioSessionId) { mutableFloatStateOf(0f) }
    val eq = remember(audioSessionId) { if (audioSessionId > 0) runCatching { Equalizer(0, audioSessionId) }.getOrNull() else null }
    val bassBoost = remember(audioSessionId) { if (audioSessionId > 0) runCatching { BassBoost(0, audioSessionId) }.getOrNull() else null }
    DisposableEffect(eq, bassBoost) { onDispose { runCatching { eq?.release() }; runCatching { bassBoost?.release() } } }
    LaunchedEffect(eq) {
        if (eq != null) runCatching {
            val r = eq.bandLevelRange; range = maxOf(abs(r[0].toFloat()), abs(r[1].toFloat()))
            val count = eq.numberOfBands.toInt().coerceAtMost(10)
            bandValues = (0 until count).map { eq.getBandLevel(it.toShort()).toFloat() }
            labels = (0 until count).map { b -> val hz = eq.getCenterFreq(b.toShort()) / 1000; if (hz >= 1000) "${hz / 1000f} kHz" else "$hz Hz" }
            enabled = eq.enabled
        }
    }
    if (audioSessionId <= 0 || eq == null) { Text("Mulai putar lagu untuk mengaktifkan equalizer.", style = MaterialTheme.typography.bodySmall); return }
    Row(verticalAlignment = Alignment.CenterVertically) { Text("Equalizer", Modifier.weight(1f), fontWeight = FontWeight.SemiBold); Switch(checked = enabled, onCheckedChange = { v -> enabled = v; runCatching { eq.enabled = v } }) }
    bandValues.forEachIndexed { idx, value ->
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) { Text(labels.getOrElse(idx) { "Band ${idx + 1}" }, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall); Text("${value.toInt()} mB", style = MaterialTheme.typography.labelSmall) }
            Slider(value = value, onValueChange = { next -> bandValues = bandValues.toMutableList().also { it[idx] = next }; runCatching { eq.setBandLevel(idx.toShort(), next.toInt().toShort()) } }, valueRange = -range..range, enabled = enabled)
        }
    }
    if (bassBoost != null) {
        Row(verticalAlignment = Alignment.CenterVertically) { Text("Bass boost", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium); Text("${bass.toInt()}%", style = MaterialTheme.typography.labelSmall) }
        Slider(value = bass, onValueChange = { next -> bass = next; runCatching { bassBoost.setStrength((next * 10).toInt().toShort()); bassBoost.enabled = next > 0f } }, valueRange = 0f..100f)
    }
    TextButton(onClick = { bandValues = List(bandValues.size) { 0f }; bandValues.forEachIndexed { i, _ -> runCatching { eq.setBandLevel(i.toShort(), 0.toShort()) } }; bass = 0f; runCatching { bassBoost?.setStrength(0.toShort()); bassBoost?.let { it.enabled = false } } }) { Text("Reset audio") }
}

@Composable
private fun AudioVisualizer(audioSessionId: Int, isPlaying: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var hasPermission by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasPermission = it }
    val binCount = 48
    var spectrum by remember { mutableStateOf<List<Float>>(List(binCount) { 0.06f }) }
    var captureError by remember { mutableStateOf(false) }
    var hasActiveSignal by remember { mutableStateOf(false) }

    DisposableEffect(audioSessionId, isPlaying, hasPermission) {
        var visualizer: Visualizer? = null
        val mainHandler = Handler(Looper.getMainLooper())
        if (isPlaying && hasPermission) {
            try {
                val capture = try { if (audioSessionId > 0) Visualizer(audioSessionId) else Visualizer(0) } catch (_: Exception) { Visualizer(0) }
                capture.enabled = false
                val range = Visualizer.getCaptureSizeRange()
                capture.captureSize = range[1].coerceIn(minOf(256, range[0]), 1024)
                capture.setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(v: Visualizer?, waveform: ByteArray?, samplingRate: Int) {
                        if (waveform == null || waveform.isEmpty() || hasActiveSignal) return
                        val values = MutableList(binCount) { 0.05f }
                        val step = (waveform.size / binCount).coerceAtLeast(1)
                        var maxAmp = 0f
                        for (i in 0 until binCount) {
                            val amp = abs((waveform[(i * step).coerceIn(0, waveform.size - 1)].toInt() and 0xFF) - 128) / 128f
                            if (amp > maxAmp) maxAmp = amp; values[i] = amp.coerceIn(0.05f, 1f)
                        }
                        if (maxAmp > 0.02f) mainHandler.post { spectrum = spectrum.mapIndexed { idx, old -> (old * 0.35f + values[idx] * 0.65f).coerceIn(0.05f, 1f) } }
                    }
                    override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, samplingRate: Int) {
                        if (fft == null || fft.size < 8) return
                        val values = MutableList(binCount) { 0.04f }; val maxBin = (fft.size / 2).coerceAtLeast(2); var maxPeak = 0.0
                        for (i in 0 until binCount) {
                            val low = (1.0 * maxBin.toDouble().pow(i.toDouble() / binCount)).toInt().coerceIn(1, maxBin - 1)
                            val high = (1.0 * maxBin.toDouble().pow((i + 1).toDouble() / binCount)).toInt().coerceIn(low + 1, maxBin)
                            var peak = 0.0
                            for (bin in low until high) { val r = fft[2 * bin].toInt(); val im = fft[2 * bin + 1].toInt(); val mag = sqrt((r * r + im * im).toDouble()); if (mag > peak) peak = mag }
                            if (peak > maxPeak) maxPeak = peak
                            values[i] = (ln(1.0 + peak) / ln(1.0 + 150.0)).toFloat().coerceIn(0.04f, 1f)
                        }
                        hasActiveSignal = maxPeak > 1.5
                        if (hasActiveSignal) mainHandler.post { spectrum = spectrum.mapIndexed { idx, old -> (old * 0.28f + values[idx] * 0.72f).coerceIn(0.04f, 1f) } }
                    }
                }, Visualizer.getMaxCaptureRate() / 2, true, true)
                capture.enabled = true; visualizer = capture; captureError = false
            } catch (_: Exception) { captureError = true; runCatching { visualizer?.release() }; visualizer = null }
        } else { spectrum = List(binCount) { 0.04f }; captureError = false; hasActiveSignal = false }
        onDispose { runCatching { visualizer?.enabled = false }; runCatching { visualizer?.release() }; mainHandler.removeCallbacksAndMessages(null) }
    }

    val infinite = rememberInfiniteTransition(label = "vis")
    val phase by infinite.animateFloat(0f, (2.0 * Math.PI).toFloat(), infiniteRepeatable(tween(850, easing = LinearEasing)), label = "ph")
    val useFallback = !hasPermission || captureError || !hasActiveSignal
    val renderSpectrum = if (useFallback && isPlaying) List(binCount) { i -> (0.08f + 0.78f * abs(sin(phase + i * 0.42f)) * (0.35f + 0.65f * abs(sin(phase * 0.63f + i * 0.13f)))).coerceIn(0.05f, 0.95f) }
    else if (!isPlaying) List(binCount) { i -> 0.04f + 0.02f * abs(sin(i * 0.3f)) } else spectrum

    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("LIVE AUDIO SPECTRUM", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            if (!hasPermission) Text("Izinkan mikrofon (FFT) →", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) })
            else Text(when { !isPlaying -> "SIAP SAAT MUSIK DIPUTAR"; hasActiveSignal && !captureError -> "REAL-TIME FFT + WAVE"; else -> "DYNAMIC PULSE" }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Canvas(Modifier.fillMaxWidth().height(48.dp)) {
            val count = renderSpectrum.size; val gap = 2.4f; val barWidth = ((size.width - gap * (count - 1)) / count).coerceAtLeast(1.5f)
            for (i in 0 until count) {
                val h = size.height * renderSpectrum[i].coerceIn(0.04f, 1f)
                drawRoundRect(Brush.verticalGradient(listOf(Color(0xFFD9FF9A), Color(0xFF8FE03C), Color(0xFF315B18))), Offset(i * (barWidth + gap), size.height - h), Size(barWidth, h), CornerRadius(barWidth / 2f, barWidth / 2f))
            }
        }
    }
}

// === 4. MAIN ACTIVITY & ROOT APP ===
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
    var metadataOverrides by remember { mutableStateOf<Map<String, MetadataOverride>>(loadMetadataOverrides(prefs.getString("metadata_overrides_json", "{}").orEmpty())) }
    var search by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Siap memindai koleksi musik.") }

    var tab by remember { mutableStateOf(LyraTab.HOME) }
    var librarySubTab by remember { mutableStateOf(LibrarySubTab.ALL) }
    var lyricsSubTab by remember { mutableStateOf(LyricsSubTab.PREVIEW) }
    var isSearchingLyrics by remember { mutableStateOf(false) }

    var minDurationSec by remember { mutableIntStateOf(prefs.getInt("min_duration_sec", 30)) }
    var sortOption by remember { mutableStateOf(runCatching { SortOption.valueOf(prefs.getString("sort_option", SortOption.TITLE_ASC.name).orEmpty()) }.getOrDefault(SortOption.TITLE_ASC)) }
    var autoWordKaraoke by remember { mutableStateOf(prefs.getBoolean("auto_word_karaoke", true)) }
    var playbackSpeed by remember { mutableFloatStateOf(1.0f) }

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
    var favoriteIds by remember { mutableStateOf<Set<String>>(prefs.getStringSet("favorites", emptySet())?.toSet() ?: emptySet()) }
    var playlists by remember { mutableStateOf<List<LyraPlaylist>>(loadPlaylists(prefs.getString("playlists_json", "[]").orEmpty())) }
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
    var lastPlayed by remember { mutableStateOf<List<String>>(prefs.getString("recent_ids", "").orEmpty().split("|").filter { it.isNotBlank() }) }
    var playCounts by remember { mutableStateOf<Map<String, Int>>(loadCounts(prefs.getString("play_counts_json", "{}").orEmpty())) }

    var showMetadataDialog by remember { mutableStateOf(false) }
    var editingTrackTarget by remember { mutableStateOf<AudioTrack?>(null) }
    var editTitle by remember { mutableStateOf("") }
    var editArtist by remember { mutableStateOf("") }
    var editAlbum by remember { mutableStateOf("") }
    var editArtworkUri by remember { mutableStateOf("") }
    var showFullKaraokeInPlayer by remember { mutableStateOf(false) }

    val parsedKaraokeLines = remember(lrcText, autoWordKaraoke) { EnhancedLrcEngine.parse(lrcText, autoWordKaraoke) }
    val hasEnhancedKaraoke = remember(parsedKaraokeLines) { parsedKaraokeLines.any { it.isEnhanced } }

    fun permissionName() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

    fun saveLrcToCache(lyrics: String, title: String = currentTitle, artist: String = currentArtist) {
        val key = lyricsCacheKey(title, artist)
        val cache = runCatching { JSONObject(prefs.getString("lyrics_cache_json", "{}").orEmpty()) }.getOrElse { JSONObject() }
        cache.put(key, lyrics)
        while (cache.length() > 180) { val fk = cache.keys().asSequence().firstOrNull() ?: break; cache.remove(fk) }
        prefs.edit().putString("current_lrc", lyrics).putString("current_lrc_key", key).putString("lyrics_cache_json", cache.toString()).apply()
    }

    fun triggerManualLyricsSearch(queryTitle: String, queryArtist: String) {
        if (queryTitle.isBlank()) return
        scope.launch {
            isSearchingLyrics = true
            val (cTitle, cArtist) = cleanSearchQuery(queryTitle, queryArtist)
            lyricsStatus = "Mencari \"$cTitle\" ${if (cArtist.isNotBlank()) "oleh $cArtist" else ""}..."
            try {
                val res = smartLyricsSearch(queryTitle, queryArtist)
                results = res
                lyricsStatus = if (res.isEmpty()) "Tidak ditemukan hasil untuk \"$cTitle\"." else "Ditemukan ${res.size} lirik."
            } catch (e: Exception) { lyricsStatus = "Gagal mencari lirik: ${e.message ?: "periksa internet"}" }
            finally { isSearchingLyrics = false }
        }
    }

    fun applyTrackOverride(raw: AudioTrack, overrides: Map<String, MetadataOverride> = metadataOverrides): AudioTrack {
        val ov = overrides[raw.id] ?: overrides[raw.uri.toString()] ?: return raw
        val customArt = ov.artworkUri.takeIf { it.isNotBlank() }?.let { runCatching { Uri.parse(it) }.getOrNull() } ?: raw.artworkUri
        return raw.copy(title = ov.title.ifBlank { raw.title }, artist = ov.artist.ifBlank { raw.artist }, album = ov.album.ifBlank { raw.album }, artworkUri = customArt)
    }

    fun openMetadataEditor(track: AudioTrack?) {
        editingTrackTarget = track; editTitle = track?.title ?: currentTitle; editArtist = track?.artist ?: currentArtist
        editAlbum = track?.album ?: currentAlbum; editArtworkUri = track?.artworkUri?.toString().orEmpty(); showMetadataDialog = true
    }

    fun scanSongs() {
        try {
            val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            val projection = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.DATA, MediaStore.Audio.Media.ALBUM_ID)
            val found = linkedMapOf<String, AudioTrack>()
            context.contentResolver.query(collection, projection, "${MediaStore.Audio.Media.IS_MUSIC} != 0", null, "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC")?.use { cursor: Cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID); val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST); val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION); val dataCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATA); val albumIdCol = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM_ID)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol).toString()
                    val title = cursor.getString(titleCol)?.takeIf { it.isNotBlank() } ?: "Judul tidak diketahui"
                    val artist = cursor.getString(artistCol)?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: "Artis tidak diketahui"
                    val album = cursor.getString(albumCol)?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: "Album tidak diketahui"
                    val path = if (dataCol >= 0) cursor.getString(dataCol).orEmpty() else ""
                    val albumId = if (albumIdCol >= 0) cursor.getLong(albumIdCol) else 0L
                    val art = if (albumId > 0L) ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), albumId) else null
                    found["media:$id"] = applyTrackOverride(AudioTrack("media:$id", Uri.withAppendedPath(collection, id), title, artist, album, cursor.getLong(durationCol), path.substringBeforeLast("/", ""), artworkUri = art))
                }
            }
            if (selectedFolder.isNotBlank()) {
                runCatching {
                    val tree = DocumentFile.fromTreeUri(context, Uri.parse(selectedFolder))
                    fun visit(folder: DocumentFile, depth: Int) {
                        if (depth > 12) return
                        for (file in folder.listFiles()) {
                            if (file.isDirectory) visit(file, depth + 1)
                            else if (file.isFile && file.name.orEmpty().substringAfterLast('.', "").lowercase(Locale.ROOT) in setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "opus", "3gp", "mid", "amr")) {
                                val id = "saf:${file.uri}"; val title = file.name.orEmpty().substringBeforeLast('.').ifBlank { "Audio tanpa judul" }
                                if (!found.containsKey(id)) found[id] = applyTrackOverride(AudioTrack(id, file.uri, title, "Artis tidak diketahui", "Folder pilihan", 0L, file.parentFile?.name.orEmpty(), true))
                            }
                        }
                    }
                    if (tree != null) visit(tree, 0)
                }
            }
            songs = found.values.toList()
            status = if (songs.isEmpty()) "Belum ada lagu ditemukan." else "${songs.size} file audio terdeteksi."
        } catch (e: Exception) { status = "Gagal membaca musik: ${e.message}" }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) scanSongs() else status = "Izin audio diperlukan." }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            selectedFolder = uri.toString(); prefs.edit().putString("music_folder_uri", selectedFolder).apply(); scanSongs()
        }
    }
    val artworkPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { runCatching { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }; editArtworkUri = uri.toString() }
    }
    val lrcPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) try {
            lrcText = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty()
            saveLrcToCache(lrcText); lyricsStatus = "Berkas LRC lokal dimuat."; lyricsSubTab = LyricsSubTab.PREVIEW
        } catch (e: Exception) { lyricsStatus = "Gagal membuka LRC: ${e.message}" }
    }
    val saveLrcLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) try { context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(lrcText) }; lyricsStatus = "Berkas LRC diekspor." } catch (e: Exception) { lyricsStatus = "Gagal ekspor: ${e.message}" }
    }
    val backupExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) try {
            val backup = JSONObject().put("format", "lyra-backup-v1").put("favorites", JSONArray(favoriteIds.toList())).put("recent_ids", JSONArray(lastPlayed)).put("play_counts", serializeCounts(playCounts)).put("playlists", serializePlaylists(playlists)).put("dark_mode", darkMode).put("smart_shuffle", smartShuffle)
            context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(backup.toString(2)) }; status = "Backup Lyra diekspor."
        } catch (e: Exception) { status = "Gagal ekspor backup: ${e.message}" }
    }
    val backupImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) try {
            val root = JSONObject(context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty())
            require(root.optString("format") == "lyra-backup-v1") { "Format tidak dikenal" }
            favoriteIds = root.optJSONArray("favorites").toStringList().toSet(); lastPlayed = root.optJSONArray("recent_ids").toStringList()
            playCounts = loadCounts(root.optJSONObject("play_counts")?.toString() ?: "{}"); playlists = loadPlaylists(root.optJSONArray("playlists")?.toString() ?: "[]")
            darkMode = root.optBoolean("dark_mode", true); smartShuffle = root.optBoolean("smart_shuffle", true)
            prefs.edit().putStringSet("favorites", favoriteIds).putString("recent_ids", lastPlayed.joinToString("|")).putString("play_counts_json", serializeCounts(playCounts).toString()).putString("playlists_json", serializePlaylists(playlists).toString()).putBoolean("dark_mode", darkMode).putBoolean("smart_shuffle", smartShuffle).apply()
            status = "Backup berhasil dipulihkan."
        } catch (e: Exception) { status = "Backup gagal: ${e.message}" }
    }
    val m3uExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/x-mpegurl")) { uri ->
        if (uri != null) try {
            val activeName = selectedPlaylist
            val listNow: List<AudioTrack> = when (activeName) { null -> songs; "Favorit" -> favoriteIds.mapNotNull { id -> songs.firstOrNull { it.id == id } }; else -> playlists.firstOrNull { p -> p.name == activeName }?.trackIds?.mapNotNull { id -> songs.firstOrNull { s -> s.id == id } } ?: songs }
            val content = buildString { appendLine("#EXTM3U"); for (t in listNow) { appendLine("#EXTINF:${(t.duration / 1000).toInt()},${t.artist} - ${t.title}"); appendLine(t.uri.toString()) } }
            context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(content) }; status = "M3U diekspor."
        } catch (e: Exception) { status = "Gagal ekspor M3U: ${e.message}" }
    }
    val m3uImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) try {
            val lines = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readLines() }.orEmpty()
            val resolved = lines.filter { it.isNotBlank() && !it.startsWith("#") }.mapNotNull { entry -> songs.firstOrNull { it.uri.toString() == entry.trim() } ?: songs.firstOrNull { it.title.equals(entry.substringAfterLast('/').substringBeforeLast('.'), true) } }.distinctBy { it.id }
            if (resolved.isNotEmpty()) {
                val name = "Impor M3U ${playlists.size + 1}"
                playlists = playlists + listOf(LyraPlaylist(name, resolved.map { it.id }))
                prefs.edit().putString("playlists_json", serializePlaylists(playlists).toString()).apply(); status = "Playlist $name dibuat."
            } else status = "Tidak ada lagu yang cocok."
        } catch (e: Exception) { status = "Gagal impor M3U: ${e.message}" }
    }

    fun savePlaylists() { prefs.edit().putString("playlists_json", serializePlaylists(playlists).toString()).apply() }
    fun toggleFavorite(track: AudioTrack) {
        favoriteIds = if (track.id in favoriteIds) favoriteIds - track.id else favoriteIds + track.id
        prefs.edit().putStringSet("favorites", favoriteIds).apply()
    }

    fun playTracks(list: List<AudioTrack>, index: Int) {
        val c = controller ?: run { status = "Pemutar sedang disiapkan."; return }
        if (list.isEmpty() || index !in list.indices) return
        val items = list.map { s -> MediaItem.Builder().setUri(s.uri).setMediaId(s.id).setMediaMetadata(MediaMetadata.Builder().setTitle(s.title).setArtist(s.artist).setAlbumTitle(s.album).setArtworkUri(s.artworkUri).build()).build() }
        c.setMediaItems(items, index, 0L); c.prepare(); c.play()
        val tr = list[index]
        currentTitle = tr.title; currentArtist = tr.artist; currentAlbum = tr.album; currentUri = tr.uri
        val (cleanT, cleanA) = cleanSearchQuery(tr.title, tr.artist)
        searchTrack = cleanT; searchArtist = cleanA
        val newRecent = (listOf(tr.id) + lastPlayed.filterNot { it == tr.id }).take(60); lastPlayed = newRecent
        val updatedCounts = playCounts.toMutableMap().also { it[tr.id] = (it[tr.id] ?: 0) + 1 }; playCounts = updatedCounts
        prefs.edit().putString("current_title", tr.title).putString("current_artist", tr.artist).putString("current_album", tr.album).putString("recent_ids", newRecent.joinToString("|")).putString("play_counts_json", serializeCounts(updatedCounts).toString()).apply()
        status = "Memutar: ${tr.title}"
    }

    DisposableEffect(context) {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future: ListenableFuture<MediaController> = MediaController.Builder(context, token).buildAsync()
        future.addListener({ try { controller = future.get() } catch (_: Exception) {} }, context.mainExecutor)
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
            playing = c.isPlaying; audioSessionId = prefs.getInt("audio_session_id", 0)
            position = c.currentPosition.coerceAtLeast(0L)
            duration = c.duration.takeIf { it > 0 && it != androidx.media3.common.C.TIME_UNSET } ?: 0L
            shuffle = c.shuffleModeEnabled; repeatMode = c.repeatMode
            val activeUri = c.currentMediaItem?.localConfiguration?.uri; currentUri = activeUri
            val activeMediaId = c.currentMediaItem?.mediaId.orEmpty()
            val ov = metadataOverrides[activeMediaId] ?: activeUri?.toString()?.let { metadataOverrides[it] }
            currentTitle = ov?.title?.takeIf { it.isNotBlank() } ?: c.mediaMetadata.title?.toString()?.takeIf { it.isNotBlank() } ?: currentTitle
            currentArtist = ov?.artist?.takeIf { it.isNotBlank() } ?: c.mediaMetadata.artist?.toString()?.takeIf { it.isNotBlank() } ?: currentArtist
            currentAlbum = ov?.album?.takeIf { it.isNotBlank() } ?: c.mediaMetadata.albumTitle?.toString() ?: currentAlbum
            currentLyric = EnhancedLrcEngine.stripEnhancedTags(LrcParser.displayLine(lrcText, position))
            val now = System.currentTimeMillis()
            if (now - lastWidgetSync > 450L) { lastWidgetSync = now; LyricsWidgetProvider.updateAll(context, currentTitle, currentLyric) }
            delay(45)
        }
    }

    LaunchedEffect(currentTitle, currentArtist, autoLyrics) {
        val rawT = currentTitle.trim(); val rawA = currentArtist.trim()
        if (rawT.isBlank() || rawT == "Belum ada lagu" || rawT == "Judul tidak diketahui") return@LaunchedEffect
        val (cleanT, cleanA) = cleanSearchQuery(rawT, rawA)
        if (searchTrack.isBlank()) { searchTrack = cleanT; searchArtist = cleanA }
        val key = lyricsCacheKey(rawT, rawA)
        if (prefs.getString("current_lrc_key", "") != key) { lrcText = ""; currentLyric = ""; prefs.edit().putString("current_lrc", "").apply() }
        val cache = runCatching { JSONObject(prefs.getString("lyrics_cache_json", "{}").orEmpty()) }.getOrElse { JSONObject() }
        val cached = cache.optString(key, "").takeIf { it.isNotBlank() && it != "null" }
        if (cached != null) { lrcText = cached; prefs.edit().putString("current_lrc", cached).putString("current_lrc_key", key).apply(); lyricsStatus = "Lirik dimuat dari cache lokal"; return@LaunchedEffect }
        if (!autoLyrics) { lyricsStatus = "Lirik otomatis nonaktif."; return@LaunchedEffect }
        isSearchingLyrics = true; lyricsStatus = "Mencari lirik otomatis: $cleanT..."
        try {
            val candidates = smartLyricsSearch(rawT, rawA)
            val found = candidates.firstOrNull()
            if (found != null && lyricsCacheKey(currentTitle, currentArtist) == key) {
                val lyrics = found.syncedLyrics ?: found.plainLyrics.orEmpty()
                lrcText = lyrics; results = candidates; saveLrcToCache(lyrics, rawT, rawA)
                lyricsStatus = if (!found.syncedLyrics.isNullOrBlank()) "Lirik tersinkron otomatis ditemukan" else "Lirik teks otomatis ditemukan"
            } else if (lyricsCacheKey(currentTitle, currentArtist) == key) lyricsStatus = "Lirik belum ditemukan otomatis. Ketuk tab 'Cari Online'."
        } catch (e: Exception) { if (lyricsCacheKey(currentTitle, currentArtist) == key) lyricsStatus = "Pencarian gagal: ${e.message}" }
        finally { isSearchingLyrics = false }
    }

    LaunchedEffect(sleepMinutes) {
        if (sleepMinutes > 0) { delay(sleepMinutes * 60_000L); controller?.pause(); sleepMinutes = 0; status = "Timer tidur selesai." }
    }

    val durationFilteredSongs = remember(songs, minDurationSec) {
        val minMs = minDurationSec * 1000L
        songs.filter { it.isSaf || it.duration == 0L || it.duration >= minMs }
    }
    val sortedAndFilteredSongs = remember(durationFilteredSongs, search, sortOption, playCounts) {
        val base = if (search.isBlank()) durationFilteredSongs else durationFilteredSongs.filter { it.title.contains(search, true) || it.artist.contains(search, true) || it.album.contains(search, true) || it.folder.contains(search, true) }
        when (sortOption) {
            SortOption.TITLE_ASC -> base.sortedBy { it.title.lowercase(Locale.ROOT) }
            SortOption.TITLE_DESC -> base.sortedByDescending { it.title.lowercase(Locale.ROOT) }
            SortOption.ARTIST_ASC -> base.sortedBy { it.artist.lowercase(Locale.ROOT) }
            SortOption.DURATION_DESC -> base.sortedByDescending { it.duration }
            SortOption.DURATION_ASC -> base.sortedBy { it.duration }
            SortOption.MOST_PLAYED -> base.sortedByDescending { playCounts[it.id] ?: 0 }
        }
    }

    val currentTrack = songs.firstOrNull { it.uri == currentUri }
    val recentTracks = lastPlayed.mapNotNull { id -> durationFilteredSongs.firstOrNull { it.id == id } }
    val favoriteTracks = sortedAndFilteredSongs.filter { it.id in favoriteIds }
    val playlistTracks: List<AudioTrack> = if (selectedPlaylist == "Favorit") favoriteTracks else {
        val activeName = selectedPlaylist
        if (activeName != null) playlists.firstOrNull { p -> p.name == activeName }?.trackIds?.mapNotNull { id -> songs.firstOrNull { s -> s.id == id } }.orEmpty() else emptyList()
    }

    val accent = Color(0xFFB8F36B)
    val colors = if (darkMode) darkColorScheme(primary = accent, secondary = accent, background = Color(0xFF0B0D0A), surface = Color(0xFF161914), surfaceVariant = Color(0xFF232820))
    else lightColorScheme(primary = Color(0xFF4D6E16), secondary = Color(0xFF4D6E16))

    MaterialTheme(colorScheme = colors) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            LyraBrandLogo(size = 38.dp, cornerRadius = 11.dp)
                            Column { Text("LYRA", fontWeight = FontWeight.Black); Text("KARAOKE · ENHANCED LRC · FFT", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }
                        }
                    },
                    actions = {
                        IconButton(onClick = { librarySubTab = LibrarySubTab.ALL; tab = LyraTab.LIBRARY }) { LyraVectorIcon(LyraIconType.SEARCH, tint = MaterialTheme.colorScheme.primary, size = 20.dp) }
                        TextButton(onClick = { if (ContextCompat.checkSelfPermission(context, permissionName()) == PackageManager.PERMISSION_GRANTED) scanSongs() else permissionLauncher.launch(permissionName()) }) { Text("Pindai") }
                    }
                )
            },
            bottomBar = {
                Column {
                    if ((currentUri != null || playing || currentTitle != "Belum ada lagu") && tab != LyraTab.NOW_PLAYING) {
                        Surface(color = MaterialTheme.colorScheme.surfaceVariant, tonalElevation = 8.dp, shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)) {
                            Column(Modifier.fillMaxWidth().clickable { tab = LyraTab.NOW_PLAYING }.padding(horizontal = 14.dp, vertical = 8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (currentTrack?.artworkUri != null) AsyncImage(model = currentTrack.artworkUri, contentDescription = null, modifier = Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop)
                                    else LyraBrandLogo(size = 42.dp, cornerRadius = 12.dp)
                                    Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                                        Text(currentTitle, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold)
                                        Text(currentLyric.ifBlank { currentArtist.ifBlank { "Ketuk untuk membuka layar putar" } }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                                    }
                                    IconButton(onClick = { controller?.seekToPreviousMediaItem() }) { LyraVectorIcon(LyraIconType.PREV, tint = Color.White, size = 18.dp) }
                                    FilledTonalIconButton(onClick = { if (playing) controller?.pause() else controller?.play() }) { LyraVectorIcon(if (playing) LyraIconType.PAUSE else LyraIconType.PLAY, tint = MaterialTheme.colorScheme.primary, size = 20.dp) }
                                    IconButton(onClick = { controller?.seekToNextMediaItem() }) { LyraVectorIcon(LyraIconType.NEXT, tint = Color.White, size = 18.dp) }
                                }
                                if (duration > 0) LinearProgressIndicator(progress = { (position.toFloat() / duration.toFloat()).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(3.dp).clip(RoundedCornerShape(2.dp)), color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                        LyraTab.values().forEach { item ->
                            val isSelected = tab == item
                            val iconType = when (item) { LyraTab.HOME -> LyraIconType.HOME; LyraTab.NOW_PLAYING -> if (playing) LyraIconType.PAUSE else LyraIconType.PLAY; LyraTab.LIBRARY -> LyraIconType.MUSIC; LyraTab.LYRICS -> LyraIconType.LYRICS; LyraTab.SETTINGS -> LyraIconType.SETTINGS }
                            NavigationBarItem(
                                selected = isSelected, onClick = { tab = item },
                                icon = { LyraVectorIcon(iconType, tint = if (isSelected) Color(0xFF0B0D0A) else MaterialTheme.colorScheme.onSurfaceVariant, size = 20.dp) },
                                label = { Text(item.label, maxLines = 1, fontSize = 11.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium) },
                                colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.primary)
                            )
                        }
                    }
                }
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
                when (tab) {
                    LyraTab.HOME -> {
                        Spacer(Modifier.height(10.dp))
                        ElevatedCard(onClick = { tab = LyraTab.NOW_PLAYING }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(26.dp), colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (currentTrack?.artworkUri != null) AsyncImage(model = currentTrack.artworkUri, contentDescription = null, modifier = Modifier.size(76.dp).clip(RoundedCornerShape(20.dp)), contentScale = ContentScale.Crop)
                                else LyraBrandLogo(size = 76.dp, cornerRadius = 20.dp)
                                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                                    Text("PLAYING NOW", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Black)
                                    Text(currentTitle, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(currentArtist.ifBlank { "Pilih lagu untuk mulai" }, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("Filter ≥${minDurationSec}s aktif · ${durationFilteredSongs.size} lagu", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp))
                                }
                                FilledTonalIconButton(onClick = { if (playing) controller?.pause() else if (currentUri != null) controller?.play() else if (durationFilteredSongs.isNotEmpty()) playTracks(durationFilteredSongs, 0) }, modifier = Modifier.size(48.dp)) {
                                    LyraVectorIcon(if (playing) LyraIconType.PAUSE else LyraIconType.PLAY, tint = MaterialTheme.colorScheme.primary, size = 22.dp)
                                }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            SummaryCard("Lagu (≥${minDurationSec}s)", durationFilteredSongs.size.toString(), Modifier.weight(1f)) { librarySubTab = LibrarySubTab.ALL; tab = LyraTab.LIBRARY }
                            SummaryCard("Favorit", favoriteTracks.size.toString(), Modifier.weight(1f)) { librarySubTab = LibrarySubTab.FAVORITES; tab = LyraTab.LIBRARY }
                            SummaryCard("Playlist", playlists.size.toString(), Modifier.weight(1f)) { librarySubTab = LibrarySubTab.PLAYLISTS; tab = LyraTab.LIBRARY }
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Baru diputar", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            TextButton(onClick = { if (durationFilteredSongs.isNotEmpty()) { val ranked = durationFilteredSongs.sortedBy { (playCounts[it.id] ?: 0) * 10 + if (it.id in lastPlayed.take(12)) 100 else 0 }.shuffled(); playTracks(ranked, 0) } }) { Text("Acak Pintar") }
                            TextButton(onClick = { librarySubTab = LibrarySubTab.ALL; tab = LyraTab.LIBRARY }) { Text("Semua") }
                        }
                        if (recentTracks.isEmpty()) EmptyState("Belum ada riwayat", "Putar lagu untuk mulai membuat riwayat lokal.")
                        else TrackList(recentTracks.take(12), currentUri, playing, favoriteIds, ::playTracks, ::toggleFavorite, { trackToAdd = it }, { openMetadataEditor(it) }, false, Modifier.weight(1f))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                            OutlinedButton(onClick = { folderPicker.launch(null) }, modifier = Modifier.weight(1f)) { Text("Pilih folder") }
                            OutlinedButton(onClick = { showSleepDialog = true }, modifier = Modifier.weight(1f)) { Text(if (sleepMinutes > 0) "Timer ($sleepMinutes mnt)" else "Timer tidur") }
                        }
                    }

                    LyraTab.NOW_PLAYING -> {
                        Spacer(Modifier.height(6.dp))
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                            Row(Modifier.clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(3.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Surface(shape = RoundedCornerShape(16.dp), color = if (!showFullKaraokeInPlayer) MaterialTheme.colorScheme.primary else Color.Transparent, modifier = Modifier.clickable { showFullKaraokeInPlayer = false }) {
                                    Text("Sampul & Spektrum", Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = if (!showFullKaraokeInPlayer) Color(0xFF101010) else MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Surface(shape = RoundedCornerShape(16.dp), color = if (showFullKaraokeInPlayer) MaterialTheme.colorScheme.primary else Color.Transparent, modifier = Modifier.clickable { showFullKaraokeInPlayer = true }) {
                                    Text(if (hasEnhancedKaraoke) "Karaoke LRC ★" else "Lirik Karaoke", Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = if (showFullKaraokeInPlayer) Color(0xFF101010) else MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = { val sp = listOf(0.75f, 1.0f, 1.25f, 1.5f); val nx = sp[(sp.indexOf(playbackSpeed) + 1) % sp.size]; playbackSpeed = nx; controller?.setPlaybackSpeed(nx) }) { Text("${playbackSpeed}x", fontWeight = FontWeight.Bold) }
                                IconButton(onClick = { librarySubTab = LibrarySubTab.QUEUE; tab = LyraTab.LIBRARY }) { LyraVectorIcon(LyraIconType.QUEUE, tint = MaterialTheme.colorScheme.primary, size = 20.dp) }
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        if (!showFullKaraokeInPlayer) {
                            Box(Modifier.fillMaxWidth().weight(0.40f).padding(horizontal = 12.dp, vertical = 4.dp).clip(RoundedCornerShape(28.dp)).background(Brush.linearGradient(listOf(Color(0xFF283A18), Color(0xFF151E0D), Color(0xFF0C1009)))).clickable { showFullKaraokeInPlayer = true }, contentAlignment = Alignment.Center) {
                                if (currentTrack?.artworkUri != null) AsyncImage(model = currentTrack.artworkUri, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                else Column(horizontalAlignment = Alignment.CenterHorizontally) { LyraBrandLogo(size = 104.dp, cornerRadius = 26.dp); Spacer(Modifier.height(8.dp)); Text(currentAlbum.ifBlank { "LYRA KARAOKE" }.uppercase(), color = Color(0xFFB8F36B), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Black) }
                                Surface(color = Color.Black.copy(alpha = 0.55f), shape = RoundedCornerShape(14.dp), modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp)) { Text("Ketuk untuk Lirik Karaoke ♪", color = Color.White, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)) }
                            }
                            KaraokeMiniStage(parsedKaraokeLines, position, hasEnhancedKaraoke, autoWordKaraoke, { showFullKaraokeInPlayer = true }, { val (cT, cA) = cleanSearchQuery(currentTitle, currentArtist); searchTrack = cT; searchArtist = cA; lyricsSubTab = LyricsSubTab.SEARCH; tab = LyraTab.LYRICS; triggerManualLyricsSearch(cT, cA) })
                        } else {
                            KaraokeFullStage(parsedKaraokeLines, position, hasEnhancedKaraoke, autoWordKaraoke, { autoWordKaraoke = !autoWordKaraoke; prefs.edit().putBoolean("auto_word_karaoke", autoWordKaraoke).apply() }, { ms -> controller?.seekTo(ms); position = ms }, { val (cT, cA) = cleanSearchQuery(currentTitle, currentArtist); searchTrack = cT; searchArtist = cA; lyricsSubTab = LyricsSubTab.SEARCH; tab = LyraTab.LYRICS; triggerManualLyricsSearch(cT, cA) }, Modifier.fillMaxWidth().weight(0.52f))
                        }
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.weight(1f).clickable { openMetadataEditor(currentTrack) }) {
                                Text(currentTitle, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${currentArtist.ifBlank { "Artis tidak diketahui" }} · ${currentAlbum.ifBlank { "Album tidak diketahui" }}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            val isFavNow = currentTrack?.id?.let { it in favoriteIds } == true
                            IconButton(onClick = { currentTrack?.let { toggleFavorite(it) } }) { LyraVectorIcon(if (isFavNow) LyraIconType.HEART_FILLED else LyraIconType.HEART_OUTLINE, tint = if (isFavNow) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, size = 24.dp) }
                            FilledTonalIconButton(onClick = { openMetadataEditor(currentTrack) }) { LyraVectorIcon(LyraIconType.EDIT, tint = MaterialTheme.colorScheme.primary, size = 18.dp) }
                        }
                        if (duration > 0) {
                            Slider(value = position.coerceIn(0L, duration).toFloat(), onValueChange = { position = it.toLong() }, onValueChangeFinished = { controller?.seekTo(position) }, valueRange = 0f..duration.toFloat(), modifier = Modifier.height(24.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text(formatTime(position), style = MaterialTheme.typography.labelSmall)
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text("↺ 10s", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable { val t = (position - 10_000L).coerceAtLeast(0L); controller?.seekTo(t); position = t })
                                    Text("10s ↻", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable { val t = (position + 10_000L).coerceAtMost(duration); controller?.seekTo(t); position = t })
                                }
                                Text("−${formatTime((duration - position).coerceAtLeast(0L))}", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        AudioVisualizer(audioSessionId = audioSessionId, isPlaying = playing, modifier = Modifier.fillMaxWidth().height(68.dp))
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                            Surface(shape = CircleShape, color = if (shuffle) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f) else Color.Transparent, modifier = Modifier.size(44.dp).clickable { val ns = !(controller?.shuffleModeEnabled ?: false); controller?.shuffleModeEnabled = ns; shuffle = ns }) {
                                Box(contentAlignment = Alignment.Center) { LyraVectorIcon(LyraIconType.SHUFFLE, tint = if (shuffle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, size = 22.dp) }
                            }
                            FilledTonalButton(onClick = { controller?.seekToPreviousMediaItem() }, modifier = Modifier.size(52.dp), shape = RoundedCornerShape(16.dp), contentPadding = PaddingValues(0.dp)) { LyraVectorIcon(LyraIconType.PREV, tint = Color.White, size = 22.dp) }
                            Button(onClick = { if (playing) controller?.pause() else if (currentUri != null) controller?.play() else if (durationFilteredSongs.isNotEmpty()) playTracks(durationFilteredSongs, 0) }, modifier = Modifier.size(66.dp), shape = RoundedCornerShape(22.dp), contentPadding = PaddingValues(0.dp)) { LyraVectorIcon(if (playing) LyraIconType.PAUSE else LyraIconType.PLAY, tint = Color(0xFF0B0D0A), size = 28.dp) }
                            FilledTonalButton(onClick = { controller?.seekToNextMediaItem() }, modifier = Modifier.size(52.dp), shape = RoundedCornerShape(16.dp), contentPadding = PaddingValues(0.dp)) { LyraVectorIcon(LyraIconType.NEXT, tint = Color.White, size = 22.dp) }
                            Surface(shape = CircleShape, color = if (repeatMode != Player.REPEAT_MODE_OFF) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f) else Color.Transparent, modifier = Modifier.size(44.dp).clickable { val nx = when (repeatMode) { Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL; Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE; else -> Player.REPEAT_MODE_OFF }; controller?.repeatMode = nx; repeatMode = nx }) {
                                Box(contentAlignment = Alignment.Center) { LyraVectorIcon(when (repeatMode) { Player.REPEAT_MODE_ONE -> LyraIconType.REPEAT_ONE; Player.REPEAT_MODE_ALL -> LyraIconType.REPEAT_ALL; else -> LyraIconType.REPEAT_OFF }, tint = if (repeatMode != Player.REPEAT_MODE_OFF) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, size = 22.dp) }
                            }
                        }
                    }

                    LyraTab.LIBRARY -> {
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            LibrarySubTab.values().forEach { sub ->
                                val active = librarySubTab == sub
                                Surface(shape = RoundedCornerShape(14.dp), color = if (active) MaterialTheme.colorScheme.primary else Color.Transparent, modifier = Modifier.weight(1f).clickable { librarySubTab = sub }) {
                                    Text(sub.title, modifier = Modifier.padding(vertical = 8.dp), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = if (active) Color(0xFF101010) else MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        when (librarySubTab) {
                            LibrarySubTab.ALL, LibrarySubTab.FAVORITES -> {
                                OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth().padding(top = 8.dp), label = { Text("Cari judul, artis, album, atau folder") }, singleLine = true, shape = RoundedCornerShape(20.dp))
                                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    FilterChip(selected = minDurationSec > 0, onClick = { val st = listOf(0, 15, 30, 45, 60, 90); val nx = st[(st.indexOf(minDurationSec) + 1) % st.size]; minDurationSec = nx; prefs.edit().putInt("min_duration_sec", nx).apply() }, label = { Text(if (minDurationSec == 0) "Durasi: Semua" else "Minimal: ≥${minDurationSec} dtk") })
                                    SortOption.values().forEach { opt -> FilterChip(selected = sortOption == opt, onClick = { sortOption = opt; prefs.edit().putString("sort_option", opt.name).apply() }, label = { Text(opt.label) }) }
                                }
                                val listToShow = if (librarySubTab == LibrarySubTab.FAVORITES) favoriteTracks else sortedAndFilteredSongs
                                Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Text("${listToShow.size} lagu ditampilkan", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                                    TextButton(onClick = { if (listToShow.isNotEmpty()) playTracks(listToShow, 0) }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) { Text("▶ Putar Semua") }
                                }
                                TrackList(listToShow, currentUri, playing, favoriteIds, ::playTracks, ::toggleFavorite, { trackToAdd = it }, { openMetadataEditor(it) }, true, Modifier.weight(1f))
                            }
                            LibrarySubTab.PLAYLISTS -> {
                                Spacer(Modifier.height(8.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) { Text("Koleksi Playlist", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)); Button(onClick = { showCreatePlaylist = true }) { Text("+ Buat Playlist") } }
                                Spacer(Modifier.height(8.dp))
                                if (selectedPlaylist != null) {
                                    TextButton(onClick = { selectedPlaylist = null }) { Text("← Kembali ke daftar playlist") }
                                    Text(selectedPlaylist.orEmpty(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                                        Button(onClick = { playTracks(playlistTracks, 0) }, enabled = playlistTracks.isNotEmpty()) { Text("▶ Putar") }
                                        OutlinedButton(onClick = { val p = playlists.firstOrNull { it.name == selectedPlaylist }; if (p != null) { val allIds = durationFilteredSongs.map { s -> s.id }; playlists = playlists.map { item -> if (item.name == p.name) item.copy(trackIds = (item.trackIds + allIds).distinct()) else item }; savePlaylists() } }) { Text("+ Semua Lagu") }
                                        OutlinedButton(onClick = { playlists = playlists.filterNot { it.name == selectedPlaylist }; savePlaylists(); selectedPlaylist = null }) { Text("Hapus") }
                                    }
                                    TrackList(playlistTracks, currentUri, playing, favoriteIds, ::playTracks, ::toggleFavorite, { trackToAdd = it }, { openMetadataEditor(it) }, false, Modifier.weight(1f))
                                } else {
                                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                                        item {
                                            ElevatedCard(onClick = { selectedPlaylist = "Favorit" }) {
                                                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                                    LyraVectorIcon(LyraIconType.HEART_FILLED, tint = MaterialTheme.colorScheme.primary, size = 26.dp)
                                                    Column(Modifier.weight(1f).padding(start = 12.dp)) { Text("Lagu Favorit", fontWeight = FontWeight.Bold); Text("${favoriteTracks.size} lagu", style = MaterialTheme.typography.bodySmall) }
                                                    FilledTonalIconButton(onClick = { playTracks(favoriteTracks, 0) }, enabled = favoriteTracks.isNotEmpty()) { LyraVectorIcon(LyraIconType.PLAY, tint = MaterialTheme.colorScheme.primary, size = 18.dp) }
                                                }
                                            }
                                        }
                                        items(playlists, key = { it.name }) { p ->
                                            ElevatedCard(onClick = { selectedPlaylist = p.name }) {
                                                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                                    LyraVectorIcon(LyraIconType.MUSIC, tint = MaterialTheme.colorScheme.primary, size = 26.dp)
                                                    Column(Modifier.weight(1f).padding(start = 12.dp)) { Text(p.name, fontWeight = FontWeight.Bold); Text("${p.trackIds.size} lagu", style = MaterialTheme.typography.bodySmall) }
                                                    FilledTonalIconButton(onClick = { playTracks(p.trackIds.mapNotNull { id -> songs.firstOrNull { it.id == id } }, 0) }) { LyraVectorIcon(LyraIconType.PLAY, tint = MaterialTheme.colorScheme.primary, size = 18.dp) }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            LibrarySubTab.QUEUE -> {
                                Spacer(Modifier.height(8.dp))
                                Text("Antrean Pemutaran Saat Ini", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                val queue = controller?.let { c -> (0 until c.mediaItemCount).mapNotNull { i -> val item = c.getMediaItemAt(i); val uri = item.localConfiguration?.uri ?: return@mapNotNull null; applyTrackOverride(AudioTrack(item.mediaId.ifBlank { uri.toString() }, uri, item.mediaMetadata.title?.toString() ?: "Audio", item.mediaMetadata.artist?.toString() ?: "Artis", item.mediaMetadata.albumTitle?.toString() ?: "", 0L)) } }.orEmpty()
                                if (queue.isEmpty()) EmptyState("Antrean kosong", "Pilih lagu untuk mulai memutar.")
                                else LazyColumn(Modifier.weight(1f)) {
                                    items(queue.indices.toList(), key = { queue[it].id + "_$it" }) { idx ->
                                        val tr = queue[idx]; val isCur = tr.uri == currentUri
                                        Row(Modifier.fillMaxWidth().clickable { controller?.seekTo(idx, 0L); controller?.play() }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Text(if (isCur) "▶" else "${idx + 1}", Modifier.width(32.dp), color = if (isCur) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                                            Column(Modifier.weight(1f)) { Text(tr.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = if (isCur) FontWeight.Bold else FontWeight.Normal); Text(tr.artist, style = MaterialTheme.typography.bodySmall) }
                                            IconButton(onClick = { openMetadataEditor(tr) }) { LyraVectorIcon(LyraIconType.EDIT, size = 18.dp) }
                                            TextButton(onClick = { controller?.removeMediaItem(idx) }) { Text("Hapus") }
                                        }
                                        HorizontalDivider()
                                    }
                                }
                            }
                        }
                    }

                    LyraTab.LYRICS -> {
                        Spacer(Modifier.height(8.dp))
                        ElevatedCard(shape = RoundedCornerShape(20.dp), colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
                            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(currentTitle, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(currentArtist.ifBlank { "Artis tidak diketahui" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                                    Text(lyricsStatus, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 3.dp))
                                }
                                OutlinedButton(onClick = { lrcPicker.launch(arrayOf("text/*", "application/octet-stream", "*/*")) }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp), shape = RoundedCornerShape(12.dp)) { Text("Impor .LRC", fontSize = 11.sp) }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            LyricsSubTab.values().forEach { sub ->
                                val active = lyricsSubTab == sub
                                Surface(shape = RoundedCornerShape(14.dp), color = if (active) MaterialTheme.colorScheme.primary else Color.Transparent, modifier = Modifier.weight(1f).clickable { lyricsSubTab = sub }) {
                                    Text(sub.title, modifier = Modifier.padding(vertical = 8.dp), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = if (active) Color(0xFF101010) else MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        when (lyricsSubTab) {
                            LyricsSubTab.PREVIEW -> {
                                KaraokeFullStage(parsedKaraokeLines, position, hasEnhancedKaraoke, autoWordKaraoke, { autoWordKaraoke = !autoWordKaraoke; prefs.edit().putBoolean("auto_word_karaoke", autoWordKaraoke).apply() }, { ms -> controller?.seekTo(ms); position = ms }, { val (cT, cA) = cleanSearchQuery(currentTitle, currentArtist); searchTrack = cT; searchArtist = cA; lyricsSubTab = LyricsSubTab.SEARCH; triggerManualLyricsSearch(cT, cA) }, Modifier.fillMaxWidth().weight(1f))
                                Spacer(Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                                    OutlinedButton(onClick = { lrcText = EnhancedLrcEngine.shiftAllTags(lrcText, -500); saveLrcToCache(lrcText) }, modifier = Modifier.weight(1f)) { Text("−0,5 dtk") }
                                    OutlinedButton(onClick = { lrcText = EnhancedLrcEngine.shiftAllTags(lrcText, 500); saveLrcToCache(lrcText) }, modifier = Modifier.weight(1f)) { Text("+0,5 dtk") }
                                    Button(onClick = { lyricsSubTab = LyricsSubTab.EDITOR }, modifier = Modifier.weight(1.2f)) { Text("✎ Edit LRC") }
                                }
                            }
                            LyricsSubTab.SEARCH -> {
                                ElevatedCard(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                            OutlinedTextField(searchTrack, { searchTrack = it }, label = { Text("Judul Lagu") }, singleLine = true, shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1.2f))
                                            OutlinedTextField(searchArtist, { searchArtist = it }, label = { Text("Artis (Opsional)") }, singleLine = true, shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f))
                                        }
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                            OutlinedButton(onClick = { val (cT, cA) = cleanSearchQuery(currentTitle, currentArtist); searchTrack = cT; searchArtist = cA; triggerManualLyricsSearch(cT, cA) }, shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f)) { Text("↻ Lagu Saat Ini") }
                                            Button(onClick = { triggerManualLyricsSearch(searchTrack, searchArtist) }, enabled = searchTrack.isNotBlank() && !isSearchingLyrics, shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1.3f)) { Text(if (isSearchingLyrics) "Mencari..." else "⌕ Cari Lirik") }
                                        }
                                    }
                                }
                                if (isSearchingLyrics) LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), color = MaterialTheme.colorScheme.primary) else Spacer(Modifier.height(8.dp))
                                if (results.isEmpty() && !isSearchingLyrics) {
                                    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { EmptyState("Cari Lirik Lagu", "Masukkan judul lagu di atas lalu ketuk 'Cari Lirik'.") }
                                } else {
                                    LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 14.dp)) {
                                        items(results) { res ->
                                            val isSynced = !res.syncedLyrics.isNullOrBlank(); val isEnhanced = res.syncedLyrics?.contains("<") == true
                                            val previewLines = (res.syncedLyrics ?: res.plainLyrics.orEmpty()).lines().map { EnhancedLrcEngine.stripEnhancedTags(it.substringAfterLast("]")).trim() }.filter { it.isNotBlank() }.take(2).joinToString(" / ")
                                            ElevatedCard(onClick = { val chosen = res.syncedLyrics ?: res.plainLyrics.orEmpty(); lrcText = chosen; saveLrcToCache(chosen); lyricsStatus = "Lirik \"${res.name}\" disimpan"; lyricsSubTab = LyricsSubTab.PREVIEW }, shape = RoundedCornerShape(18.dp), colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                                                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                                        Text(res.name, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                                        Surface(shape = RoundedCornerShape(10.dp), color = when { isEnhanced -> Color(0xFFB8F36B); isSynced -> Color(0xFF3A5A20); else -> Color(0xFF3A3A3A) }) {
                                                            Text(when { isEnhanced -> "KARAOKE LRC ★"; isSynced -> "SYNCED LRC"; else -> "TEKS BIASA" }, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = if (isEnhanced) Color(0xFF101010) else Color.White, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                                                        }
                                                    }
                                                    Text("${res.artist}${if (res.album.isNotBlank()) " · ${res.album}" else ""}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                    if (previewLines.isNotBlank()) Text("\"$previewLines\"", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            LyricsSubTab.EDITOR -> {
                                ElevatedCard(shape = RoundedCornerShape(16.dp), colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                            Text("Waktu Lagu: ${formatTime(position)}", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                            Text("Format: [mm:ss.xx]<mm:ss.xx>Kata", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                                            FilledTonalButton(onClick = { lrcText = (lrcText.trimEnd() + "\n" + EnhancedLrcEngine.formatLineTag(position) + " ").trimStart() }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) { Text("+ [Baris] ${EnhancedLrcEngine.formatLineTag(position)}", fontSize = 11.sp) }
                                            FilledTonalButton(onClick = { lrcText = lrcText.trimEnd() + " " + EnhancedLrcEngine.formatWordTag(position) + " " }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) { Text("+ <Kata> ${EnhancedLrcEngine.formatWordTag(position)}", fontSize = 11.sp) }
                                        }
                                    }
                                }
                                Spacer(Modifier.height(6.dp))
                                OutlinedTextField(value = lrcText, onValueChange = { lrcText = it }, modifier = Modifier.weight(1f).fillMaxWidth(), shape = RoundedCornerShape(16.dp), label = { Text("Editor Teks .LRC / Enhanced LRC") })
                                Spacer(Modifier.height(6.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                                    OutlinedButton(onClick = { saveLrcLauncher.launch(currentTitle.substringBeforeLast('.', currentTitle) + ".lrc") }, Modifier.weight(1f)) { Text("Ekspor") }
                                    OutlinedButton(onClick = { val uri = currentUri ?: return@OutlinedButton; scope.launch { val r = runCatching { withContext(Dispatchers.IO) { AudioTagEditor.embedLyrics(context, uri, lrcText) } }; lyricsStatus = if (r.isSuccess) "Lirik ditanamkan ke file." else "Gagal tanam tag." } }, Modifier.weight(1f)) { Text("Tanam Tag") }
                                    Button(onClick = { saveLrcToCache(lrcText); LyricsWidgetProvider.updateAll(context, currentTitle, EnhancedLrcEngine.stripEnhancedTags(LrcParser.displayLine(lrcText, position))); lyricsStatus = "Lirik disimpan."; lyricsSubTab = LyricsSubTab.PREVIEW }, Modifier.weight(1.2f)) { Text("Simpan Lirik") }
                                }
                            }
                        }
                    }

                    LyraTab.SETTINGS -> {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 10.dp)) {
                            item {
                                Text("Pengaturan", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                                SettingRow("Tema gelap", "Gunakan antarmuka gelap khas Lyra", darkMode) { darkMode = it; prefs.edit().putBoolean("dark_mode", it).apply() }
                                SettingRow("Lirik otomatis", "Cari lirik otomatis saat lagu diputar", autoLyrics) { autoLyrics = it; prefs.edit().putBoolean("auto_lyrics", it).apply() }
                                SettingRow("Auto-Word Karaoke (Estimasi Kata)", "Sorot kata demi kata otomatis untuk LRC biasa yang belum memiliki tag <mm:ss.xx>", autoWordKaraoke) { autoWordKaraoke = it; prefs.edit().putBoolean("auto_word_karaoke", it).apply() }
                            }
                            item {
                                ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("Filter Durasi Minimal Musik", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                        Text("Sembunyikan audio pendek (seperti voice note/notifikasi) di bawah durasi ini.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            listOf(0, 15, 30, 45, 60, 90).forEach { sec -> FilterChip(selected = minDurationSec == sec, onClick = { minDurationSec = sec; prefs.edit().putInt("min_duration_sec", sec).apply() }, label = { Text(if (sec == 0) "Semua" else "≥ $sec dtk") }) }
                                        }
                                    }
                                }
                            }
                            item {
                                ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text("Audio & Equalizer", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold); EqualizerPanel(audioSessionId = audioSessionId) }
                                }
                            }
                            item {
                                ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("Backup & Playlist", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button(onClick = { backupExportLauncher.launch("lyra-backup.json") }) { Text("Ekspor backup") }; OutlinedButton(onClick = { backupImportLauncher.launch(arrayOf("application/json", "text/*")) }) { Text("Pulihkan") } }
                                        OutlinedButton(onClick = { m3uExportLauncher.launch("lyra-playlist.m3u") }, Modifier.fillMaxWidth()) { Text("Ekspor M3U") }
                                        OutlinedButton(onClick = { m3uImportLauncher.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth()) { Text("Impor M3U") }
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
                    OutlinedTextField(editTitle, { editTitle = it }, label = { Text("Judul lagu") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(editArtist, { editArtist = it }, label = { Text("Artis") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(editAlbum, { editAlbum = it }, label = { Text("Album") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(editArtworkUri, { editArtworkUri = it }, label = { Text("URI Sampul") }, singleLine = true, modifier = Modifier.weight(1f))
                        OutlinedButton(onClick = { artworkPicker.launch(arrayOf("image/*")) }) { Text("Foto") }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    val title = editTitle.trim().ifBlank { target?.title ?: currentTitle }
                    val artist = editArtist.trim().ifBlank { "Artis tidak diketahui" }
                    val album = editAlbum.trim().ifBlank { "Album tidak diketahui" }
                    val ov = MetadataOverride(title, artist, album, editArtworkUri.trim())
                    val updatedMap = metadataOverrides.toMutableMap()
                    if (target != null) { updatedMap[target.id] = ov; updatedMap[target.uri.toString()] = ov } else if (currentUri != null) updatedMap[currentUri.toString()] = ov
                    metadataOverrides = updatedMap; saveMetadataOverrides(prefs, updatedMap)
                    songs = songs.map { s -> if ((target != null && (s.id == target.id || s.uri == target.uri)) || (target == null && s.uri == currentUri)) applyTrackOverride(s, updatedMap) else s }
                    if (target == null || target.uri == currentUri) {
                        currentTitle = title; currentArtist = artist; currentAlbum = album
                        val (cT, cA) = cleanSearchQuery(title, artist); searchTrack = cT; searchArtist = cA
                        prefs.edit().putString("current_title", title).putString("current_artist", artist).putString("current_album", album).apply()
                    }
                    showMetadataDialog = false
                }) { Text("Simpan") }
            },
            dismissButton = { TextButton(onClick = { showMetadataDialog = false }) { Text("Batal") } }
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
                    if (name.isNotBlank() && playlists.none { it.name.equals(name, true) }) { playlists = playlists + listOf(LyraPlaylist(name, emptyList())); savePlaylists(); newPlaylistName = ""; showCreatePlaylist = false; selectedPlaylist = name }
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
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (pl in playlists) {
                        TextButton(onClick = {
                            val tr = trackToAdd
                            if (tr != null) { playlists = playlists.map { p -> if (p.name == pl.name) p.copy(trackIds = (p.trackIds + listOf(tr.id)).distinct()) else p }; savePlaylists() }
                            trackToAdd = null
                        }, modifier = Modifier.fillMaxWidth()) { Text("${pl.name} · ${pl.trackIds.size} lagu") }
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
                Column {
                    listOf(5, 10, 15, 30, 45, 60).forEach { m ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { sleepMinutes = m; showSleepDialog = false }) {
                            RadioButton(selected = sleepMinutes == m, onClick = { sleepMinutes = m; showSleepDialog = false }); Text("$m menit")
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { sleepMinutes = 0; showSleepDialog = false }) { Text("Matikan") } },
            dismissButton = { TextButton(onClick = { showSleepDialog = false }) { Text("Tutup") } }
        )
    }
}
// === END OF MAINACTIVITY ===
