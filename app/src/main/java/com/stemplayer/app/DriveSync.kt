package com.stemplayer.app

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** The shared Google Drive folder songs are updated from. Each subfolder holds the stems (+ score) of one song. */
const val DRIVE_FOLDER_ID = "1X2ATNGl4Lgp5zvAe6wMY3Ya48tE8rxFE"

data class DriveEntry(val id: String, val name: String, val isFolder: Boolean)

/**
 * A file on Drive. [version] changes whenever the file is replaced or edited (file id, size and modification time),
 * so comparing it with the stored one tells whether the file is different from what the app has.
 */
data class RemoteFile(val id: String, val name: String, val version: String)

data class RemoteFolder(val id: String, val name: String, val audio: List<RemoteFile>, val score: RemoteFile?)

/**
 * Reads a publicly shared ("anyone with the link") Drive folder without an API key: the listing comes from
 * Drive's embeddable folder view, file details and contents from the public download endpoint.
 */
object DriveClient {
    private const val UA = "StemPlayer"

    fun listFolder(folderId: String): List<DriveEntry> {
        val html = get("https://drive.google.com/embeddedfolderview?id=${enc(folderId)}") { conn ->
            conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        }
        return parseFolderView(html)
    }

    /** Fingerprint of a file's current contents, from the download headers (nothing is downloaded). */
    fun version(fileId: String): String = get(downloadUrl(fileId), method = "HEAD") { versionOf(fileId, it) }

    fun download(fileId: String, dest: File) {
        get(downloadUrl(fileId)) { conn ->
            if (conn.contentType?.startsWith("text/html") == true) {
                throw IOException("Drive did not send the file (is it shared with \"anyone with the link\"?)")
            }
            conn.inputStream.use { input -> dest.outputStream().use { input.copyTo(it, 1 shl 16) } }
        }
    }

    private fun versionOf(fileId: String, conn: HttpURLConnection) =
        "$fileId|${conn.getHeaderField("Content-Length") ?: "?"}|${conn.getHeaderField("Last-Modified") ?: "?"}"

    private fun downloadUrl(fileId: String) =
        "https://drive.usercontent.google.com/download?id=${enc(fileId)}&export=download&confirm=t"

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun <T> get(url: String, method: String = "GET", read: (HttpURLConnection) -> T): T {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", UA)
            val code = conn.responseCode
            if (code !in 200..299) {
                throw IOException(if (code == 404) "Not found on Drive (is it shared publicly?)" else "Drive answered HTTP $code")
            }
            return read(conn)
        } finally {
            conn.disconnect()
        }
    }

    private val ENTRY = Regex(
        """<a href="https://drive\.google\.com/(file/d|drive/folders)/([A-Za-z0-9_-]+)[^"]*"[^>]*>.*?<div class="flip-entry-title">(.*?)</div>""",
        RegexOption.DOT_MATCHES_ALL,
    )

    fun parseFolderView(html: String): List<DriveEntry> =
        ENTRY.findAll(html).map { m ->
            DriveEntry(
                id = m.groupValues[2],
                name = decodeEntities(m.groupValues[3]).trim(),
                isFolder = m.groupValues[1] == "drive/folders",
            )
        }.toList()

    fun decodeEntities(s: String): String =
        Regex("&(#x[0-9a-fA-F]+|#[0-9]+|amp|lt|gt|quot|apos|#39);").replace(s) { m ->
            when (val e = m.groupValues[1]) {
                "amp" -> "&"; "lt" -> "<"; "gt" -> ">"; "quot" -> "\""; "apos", "#39" -> "'"
                else -> {
                    val cp = if (e.startsWith("#x")) e.substring(2).toInt(16) else e.substring(1).toInt()
                    String(Character.toChars(cp))
                }
            }
        }
}

// ---------------------------------------------------------------- Comparing Drive with the app

