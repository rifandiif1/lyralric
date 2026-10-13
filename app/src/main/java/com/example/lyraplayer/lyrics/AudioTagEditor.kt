package com.example.lyraplayer.lyrics

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.images.ArtworkFactory
import java.io.File

/** Writes common metadata and lyrics into formats supported by jaudiotagger. */
object AudioTagEditor {
    data class Metadata(
        val title: String = "", val artist: String = "", val album: String = "",
        val genre: String = "", val year: String = "", val track: String = "",
        val composer: String = "", val lyrics: String? = null, val artworkUri: Uri? = null
    )

    fun write(context: Context, uri: Uri, metadata: Metadata) {
        require(metadata.title.isNotBlank() || metadata.artist.isNotBlank() || metadata.album.isNotBlank() ||
            metadata.genre.isNotBlank() || metadata.year.isNotBlank() || metadata.track.isNotBlank() ||
            metadata.composer.isNotBlank() || !metadata.lyrics.isNullOrBlank() || metadata.artworkUri != null) {
            "Tidak ada metadata untuk disimpan"
        }
        val resolver = context.contentResolver
        var displayName = ""
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) displayName = cursor.getString(0).orEmpty()
        }
        val ext = displayName.substringAfterLast('.', "mp3").lowercase().filter { it.isLetterOrDigit() }.ifBlank { "mp3" }
        val temp = File.createTempFile("lyra-tags-", ".$ext", context.cacheDir)
        try {
            resolver.openInputStream(uri)?.use { input -> temp.outputStream().use { output -> input.copyTo(output) } }
                ?: error("Tidak bisa membaca file audio yang dipilih")
            val audio = AudioFileIO.read(temp)
            val tag = audio.tagOrCreateAndSetDefault
            fun set(key: FieldKey, value: String) { if (value.isNotBlank()) tag.setField(key, value.trim()) }
            set(FieldKey.TITLE, metadata.title)
            set(FieldKey.ARTIST, metadata.artist)
            set(FieldKey.ALBUM, metadata.album)
            set(FieldKey.GENRE, metadata.genre)
            set(FieldKey.YEAR, metadata.year)
            set(FieldKey.TRACK, metadata.track)
            set(FieldKey.COMPOSER, metadata.composer)
            metadata.lyrics?.takeIf { it.isNotBlank() }?.let { tag.setField(FieldKey.LYRICS, it) }
            metadata.artworkUri?.let { artUri ->
                val bytes = resolver.openInputStream(artUri)?.use { it.readBytes() } ?: error("Gambar sampul tidak bisa dibaca")
                require(bytes.isNotEmpty()) { "Gambar sampul kosong" }
                val artwork = ArtworkFactory.getNew().apply {
                    binaryData = bytes
                    mimeType = resolver.getType(artUri) ?: "image/jpeg"
                    description = "Lyra Player artwork"
                }
                tag.setField(artwork)
            }
            AudioFileIO.write(audio)
            require(temp.length() > 0L) { "File hasil tag kosong" }
            val output = runCatching { resolver.openOutputStream(uri, "rwt") }.getOrNull()
                ?: resolver.openOutputStream(uri, "wt")
                ?: error("File tidak mengizinkan penulisan. Pilih file asli melalui pemilih dokumen Android dan pastikan izin tulis diberikan.")
            output.use { out -> temp.inputStream().use { input -> input.copyTo(out) } }
        } finally { temp.delete() }
    }

    fun embedLyrics(context: Context, uri: Uri, lyrics: String) = write(context, uri, Metadata(lyrics = lyrics))
}
