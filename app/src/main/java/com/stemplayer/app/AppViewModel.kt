package com.stemplayer.app

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ImportState(val songName: String, val done: Int, val total: Int)

sealed interface SyncState {
    /** [step] of [total] (total 0 = unknown). */
    data class Working(val message: String, val step: Int = 0, val total: Int = 0) : SyncState
    data class Review(val added: List<String>, val updates: List<SongUpdate>, val failed: List<String>) : SyncState
}

sealed interface ScoreState {
    data object None : ScoreState
    data object Loading : ScoreState
    data class Ready(val songId: String, val xml: String, val timeline: ScoreTimeline) : ScoreState
    data class Failed(val message: String) : ScoreState
}

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = SongRepository(app)

    private val _songs = MutableStateFlow<List<Song>>(emptyList())
    val songs: StateFlow<List<Song>> = _songs

    val current: StateFlow<Song?> = Playback.current
    val playing: StateFlow<Boolean> = Playback.playing

    private val _importing = MutableStateFlow<ImportState?>(null)
    val importing: StateFlow<ImportState?> = _importing

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    val player: MixerPlayer? get() = Playback.player

    private val _score = MutableStateFlow<ScoreState>(ScoreState.None)
    val score: StateFlow<ScoreState> = _score

    private val prefs = app.getSharedPreferences("settings", 0)
    private val _scoreZoom = MutableStateFlow(prefs.getInt("scoreZoom", 35))
    val scoreZoom: StateFlow<Int> = _scoreZoom

    private val _sync = MutableStateFlow<SyncState?>(null)
    val sync: StateFlow<SyncState?> = _sync

    init {
        Playback.init(app)
        viewModelScope.launch(Dispatchers.IO) { repo.cleanUp() }
        refresh()
    }

    private fun refresh() {
        viewModelScope.launch {
            _songs.value = withContext(Dispatchers.IO) { repo.loadAll() }
        }
    }

    fun clearError() { _error.value = null }

    fun displayNames(uris: List<Uri>): List<String> = uris.map { repo.displayName(it) }

    fun import(name: String, stems: List<StemInput>, score: ScoreInput?) {
        if (_importing.value != null || _sync.value != null) return
        _importing.value = ImportState(name, 0, stems.size)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    repo.import(name, SongRepository.inVoiceOrder(stems), score) { done, total ->
                        _importing.value = ImportState(name, done, total)
                    }
                }
                refresh()
            } catch (e: Exception) {
                _error.value = e.message ?: "Import failed"
            } finally {
                _importing.value = null
            }
        }
    }

    fun open(song: Song) {
        try {
            Playback.open(song, repo.songDir(song.id))
        } catch (e: Exception) {
            _error.value = "Could not open song: ${e.message}"
        }
    }

    fun close() {
        Playback.close()
        _score.value = ScoreState.None
        refresh()
    }

    /** Loads (and times) the score of [song]; called whenever the open song or its score changes. */
    fun loadScore(song: Song, force: Boolean = false) {
        val cur = _score.value
        if (song.score == null) { _score.value = ScoreState.None; return }
        if (!force && cur is ScoreState.Ready && cur.songId == song.id) return
        _score.value = ScoreState.Loading
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val xml = repo.readScore(song) ?: return@withContext ScoreState.Failed("The score file is missing")
                    ScoreState.Ready(song.id, xml, ScoreTimeline.parse(xml))
                } catch (e: Exception) {
                    ScoreState.Failed("Could not read the score: ${e.message}")
                }
            }
            if (current.value?.id == song.id) _score.value = result
        }
    }

    fun setScore(uri: Uri) {
        val song = current.value ?: return
        _score.value = ScoreState.Loading
        viewModelScope.launch {
            try {
                val file = withContext(Dispatchers.IO) { repo.saveScore(song.id, uri) }
                val source = withContext(Dispatchers.IO) { repo.displayName(uri) }
                val latest = current.value?.copy(score = file, scoreSource = source, scoreVersion = null)
                    ?: return@launch
                commit(latest, persist = true)
                loadScore(latest, force = true)
            } catch (e: Exception) {
                _error.value = e.message ?: "Could not add the score"
                loadScore(song, force = true)
            }
        }
    }

    fun removeScore() {
        val song = current.value ?: return
        commit(song.copy(score = null), persist = true)
        _score.value = ScoreState.None
        viewModelScope.launch(Dispatchers.IO) { repo.deleteScore(song.id) }
    }

    fun setScoreZoom(z: Int) {
        _scoreZoom.value = z
        prefs.edit().putInt("scoreZoom", z).apply()
    }

    fun togglePlay() = Playback.toggle()
    fun seekToMs(ms: Long) = Playback.seekToMs(ms)

    fun setVolume(index: Int, volume: Float) = updateStem(index, persist = false) { it.copy(volume = volume) }
    fun toggleMute(index: Int) = updateStem(index, persist = true) { it.copy(muted = !it.muted) }

    fun solo(index: Int) {
        val song = current.value ?: return
        val alreadySolo = song.stems.withIndex().all { (i, s) -> s.muted == (i != index) }
        val updated = song.copy(stems = song.stems.mapIndexed { i, s ->
            s.copy(muted = if (alreadySolo) false else i != index)
        })
        commit(updated, persist = true)
    }

    fun persistCurrent() {
        val song = current.value ?: return
        viewModelScope.launch(Dispatchers.IO) { repo.save(song) }
    }

    private fun updateStem(index: Int, persist: Boolean, f: (Stem) -> Stem) {
        val song = current.value ?: return
        commit(song.copy(stems = song.stems.mapIndexed { i, s -> if (i == index) f(s) else s }), persist)
    }

    private fun commit(song: Song, persist: Boolean) {
        Playback.update(song)
        if (persist) persistCurrent()
    }

    fun rename(song: Song, newName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repo.save(song.copy(name = newName))
            _songs.update { list -> list.map { if (it.id == song.id) it.copy(name = newName) else it } }
        }
    }

    fun renameStem(index: Int, newName: String) = updateStem(index, persist = true) { it.copy(name = newName) }

    fun delete(song: Song) {
        viewModelScope.launch(Dispatchers.IO) {
            repo.delete(song.id)
            _songs.update { list -> list.filter { it.id != song.id } }
        }
    }

    // ------------------------------------------------------------ Drive

    /** Compares the app with the Drive folder, adds the songs that are new and lists the ones that changed. */
    fun syncFromDrive() {
        if (_sync.value != null || _importing.value != null) return
        _sync.value = SyncState.Working("Checking Google Drive…")
        viewModelScope.launch(Dispatchers.IO) {
            val failed = mutableListOf<String>()
            val folders = try {
                readDrive(failed)
            } catch (e: Exception) {
                _sync.value = null
                _error.value = "Could not read the Google Drive folder: ${e.message ?: e.javaClass.simpleName}"
                return@launch
            }
            val plan = SyncPlanner.plan(repo.loadAll(), folders)
            val added = mutableListOf<String>()
            plan.newSongs.forEachIndexed { i, folder ->
                _sync.value = SyncState.Working("Adding \"${folder.name}\"…", i, plan.newSongs.size)
                try {
                    withDownloads(folder) { stems, score -> repo.import(folder.name, stems, score) { _, _ -> } }
                    added += folder.name
                } catch (e: Exception) {
                    failed += "${folder.name}: ${e.message ?: e.javaClass.simpleName}"
                }
            }
            if (added.isNotEmpty()) _songs.value = repo.loadAll()
            _sync.value = SyncState.Review(added, plan.updates, failed)
        }
    }

    /** Ends the review: replaces the [replace]d songs with the Drive version and keeps the app's copy of the rest. */
    fun finishSync(replace: Set<String>) {
        val review = _sync.value as? SyncState.Review ?: return
        _sync.value = SyncState.Working("Updating…")
        viewModelScope.launch(Dispatchers.IO) {
            val failed = mutableListOf<String>()
            val chosen = review.updates.filter { it.song.id in replace }
            review.updates.filter { it.song.id !in replace }.forEach { repo.save(it.keepMine) }
            chosen.forEachIndexed { i, u ->
                _sync.value = SyncState.Working("Updating \"${u.song.name}\"…", i, chosen.size)
                try {
                    withDownloads(u.folder) { stems, score -> repo.replace(u.song, stems, score) { _, _ -> } }
                } catch (e: Exception) {
                    failed += "${u.song.name}: ${e.message ?: e.javaClass.simpleName}"
                }
            }
            _songs.value = repo.loadAll()
            _sync.value = null
            if (failed.isNotEmpty()) _error.value = "Some songs could not be updated:\n" + failed.joinToString("\n")
        }
    }

    /** Lists the song folders on Drive with a fingerprint of every file. Folders that can't be read go to [failed]. */
    private suspend fun readDrive(failed: MutableList<String>): List<RemoteFolder> {
        val folders = DriveClient.listFolder(DRIVE_FOLDER_ID).filter { it.isFolder }
        val limit = Semaphore(6)
        var done = 0
        return coroutineScope {
            folders.map { folder ->
                async {
                    limit.withPermit {
                        try {
                            val entries = DriveClient.listFolder(folder.id).filter { !it.isFolder }
                            val audio = entries.filter { isAudioFile(it.name) }.sortedBy { it.name }
                            val score = entries.firstOrNull { SongRepository.isScoreFile(it.name) }
                            fun remote(e: DriveEntry) = RemoteFile(e.id, e.name, DriveClient.version(e.id))
                            RemoteFolder(folder.id, folder.name, audio.map(::remote), score?.let(::remote))
                        } catch (e: Exception) {
                            synchronized(failed) { failed += "${folder.name}: ${e.message ?: e.javaClass.simpleName}" }
                            null
                        } finally {
                            synchronized(this@AppViewModel) { done++ }
                            _sync.value = SyncState.Working("Checking Google Drive…", done, folders.size)
                        }
                    }
                }
            }.awaitAll().filterNotNull()
        }
    }

    /** Downloads the files of [folder] to a temporary folder and hands them to [use]. */
    private fun <T> withDownloads(folder: RemoteFolder, use: (List<StemInput>, ScoreInput?) -> T): T {
        val dir = File(getApplication<Application>().cacheDir, "drive/${folder.id}").apply {
            deleteRecursively(); mkdirs()
        }
        try {
            val (_, stemNames) = SongRepository.guessNames(folder.audio.map { it.name })
            val stems = folder.audio.mapIndexed { i, f ->
                val file = File(dir, "stem_$i.${f.name.substringAfterLast('.', "audio")}")
                DriveClient.download(f.id, file)
                StemInput(Uri.fromFile(file), stemNames[i], f.name, f.version)
            }
            val score = folder.score?.let { f ->
                val file = File(dir, "score.${f.name.substringAfterLast('.', "mxl")}")
                DriveClient.download(f.id, file)
                ScoreInput(Uri.fromFile(file), f.name, f.version)
            }
            return use(SongRepository.inVoiceOrder(stems), score)
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun isAudioFile(name: String) =
        name.substringAfterLast('.', "").lowercase() in setOf("mp3", "wav", "ogg", "oga", "opus", "flac", "m4a", "aac", "3gp", "mp4")
}