/** A song on Drive that already exists in the app but differs from it. */
data class SongUpdate(
    val song: Song,
    val folder: RemoteFolder,
    /** Human-readable list of what is different ("Alto changed", "score added"…). */
    val changes: List<String>,
    /** [song] with the Drive file names/versions recorded, for when the user keeps their copy. */
    val keepMine: Song,
)

data class SyncPlan(val newSongs: List<RemoteFolder>, val updates: List<SongUpdate>)

object SyncPlanner {

    /**
     * Songs are matched by the original file names of their stems: a Drive folder belongs to the local song
     * sharing the most stem file names with it. Songs imported before file names were recorded are matched by
     * song name and voice names as guessed from the Drive file names.
     */
    fun plan(local: List<Song>, remote: List<RemoteFolder>): SyncPlan {
        val claimed = mutableSetOf<String>()
        val newSongs = mutableListOf<RemoteFolder>()
        val updates = mutableListOf<SongUpdate>()
        for (folder in remote) {
            if (folder.audio.isEmpty()) continue
            val (_, guessedStems) = SongRepository.guessNames(folder.audio.map { it.name })
            val match = matchBySource(local, folder, claimed) ?: matchLegacy(local, folder, guessedStems, claimed)
            if (match == null) {
                newSongs += folder
                continue
            }
            val (song, mapping) = match
            claimed += song.id
            compare(song, folder, mapping, guessedStems)?.let { updates += it }
        }
        return SyncPlan(newSongs, updates)
    }

    /** Song plus, for each Drive audio file, the index of the local stem it corresponds to. */
    private fun matchBySource(local: List<Song>, folder: RemoteFolder, claimed: Set<String>): Pair<Song, List<Int?>>? {
        val names = folder.audio.map { it.name }.toSet()
        val song = local.filter { it.id !in claimed }
            .map { s -> s to s.stems.count { it.source != null && it.source in names } }
            .filter { it.second > 0 }
            .maxByOrNull { it.second }?.first ?: return null
        return song to folder.audio.map { f -> song.stems.indexOfFirst { it.source == f.name }.takeIf { it >= 0 } }
    }

    private fun matchLegacy(
        local: List<Song>, folder: RemoteFolder, guessedStems: List<String>, claimed: Set<String>,
    ): Pair<Song, List<Int?>>? {
        val (guessedSong, _) = SongRepository.guessNames(folder.audio.map { it.name })
        val songNames = setOf(norm(guessedSong), norm(folder.name))
        val voices = guessedStems.map(::norm).sorted()
        val song = local.firstOrNull { s ->
            s.id !in claimed && s.stems.all { it.source == null } && norm(s.name) in songNames &&
                s.stems.map { norm(it.name) }.sorted() == voices
        } ?: return null
        return song to guessedStems.map { g -> song.stems.indexOfFirst { norm(it.name) == norm(g) }.takeIf { it >= 0 } }
    }

    private fun compare(song: Song, folder: RemoteFolder, mapping: List<Int?>, guessedStems: List<String>): SongUpdate? {
        val changes = mutableListOf<String>()
        folder.audio.forEachIndexed { i, f ->
            val idx = mapping[i]
            if (idx == null) changes += "${guessedStems[i]} added"
            else if (song.stems[idx].version != f.version) changes += "${song.stems[idx].name} changed"
        }
        song.stems.forEachIndexed { i, s -> if (i !in mapping) changes += "${s.name} removed" }
        folder.score?.let { sc ->
            if (song.scoreVersion != sc.version) changes += if (song.score == null) "score added" else "score changed"
        }
        if (changes.isEmpty()) return null

        val keep = song.copy(
            stems = song.stems.mapIndexed { i, s ->
                val j = mapping.indexOf(i)
                if (j < 0) s else s.copy(source = folder.audio[j].name, version = folder.audio[j].version)
            },
            scoreSource = folder.score?.name ?: song.scoreSource,
            scoreVersion = folder.score?.version ?: song.scoreVersion,
        )
        return SongUpdate(song, folder, changes, keep)
    }

    private fun norm(s: String) = s.trim().lowercase()
}
