package com.stemplayer.app

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class Stem(
    val name: String,
    val file: String,
    val volume: Float = 0.8f,
    val muted: Boolean = false,
)

data class Song(
    val id: String,
    val name: String,
    val sampleRate: Int,
    val durationMs: Long,
    val createdAt: Long,
    val stems: List<Stem>,
    /** File name of the score (plain MusicXML) in the song folder, if any. */
    val score: String? = null,
)

/**
 * Each song lives in filesDir/songs/<id>/ with a song.json and one decoded .pcm file per stem.
 */
class SongRepository(private val context: Context) {

    private val root = File(context.filesDir, "songs").apply { mkdirs() }

    fun songDir(id: String) = File(root, id)

    fun loadAll(): List<Song> =
        root.listFiles()?.mapNotNull { dir ->
            runCatching { readSong(dir) }.getOrNull()
        }?.sortedBy { it.name.lowercase() } ?: emptyList()

    fun save(song: Song) {
        val json = JSONObject().apply {
            put("name", song.name)
            put("sampleRate", song.sampleRate)
            put("durationMs", song.durationMs)
            put("createdAt", song.createdAt)
            song.score?.let { put("score", it) }
            put("stems", JSONArray().apply {
                song.stems.forEach {
                    put(JSONObject().apply {
                        put("name", it.name)
                        put("file", it.file)
                        put("volume", it.volume.toDouble())
                        put("muted", it.muted)
                    })
                }
            })
        }
        val dir = songDir(song.id).apply { mkdirs() }
        val tmp = File(dir, "song.json.tmp")
        tmp.writeText(json.toString())
        tmp.renameTo(File(dir, "song.json"))
    }

    fun delete(id: String) {
        songDir(id).deleteRecursively()
    }

    fun displayName(uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0)?.let { return it } }
        return uri.lastPathSegment ?: "Stem"
    }

    /**
     * Reads a .mxl / .musicxml file, checks that it can be understood and stores it in the song folder
     * as plain MusicXML. Returns the stored file name.
     */
    fun saveScore(songId: String, uri: Uri): String {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalStateException("Could not read the score file")
        val xml = try {
            ScoreTimeline.extractXml(bytes).also { ScoreTimeline.parse(it) }
        } catch (e: Exception) {
            throw IllegalStateException("Could not read the score: ${e.message}", e)
        }
        val dir = songDir(songId).apply { mkdirs() }
        File(dir, SCORE_FILE).writeText(xml)
        return SCORE_FILE
    }

    fun deleteScore(songId: String) {
        File(songDir(songId), SCORE_FILE).delete()
    }

    fun readScore(song: Song): String? = song.score?.let { File(songDir(song.id), it) }?.takeIf { it.exists() }?.readText()

    /** Decodes all files and stores them as a new song. Calls [progress] with (done, total). */
    fun import(
        name: String,
        uris: List<Uri>,
        stemNames: List<String>,
        scoreUri: Uri?,
        progress: (Int, Int) -> Unit,
    ): Song {
        val id = UUID.randomUUID().toString()
        val dir = songDir(id).apply { mkdirs() }
        try {
            val score = scoreUri?.let { saveScore(id, it) }
            var sampleRate = 0
            var maxFrames = 0L
            val stems = uris.mapIndexed { i, uri ->
                progress(i, uris.size)
                val file = "stem_$i.pcm"
                val info = try {
                    AudioDecoder.decodeToPcm(context, uri, File(dir, file))
                } catch (e: Exception) {
                    throw IllegalStateException("Could not decode \"${stemNames[i]}\": ${e.message}", e)
                }
                if (sampleRate == 0) sampleRate = info.sampleRate
                else if (info.sampleRate != sampleRate) {
                    throw IllegalStateException(
                        "\"${stemNames[i]}\" is ${info.sampleRate} Hz but the others are $sampleRate Hz. " +
                            "All stems must use the same sample rate."
                    )
                }
                maxFrames = maxOf(maxFrames, info.frames)
                Stem(name = stemNames[i], file = file)
            }
            progress(uris.size, uris.size)
            val song = Song(
                id = id,
                name = name,
                sampleRate = sampleRate,
                durationMs = maxFrames * 1000 / maxOf(sampleRate, 1),
                createdAt = System.currentTimeMillis(),
                stems = stems,
                score = score,
            )
            save(song)
            return song
        } catch (e: Exception) {
            dir.deleteRecursively()
            throw e
        }
    }

    private fun readSong(dir: File): Song {
        val o = JSONObject(File(dir, "song.json").readText())
        val arr = o.getJSONArray("stems")
        return Song(
            id = dir.name,
            name = o.getString("name"),
            sampleRate = o.getInt("sampleRate"),
            durationMs = o.optLong("durationMs"),
            createdAt = o.optLong("createdAt"),
            stems = (0 until arr.length()).map { i ->
                val s = arr.getJSONObject(i)
                Stem(
                    name = s.getString("name"),
                    file = s.getString("file"),
                    volume = s.optDouble("volume", 0.8).toFloat(),
                    muted = s.optBoolean("muted", false),
                )
            },
            score = o.optString("score").ifEmpty { null },
        )
    }

    companion object {
        const val SCORE_FILE = "score.musicxml"

        fun isScoreFile(name: String): Boolean {
            val n = name.lowercase()
            return n.endsWith(".mxl") || n.endsWith(".musicxml") || n.endsWith(".xml")
        }

        /**
         * Turns file names like "Ectenia-Soprano.mp3", "Ectenia-Alto.mp3" into a song name ("Ectenia")
         * and stem names ("Soprano", "Alto"), and orders stems S-A-T-B when the names allow it.
         */
        fun guessNames(fileNames: List<String>): Pair<String, List<String>> {
            val bases = fileNames.map { it.substringBeforeLast('.') }
            if (bases.size < 2) return (bases.firstOrNull() ?: "Song") to bases
            var prefix = bases.reduce { a, b -> a.commonPrefixWith(b) }
            // Don't cut into the middle of a voice name ("Song-S" from "Song-Soprano"/"Song-Sopran 2").
            val sep = prefix.indexOfLast { it in "-_ ." }
            prefix = if (sep >= 0) prefix.substring(0, sep + 1) else ""
            val stemNames = bases.map { it.removePrefix(prefix).trim(' ', '-', '_', '.').ifEmpty { it } }
            val songName = prefix.trim(' ', '-', '_', '.').ifEmpty { "New song" }
            return songName to stemNames
        }

        /** Sort key putting choral voices in score order. */
        fun voiceOrder(name: String): Int {
            val n = name.lowercase()
            return when {
                n.startsWith("sop") || n == "s" -> 0
                n.startsWith("mezzo") -> 1
                n.startsWith("alt") || n == "a" -> 2
                n.startsWith("ten") || n == "t" -> 3
                n.startsWith("bari") -> 4
                n.startsWith("bas") || n == "b" -> 5
                else -> 6
            }
        }
    }
}
