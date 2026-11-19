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
    /** Original file name the stem was imported from; used to recognise the song on Drive. */
    val source: String? = null,
    /** Drive fingerprint of that file, when it came from Drive (see [RemoteFile.version]). */
    val version: String? = null,
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
    val scoreSource: String? = null,
    val scoreVersion: String? = null,
)

data class StemInput(val uri: Uri, val name: String, val source: String?, val version: String? = null)
data class ScoreInput(val uri: Uri, val source: String?, val version: String? = null)

/**
 * Each song lives in filesDir/songs/<id>/ with a song.json and one decoded .pcm file per stem.
 */
class SongRepository(private val context: Context) {

    private val root = File(context.filesDir, "songs").apply { mkdirs() }

    fun songDir(id: String) = File(root, id)

    fun loadAll(): List<Song> =
        root.listFiles()?.filter { !it.name.startsWith(".") }?.mapNotNull { dir ->
            runCatching { readSong(dir) }.getOrNull()
        }?.sortedBy { it.name.lowercase() } ?: emptyList()

    fun save(song: Song) {
        val json = JSONObject().apply {
            put("name", song.name)
            put("sampleRate", song.sampleRate)
            put("durationMs", song.durationMs)
            put("createdAt", song.createdAt)
            song.score?.let { put("score", it) }
            song.scoreSource?.let { put("scoreSource", it) }
            song.scoreVersion?.let { put("scoreVersion", it) }
            put("stems", JSONArray().apply {
                song.stems.forEach {
                    put(JSONObject().apply {
                        put("name", it.name)
                        put("file", it.file)
                        put("volume", it.volume.toDouble())
                        put("muted", it.muted)
                        it.source?.let { v -> put("source", v) }
                        it.version?.let { v -> put("version", v) }
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
    fun import(name: String, stems: List<StemInput>, score: ScoreInput?, progress: (Int, Int) -> Unit): Song =
        importInto(UUID.randomUUID().toString(), name, stems, score, progress)

    /**
     * Replaces the audio (and the score, if [score] is given) of [old] with new files. The song keeps its id and
     * name; voices that are still there keep their name, volume and mute setting.
     */
    fun replace(old: Song, stems: List<StemInput>, score: ScoreInput?, progress: (Int, Int) -> Unit): Song {
        val tmpId = ".new-" + UUID.randomUUID()
        val fresh = importInto(tmpId, old.name, stems, score, progress)
        val tmpDir = songDir(tmpId)
        val trash = songDir(".old-" + UUID.randomUUID())
        try {
            var song = fresh.copy(
                id = old.id,
                createdAt = old.createdAt,
                stems = fresh.stems.map { s ->
                    val prev = old.stems.firstOrNull { it.source != null && it.source == s.source }
                        ?: old.stems.firstOrNull { it.name.equals(s.name, ignoreCase = true) }
                    if (prev == null) s else s.copy(name = prev.name, volume = prev.volume, muted = prev.muted)
                },
            )
            if (score == null && old.score != null) {
                // Drive has no score for this song: keep the one the app has.
                File(songDir(old.id), old.score).takeIf { it.exists() }?.copyTo(File(tmpDir, old.score), overwrite = true)
                song = song.copy(score = old.score, scoreSource = old.scoreSource, scoreVersion = old.scoreVersion)
            }
            val dir = songDir(old.id)
            if (dir.exists() && !dir.renameTo(trash)) throw IllegalStateException("Could not replace the song files")
            if (!tmpDir.renameTo(dir)) {
                trash.renameTo(dir)
                throw IllegalStateException("Could not replace the song files")
            }
            save(song)
            trash.deleteRecursively()
            return song
        } catch (e: Exception) {
            tmpDir.deleteRecursively()
            throw e
        }
    }

    /** Removes leftovers of replacements that were interrupted. */
    fun cleanUp() {
        root.listFiles()?.filter { it.name.startsWith(".") }?.forEach { it.deleteRecursively() }
    }

    private fun importInto(
        id: String,
        name: String,
        inputs: List<StemInput>,
        scoreInput: ScoreInput?,
        progress: (Int, Int) -> Unit,
    ): Song {
        val dir = songDir(id).apply { mkdirs() }
        try {
            val score = scoreInput?.let { saveScore(id, it.uri) }
            var sampleRate = 0
            var maxFrames = 0L
            val stems = inputs.mapIndexed { i, input ->
                progress(i, inputs.size)
                val file = "stem_$i.pcm"
                val info = try {
                    AudioDecoder.decodeToPcm(context, input.uri, File(dir, file))
                } catch (e: Exception) {
                    throw IllegalStateException("Could not decode \"${input.name}\": ${e.message}", e)
                }
                if (sampleRate == 0) sampleRate = info.sampleRate
                else if (info.sampleRate != sampleRate) {
                    throw IllegalStateException(
                        "\"${input.name}\" is ${info.sampleRate} Hz but the others are $sampleRate Hz. " +
                            "All stems must use the same sample rate."
                    )
                }
                maxFrames = maxOf(maxFrames, info.frames)
                Stem(name = input.name, file = file, source = input.source, version = input.version)
            }
            progress(inputs.size, inputs.size)
            val song = Song(
                id = id,
                name = name,
                sampleRate = sampleRate,
                durationMs = maxFrames * 1000 / maxOf(sampleRate, 1),
                createdAt = System.currentTimeMillis(),
                stems = stems,
                score = score,
                scoreSource = scoreInput?.source,
                scoreVersion = scoreInput?.version,
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
                    source = s.optString("source").ifEmpty { null },
                    version = s.optString("version").ifEmpty { null },
                )
            },
            score = o.optString("score").ifEmpty { null },
            scoreSource = o.optString("scoreSource").ifEmpty { null },
            scoreVersion = o.optString("scoreVersion").ifEmpty { null },
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

        /** Orders stems S-A-T-B when the names make that possible; otherwise keeps the given order. */
        fun inVoiceOrder(stems: List<StemInput>): List<StemInput> =
            stems.withIndex().sortedWith(compareBy({ voiceOrder(it.value.name) }, { it.index })).map { it.value }

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
