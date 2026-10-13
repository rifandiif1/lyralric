package com.example.lyraplayer.lyrics

data class LyricLine(val timeMs: Long, val text: String)

object LrcParser {
    private val timeRegex = Regex("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?]")
    private val wordTimeRegex = Regex("<\\d{1,3}:\\d{2}(?:[.:]\\d{1,3})?>")

    /** Supports multiple timestamps per line, [mm:ss], [mm:ss.d], [mm:ss.dd] and [mm:ss.ddd]. */
    fun parse(lrc: String): List<LyricLine> = lrc.lineSequence().flatMap { rawLine ->
        val line = rawLine.removePrefix("\uFEFF")
        val matches = timeRegex.findAll(line).toList()
        if (matches.isEmpty()) emptySequence() else {
            val text = wordTimeRegex.replace(timeRegex.replace(line, ""), "").trim()
            matches.asSequence().mapNotNull { match ->
                val minutes = match.groupValues[1].toLongOrNull() ?: return@mapNotNull null
                val seconds = match.groupValues[2].toLongOrNull() ?: return@mapNotNull null
                if (seconds > 59) return@mapNotNull null
                val fraction = match.groupValues[3]
                val millis = when (fraction.length) {
                    1 -> fraction.toLongOrNull()?.times(100) ?: 0L
                    2 -> fraction.toLongOrNull()?.times(10) ?: 0L
                    3 -> fraction.toLongOrNull() ?: 0L
                    else -> 0L
                }
                LyricLine((minutes * 60 + seconds) * 1000 + millis, text)
            }
        }
    }.sortedBy { it.timeMs }.toList()

    fun currentLine(lines: List<LyricLine>, positionMs: Long): LyricLine? =
        lines.lastOrNull { it.timeMs <= positionMs.coerceAtLeast(0L) }

    /** Plain-text lyric files have no timing; show their first meaningful line as a useful fallback. */
    fun displayLine(lrc: String, positionMs: Long): String {
        val lines = parse(lrc)
        currentLine(lines, positionMs)?.text?.takeIf { it.isNotBlank() }?.let { return it }
        if (lines.isNotEmpty()) return "Lirik belum dimulai"
        return lrc.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() && !it.startsWith("[") }
            .orEmpty()
            .ifBlank { "Lirik belum tersedia" }
    }

    fun shift(lrc: String, offsetMs: Long): String = lrc.lineSequence().joinToString("\n") { line ->
        timeRegex.replace(line) { match ->
            val min = match.groupValues[1].toLongOrNull() ?: 0L
            val sec = match.groupValues[2].toLongOrNull() ?: 0L
            val fraction = match.groupValues[3]
            val ms = when (fraction.length) {
                1 -> fraction.toLongOrNull()?.times(100) ?: 0L
                2 -> fraction.toLongOrNull()?.times(10) ?: 0L
                3 -> fraction.toLongOrNull() ?: 0L
                else -> 0L
            }
            val shifted = (((min * 60 + sec) * 1000 + ms) + offsetMs).coerceAtLeast(0L)
            val totalSeconds = shifted / 1000
            "[%02d:%02d.%03d]".format(totalSeconds / 60, totalSeconds % 60, shifted % 1000)
        }
    }

    fun timestamp(positionMs: Long): String {
        val safe = positionMs.coerceAtLeast(0L)
        return "[%02d:%02d.%03d]".format(safe / 60_000, (safe / 1_000) % 60, safe % 1_000)
    }
}
